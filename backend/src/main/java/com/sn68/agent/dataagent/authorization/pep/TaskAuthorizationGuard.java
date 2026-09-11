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

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.OwnerAuthorizationQueryGateway;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 任务拉起授权守卫（PR-3c 交付，PR-6 接线到 DefaultTaskRunLauncher 消费）。
 *
 * <p>判定动作 START_UNATTENDED：owner 恒为 DIGITAL_EMPLOYEE（v1.2 PR-6 契约）。本类同时消费
 * PR-3b 组合门面 {@link OwnerAuthorizationQueryGateway} 做 owner 维度 USE 预检，其结论作为
 * original_decision 参与影子比对（现网粗粒度授权 vs PDP 细粒度策略）。</p>
 *
 * <p>SHADOW（默认）：只记录不拦截；ENFORCE：空主体或 PDP 拒绝时抛 {@link CheckedException}
 * （BUSINESS_DENIED 语义，PR-6 映射为 FAILED + AUTHORIZATION_DENIED）。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskAuthorizationGuard {

	/**
	 * 影子日志场景标识：任务拉起。
	 */
	public static final String SCENE_TASK_START = "TASK_START";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator;

	private final InvocationSubjectGuard subjectGuard;

	private final ShadowRecorder shadowRecorder;

	private final OwnerAuthorizationQueryGateway ownerQueryGateway;

	private final PepAuthorizationProperties properties;

	/**
	 * 任务拉起判定。
	 *
	 * @param context          PEP 决策上下文（action 应为 START_UNATTENDED，owner 为 DIGITAL_EMPLOYEE）
	 * @param subjectSnapshot  主体快照（owner 维度 USE 预检输入，可空则跳过预检）
	 * @return 决策结果（SHADOW 评估失败时返回 null）
	 */
	public PepDecisionResult authorizeTaskStart(PepDecisionContext context, AuthorizationSubjectSnapshot subjectSnapshot) {
		PepAuthorizationMode mode = properties.resolveMode(context.getTenantId());
		InvocationSubjectGuard.SubjectCheckResult subjectCheck = subjectGuard.checkSubject(context, mode);
		Boolean originalAllowed = originalUseDecision(context, subjectSnapshot);
		if (mode.enforce()) {
			subjectGuard.rejectIfMissingSubject(subjectCheck, SCENE_TASK_START);
		}
		else if (!subjectCheck.isCompliant()) {
			log.warn("任务拉起主体缺失(SHADOW 仅记录). missingFields={}, ownerId={}, tenantId={}",
					subjectCheck.missingFields(), context.getOwnerId(), context.getTenantId());
		}
		PepDecisionResult result;
		try {
			result = runtimePolicyEvaluator.evaluate(context);
		}
		catch (Exception ex) {
			if (mode.enforce()) {
				throw ex;
			}
			log.warn("任务拉起 PEP 评估失败(quiet, SHADOW). ownerId={}, tenantId={}, errorType={}, errorMessage={}",
					context.getOwnerId(), context.getTenantId(), ex.getClass().getSimpleName(), ex.getMessage());
			return null;
		}
		shadowRecorder.recordShadowDecision(result, SCENE_TASK_START, originalAllowed,
				parseLongQuietly(context.getTenantId()), context.getRunId(), context.getStepKey());
		if (mode.enforce() && !result.allowed()) {
			throw CheckedException.fail("任务拉起被授权策略拒绝（BUSINESS_DENIED）：reasonCode=" + result.reasonCode()
					+ "，ownerId=" + context.getOwnerId());
		}
		return result;
	}

	/**
	 * owner 维度 USE 预检（现网口径）：主体快照缺失或 owner 类型不支持时返回 null（无法观测）。
	 */
	private Boolean originalUseDecision(PepDecisionContext context, AuthorizationSubjectSnapshot subjectSnapshot) {
		if (subjectSnapshot == null || context.getOwnerType() == null || context.getOwnerId() == null) {
			return null;
		}
		return ownerQueryGateway.canUse(context.getOwnerType(), context.getOwnerId(), subjectSnapshot);
	}

	/**
	 * 租户ID转 Long（事件表数字口径；解析失败返回 null 由 ShadowRecorder 落 0）。
	 */
	private Long parseLongQuietly(String tenantId) {
		if (tenantId == null || tenantId.isBlank()) {
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
