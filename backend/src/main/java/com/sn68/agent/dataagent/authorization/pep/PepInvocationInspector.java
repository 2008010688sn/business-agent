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
package com.sn68.agent.dataagent.authorization.pep;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInvocation;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeInvocationState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeInvocationMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 能力网关 PEP 检查员（PR-3c 交付物 3 的 Hook/输出两线接缝组合器）。
 *
 * <p>{@code DefaultCapabilityGateway} 的唯一新增依赖：检查链起点调用 {@link #authorizeBeforeExecute}
 * （Hook 线，ENFORCE 只拒绝空主体）、紧随其后调用 {@link #enforceDenyDecision}（PR-4 接线：
 * ENFORCE 下 PDP deny 强制拦截）、{@code openInvocation} 之后调用
 * {@link #recordAfterInvocationOpened}（现网已放行口径的影子记录 + invocation 审计列回填）、
 * 结果封装前调用 {@link #applyOutputObligations}（输出线，MASK_FIELDS/FILTER_FIELDS）。</p>
 *
 * <p>入参用本类声明的原始字段 record 而非 capability 包类型，保持依赖方向单向
 * （capability → authorization.pep），pep 内核不感知网关请求结构。</p>
 *
 * <p>默认 SHADOW：三条方法均不改变现网语义——评估失败 quiet 吞掉、PDP deny 只进影子日志、
 * 输出义务仅预览；ENFORCE（租户白名单灰度）时空主体 fail-closed、PDP deny 经
 * {@link #enforceDenyDecision} 拦截、义务真实生效。</p>
 *
 * @author James (PR-3c PEP 内核扩展、PR-4 ENFORCE 接线)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PepInvocationInspector {

	/**
	 * 影子日志场景标识：能力网关执行线（Hook 检查链）。
	 */
	public static final String SCENE_HOOK_EXECUTE = "HOOK_EXECUTE";

	/**
	 * 影子日志场景标识：输出线义务跳过（String 输出无法按 JSON 应用义务时的 OBLIGATION_SKIPPED 标记）。
	 */
	public static final String SCENE_OUTPUT_OBLIGATION = "OUTPUT_OBLIGATION";

	/**
	 * ENFORCE 拒绝终态 invocation 幂等键前缀：决策ID全局唯一，不与正常调用键（cap:source:...）冲突。
	 */
	private static final String DENIAL_IDEMPOTENCY_KEY_PREFIX = "authz-deny-";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator;

	private final InvocationSubjectGuard subjectGuard;

	private final ShadowRecorder shadowRecorder;

	private final OutputObligationApplier outputObligationApplier;

	private final PepAuthorizationProperties properties;

	private final AgentRuntimeInvocationMapper invocationMapper;

	private final ObjectMapper objectMapper;

	/**
	 * Hook 线决策输入（网关从 InvocationRequest 抽取的原始字段）。
	 *
	 * @param tenantId          已解析租户ID（网关 resolvedTenantId 口径，可空）
	 * @param runId             运行ID（可空）
	 * @param stepKey           步骤键（可空）
	 * @param ownerType         运行主体类型编码（DIGITAL_EMPLOYEE/CALLER/PLATFORM，可空）
	 * @param ownerId           运行主体ID（可空）
	 * @param capabilityCode    能力编码（必填，网关入口已校验）
	 * @param capabilityVersion 请求的能力版本（可空，快照版本源 PR-3d 接入）
	 * @param agentId           智能体ID（可空）
	 * @param userId            用户ID（可空）
	 */
	public record HookDecisionInput(String tenantId, Long runId, String stepKey, String ownerType, Long ownerId,
			String capabilityCode, String capabilityVersion, Long agentId, String userId) {
	}

	/**
	 * Hook 线：能力执行前判定。
	 *
	 * <p>ENFORCE 只拒绝空主体（缺 tenant/subject 即策略求值无法归属，平台硬基线）；PDP deny
	 * 决策照常返回，强制拦截由紧随其后的 {@link #enforceDenyDecision} 承担（PR-4 已接线）。</p>
	 *
	 * @param input 决策输入（网关抽取）
	 * @return 决策结果；SHADOW 且评估失败时返回 null（现网行为不变）
	 */
	public PepDecisionResult authorizeBeforeExecute(HookDecisionInput input) {
		PepDecisionContext context = buildContext(input);
		PepAuthorizationMode mode = properties.resolveMode(input.tenantId());
		InvocationSubjectGuard.SubjectCheckResult subjectCheck = subjectGuard.checkSubject(context, mode);
		if (mode.enforce()) {
			subjectGuard.rejectIfMissingSubject(subjectCheck, SCENE_HOOK_EXECUTE);
		}
		else if (!subjectCheck.isCompliant()) {
			log.warn("能力执行主体缺失(SHADOW 仅记录). missingFields={}, capabilityCode={}, tenantId={}, runId={}",
					subjectCheck.missingFields(), input.capabilityCode(), input.tenantId(), input.runId());
		}
		try {
			return runtimePolicyEvaluator.evaluate(context);
		}
		catch (Exception ex) {
			if (mode.enforce()) {
				throw ex;
			}
			log.warn("能力执行 PEP 评估失败(quiet, SHADOW). capabilityCode={}, tenantId={}, runId={}, errorType={}, "
					+ "errorMessage={}", input.capabilityCode(), input.tenantId(), input.runId(),
					ex.getClass().getSimpleName(), ex.getMessage());
			return null;
		}
	}

	/**
	 * PR-4 接线：ENFORCE 下 PDP deny 的强制拦截（网关授权检查项）。
	 *
	 * <p>SHADOW：不拦截（现网行为不变），deny 决策照常返回，由 {@link #recordAfterInvocationOpened}
	 * 在现网检查链放行后落影子日志（original=TRUE → MISMATCHED，PR-10 差异报告的核心输入）。</p>
	 *
	 * <p>ENFORCE：deny 即拦截——先落影子日志（originalAllowed=FALSE：拦截即现网最终结论，
	 * MATCHED 口径不虚增差异率），再补写终态 invocation 留痕（评审高-2：被拦截调用不会走到
	 * openInvocation，审计列无从回填，补一条 FAILED 终态记录携带决策审计四列），最后抛
	 * {@link CheckedException}（BUSINESS_DENIED 语义，PDP 原因码原样传播不重映射）。</p>
	 *
	 * @param result 前置判定结果（null 表示 SHADOW 评估失败，直接跳过）
	 * @param input  决策输入（与前置判定同一份）
	 */
	public void enforceDenyDecision(PepDecisionResult result, HookDecisionInput input) {
		if (result == null || result.allowed()) {
			return;
		}
		if (result.getEffectiveMode() == null || !result.getEffectiveMode().enforce()) {
			return;
		}
		shadowRecorder.recordShadowDecision(result, SCENE_HOOK_EXECUTE, Boolean.FALSE,
				parseTenantId(input.tenantId()), input.runId(), input.stepKey());
		recordEnforcedDenial(result, input);
		throw CheckedException.fail("授权决策拒绝（BUSINESS_DENIED）：能力执行被授权策略拒绝, reasonCode="
				+ (result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name())
				+ ", decisionId=" + result.getDecisionId()
				+ ", capabilityCode=" + input.capabilityCode()
				+ ", tenantId=" + input.tenantId()
				+ ", subjectKind=" + (result.getSubjectKind() == null ? null : result.getSubjectKind().getCode()));
	}

	/**
	 * 现网检查链放行后的影子记录与审计列回填（quiet）。
	 *
	 * <p>调用时点在 {@code openInvocation} 之后：走到此处即现网结论 allowed=true，
	 * original_decision 按 TRUE 记录参与影子比对；invocation 审计列（decision_id/policy_hash/
	 * subject_kind/reason_code）回填失败不影响主流程。</p>
	 *
	 * @param result       前置判定结果（null 表示 SHADOW 评估失败，跳过）
	 * @param input        决策输入（复用前置判定的同一份）
	 * @param invocationId 调用记录ID（null 表示无 runId 调用，跳过审计列回填）
	 */
	public void recordAfterInvocationOpened(PepDecisionResult result, HookDecisionInput input, Long invocationId) {
		if (result == null) {
			return;
		}
		shadowRecorder.recordShadowDecision(result, SCENE_HOOK_EXECUTE, Boolean.TRUE,
				parseTenantId(input.tenantId()), input.runId(), input.stepKey());
		if (invocationId == null) {
			return;
		}
		AuthorizationDecision decision = result.getDecision();
		try {
			invocationMapper.attachDecision(invocationId, result.getDecisionId(),
					decision == null ? null : decision.getPolicyHash(),
					result.getSubjectKind() == null ? null : result.getSubjectKind().getCode(),
					result.reasonCode() == null ? null : result.reasonCode().name());
		}
		catch (Exception ex) {
			// 审计列回填失败不破坏调用主流程：决策字段完整带回日志便于人工补录
			log.warn("invocation 授权审计列回填失败(quiet). invocationId={}, decisionId={}, reasonCode={}, "
					+ "errorType={}, errorMessage={}", invocationId, result.getDecisionId(), result.reasonCode(),
					ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * ENFORCE 拒绝终态留痕（评审高-2）：被拦截调用不会走到 openInvocation，invocation 审计列
	 * 无从回填——补写一条 FAILED 终态 invocation 记录，携带决策审计四列（decision_id/policy_hash/
	 * subject_kind/reason_code，DDL 列已存在），供审计检索与对账。幂等键为 authz-deny-{decisionId}，
	 * 决策ID全局唯一故不与正常调用键冲突；写入失败 quiet 吞掉（拒绝本身必须照常抛出，不能因
	 * 审计失败放行或二次失败）；无 runId 时 invocation 无处挂靠，留痕由影子事件/结构化日志承载。
	 */
	private void recordEnforcedDenial(PepDecisionResult result, HookDecisionInput input) {
		if (input.runId() == null) {
			log.warn("ENFORCE 拒绝留痕(无runId, 降级日志输出). decisionId={}, reasonCode={}, capabilityCode={}, "
					+ "tenantId={}", result.getDecisionId(),
					result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name(), input.capabilityCode(),
					input.tenantId());
			return;
		}
		AuthorizationDecision decision = result.getDecision();
		String tenantId = input.tenantId();
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			log.warn("ENFORCE 拒绝留痕(无租户, 跳过 invocation 落库). decisionId={}, reasonCode={}, capabilityCode={}, "
					+ "runId={}", result.getDecisionId(),
					result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name(), input.capabilityCode(),
					input.runId());
			return;
		}
		try {
			AgentRuntimeInvocation denial = AgentRuntimeInvocation.builder()
					.tenantId(tenantId.trim())
					.runId(input.runId())
					.idempotencyKey(DENIAL_IDEMPOTENCY_KEY_PREFIX + result.getDecisionId())
					.capabilityHandle(input.capabilityCode())
					.state(RuntimeInvocationState.FAILED.getValue())
					.stateVersion(0L)
					.errorCode(result.reasonCode() == null ? "UNKNOWN" : result.reasonCode().name())
					.errorMessage("授权决策拒绝（BUSINESS_DENIED）：能力执行被授权策略拦截，未发出外部调用")
					.completedAt(Instant.now())
					.decisionId(result.getDecisionId())
					.policyHash(decision == null ? null : decision.getPolicyHash())
					.subjectKind(result.getSubjectKind() == null ? null : result.getSubjectKind().getCode())
					.reasonCode(result.reasonCode() == null ? null : result.reasonCode().name())
					.createTime(Instant.now())
					.lastModifyTime(Instant.now())
					.deleted(false)
					.build();
			invocationMapper.insert(denial);
		}
		catch (Exception ex) {
			log.warn("ENFORCE 拒绝终态 invocation 落库失败(quiet). decisionId={}, runId={}, capabilityCode={}, "
					+ "errorType={}, errorMessage={}", result.getDecisionId(), input.runId(), input.capabilityCode(),
					ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * 输出线：非 Map 形态数据的义务处理（JSON 感知，评审高-1）。
	 *
	 * <p>AgentScope 进程内工具经网关返回 String（JSON 文本），原实现对非 Map 输出原样返回导致
	 * 义务静默失效（脱敏 no-op 且 SHADOW 无预览）。现按 JSON 解析：对象/数组解析成功时 ENFORCE
	 * 经 {@link OutputObligationApplier} 应用义务后重新序列化返回，SHADOW 只记预览日志不修改
	 * 输出；解析失败时 MASK 落 OBLIGATION_SKIPPED 影子标记并告警，FILTER 在 ENFORCE 下保守
	 * fail-closed。无义务、非 String 输出或空白文本保持原样透传（现网行为不变）。</p>
	 */
	private Object handleNonMapData(PepDecisionResult result, AuthorizationDecision decision,
			String sensitiveFieldsJson, Object data, boolean enforce, HookDecisionInput input) {
		Set<String> defaultFields = parseSensitiveFields(sensitiveFieldsJson);
		Set<String> preview = outputObligationApplier.shadowPreview(decision, defaultFields);
		if (preview.isEmpty()) {
			return data;
		}
		if (!(data instanceof String text) || !StringUtils.hasText(text)) {
			// 非 String 输出（List 等内存对象）与空文本无从按 JSON 字段定位，保持现网透传
			return data;
		}
		Object parsed = parseJsonPayload(text);
		if (!(parsed instanceof Map) && !(parsed instanceof List)) {
			return handleUnparseablePayload(result, decision, enforce, input, text);
		}
		if (!enforce) {
			log.info("输出义务影子预览(SHADOW 不修改输出). decisionId={}, previewFields={}, payloadShape={}",
					result.getDecisionId(), preview, parsed instanceof Map ? "OBJECT" : "ARRAY");
			return data;
		}
		String processed = applyToJsonPayload(decision, defaultFields, parsed);
		if (processed == null) {
			return handleUnparseablePayload(result, decision, enforce, input, text);
		}
		return processed;
	}

	/**
	 * String 输出按 JSON 解析（对象/数组）；非 JSON 文本或标量 JSON 返回 null 交由调用方兜底。
	 */
	private Object parseJsonPayload(String text) {
		try {
			return objectMapper.readValue(text, Object.class);
		}
		catch (Exception ex) {
			return null;
		}
	}

	/**
	 * JSON 解析成功后的义务应用：对象整体应用，数组逐元素应用（元素为对象才处理，标量元素
	 * 原样保留），处理后重新序列化；序列化失败返回 null 交由调用方按解析失败口径兑底。
	 */
	private String applyToJsonPayload(AuthorizationDecision decision, Set<String> defaultFields, Object parsed) {
		try {
			if (parsed instanceof Map<?, ?>) {
				@SuppressWarnings("unchecked")
				Map<String, Object> payload = (Map<String, Object>) parsed;
				return objectMapper.writeValueAsString(
						outputObligationApplier.applyObligations(decision, defaultFields, payload));
			}
			List<?> list = (List<?>) parsed;
			List<Object> processed = new ArrayList<>(list.size());
			for (Object item : list) {
				if (item instanceof Map<?, ?>) {
					@SuppressWarnings("unchecked")
					Map<String, Object> payload = (Map<String, Object>) item;
					processed.add(outputObligationApplier.applyObligations(decision, defaultFields, payload));
				}
				else {
					processed.add(item);
				}
			}
			return objectMapper.writeValueAsString(processed);
		}
		catch (Exception ex) {
			log.warn("输出义务 JSON 序列化失败. errorType={}, errorMessage={}", ex.getClass().getSimpleName(),
					ex.getMessage());
			return null;
		}
	}

	/**
	 * JSON 解析失败时的义务兑底（差异报告可见性）：MASK 落 OBLIGATION_SKIPPED 影子标记并
	 * 告警后透传；FILTER 在 ENFORCE 下保守 fail-closed（无法证明输出不含过滤字段即拒绝，
	 * BUSINESS_DENIED 语义），SHADOW 仅落标记不拦截。
	 */
	private Object handleUnparseablePayload(PepDecisionResult result, AuthorizationDecision decision,
			boolean enforce, HookDecisionInput input, String text) {
		boolean filterObligation = decision.getObligations() != null
				&& decision.getObligations().contains(AuthorizationObligation.FILTER_FIELDS);
		String skippedObligation = filterObligation ? AuthorizationObligation.FILTER_FIELDS.name()
				: AuthorizationObligation.MASK_FIELDS.name();
		log.warn("输出义务无法应用(输出不是可解析的 JSON 对象/数组). decisionId={}, obligation={}, enforce={}, "
					+ "capabilityCode={}, tenantId={}, runId={}", result.getDecisionId(), skippedObligation, enforce,
				input == null ? null : input.capabilityCode(), input == null ? null : input.tenantId(),
				input == null ? null : input.runId());
		shadowRecorder.recordObligationSkipped(result, SCENE_OUTPUT_OBLIGATION, skippedObligation,
				"UNPARSEABLE_OUTPUT", input == null ? null : parseTenantId(input.tenantId()),
				input == null ? null : input.runId(), input == null ? null : input.stepKey());
		if (filterObligation && enforce) {
			throw CheckedException.fail("输出义务拒绝（BUSINESS_DENIED）：FILTER_FIELDS 义务无法应用"
						+ "（输出不是可解析的 JSON 对象/数组），保守拒绝以防过滤字段泄露, decisionId="
						+ result.getDecisionId()
						+ (input == null ? "" : ", capabilityCode=" + input.capabilityCode()));
		}
		return text;
	}

	/**
	 * 输出线：结果封装前应用输出义务。
	 *
	 * <p>ENFORCE：按决策义务对 Map 形态 data 执行 FILTER_FIELDS/MASK_FIELDS（副本处理，原数据不动）；
	 * String 形态（AgentScope 进程内工具经网关的 JSON 文本）尝试按 JSON 解析（对象为主、兼顾数组）
	 * 后同样应用义务再重新序列化返回（评审高-1：原实现对非 Map 输出原样返回，义务静默失效）；
	 * SHADOW：只预览"若 ENFORCE 将处理的字段清单"并结构化日志，不修改输出。决策级义务（策略规则
	 * 产生的 maskFields）优先于能力契约默认敏感字段集，能力目录版本为 null 时依然生效。
	 * JSON 解析失败时 MASK 落 OBLIGATION_SKIPPED 影子标记并告警，FILTER 在 ENFORCE 下保守
	 * fail-closed 拒绝（BUSINESS_DENIED 语义）。</p>
	 *
	 * @param result              前置判定结果（null 直接透传）
	 * @param sensitiveFieldsJson 能力契约默认敏感字段集 JSON 数组（可空）
	 * @param data                能力执行输出数据
	 * @param input               决策输入（义务跳过标记的租户/运行/步骤定位，可空）
	 * @return 处理后的输出（SHADOW/无义务/非 JSON 文本时为原对象或原字符串）
	 */
	public Object applyOutputObligations(PepDecisionResult result, String sensitiveFieldsJson, Object data,
			HookDecisionInput input) {
		if (result == null || result.getDecision() == null || data == null) {
			return data;
		}
		AuthorizationDecision decision = result.getDecision();
		boolean enforce = result.getEffectiveMode() != null && result.getEffectiveMode().enforce();

		// 1. 非 Map 形态：String 尝试按 JSON 解析应用义务（评审高-1），其余透传
		if (!(data instanceof Map<?, ?> map)) {
			return handleNonMapData(result, decision, sensitiveFieldsJson, data, enforce, input);
		}
		// 2. Map 形态：原有义务应用/预览流程
		Set<String> defaultFields = parseSensitiveFields(sensitiveFieldsJson);
		if (!enforce) {
			Set<String> preview = outputObligationApplier.shadowPreview(decision, defaultFields);
			if (!preview.isEmpty()) {
				log.info("输出义务影子预览(SHADOW 不修改输出). decisionId={}, previewFields={}",
						result.getDecisionId(), preview);
			}
			return data;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) map;
		return outputObligationApplier.applyObligations(decision, defaultFields, payload);
	}

	/**
	 * 组装决策上下文：owner 解析（PLATFORM 未纳入策略域按无 owner 求值）、主体推导
	 * （数字员工运行主体 → DIGITAL_EMPLOYEE，否则 CALLER 真人用户）、动作 EXECUTE。
	 */
	private PepDecisionContext buildContext(HookDecisionInput input) {
		AuthorizationOwnerType ownerType = AuthorizationOwnerType.fromCode(input.ownerType());
		boolean employeeOwned = AuthorizationOwnerType.DIGITAL_EMPLOYEE == ownerType;
		SubjectKindHolder holder = resolveSubject(employeeOwned, input);
		return PepDecisionContext.builder()
			.tenantId(input.tenantId())
			.runId(input.runId())
			.stepKey(input.stepKey())
			.ownerType(ownerType)
			.ownerId(input.ownerId())
			.subjectKind(holder.kind())
			.subjectId(holder.id())
			.capabilityCode(input.capabilityCode())
			.capabilityVersion(input.capabilityVersion())
			.action(AuthorizationAction.EXECUTE)
			.build();
	}

	/**
	 * 主体推导（按触发身份标注，评审低-1）：有真人 userId → (CALLER, 真人ID)——真人发起的
	 * employeeOwned 调用主体是 CALLER（PDP 双锚语义下不被员工硬基线拒绝）；仅服务身份
	 * （employeeOwned 且 ownerId 非空）→ (DIGITAL_EMPLOYEE, 员工ID)；其余主体不可归属
	 * （subjectId 空，ENFORCE 由空主体校验失败关闭）。
	 */
	private SubjectKindHolder resolveSubject(boolean employeeOwned, HookDecisionInput input) {
		if (StringUtils.hasText(input.userId())) {
			return new SubjectKindHolder(com.sn68.agent.dataagent.authorization.pdp.SubjectKind.CALLER,
					input.userId().trim());
		}
		if (employeeOwned && input.ownerId() != null) {
			return new SubjectKindHolder(com.sn68.agent.dataagent.authorization.pdp.SubjectKind.DIGITAL_EMPLOYEE,
					String.valueOf(input.ownerId()));
		}
		return new SubjectKindHolder(com.sn68.agent.dataagent.authorization.pdp.SubjectKind.CALLER, null);
	}

	/**
	 * 主体（类别, 标识）不可变对。
	 */
	private record SubjectKindHolder(com.sn68.agent.dataagent.authorization.pdp.SubjectKind kind, String id) {
	}

	/**
	 * 能力契约敏感字段集解析（JSON 数组字符串 → 有序集合；损坏或空解析为空集）。
	 */
	private Set<String> parseSensitiveFields(String sensitiveFieldsJson) {
		if (!StringUtils.hasText(sensitiveFieldsJson) || "[]".equals(sensitiveFieldsJson.trim())) {
			return Set.of();
		}
		try {
			return new LinkedHashSet<>(objectMapper.readValue(sensitiveFieldsJson, new TypeReference<Set<String>>() {
			}));
		}
		catch (Exception ex) {
			log.debug("能力契约敏感字段集解析失败, 按空默认集处理. errorType={}, errorMessage={}",
					ex.getClass().getSimpleName(), ex.getMessage());
			return Set.of();
		}
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

}
