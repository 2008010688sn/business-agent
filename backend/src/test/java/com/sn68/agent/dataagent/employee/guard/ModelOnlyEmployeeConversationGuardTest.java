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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 默认 Guard（MODEL_ONLY 模板）求值测试：纯模型动作放行、业务动作拒绝（fail-closed）。
 */
class ModelOnlyEmployeeConversationGuardTest {

	private final AuthorizationPolicyEvaluator evaluator = new AuthorizationPolicyEvaluator();

	private final ModelOnlyEmployeeConversationGuard guard = new ModelOnlyEmployeeConversationGuard(evaluator);

	@Test
	@DisplayName("READ_KNOWLEDGE 属于纯模型动作集，默认放行")
	void readKnowledgeIsAllowed() {
		assertDoesNotThrow(() -> guard.requireAllowed(AuthorizationAction.READ_KNOWLEDGE));
	}

	@Test
	@DisplayName("USE/EXECUTE/START_UNATTENDED 业务动作一律拒绝")
	void businessActionsAreDenied() {
		for (AuthorizationAction action : new AuthorizationAction[] { AuthorizationAction.USE,
				AuthorizationAction.EXECUTE, AuthorizationAction.START_UNATTENDED, AuthorizationAction.WRITE }) {
			CheckedException ex = assertThrows(CheckedException.class, () -> guard.requireAllowed(action),
					"动作应被拒绝: " + action);
			assertTrue(ex.getMessage().contains("MODEL_ONLY"), ex.getMessage());
		}
	}

	@Test
	@DisplayName("空动作参数直接拒绝（非法输入 fail-fast）")
	void nullActionIsRejected() {
		CheckedException ex = assertThrows(CheckedException.class, () -> guard.requireAllowed(null));
		assertTrue(ex.getMessage().contains("不能为空"), ex.getMessage());
	}

}
