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

import cn.hutool.core.collection.CollUtil;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * PEP 授权执行配置消费视图（PR-3c 建立、PR-4 收敛配置源）。
 *
 * <p>前缀与 {@code DataAgentProperties.Authorization}（清单 PR-4 钦定的统一配置入口
 * {@code spring.ai.agent.authorization.*}）指向同一配置子树：单处 YAML 配置
 * 同时注入两个视图，pep 包内核只依赖本类，避免两处配置漂移；denyOnMissingSql/
 * freezeLegacyVisibilityWrites 等 SQL/legacy 侧开关仅由 DataAgentProperties 承载，
 * pep 内核不消费。</p>
 *
 * <p>默认 SHADOW + 空 ENFORCE 租户白名单：上线即影子，普通 DataAgent 行为不变（v1.2 清单总约束）。</p>
 *
 * @author James (PR-3c PEP 内核扩展、PR-4 配置收敛)
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "spring.ai.agent.authorization")
public class PepAuthorizationProperties {

	/**
	 * 全局默认模式：SHADOW 只记录不拦截；ENFORCE 为强制模式（仍受租户白名单约束之外的兜底口径）。
	 */
	private PepAuthorizationMode mode = PepAuthorizationMode.SHADOW;

	/**
	 * ENFORCE 租户白名单：命中租户按 ENFORCE 处理，未命中租户一律 SHADOW（灰度切换的最小单元）。
	 */
	private List<String> enforceTenantIds = new ArrayList<>();

	/**
	 * 解析指定租户的生效模式：白名单优先于全局 mode，保证灰度租户强制 ENFORCE、其余租户恒 SHADOW。
	 *
	 * @param tenantId 租户ID（可空，空按全局 mode 处理）
	 * @return 生效模式，永不返回 null（mode 为 null 时按 SHADOW 兜底）
	 */
	public PepAuthorizationMode resolveMode(String tenantId) {
		if (tenantId != null && !tenantId.isBlank() && CollUtil.contains(enforceTenantIds, tenantId.trim())) {
			return PepAuthorizationMode.ENFORCE;
		}
		return mode == null ? PepAuthorizationMode.SHADOW : mode;
	}
}
