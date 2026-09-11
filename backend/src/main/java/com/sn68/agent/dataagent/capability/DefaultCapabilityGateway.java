/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.PepInvocationInspector;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageReservation;
import com.sn68.agent.dataagent.tool.ToolInvocationException;
import com.sn68.agent.dataagent.tool.ToolPermissionService;
import com.sn68.agent.dataagent.tool.ToolTransportInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 统一能力网关默认实现。按方案第八章编排检查链：租户校验 → Release 固定 →
 * 能力安装与授权 → 数据权限与资源范围 → 凭据检查 → 风险与审批 → 参数校验 → 限流预算配额 →
 * 幂等键与调用记录 → 审计脱敏 → 结构化结果转换。每步失败抛 {@link CheckedException}
 * 并说明被哪项检查拒绝，失败关闭、不降级放行。
 *
 * <p>两条执行路径：
 * <ul>
 * <li>目录能力（Runtime Hook 等）：能力编码即工具目录 resourceKey，检查通过后经
 * {@link ToolTransportInvoker} 执行，消除 Hook 直连传输层的权限旁路；</li>
 * <li>进程内能力（AgentScope 工具回调）：检查通过后执行 {@link CapabilityExecutor}。智能体类型级
 * 授权仍由装配期 AgentToolPolicyService 过滤承担；限流预算由模型级用量策略与
 * AgentRuntimeToolMetrics 承担，网关不重复预扣，避免 REQUEST_RATE 策略二次计数。</li>
 * </ul>
 *
 * <p>租户口径：高风险审批与带 runId 的调用记录都要求能解析出真实租户，解析失败一律拒绝，
 * 不再退化为租户 0——落 0 的审批无人可批（管理端按当前租户查询），且所有解析失败的调用会共用
 * 同一个批复池。只读、无 runId 的调用不强制租户，由资源范围检查按能力自身的租户元数据裁决。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultCapabilityGateway implements CapabilityGateway {

	private static final String USAGE_SOURCE_GATEWAY = "CAPABILITY_GATEWAY";

	/** 审批来源标识：网关高风险检查发起的审批。 */
	private static final String APPROVAL_SOURCE_GATEWAY = "CAPABILITY_GATEWAY";

	private static final int FINGERPRINT_LENGTH = 16;

	private final CapabilitySourceGuard sourceGuard;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final ToolPermissionService toolPermissionService;

	private final AgentUsageLimitService usageLimitService;

	private final ToolTransportInvoker toolTransportInvoker;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	private final RuntimeInvocationService runtimeInvocationService;

	private final AgentApprovalService approvalService;

	private final RuntimeBudgetService runtimeBudgetService;

	// PR-3c PEP 检查员：唯一新增依赖，Hook/输出两线接缝的全部 PEP 逻辑收敛在其内（SHADOW 默认不拦截）。
	private final PepInvocationInspector pepInvocationInspector;

	// PR-3d：数字员工链路 Release 快照事实源（能力版本固定 + Release 固定检查的统一解析入口）。
	private final EmployeeReleaseSnapshotResolver employeeSnapshotResolver;

	// 批次 E：ENFORCE 空权限码拒绝只看本视图，SHADOW 默认不拦截。
	private final PepAuthorizationProperties pepAuthorizationProperties;

	@Override
	public ResultEnvelope invoke(InvocationRequest request) {
		return doInvoke(request, null);
	}

	@Override
	public ResultEnvelope invoke(InvocationRequest request, CapabilityExecutor executor) {
		if (executor == null) {
			throw CheckedException.badRequest("能力调用请求不完整：进程内能力执行器不能为空");
		}
		return doInvoke(request, executor);
	}

	private ResultEnvelope doInvoke(InvocationRequest request, CapabilityExecutor executor) {
		long startNanos = System.nanoTime();
		requireBasicRequest(request);
		String tenantId = resolvedTenantId(request);
		try {
			return runChecksAndExecute(request, executor, tenantId, startNanos);
		}
		catch (Exception ex) {
			// 失败关闭且失败可见：审计日志记录被拒绝/失败的调用后原样抛出，绝不吞异常降级放行。
			log.warn(
					"能力网关调用失败. capabilityKind={}, capabilityCode={}, source={}, tenantId={}, agentId={}, runId={}, stepKey={}, errorType={}, errorMessage={}",
					request.capabilityKind(), request.capabilityCode(), request.source(), tenantId, request.agentId(),
					request.runId(), request.stepKey(), ex.getClass().getSimpleName(), ex.getMessage());
			if (ex instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw CheckedException.fail("能力执行失败：" + ex.getMessage());
		}
	}

	private ResultEnvelope runChecksAndExecute(InvocationRequest request, CapabilityExecutor executor, String tenantId,
			long startNanos) throws Exception {
		boolean dryRun = ExecutionIntentContext.dryRunActive(request.executionIntent(), request.executionScopeKey());
		// PR-3d：数字员工链路一次性锚定 Release 快照（未部署/RETIRED/摘要篡改均失败关闭），
		// 锚点同时供 Hook 线能力版本与 Release 固定检查消费，不重复解析。
		EmployeeReleaseSnapshot employeeSnapshot = resolveEmployeeSnapshot(request, tenantId);
		// PR-3c PEP Hook 线 + PR-4 接线：检查链起点影子判定，ENFORCE 下 PDP deny 强制拦截。
		PepInvocationInspector.HookDecisionInput pepInput = new PepInvocationInspector.HookDecisionInput(tenantId,
				request.runId(), request.stepKey(), request.ownerType(), request.ownerId(), request.capabilityCode(),
				employeeSnapshot == null ? null : employeeSnapshot.versionAnchor(), request.agentId(),
				resolvedUserId(request));
		PepDecisionResult pepDecision = pepInvocationInspector.authorizeBeforeExecute(pepInput);
		AgentExecutionResource resource;
		AgentExecutionResourceVersion version;
		try {
			// PR-4：ENFORCE 租户 PDP deny 拦截（授权拒绝计入 DRY_RUN 隔离违规，发布门禁可见）；
			// SHADOW 不拦截，deny 决策交由后续影子比对观测（original=TRUE → MISMATCHED）。
			pepInvocationInspector.enforceDenyDecision(pepDecision, pepInput);
			pinReleaseVersion(request, employeeSnapshot);
			resource = requireDeclaredCapability(request, executor);
			version = resolvePublishedVersion(request, resource);
			requireAuthorization(request, tenantId, version);
			requireResourceScope(request, tenantId, version);
		}
		catch (CheckedException ex) {
			// DRY_RUN 评估中命中租户/授权/资源范围类拒绝 = 权限或租户隔离违规，计入发布门禁。
			recordIsolationViolationIfDryRun(dryRun, request, ex.getMessage());
			throw ex;
		}
		enforceDryRunSideEffectBan(dryRun, request, executor, version);
		requireCredentialRef(resource);
		// 参数校验（检查项 7）前置到审批检查之前：审批与参数指纹绑定，两项检查均失败关闭，顺序调整无放行面变化。
		String argumentsJson = validateArguments(request);
		String fingerprint = fingerprint(argumentsJson);
		requireApprovalForHighRisk(request, version, tenantId, fingerprint);
		sourceGuard.requireDeclaredNetworkAddresses(request, resource);
		enforceUsageLimits(request, tenantId, executor != null);
		runtimeBudgetService.enforceCapabilityCallBudget(request.runId());
		String idempotencyKey = idempotencyKey(request, fingerprint);
		Long invocationId = openInvocation(request, version, tenantId, idempotencyKey, fingerprint);
		// PR-3c PEP Hook 线（续）：现网检查链已放行，落影子日志并回填 invocation 审计列（quiet）。
		pepInvocationInspector.recordAfterInvocationOpened(pepDecision, pepInput, invocationId);
		Object data;
		try {
			data = execute(request, executor, idempotencyKey);
		}
		catch (Exception ex) {
			finishInvocationFailure(invocationId, ex);
			throw ex;
		}
		long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
		finishInvocationSuccess(invocationId, request, version, data, idempotencyKey);
		runtimeBudgetService.recordCapabilityCall(parseTenantId(tenantId), request.runId(), request.stepKey(),
				invocationId, durationMs, request.capabilityCode());
		recordInvocation(request, tenantId, idempotencyKey, fingerprint, durationMs);
		// PR-3c PEP 输出线：ENFORCE 应用 MASK_FIELDS/FILTER_FIELDS 义务（Map 与 String JSON 形态均覆盖，
		// 评审高-1），SHADOW（默认）只预览不修改输出；pepInput 供义务跳过标记的租户/运行/步骤定位。
		data = pepInvocationInspector.applyOutputObligations(pepDecision,
				version == null ? null : version.getSensitiveFields(), data, pepInput);
		return envelope(request, resource, version, data, idempotencyKey, fingerprint, durationMs);
	}

	private void requireBasicRequest(InvocationRequest request) {
		if (request == null || request.capabilityKind() == null || !StringUtils.hasText(request.capabilityCode())) {
			throw CheckedException.badRequest("能力调用请求不完整：capabilityKind 与 capabilityCode 必填");
		}
	}

	/** 检查项 1：租户校验。请求未携带租户时回退到当前授权上下文；是否强制由资源范围检查裁决。 */
	private String resolvedTenantId(InvocationRequest request) {
		if (StringUtils.hasText(request.tenantId())) {
			return request.tenantId().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.tenantId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("能力网关读取授权上下文租户失败，按无租户上下文处理. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * 检查项 2：Release 固定。数字员工链路（ownerType=DIGITAL_EMPLOYEE）的 releaseId 语义是
	 * digital_employee_release.id，事实源校验已在 {@link EmployeeReleaseSnapshotResolver} 完成，
	 * 本方法只复核锚点一致。非员工不再按 {@code data_agent_release} 固定，releaseId 忽略。
	 */
	private void pinReleaseVersion(InvocationRequest request, EmployeeReleaseSnapshot employeeSnapshot) {
		if (employeeSnapshot == null) {
			return;
		}
		if (request.releaseId() != null && !request.releaseId().equals(employeeSnapshot.releaseId())) {
			throw CheckedException.forbidden("Release 固定检查拒绝：调用钉住的 Release 与员工可解析的快照不一致, releaseId="
					+ request.releaseId() + ", snapshotReleaseId=" + employeeSnapshot.releaseId());
		}
	}

	/**
	 * PR-3d / P0-F：数字员工链路 Release 快照锚定。DIGITAL_EMPLOYEE owner 必须钉死
	 * {@code releaseId} 到 PUBLISHED 快照；{@code releaseId==null}（MODEL_ONLY 沙箱）失败关闭，
	 * 禁止 {@code resolveActive(PRODUCTION)} 放行生产工具。无法锚定（未部署 / RETIRED /
	 * 摘要篡改）由解析器失败关闭。其余 owner 返回 null，不触碰现网路径。
	 */
	private EmployeeReleaseSnapshot resolveEmployeeSnapshot(InvocationRequest request, String tenantId) {
		if (AuthorizationOwnerType.fromCode(request.ownerType()) != AuthorizationOwnerType.DIGITAL_EMPLOYEE
				|| request.ownerId() == null) {
			return null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Release 固定检查拒绝：数字员工调用必须携带可解析的租户上下文");
		}
		if (request.releaseId() == null) {
			throw CheckedException.forbidden("Release 固定检查拒绝：数字员工未钉死 Release，禁止回落生产部署");
		}
		return employeeSnapshotResolver.resolveById(tenantId, request.ownerId(), request.releaseId());
	}

	private String parseTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			return null;
		}
		return tenantId.trim();
	}

	/** 检查项 3：能力安装与来源白名单。目录能力必须在工具目录声明；进程内能力校验编码形状。 */
	private AgentExecutionResource requireDeclaredCapability(InvocationRequest request, CapabilityExecutor executor) {
		if (executor != null) {
			// 进程内工具由 Spring 容器注册并经装配期策略过滤，属于服务端声明的能力，无目录条目。
			sourceGuard.requireCapabilityCode(request);
			return null;
		}
		return sourceGuard.requireDeclaredResource(request);
	}

	/**
	 * 检查项 6 前置：DRY_RUN 副作用禁令（方案第十四章离线评估约束，执行意图=DRY_RUN、
	 * writeEnabled=false、externalSideEffect=false）。失败关闭：
	 * <ul>
	 * <li>目录能力：发布版本声明 WRITE 或需人工确认 → 按写副作用拦截；无发布版本元数据
	 * （无法证明只读）→ 按外部副作用拦截；只读目录能力放行（查询不产生副作用）；</li>
	 * <li>进程内能力：datasource/semantic/sqlguard/knowledge 四族为 SQL 守卫约束下的只读
	 * 工具，放行；skill_* 工具由 ToolInvoker 关口按目录 accessMode 精确拦截（见
	 * {@code ToolInvokerImpl}）；其余（MCP 别名工具、未知工具）无法证明只读 → 拦截。</li>
	 * </ul>
	 * 拦截先记违规（回投评估采集器计入发布门禁）再抛异常，绝不静默放过。
	 *
	 * <p>TODO（残余覆盖面，见自进化方案报告）：不经本网关与 ToolInvoker 的写路径
	 * （Flow 引擎内部状态写、进程内直接 DB 写、外部 MCP 服务器侧行为）尚无法在关口拦截，
	 * 需要在对应执行器补 {@link ExecutionIntentContext} 检查。
	 */
	private void enforceDryRunSideEffectBan(boolean dryRun, InvocationRequest request, CapabilityExecutor executor,
			AgentExecutionResourceVersion version) {
		if (!dryRun) {
			return;
		}
		if (executor == null) {
			if (version == null) {
				rejectDryRun(request, ExecutionIntentContext.VIOLATION_EXTERNAL_SIDE_EFFECT,
						"目录能力缺少发布版本元数据，无法证明只读");
			}
			if (writeCapability(version) || Boolean.TRUE.equals(version.getConfirmRequired())) {
				rejectDryRun(request, ExecutionIntentContext.VIOLATION_WRITE_ATTEMPT,
						"目录能力为写操作或需人工确认");
			}
			return;
		}
		String capabilityCode = request.capabilityCode();
		if (AgentModelToolName.isDataQueryTool(capabilityCode) || AgentModelToolName.isKnowledgeTool(capabilityCode)) {
			return;
		}
		if (AgentModelToolName.isSkillTool(capabilityCode)) {
			// skill 工具内部经 ToolInvoker 执行，该关口持有精确的目录 accessMode，写工具在那里拦截。
			return;
		}
		rejectDryRun(request, ExecutionIntentContext.VIOLATION_EXTERNAL_SIDE_EFFECT,
				"进程内能力不属于已知只读工具族，无法证明无外部副作用");
	}

	private void rejectDryRun(InvocationRequest request, String violationType, String reason) {
		ExecutionIntentContext.recordViolation(request.executionScopeKey(), violationType, request.capabilityCode(),
				reason);
		// 用 fail 把命中的禁令原因带回评估侧，别退化成看不出原因的通用拒绝。
		throw CheckedException.fail("DRY_RUN 副作用禁令拒绝：离线评估禁止执行写操作与外部副作用能力，已记违规（"
				+ reason + "），capabilityCode=" + request.capabilityCode());
	}

	private void recordIsolationViolationIfDryRun(boolean dryRun, InvocationRequest request, String detail) {
		if (!dryRun) {
			return;
		}
		ExecutionIntentContext.recordViolation(request.executionScopeKey(),
				ExecutionIntentContext.VIOLATION_ISOLATION, request.capabilityCode(), detail);
	}

	/**
	 * 检查项 3（续）：授权检查。委托 ToolPermissionService 校验发布版本绑定的功能权限码。
	 * SHADOW 空权限码仍跳过（DataAgent 现网行为不变）；ENFORCE 下 TOOL 空码按 MISSING_PERMISSION_CODE 拒绝。
	 */
	private void requireAuthorization(InvocationRequest request, String tenantId, AgentExecutionResourceVersion version) {
		if (version == null) {
			return;
		}
		if (!StringUtils.hasText(version.getPermissionCode())) {
			if (isEnforceBusinessTool(request, tenantId)) {
				throw CheckedException.fail("授权决策拒绝（MISSING_PERMISSION_CODE）：ENFORCE 租户禁止调用未配置权限码的业务工具, "
						+ "capabilityCode=" + request.capabilityCode());
			}
			return;
		}
		ToolPermissionResult permission = toolPermissionService.canAccess(List.of(version.getPermissionCode()), null);
		if (!permission.allowed()) {
			throw CheckedException.forbidden("能力安装与授权检查拒绝：" + (StringUtils.hasText(permission.denyMessage())
					? permission.denyMessage() : "当前账号缺少该能力的功能权限"));
		}
	}

	private boolean isEnforceBusinessTool(InvocationRequest request, String tenantId) {
		return request.capabilityKind() == CapabilityKind.TOOL
				&& pepAuthorizationProperties != null
				&& pepAuthorizationProperties.resolveMode(tenantId).enforce();
	}

	/** 检查项 4：数据权限与资源范围。委托工具目录发布版本的租户范围元数据，租户不匹配即拒绝。 */
	private void requireResourceScope(InvocationRequest request, String tenantId,
			AgentExecutionResourceVersion version) {
		if (version == null || !StringUtils.hasText(version.getTenantId())) {
			return;
		}
		if (!version.getTenantId().equals(tenantId)) {
			throw CheckedException.forbidden(
					"数据权限与资源范围检查拒绝：当前租户不能使用该能力，capabilityCode=" + request.capabilityCode());
		}
	}

	/** 检查项 5：凭据检查。复用现有凭据处理路径（credentialRef 经 X-Credential-Ref 透传），只做存在性检查。 */
	private void requireCredentialRef(AgentExecutionResource resource) {
		if (resource == null || !StringUtils.hasText(resource.getAuthType())
				|| "none".equalsIgnoreCase(resource.getAuthType().trim())) {
			return;
		}
		if (!StringUtils.hasText(resource.getCredentialRef())) {
			throw CheckedException.badRequest(
					"凭据检查拒绝：能力声明了认证方式但未配置凭据引用，capabilityCode=" + resource.getResourceKey());
		}
	}

	/**
	 * 进程内工具没有目录资源行时，仍按能力编码回查已发布版本；查不到才视为无版本元数据。
	 */
	private AgentExecutionResourceVersion resolvePublishedVersion(InvocationRequest request,
			AgentExecutionResource resource) {
		String resourceKey = resource != null && StringUtils.hasText(resource.getResourceKey())
				? resource.getResourceKey() : request.capabilityCode();
		if (!StringUtils.hasText(resourceKey)) {
			return null;
		}
		return resourceVersionMapper.findLatestPublished(resourceKey);
	}

	/**
	 * 检查项 6：风险与审批。仅当技能打开 {@code requireManagerApproval} 时走管理端 PAP。
	 * WRITE / confirmRequired 本身不自动建单；FLOW 用户确认仍由 ToolInvoker 承担。
	 * 续跑必须带 approvalId + sourceRefId，按 ID 消费并核对指纹，禁止用不带 sourceRef 的
	 * findConsumableApproved 以免同租户串单。
	 */
	private void requireApprovalForHighRisk(InvocationRequest request, AgentExecutionResourceVersion version,
			String tenantId, String paramsHash) {
		if (!Boolean.TRUE.equals(request.requireManagerApproval())) {
			return;
		}
		String tenant = parseTenantId(tenantId);
		if (tenant == null) {
			throw CheckedException.fail("风险与审批检查拒绝：无法解析当前租户，高风险能力不允许在无租户上下文下调用，"
					+ "capabilityCode=" + request.capabilityCode());
		}
		if (!StringUtils.hasText(request.sourceRefId())) {
			throw CheckedException.fail("风险与审批检查拒绝：管理端审批必须绑定来源实例（sourceRefId），"
					+ "capabilityCode=" + request.capabilityCode());
		}
		if (request.approvalId() != null) {
			if (approvalService.consumeMatching(tenant, request.approvalId(), request.ownerType(), request.ownerId(),
					request.sourceRefId(), request.capabilityCode(), paramsHash)) {
				log.info("风险与审批检查通过：按审批ID一次性消费. approvalId={}, capabilityCode={}, sourceRefId={}",
						request.approvalId(), request.capabilityCode(), request.sourceRefId());
				return;
			}
			throw CheckedException.fail("风险与审批检查拒绝：审批不存在、已消费或与当前实例/参数不匹配，approvalId="
					+ request.approvalId() + ", capabilityCode=" + request.capabilityCode());
		}
		AgentRuntimeApproval pending = approvalService.createPending(new AgentApprovalService.ApprovalCreateRequest(
				tenant, request.ownerType(), request.ownerId(), request.runId(), request.stepKey(),
				request.capabilityCode(), paramsHash, null, resolvedUserId(request), null, APPROVAL_SOURCE_GATEWAY,
				request.sourceRefId().trim()));
		throw new CapabilityApprovalRequiredException(pending.getId(), request.capabilityCode(), paramsHash);
	}

	/**
	 * 检查项 7：参数校验。网关做结构性校验（JSON 对象、参数名非空）；字段级 Schema 归一化与
	 * 时间字段失败关闭委托既有链路（MCP 资源经 McpToolArgumentNormalizer，版本化调用经 ToolInvoker）。
	 */
	private String validateArguments(InvocationRequest request) {
		Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
		for (String key : arguments.keySet()) {
			if (!StringUtils.hasText(key)) {
				throw CheckedException.badRequest("参数 Schema 校验拒绝：参数名不能为空");
			}
		}
		try {
			return objectMapper.writeValueAsString(arguments);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("参数 Schema 校验拒绝：参数无法序列化为 JSON 对象");
		}
	}

	/** 检查项 8：限流、预算与配额。对接现有用量限制服务；能力调用不消耗 token，token 类预扣立即回冲。 */
	private void enforceUsageLimits(InvocationRequest request, String tenantId, boolean inProcess) {
		if (inProcess) {
			// 进程内工具已由模型级用量策略（SpringAiAgentScopeModel 预扣）与 AgentRuntimeToolMetrics
			// 工具预算计量，网关不重复预扣，避免 REQUEST_RATE 策略对同一请求二次计数。
			return;
		}
		AgentTokenUsageContext context = AgentTokenUsageContext.builder()
			.tenantId(tenantId)
			.userId(resolvedUserId(request))
			.agentId(request.agentId())
			.requestSource(request.source())
			.usageSource(USAGE_SOURCE_GATEWAY)
			.cacheHit(false)
			.build();
		try {
			AgentUsageReservation reservation = usageLimitService.preCheckAndReserve(context, 0L);
			usageLimitService.settleActualUsage(reservation, 0L);
		}
		catch (CheckedException ex) {
			throw CheckedException.badRequest("限流与配额检查拒绝：" + ex.getMessage());
		}
	}

	private String resolvedUserId(InvocationRequest request) {
		if (StringUtils.hasText(request.userId())) {
			return request.userId().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.userId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("能力网关读取授权上下文用户失败，按无用户上下文处理. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/** 检查项 9：幂等键。调用方未提供时按「来源 + 能力 + 参数 hash」生成。 */
	private String idempotencyKey(InvocationRequest request, String fingerprint) {
		if (StringUtils.hasText(request.idempotencyKey())) {
			return request.idempotencyKey().trim();
		}
		String source = StringUtils.hasText(request.source()) ? request.source().trim() : "UNKNOWN";
		return String.join(":", "cap", source, request.capabilityKind().name(), request.capabilityCode(), fingerprint);
	}

	private String fingerprint(String argumentsJson) {
		return SecureUtil.sha256(argumentsJson == null ? "" : argumentsJson).substring(0, FINGERPRINT_LENGTH);
	}

	private Object execute(InvocationRequest request, CapabilityExecutor executor, String idempotencyKey)
			throws Exception {
		if (executor != null) {
			return executor.execute();
		}
		Map<String, Object> arguments = new LinkedHashMap<>(request.arguments() == null ? Map.of() : request.arguments());
		arguments.putIfAbsent("idempotencyKey", idempotencyKey);
		try {
			return toolTransportInvoker.invoke(request.capabilityCode(), arguments);
		}
		catch (WebClientResponseException ex) {
			// 与 ToolInvokerImpl 一致：把业务服务的 401/403 翻译为稳定错误码与用户可读提示。
			if (ex.getStatusCode().value() == 401) {
				recordIsolationViolationIfDryRun(
						ExecutionIntentContext.dryRunActive(request.executionIntent(), request.executionScopeKey()),
						request, "业务服务返回 401：评估身份对该能力无有效授权");
				throw new ToolInvocationException(ToolInvocationException.Code.TOOL_TOKEN_INVALID,
						"业务服务拒绝当前授权，请重新发起当前操作", ex);
			}
			if (ex.getStatusCode().value() == 403) {
				recordIsolationViolationIfDryRun(
						ExecutionIntentContext.dryRunActive(request.executionIntent(), request.executionScopeKey()),
						request, "业务服务返回 403：评估身份越权访问该能力");
				throw new ToolInvocationException(ToolInvocationException.Code.TOOL_PERMISSION_DENIED,
						"业务服务拒绝当前账号访问，请确认功能权限和数据范围", ex);
			}
			throw ex;
		}
	}

	/**
	 * 检查项 9（续）：携带 runId 的调用持久化到 agent_runtime_invocation（open → markSent），
	 * 返回可承接终态的调用记录ID；runId 为空的调用不强造记录，返回 null，仅由结构化日志承载。
	 *
	 * <p><b>幂等边界（同一 runId + 同一幂等键再次进来时）：</b>
	 * <ul>
	 * <li>DISPATCH_INTENT：上次未发出，迁 INVOCATION_SENT 后正常执行；</li>
	 * <li>INVOCATION_SENT：上次发出后进程中断，继续用原记录承接终态；</li>
	 * <li>SUCCESS 且能力为写操作：<b>拒绝执行</b>——副作用已经发生，重放会真的执行第二次；
	 * 需要再执行一次必须换幂等键（换参数即换指纹，或调用方显式传新 idempotencyKey）；</li>
	 * <li>SUCCESS 且能力只读：重复读无副作用，放行执行，但不再改写已终态的记录；</li>
	 * <li>FAILED：外部端明确返回失败已证明副作用未生效，放行重试（步骤级重试依赖该语义），
	 * 同样不改写原记录；</li>
	 * <li>OUTCOME_UNKNOWN / RECONCILING：已在 {@code RuntimeInvocationService#open} 内拒绝，
	 * 必须先走对账端点收敛。</li>
	 * </ul>
	 */
	private Long openInvocation(InvocationRequest request, AgentExecutionResourceVersion version, String tenantId,
			String idempotencyKey, String fingerprint) {
		if (request.runId() == null) {
			return null;
		}
		// Run 必然属于某个租户，调用记录是写侧审计凭据：解析不到租户就落 tenant 0 会让审计与 Run 脱节。
		String tenant = parseTenantId(tenantId);
		if (tenant == null) {
			throw CheckedException.fail("幂等与调用记录检查拒绝：无法解析当前租户，携带 runId 的调用不允许落无租户调用记录，"
					+ "runId=" + request.runId());
		}
		AgentRuntimeInvocation invocation = runtimeInvocationService
			.open(new RuntimeInvocationService.InvocationOpen(tenant, request.runId(), null, null, idempotencyKey,
					request.capabilityCode(), request.capabilityKind().name(), fingerprint));
		String state = invocation.getState();
		if (RuntimeInvocationState.DISPATCH_INTENT.getValue().equals(state)) {
			runtimeInvocationService.markSent(invocation.getId());
			return invocation.getId();
		}
		if (RuntimeInvocationState.INVOCATION_SENT.getValue().equals(state)) {
			// 上次进程在发出后中断，记录残留在 INVOCATION_SENT：同幂等键重放继续用原记录承接终态。
			return invocation.getId();
		}
		if (RuntimeInvocationState.SUCCESS.getValue().equals(state) && writeCapability(version)) {
			throw CheckedException.fail("幂等与调用记录检查拒绝：该幂等键的写操作已执行成功，禁止重放，"
					+ "如确需再次执行请更换幂等键（invocationId=" + invocation.getId() + ", idempotencyKey="
					+ idempotencyKey + ", capabilityCode=" + request.capabilityCode() + "）");
		}
		log.warn("能力网关幂等键命中终态调用记录, 本次按可重复执行放行且不更新该记录. invocationId={}, state={}, "
				+ "capabilityCode={}", invocation.getId(), state, request.capabilityCode());
		return null;
	}

	/** 写能力判定统一口径：目录发布版本声明 accessMode=WRITE；进程内能力无目录版本，按非写处理。 */
	private boolean writeCapability(AgentExecutionResourceVersion version) {
		return version != null && "WRITE".equalsIgnoreCase(version.getAccessMode());
	}

	/** 外部端明确返回结论（HTTP 状态到达 / 业务异常）落 FAILED；无法证明外部结果的落 OUTCOME_UNKNOWN。 */
	private void finishInvocationFailure(Long invocationId, Exception ex) {
		if (invocationId == null) {
			return;
		}
		boolean outcomeKnown = ex instanceof WebClientResponseException || ex instanceof ToolInvocationException
				|| ex instanceof CheckedException;
		String errorCode = ex instanceof WebClientResponseException webError
				? String.valueOf(webError.getStatusCode().value()) : ex.getClass().getSimpleName();
		if (outcomeKnown) {
			runtimeInvocationService.markFailed(invocationId, errorCode, ex.getMessage());
			return;
		}
		runtimeInvocationService.markOutcomeUnknown(invocationId, errorCode, ex.getMessage());
	}

	/** 成功终态：只落响应指纹与写回执，不落原始响应体。 */
	private void finishInvocationSuccess(Long invocationId, InvocationRequest request,
			AgentExecutionResourceVersion version, Object data, String idempotencyKey) {
		if (invocationId == null) {
			return;
		}
		boolean write = writeCapability(version);
		String receiptJson = null;
		if (write) {
			receiptJson = toJsonQuietly(
					Map.of("capabilityCode", request.capabilityCode(), "idempotencyKey", idempotencyKey));
		}
		runtimeInvocationService.markSuccess(invocationId, digestQuietly(data), receiptJson);
	}

	private String digestQuietly(Object data) {
		String json = toJsonQuietly(data);
		return json == null ? null : fingerprint(json);
	}

	private String toJsonQuietly(Object value) {
		if (value == null) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			log.debug("能力网关序列化调用记录附加信息失败, 按空值落库. errorType={}", ex.getClass().getSimpleName());
			return null;
		}
	}

	/** 检查项 10：审计脱敏的结构化日志。参数只记录键名与指纹，不落参数值与凭据。 */
	private void recordInvocation(InvocationRequest request, String tenantId, String idempotencyKey,
			String fingerprint, long durationMs) {
		log.info(
				"能力网关调用完成. capabilityKind={}, capabilityCode={}, source={}, tenantId={}, agentId={}, runId={}, stepKey={}, idempotencyKey={}, argumentKeys={}, argumentFingerprint={}, durationMs={}",
				request.capabilityKind(), request.capabilityCode(), request.source(), tenantId, request.agentId(),
				request.runId(), request.stepKey(), idempotencyKey,
				request.arguments() == null ? List.of() : request.arguments().keySet(), fingerprint, durationMs);
	}

	/** 检查项 11：结构化结果转换。所有能力统一装入 ResultEnvelope。 */
	private ResultEnvelope envelope(InvocationRequest request, AgentExecutionResource resource,
			AgentExecutionResourceVersion version, Object data, String idempotencyKey, String fingerprint,
			long durationMs) {
		Map<String, Object> evidence = new LinkedHashMap<>();
		evidence.put("capabilityKind", request.capabilityKind().name());
		evidence.put("capabilityCode", request.capabilityCode());
		evidence.put("invocationSource", request.source());
		evidence.put("argumentFingerprint", fingerprint);
		evidence.put("durationMs", durationMs);
		if (resource != null) {
			evidence.put("resourceType", resource.getResourceType());
		}
		if (version != null) {
			evidence.put("resourceVersionNo", version.getVersionNo());
		}
		boolean write = writeCapability(version);
		return ResultEnvelope.builder()
			.status(ResultEnvelope.STATUS_SUCCESS)
			.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
			.data(data)
			.evidence(evidence)
			.sensitivity(sensitivity(version))
			.source(request.source())
			.retryability(retryability(version))
			.sideEffectReceipt(write
					? Map.of("capabilityCode", request.capabilityCode(), "idempotencyKey", idempotencyKey) : null)
			.idempotencyKey(idempotencyKey)
			.build();
	}

	private String sensitivity(AgentExecutionResourceVersion version) {
		if (version == null || !StringUtils.hasText(version.getSensitiveFields())
				|| "[]".equals(version.getSensitiveFields().trim())) {
			return ResultEnvelope.SENSITIVITY_INTERNAL;
		}
		return ResultEnvelope.SENSITIVITY_SENSITIVE;
	}

	private String retryability(AgentExecutionResourceVersion version) {
		if (version == null) {
			return ResultEnvelope.RETRYABILITY_UNKNOWN;
		}
		return "WRITE".equalsIgnoreCase(version.getAccessMode()) ? ResultEnvelope.RETRYABILITY_NON_RETRYABLE
				: ResultEnvelope.RETRYABILITY_RETRYABLE;
	}

}
