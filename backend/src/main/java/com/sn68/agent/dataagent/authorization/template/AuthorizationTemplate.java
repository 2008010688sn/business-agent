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
package com.sn68.agent.dataagent.authorization.template;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEffect;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.IamUnavailableBehavior;
import com.sn68.agent.dataagent.authorization.model.SubjectMode;
import java.util.List;

/**
 * 预设授权模板枚举。
 *
 * <p>PDP 内核契约的一部分，提供四个种子策略模板，通过 defaultPolicy() 生成预定义规则。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum AuthorizationTemplate {

	/**
	 * MODEL_ONLY：仅允许模型对话能力（纯知识检索 READ_KNOWLEDGE），无业务动作。IAM 不可用仍允许（降级开放）。
	 */
	MODEL_ONLY {
		@Override
		public AuthorizationPolicy defaultPolicy() {
			return AuthorizationPolicy.builder()
					.schemaVersion(1)
					.templateCode("MODEL_ONLY")
					.subjectMode(SubjectMode.CALLER)
					.iamUnavailableBehavior(IamUnavailableBehavior.ALLOW)
					.allowModelOnly(true)
					.rules(List.of()) // 空规则表示默认拒绝，但 allowModelOnly=true 下 Evaluator 放 READ_KNOWLEDGE
					.build();
		}
	},

	/**
	 * CALLER_READ_ONLY：调用者（真人）仅允许读/发现/使用，禁止写操作。IAM 不可用时拒绝。
	 */
	CALLER_READ_ONLY {
		@Override
		public AuthorizationPolicy defaultPolicy() {
			return AuthorizationPolicy.builder()
					.schemaVersion(1)
					.templateCode("CALLER_READ_ONLY")
					.subjectMode(SubjectMode.CALLER)
					.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
					.allowModelOnly(false)
					.rules(List.of(
							// DENY 写操作（首条命中）
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("deny-write-actions")
									.effect(AuthorizationEffect.DENY)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.WRITE))
									.obligations(List.of())
									.maskFields(List.of())
									.build(),
							// ALLOW 读类动作
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("allow-read-discover-use")
									.effect(AuthorizationEffect.ALLOW)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.READ, AuthorizationAction.DISCOVER, AuthorizationAction.USE))
									.obligations(List.of())
									.maskFields(List.of())
									.build()))
					.build();
		}
	},

	/**
	 * CALLER_INTERACTIVE：调用者全权限允许，但写操作带审批义务。IAM 不可用时拒绝。
	 */
	CALLER_INTERACTIVE {
		@Override
		public AuthorizationPolicy defaultPolicy() {
			return AuthorizationPolicy.builder()
					.schemaVersion(1)
					.templateCode("CALLER_INTERACTIVE")
					.subjectMode(SubjectMode.CALLER)
					.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
					.allowModelOnly(false)
					.rules(List.of(
							// WRITE 带 APPROVAL
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("write-with-approval")
									.effect(AuthorizationEffect.ALLOW)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.WRITE))
									.obligations(List.of(AuthorizationObligation.APPROVAL))
									.maskFields(List.of())
									.build(),
							// 其他读/执行动作允许
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("allow-rest-actions")
									.effect(AuthorizationEffect.ALLOW)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.READ, AuthorizationAction.EXECUTE, AuthorizationAction.DISCOVER,
											AuthorizationAction.USE, AuthorizationAction.START_UNATTENDED, AuthorizationAction.READ_KNOWLEDGE,
											AuthorizationAction.READ_MEMORY, AuthorizationAction.WRITE_MEMORY))
									.obligations(List.of())
									.maskFields(List.of())
									.build()))
					.build();
		}
	},

	/**
	 * DIGITAL_WORKER：数字员工（EMPLOYEE 主体）全权限允许，无人值守可启动，但写操作需审批 + 字段脱敏；IAM 不可用时拒绝。
	 * maskFields 留空列表，表示遵循能力契约的 sensitiveFields 默认敏感字段。
	 */
	DIGITAL_WORKER {
		@Override
		public AuthorizationPolicy defaultPolicy() {
			return AuthorizationPolicy.builder()
					.schemaVersion(1)
					.templateCode("DIGITAL_WORKER")
					.subjectMode(SubjectMode.EMPLOYEE)
					.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
					.allowModelOnly(false)
					.rules(List.of(
							// WRITE 带 APPROVAL + MASK_FIELDS
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("write-with-approval-and-mask")
									.effect(AuthorizationEffect.ALLOW)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.WRITE))
									.obligations(List.of(AuthorizationObligation.APPROVAL, AuthorizationObligation.MASK_FIELDS))
									.maskFields(List.of()) // 遵循能力契约 sensitiveFields
									.build(),
							// 其他业务动作允许（含 START_UNATTENDED）
							com.sn68.agent.dataagent.authorization.model.AuthorizationRule.builder()
									.name("allow-business-actions")
									.effect(AuthorizationEffect.ALLOW)
									.capabilityCodes(List.of("*"))
									.actions(List.of(AuthorizationAction.READ, AuthorizationAction.EXECUTE, AuthorizationAction.DISCOVER,
											AuthorizationAction.USE, AuthorizationAction.START_UNATTENDED, AuthorizationAction.READ_KNOWLEDGE,
											AuthorizationAction.READ_MEMORY, AuthorizationAction.WRITE_MEMORY))
									.obligations(List.of())
									.maskFields(List.of())
									.build()))
					.build();
		}
	};

	/**
	 * 按模板代码查找模板实例。
	 *
	 * @param code 模板代码（MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER）
	 * @return 模板实例，未找到返回 null
	 */
	public static AuthorizationTemplate fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationTemplate template : values()) {
			if (template.getCode().equals(code)) {
				return template;
			}
		}
		return null;
	}

	/**
	 * 获取模板代码。
	 *
	 * @return 模板代码
	 */
	public String getCode() {
		return name();
	}

	/**
	 * 生成默认策略（工厂方法）。
	 *
	 * @return 默认策略实例
	 */
	public abstract AuthorizationPolicy defaultPolicy();
}
