/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.OwnerAuthorizationQueryGateway;
import com.sn68.agent.dataagent.authorization.pep.InvocationSubjectGuard;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pep.ShadowRecorder;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 对话入口 USE 判定守卫（PR-4 接线点：对话/调用入口 PEP 化）。
 *
 * <p>v1.2 清单 PR-4 表格的对话线：{@code streamSearch}（流式）与 {@code executeSingleTurn}（单轮）
 * 公共入口判定主体对运行 owner 的 USE 授权。协作者子请求直接汇入私有 {@code executeAgent}，
 * 不经过本守卫（父请求已判定，编排内部不重复判定）。</p>
 *
 * <p>判定链与能力网关 Hook 线（{@code PepInvocationInspector#authorizeBeforeExecute}）同构：
 * 主体解析（有真人 → CALLER；仅服务身份 → DIGITAL_EMPLOYEE，评审低-1 双锚语义）→
 * 空主体校验 → PDP 策略求值 → 影子记录 / ENFORCE 拦截。差异仅在动作（USE）与拦截出口
 * （对话入口无 invocation 记录，决策留痕由影子事件或结构化日志承载）。</p>
 *
 * <p>SHADOW（默认）：只记录不拦截——allow/deny 均落影子日志，original 取 Legacy/Grant
 * USE（DataAgent 走 {@code LegacyVisibilityPolicyProvider}，员工走授权 Grant），
 * 评估失败 quiet 吞掉，现网对话行为零变化。ENFORCE：空主体 fail-closed、PDP deny
 * 先落影子（original 仍是 Legacy/Grant 口径，供差异率对账）再抛 {@link CheckedException}
 * （BUSINESS_DENIED 语义，原因码原样传播）。</p>
 *
 * <p>X-Auto-Token 平台硬基线：请求头命中即记 {@link DecisionReasonCode#AUTO_TOKEN_FORBIDDEN}
 * 影子决策（SHADOW 记录、ENFORCE 拒绝）——系统自动 token 不得作为真人对话主体。</p>
 *
 * @author Leo (PR-4 遗留接线：对话入口 USE 判定)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationAuthorizationGuard {

	/**
	 * 影子日志场景标识：对话/调用入口 USE 判定。
	 */
	public static final String SCENE_CONVERSATION_USE = "CONVERSATION_USE";

	/**
	 * 影子日志场景标识：X-Auto-Token 平台硬基线命中。
	 */
	public static final String SCENE_AUTO_TOKEN = "AUTO_TOKEN";

	/**
	 * 系统自动 token 请求头（与 feign-plugin 的 {@code X-Auto-Token} 约定一致）。
	 */
	private static final String AUTO_TOKEN_HEADER = "X-Auto-Token";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator;

	private final ShadowRecorder shadowRecorder;

	private final InvocationSubjectGuard subjectGuard;

	private final PepAuthorizationProperties pepProperties;

	private final AuthenticationContext authenticationContext;

	private final OwnerAuthorizationQueryGateway ownerQueryGateway;

	/**
	 * 对话/调用入口 USE 判定（v1.2 PR-4：/stream/search 走 USE）。
	 *
	 * @param request 对话请求（null 直接放行，由入口自身的参数校验兜底）
	 */
	public void authorizeConversationUse(AgentRequest request) {
		if (request == null) {
			return;
		}
		String tenantId = resolveTenantId(request);
		PepAuthorizationMode mode = pepProperties.resolveMode(tenantId);
		rejectAutoTokenRequest(request, tenantId, mode);
		PepDecisionContext context = buildUseDecisionContext(request, tenantId);
		Boolean originalAllowed = originalUseDecision(context);
		InvocationSubjectGuard.SubjectCheckResult subjectCheck = subjectGuard.checkSubject(context, mode);
		if (mode.enforce()) {
			subjectGuard.rejectIfMissingSubject(subjectCheck, SCENE_CONVERSATION_USE);
		}
		else if (!subjectCheck.isCompliant()) {
			log.warn("对话入口主体缺失(SHADOW 仅记录). missingFields={}, agentId={}, tenantId={}, threadId={}",
					subjectCheck.missingFields(), request.getAgentId(), tenantId, request.getThreadId());
		}
		PepDecisionResult result;
		try {
			result = runtimePolicyEvaluator.evaluate(context);
		}
		catch (Exception ex) {
			if (mode.enforce()) {
				throw ex;
			}
			log.warn("对话入口 USE 判定评估失败(quiet, SHADOW). agentId={}, tenantId={}, threadId={}, errorType={}, "
					+ "errorMessage={}", request.getAgentId(), tenantId, request.getThreadId(),
					ex.getClass().getSimpleName(), ex.getMessage());
			return;
		}
		recordDecision(result, tenantId, request, originalAllowed);
		enforceDenyDecision(result, tenantId, request, originalAllowed);
	}

	/**
	 * USE 决策影子记录：original 取 Legacy/Grant USE（与任务守卫同构），不再写死 TRUE。
	 */
	private void recordDecision(PepDecisionResult result, String tenantId, AgentRequest request,
			Boolean originalAllowed) {
		if (result.allowed()) {
			shadowRecorder.recordShadowDecision(result, SCENE_CONVERSATION_USE, originalAllowed,
					parseTenantId(tenantId), null, null);
		}
	}

	/**
	 * ENFORCE 下 PDP deny 的强制拦截（与 {@code PepInvocationInspector#enforceDenyDecision} 同构）：
	 * 先落影子（original 仍是 Legacy/Grant USE），再抛 {@link CheckedException}。
	 * SHADOW 不拦截，deny 决策的影子记录在此补齐。
	 */
	private void enforceDenyDecision(PepDecisionResult result, String tenantId, AgentRequest request,
			Boolean originalAllowed) {
		if (result.allowed()) {
			return;
		}
		boolean enforce = result.getEffectiveMode() != null && result.getEffectiveMode().enforce();
		shadowRecorder.recordShadowDecision(result, SCENE_CONVERSATION_USE, originalAllowed,
				parseTenantId(tenantId), null, null);
		if (!enforce) {
			log.info("对话入口 USE 判定 deny(SHADOW 仅记录). decisionId={}, reasonCode={}, agentId={}, tenantId={}, "
					+ "threadId={}", result.getDecisionId(), result.reasonCode(), request.getAgentId(), tenantId,
					request.getThreadId());
			return;
		}
		throw CheckedException.fail("授权决策拒绝（BUSINESS_DENIED）：对话入口 USE 判定被授权策略拒绝, reasonCode="
				+ (result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name())
				+ ", decisionId=" + result.getDecisionId()
				+ ", agentId=" + request.getAgentId()
				+ ", tenantId=" + tenantId
				+ ", subjectKind=" + (result.getSubjectKind() == null ? null : result.getSubjectKind().getCode()));
	}

	/**
	 * X-Auto-Token 平台硬基线：命中系统自动 token 头即构造 {@link DecisionReasonCode#AUTO_TOKEN_FORBIDDEN}
	 * 决策落影子（不经过 PDP——硬基线无策略裁量空间）；ENFORCE 租户直接拒绝。
	 */
	private void rejectAutoTokenRequest(AgentRequest request, String tenantId, PepAuthorizationMode mode) {
		if (!autoTokenHeaderPresent()) {
			return;
		}
		AuthorizationDecision decision = AuthorizationDecision.builder()
			.allowed(false)
			.reasonCode(DecisionReasonCode.AUTO_TOKEN_FORBIDDEN)
			.obligations(List.of())
			.maskFields(List.of())
			.evaluatedAt(Instant.now())
			.build();
		PepDecisionResult result = PepDecisionResult.builder()
			.decisionId(UUID.randomUUID().toString())
			.subjectKind(SubjectKind.CALLER)
			.decision(decision)
			.effectiveMode(mode)
			.evaluatedAt(Instant.now())
			.build();
		shadowRecorder.recordShadowDecision(result, SCENE_AUTO_TOKEN, mode.enforce() ? Boolean.FALSE : Boolean.TRUE,
				parseTenantId(tenantId), null, null);
		if (mode.enforce()) {
			throw CheckedException.fail("授权决策拒绝（AUTO_TOKEN_FORBIDDEN）：系统自动 token 不得作为对话主体, "
					+ "decisionId=" + result.getDecisionId() + ", agentId=" + request.getAgentId()
					+ ", tenantId=" + tenantId);
		}
		log.warn("对话入口命中 X-Auto-Token(SHADOW 仅记录). agentId={}, tenantId={}, threadId={}",
				request.getAgentId(), tenantId, request.getThreadId());
	}

	/**
	 * owner 维度 USE 预检（现网口径）：DataAgent 走 Legacy 可见性，员工走 Grant。
	 * 快照或 owner 缺失、查询失败时返回 null（无法观测，不阻断对话）。
	 */
	private Boolean originalUseDecision(PepDecisionContext context) {
		if (context.getOwnerType() == null || context.getOwnerId() == null) {
			return null;
		}
		AuthorizationSubjectSnapshot snapshot = buildSubjectSnapshot(context);
		if (snapshot == null) {
			return null;
		}
		try {
			return ownerQueryGateway.canUse(context.getOwnerType(), context.getOwnerId(), snapshot);
		}
		catch (RuntimeException ex) {
			log.warn("对话入口 Legacy USE 预检失败(quiet). ownerType={}, ownerId={}, tenantId={}, errorType={}",
					context.getOwnerType(), context.getOwnerId(), context.getTenantId(),
					ex.getClass().getSimpleName());
			return null;
		}
	}

	private AuthorizationSubjectSnapshot buildSubjectSnapshot(PepDecisionContext context) {
		if (!StringUtils.hasText(context.getSubjectId()) && !StringUtils.hasText(context.getTenantId())) {
			return null;
		}
		return AuthorizationSubjectSnapshot.builder()
			.userId(context.getSubjectId())
			.tenantId(context.getTenantId())
			.teamIds(safeTeamIds())
			.funcPermissions(safeFuncPermissions())
			.admin(isHumanAdmin(context.getSubjectId()))
			.build();
	}

	private List<String> safeTeamIds() {
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				List<String> teamIds = authenticationContext.teamIds();
				return teamIds == null ? List.of() : teamIds;
			}
		}
		catch (RuntimeException ex) {
			log.debug("对话入口读取团队列表失败. errorType={}", ex.getClass().getSimpleName());
		}
		return List.of();
	}

	private List<String> safeFuncPermissions() {
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				List<String> permissions = authenticationContext.funcPermissionList();
				return permissions == null ? List.of() : permissions;
			}
		}
		catch (RuntimeException ex) {
			log.debug("对话入口读取功能权限失败. errorType={}", ex.getClass().getSimpleName());
		}
		return List.of();
	}

	private boolean isHumanAdmin(String userId) {
		if (isServicePrincipalId(userId)) {
			return false;
		}
		try {
			return authenticationContext != null && !authenticationContext.anonymous()
					&& UserType.isAdmin(authenticationContext.userType());
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	/**
	 * 组装 USE 决策上下文：owner 解析（请求显式携带优先，普通智能体对话回退 DATA_AGENT+agentId）、
	 * 主体推导（数字员工运行主体 → DIGITAL_EMPLOYEE，否则 CALLER 真人）、动作 USE。
	 */
	private PepDecisionContext buildUseDecisionContext(AgentRequest request, String tenantId) {
		AuthorizationOwnerType ownerType = resolveOwnerType(request);
		Long ownerId = resolveOwnerId(request, ownerType);
		SubjectHolder subject = resolveSubject(request, ownerType);
		return PepDecisionContext.builder()
			.tenantId(tenantId)
			.ownerType(ownerType)
			.ownerId(ownerId)
			.subjectKind(subject.kind())
			.subjectId(subject.id())
			.action(AuthorizationAction.USE)
			.build();
	}

	/**
	 * owner 类型解析：请求显式携带（DIGITAL_EMPLOYEE/CALLER）优先；未携带按普通智能体对话
	 * 处理（DATA_AGENT 域，授权策略绑定维度）。
	 */
	private AuthorizationOwnerType resolveOwnerType(AgentRequest request) {
		AuthorizationOwnerType ownerType = AuthorizationOwnerType.fromCode(request.getOwnerType());
		return ownerType == null ? AuthorizationOwnerType.DATA_AGENT : ownerType;
	}

	/**
	 * owner ID 解析：数字员工链路取请求 ownerId（员工ID）；其余按 agentId（DATA_AGENT 域）。
	 */
	private Long resolveOwnerId(AgentRequest request, AuthorizationOwnerType ownerType) {
		if (ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE && request.getOwnerId() != null) {
			return request.getOwnerId();
		}
		if (!StringUtils.hasText(request.getAgentId())) {
			return null;
		}
		try {
			return Long.valueOf(request.getAgentId().trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 主体推导（按触发身份标注，评审低-1）：真人用户ID（快照优先，Web 当前登录人 /
	 * IM 委托真人均由快照承载）→ CALLER；数字员工链路无真人快照、或解析到
	 * {@code sp_} 服务身份时 → DIGITAL_EMPLOYEE（无人值守 / 换票后兜底）。
	 */
	private SubjectHolder resolveSubject(AgentRequest request, AuthorizationOwnerType ownerType) {
		boolean employeeOwned = ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE;
		String userId = resolveUserId(request);
		if (StringUtils.hasText(userId)) {
			if (employeeOwned && isServicePrincipalId(userId)) {
				return new SubjectHolder(SubjectKind.DIGITAL_EMPLOYEE, userId.trim());
			}
			return new SubjectHolder(SubjectKind.CALLER, userId.trim());
		}
		if (employeeOwned && request.getOwnerId() != null) {
			return new SubjectHolder(SubjectKind.DIGITAL_EMPLOYEE, String.valueOf(request.getOwnerId()));
		}
		return new SubjectHolder(SubjectKind.CALLER, null);
	}

	private boolean isServicePrincipalId(String userId) {
		return userId != null && userId.trim().startsWith("sp_");
	}

	/**
	 * 真人用户ID解析：请求快照优先（Controller/IM 通道在入口写入），授权上下文回退
	 * （异步委托链路），读取异常按无用户处理（ENFORCE 由空主体校验失败关闭）。
	 */
	private String resolveUserId(AgentRequest request) {
		if (StringUtils.hasText(request.getUserIdSnapshot())) {
			return request.getUserIdSnapshot().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.userId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("对话入口读取授权上下文用户失败. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * 租户解析：请求快照优先，授权上下文回退，读取异常按无租户处理（ENFORCE 由空主体校验
	 * 失败关闭；SHADOW 仅在影子明细中体现）。
	 */
	private String resolveTenantId(AgentRequest request) {
		if (StringUtils.hasText(request.getTenantIdSnapshot())) {
			return request.getTenantIdSnapshot().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.tenantId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("对话入口读取授权上下文租户失败. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * X-Auto-Token 请求头检测（仅 Web 入口线程可解析；异步/非 Web 上下文视为未命中）。
	 */
	private boolean autoTokenHeaderPresent() {
		try {
			if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes) {
				return servletAttributes.getRequest().getHeader(AUTO_TOKEN_HEADER) != null;
			}
		}
		catch (IllegalStateException ex) {
			log.debug("对话入口解析请求上下文失败, 按 X-Auto-Token 未命中处理. errorType={}",
					ex.getClass().getSimpleName());
		}
		return false;
	}

	/**
	 * 租户ID转 Long（事件表数字口径；解析失败返回 null 由 ShadowRecorder 落 0）。
	 */
	private Long parseTenantId(String tenantId) {
		if (StrUtil.isBlank(tenantId)) {
			return null;
		}
		try {
			return Long.valueOf(tenantId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 主体（类别, 标识）不可变对。
	 */
	private record SubjectHolder(SubjectKind kind, String id) {
	}

}
