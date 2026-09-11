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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.observability.AuthorizationMetrics;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 授权决策影子记录器（PR-3c 交付物 2）。
 *
 * <p>把 PEP 决策结果写入 {@code agent_runtime_event}：事件类型 AUTHORIZATION_DECISION，
 * 审计四列（decision_id/policy_hash/subject_kind/reason_code）冗余落库供 SQL 聚合，
 * 明细（original_decision/shadow_decision/comparison_status 等）写入
 * {@code detailed_decision_log(JSONB)}，供 PR-10 差异报告消费。</p>
 *
 * <p>失败语义（硬约束）：审计写入失败不得破坏主流程——所有异常 quiet 吞掉并 log.warn；
 * runId 缺失时事件无处挂靠（表按 run 组织），降级为结构化日志输出同等字段。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowRecorder {

	/**
	 * 影子事件类型值（与 {@link RuntimeEventType#AUTHORIZATION_DECISION} 一致，SQL 直传字符串）。
	 */
	private static final String EVENT_TYPE_AUTHZ = RuntimeEventType.AUTHORIZATION_DECISION.getValue();

	/**
	 * 事件幂等键前缀：同一 decisionId 只落一行。
	 */
	private static final String EVENT_KEY_PREFIX = "authz-decision-";

	/**
	 * 义务跳过事件幂等键前缀：与主决策影子行（authz-decision-）区分，同一 decisionId 只落一行。
	 */
	private static final String OBLIGATION_SKIPPED_KEY_PREFIX = "authz-obligation-skipped-";

	private final AgentRuntimeEventMapper eventMapper;

	private final ObjectMapper objectMapper;

	// PR-10 指标挂点：全部场景（HOOK/MEMORY/TASK 等）统一汇聚到本记录器，计数旁路不影响落库
	private final AuthorizationMetrics metrics;

	/**
	 * 记录一次影子决策（quiet）。
	 *
	 * @param result          PEP 决策结果（不允许为 null，调用方保证）
	 * @param scene           判定场景标识（HOOK/OUTPUT/MEMORY_READ/MEMORY_WRITE/TASK_START）
	 * @param originalAllowed 现网链路实际结论（null 表示无法观测）
	 * @param tenantId        租户ID（Long 口径，事件表按数字租户落库；null 落 0 与既有事件口径一致）
	 * @param runId           运行ID（null 时降级结构化日志）
	 * @param stepKey         步骤键（可空）
	 */
	public void recordShadowDecision(PepDecisionResult result, String scene, Boolean originalAllowed, Long tenantId,
			Long runId, String stepKey) {
		if (result == null) {
			return;
		}
		AuthorizationDecision decision = result.getDecision();
		ShadowComparisonStatus comparison = ShadowComparisonStatus.of(originalAllowed, result.allowed());
		Long safeTenantId = tenantId == null ? 0L : tenantId;
		// PR-10 指标挂点（旁路）：比对计数 + 决策计数，供差异率实时观测；失败不中断影子落库
		metrics.recordShadowComparison(comparison.getCode(), safeTenantId);
		metrics.recordDecision(scene, result.getEffectiveMode() == null ? null : result.getEffectiveMode().getCode(),
				result.reasonCode() == null ? null : result.reasonCode().name(), result.allowed());
		Map<String, Object> detailedLog = buildDetailedLog(result, scene, originalAllowed, comparison);
		String decisionId = result.getDecisionId();
		if (runId == null) {
			log.info("授权影子决策(无run挂靠, 降级日志输出). decisionId={}, scene={}, policyHash={}, subjectKind={}, "
					+ "reasonCode={}, originalAllowed={}, shadowAllowed={}, comparisonStatus={}, mode={}, "
					+ "revisionMatched={}", decisionId, scene, decision == null ? null : decision.getPolicyHash(),
					result.getDecision() == null ? null : subjectKindCode(result), result.reasonCode(),
					originalAllowed, result.allowed(), comparison.getCode(),
					result.getEffectiveMode() == null ? null : result.getEffectiveMode().getCode(),
					result.getRevisionMatched());
			return;
		}
		try {
			eventMapper.appendAuthorizationDecision(String.valueOf(safeTenantId), runId, EVENT_KEY_PREFIX + decisionId,
					EVENT_TYPE_AUTHZ, stepKey, serialize(detailedLog), decisionId,
					decision == null ? null : decision.getPolicyHash(), subjectKindCode(result),
					result.reasonCode() == null ? null : result.reasonCode().name(), Instant.now());
		}
		catch (Exception ex) {
			// 审计写入失败不得破坏主流程：quiet + warn，决策字段完整带回日志便于人工补录
			log.warn("授权影子决策落库失败(quiet). decisionId={}, scene={}, runId={}, subjectKind={}, reasonCode={}, "
					+ "detailedLog={}, errorType={}, errorMessage={}", decisionId, scene, runId, subjectKindCode(result),
					result.reasonCode(), serializeQuietly(detailedLog), ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * 记录一次输出义务跳过标记（quiet，评审高-1）：String 输出无法按 JSON 解析应用义务时，
	 * 落 OBLIGATION_SKIPPED 事件行（event_key 独立前缀 authz-obligation-skipped-，不与主决策
	 * 影子行的 NOT EXISTS 幂等冲突），供差异报告与人工核对检索。不携带比对字段（义务跳过不是
	 * allow/deny 比对事件，不参与 PR-10 差异率分母）、不走 metrics 计数（不虚增比对计数）；
	 * 写入失败不破坏主流程。
	 *
	 * @param result            PEP 决策结果（null 直接返回）
	 * @param scene             场景标识（OUTPUT_OBLIGATION）
	 * @param skippedObligation 被跳过的义务（MASK_FIELDS/FILTER_FIELDS）
	 * @param skipReason        跳过原因码（UNPARSEABLE_OUTPUT）
	 * @param tenantId          租户ID（null 落 0）
	 * @param runId             运行ID（null 时降级结构化日志）
	 * @param stepKey           步骤键（可空）
	 */
	public void recordObligationSkipped(PepDecisionResult result, String scene, String skippedObligation,
			String skipReason, Long tenantId, Long runId, String stepKey) {
		if (result == null) {
			return;
		}
		AuthorizationDecision decision = result.getDecision();
		Long safeTenantId = tenantId == null ? 0L : tenantId;
		Map<String, Object> detailedLog = buildSkippedObligationLog(result, scene, skippedObligation, skipReason);
		String decisionId = result.getDecisionId();
		if (runId == null) {
			log.warn("输出义务跳过标记(无run挂靠, 降级日志输出). decisionId={}, scene={}, obligation={}, "
					+ "skipReason={}, subjectKind={}, reasonCode={}, mode={}", decisionId, scene, skippedObligation,
				skipReason, subjectKindCode(result),
				result.reasonCode() == null ? null : result.reasonCode().name(),
				result.getEffectiveMode() == null ? null : result.getEffectiveMode().getCode());
			return;
		}
		try {
			eventMapper.appendAuthorizationDecision(String.valueOf(safeTenantId), runId, OBLIGATION_SKIPPED_KEY_PREFIX + decisionId,
					EVENT_TYPE_AUTHZ, stepKey, serialize(detailedLog), decisionId,
					decision == null ? null : decision.getPolicyHash(), subjectKindCode(result),
					result.reasonCode() == null ? null : result.reasonCode().name(), Instant.now());
		}
		catch (Exception ex) {
			// 审计写入失败不得破坏主流程：quiet + warn
			log.warn("输出义务跳过标记落库失败(quiet). decisionId={}, scene={}, runId={}, obligation={}, "
					+ "errorType={}, errorMessage={}", decisionId, scene, runId, skippedObligation,
					ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * 组装 detailed_decision_log 明细：decision_id/policy_hash/original_decision/shadow_decision/
	 * comparison_status + PEP 扩展观测字段（scene/mode/revision_matched/bind_revision）。
	 */
	private Map<String, Object> buildDetailedLog(PepDecisionResult result, String scene, Boolean originalAllowed,
			ShadowComparisonStatus comparison) {
		AuthorizationDecision decision = result.getDecision();
		Map<String, Object> log = new LinkedHashMap<>();
		log.put("decisionId", result.getDecisionId());
		log.put("scene", scene);
		log.put("policyHash", decision == null ? null : decision.getPolicyHash());
		log.put("originalDecision", originalAllowed == null ? null : originalAllowed);
		Map<String, Object> shadow = new LinkedHashMap<>();
		shadow.put("allowed", result.allowed());
		shadow.put("reasonCode", result.reasonCode() == null ? null : result.reasonCode().name());
		if (decision != null && decision.getObligations() != null && !decision.getObligations().isEmpty()) {
			shadow.put("obligations", decision.getObligations());
		}
		if (decision != null && decision.getMaskFields() != null && !decision.getMaskFields().isEmpty()) {
			shadow.put("maskFields", decision.getMaskFields());
		}
		log.put("shadowDecision", shadow);
		log.put("comparisonStatus", comparison.getCode());
		log.put("mode", result.getEffectiveMode() == null ? null : result.getEffectiveMode().getCode());
		log.put("revisionMatched", result.getRevisionMatched());
		log.put("bindRevision", result.getBindRevision());
		return log;
	}

	/**
	 * 组装义务跳过明细（OBLIGATION_SKIPPED）：决策定位字段 + 跳过义务/原因，不携带比对字段
	 * （义务跳过不是 allow/deny 比对事件，不参与 PR-10 差异率分母）。
	 */
	private Map<String, Object> buildSkippedObligationLog(PepDecisionResult result, String scene,
			String skippedObligation, String skipReason) {
		AuthorizationDecision decision = result.getDecision();
		Map<String, Object> log = new LinkedHashMap<>();
		log.put("decisionId", result.getDecisionId());
		log.put("scene", scene);
		log.put("policyHash", decision == null ? null : decision.getPolicyHash());
		log.put("obligationSkipped", true);
		log.put("skippedObligation", skippedObligation);
		log.put("skipReason", skipReason);
		log.put("mode", result.getEffectiveMode() == null ? null : result.getEffectiveMode().getCode());
		return log;
	}

	/**
	 * 主体类别代码（决策上下文不在结果内，从 reasonCode 之外无法还原，故在结果构造时由
	 * 调用方保证 subjectKind 已随决策评估写入；此处从结果中取不到时返回 null）。
	 */
	private String subjectKindCode(PepDecisionResult result) {
		return result.getSubjectKind() == null ? null : result.getSubjectKind().getCode();
	}

	/**
	 * 序列化明细 JSON（失败抛出由调用方 quiet 兜住）。
	 */
	private String serialize(Map<String, Object> detailedLog) {
		try {
			return objectMapper.writeValueAsString(detailedLog);
		}
		catch (Exception ex) {
			throw new IllegalStateException("授权影子决策明细序列化失败: " + ex.getMessage(), ex);
		}
	}

	/**
	 * 失败兜底序列化（仅用于日志输出，失败返回 null）。
	 */
	private String serializeQuietly(Map<String, Object> detailedLog) {
		try {
			return objectMapper.writeValueAsString(detailedLog);
		}
		catch (Exception ex) {
			return null;
		}
	}

}
