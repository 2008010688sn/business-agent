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
package com.sn68.agent.dataagent.authorization.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权规则实体。
 *
 * <p>PDP 内核契约的一部分，表示单条授权规则的定义。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Getter
@Builder
public class AuthorizationRule {

	/**
	 * 规则名称（用于标识），必填。
	 */
	@Schema(description = "规则名称", requiredMode = Schema.RequiredMode.REQUIRED)
	private final String name;

	/**
	 * 效果：允许或拒绝。
	 */
	@Schema(description = "效果：ALLOW/DENY", requiredMode = Schema.RequiredMode.REQUIRED)
	private final AuthorizationEffect effect;

	/**
	 * 能力码列表（支持通配符"*"匹配所有能力）。
	 */
	@Schema(description = "能力码列表，支持'*'通配")
	private final List<String> capabilityCodes;

	/**
	 * 动作列表（READ/WRITE/EXECUTE 等）。
	 */
	@Schema(description = "动作列表")
	private final List<AuthorizationAction> actions;

	/**
	 * 义务列表（APPROVAL/MASK_FIELDS/FILTER_FIELDS 等）。当包含 MASK_FIELDS 时，maskFields 生效。
	 */
	@Schema(description = "义务列表")
	private final List<AuthorizationObligation> obligations;

	/**
	 * MASK_FIELDS 义务对应的敏感字段名列表（如 ["phone", "idCard"]）；空列表表示由能力契约默认敏感字段决定。
	 */
	@Schema(description = "MASK_FIELDS 义务对应的敏感字段名列表")
	private final List<String> maskFields;

	/**
	 * 构造空规则（通配所有能力/动作）。
	 *
	 * @return 通配规则实例
	 */
	public static AuthorizationRule allPermit() {
		return builder()
				.name("ALL_PERMIT")
				.effect(AuthorizationEffect.ALLOW)
				.capabilityCodes(List.of("*"))
				.actions(List.of())
				.obligations(List.of())
				.maskFields(List.of())
				.build();
	}

	/**
	 * 判断规则是否完全通配。
	 *
	 * @return 若能力码为 ["*"] 且无动作限制则返回 true
	 */
	public boolean isWildcardPermit() {
		if (!isPermit()) {
			return false;
		}
		return capabilityCodes != null && capabilityCodes.size() == 1 && "*".equals(capabilityCodes.get(0));
	}

	/**
	 * 判断是否为允许效果。
	 *
	 * @return 允许返回 true
	 */
	public boolean isPermit() {
		return effect == AuthorizationEffect.ALLOW;
	}
}
