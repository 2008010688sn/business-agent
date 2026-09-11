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
package com.sn68.agent.dataagent.employee.guard;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.authorization.template.AuthorizationTemplate;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认 Guard：PR-3a MODEL_ONLY 模板（CALLER 主体 + IAM 不可用 ALLOW + allowModelOnly）求值。
 *
 * <p>纯模型对话动作（READ_KNOWLEDGE）在 allowModelOnly 集合内恒放行；
 * 业务动作（USE/EXECUTE 等）一律拒绝（MODEL_ONLY 不允许业务副作用）。
 * 求值主体固定 CALLER（模板 subjectMode=CALLER；DIGITAL_EMPLOYEE 主体策略由 PR-4 接线，
 * 彼时替换本 Guard 而非修改 PDP 内核）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelOnlyEmployeeConversationGuard implements EmployeeConversationGuard {

	private final AuthorizationPolicyEvaluator policyEvaluator;

	@Override
	public void requireAllowed(AuthorizationAction action) {
		if (action == null) {
			throw CheckedException.badRequest("对话授权动作不能为空");
		}
		AuthorizationDecision decision = policyEvaluator.evaluate(AuthorizationTemplate.MODEL_ONLY.defaultPolicy(),
				AuthorizationRequest.builder()
					.subjectKind(SubjectKind.CALLER)
					.action(action)
					.build());
		if (!decision.isAllowed()) {
			log.warn("数字员工对话动作被默认 Guard（MODEL_ONLY）拒绝. action={}, reasonCode={}", action.getCode(),
					decision.getReasonCode());
			throw CheckedException.forbidden("数字员工当前仅允许纯模型对话（MODEL_ONLY），动作被拒绝: " + action.getCode());
		}
	}

}
