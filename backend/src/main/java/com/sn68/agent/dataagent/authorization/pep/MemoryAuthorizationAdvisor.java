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

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 记忆读写 PDP 拦截入口（PR-3c 交付物 6）。
 *
 * <p>READ_KNOWLEDGE/READ_MEMORY/WRITE_MEMORY 全走 PDP：MODEL_ONLY 策略下纯模型动作
 * （READ_KNOWLEDGE）放行、记忆读写拒绝（由 PR-3a 冻结求值器裁决，本类不重复实现）。
 * SHADOW（默认）：只记录影子决策不拦截，现网记忆链路行为不变；ENFORCE：决策拒绝时抛
 * {@link CheckedException}（BUSINESS_DENIED 口径）。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryAuthorizationAdvisor {

	/**
	 * 影子日志场景标识：记忆读取。
	 */
	public static final String SCENE_MEMORY_READ = "MEMORY_READ";

	/**
	 * 影子日志场景标识：记忆写入。
	 */
	public static final String SCENE_MEMORY_WRITE = "MEMORY_WRITE";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator;

	private final ShadowRecorder shadowRecorder;

	private final PepAuthorizationProperties properties;

	/**
	 * 记忆访问判定（读或写统一入口）。
	 *
	 * <p>接缝语义：SHADOW 下评估异常 quiet 吞掉（返回 null，调用方按"无决策"放行现网路径），
	 * 审计/评估失败不得破坏记忆主流程；ENFORCE 下评估异常向上抛（fail-closed）。
	 * 生效模式在方法入口只解析一次，异常分支与决策拒绝分支共用同一口径，
	 * 避免配置热更窗口内两次解析口径分裂。</p>
	 *
	 * @param context         PEP 决策上下文（action 由调用侧按读/写组装）
	 * @param originalAllowed 现网链路结论（记忆链路现网默认放行，传 Boolean.TRUE）
	 * @return 决策结果；SHADOW 且评估失败时返回 null
	 */
	public PepDecisionResult checkMemoryAccess(PepDecisionContext context, Boolean originalAllowed) {
		boolean enforce = properties.resolveMode(context.getTenantId()).enforce();
		PepDecisionResult result;
		try {
			result = runtimePolicyEvaluator.evaluate(context);
		}
		catch (Exception ex) {
			if (enforce) {
				throw ex;
			}
			log.warn("记忆访问 PEP 评估失败(quiet, SHADOW). action={}, agentId={}, tenantId={}, errorType={}, "
					+ "errorMessage={}", context.getAction(), context.getOwnerId(), context.getTenantId(),
					ex.getClass().getSimpleName(), ex.getMessage());
			return null;
		}
		shadowRecorder.recordShadowDecision(result, sceneOf(context.getAction()), originalAllowed,
				parseLongQuietly(context.getTenantId()), context.getRunId(), context.getStepKey());
		if (enforce && !result.allowed()) {
			throw CheckedException.fail("记忆访问被授权策略拒绝（BUSINESS_DENIED）：reasonCode="
					+ result.reasonCode() + "，action=" + context.getAction());
		}
		return result;
	}

	/**
	 * 按动作推导影子场景标识。
	 */
	private String sceneOf(AuthorizationAction action) {
		return AuthorizationAction.WRITE_MEMORY == action ? SCENE_MEMORY_WRITE : SCENE_MEMORY_READ;
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
