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

/**
 * 数字员工对话接缝 Guard（Facade 默认 PEP 判定入口）。
 *
 * <p>PR-5 默认实现 {@link ModelOnlyEmployeeConversationGuard}：以 PR-3a MODEL_ONLY 模板
 * （CALLER 主体 + IAM 不可用 ALLOW + allowModelOnly）求值，仅放行纯模型类动作
 * （READ_KNOWLEDGE）；rollout 未开启时对话即走该默认 Guard 放行纯模型对话。
 * PR-4 灰度接线时替换为读取员工 Release 授权策略的 Guard 实现，接口不变。</p>
 */
public interface EmployeeConversationGuard {

	/**
	 * 求值当前 Guard 策略下动作是否允许，不允许时抛 CheckedException（fail-closed）。
	 * @param action 请求动作（纯模型对话为 READ_KNOWLEDGE）
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 策略拒绝
	 */
	void requireAllowed(AuthorizationAction action);

}
