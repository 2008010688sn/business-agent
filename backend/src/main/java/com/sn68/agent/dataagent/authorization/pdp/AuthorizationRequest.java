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
package com.sn68.agent.dataagent.authorization.pdp;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权决策请求（PDP 输入契约）。
 *
 * <p>PEP 装订主体后构造本对象提交 PDP 求值；本切片仅承载求值所需的最小字段集，
 * PR-3c/PR-4 接线时可在不破坏既有字段语义的前提下扩展（如 authRevision）。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Getter
@Builder
public class AuthorizationRequest {

	/**
	 * 请求主体类别：CALLER（真人调用者）/ DIGITAL_EMPLOYEE（数字员工）。
	 */
	private final SubjectKind subjectKind;

	/**
	 * 能力码（如 capability:xxx）；为 null 时按任意能力参与规则匹配。
	 */
	private final String capabilityCode;

	/**
	 * 请求动作。
	 */
	private final AuthorizationAction action;

	/**
	 * 请求的能力版本（可空）；与策略绑定版本都非空且不一致时返回 CAPABILITY_VERSION_MISMATCH。
	 */
	private final String capabilityVersion;

	/**
	 * IAM 是否可用，默认 true。
	 */
	@Builder.Default
	private final Boolean iamAvailable = Boolean.TRUE;
}
