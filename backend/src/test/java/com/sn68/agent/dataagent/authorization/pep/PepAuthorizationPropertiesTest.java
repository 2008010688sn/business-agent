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

import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PEP 授权配置绑定测试（v1.2 清单 PR-4「配置加载正确」验证项）：
 * <ul>
 * <li>租户白名单灰度解析边界（resolveMode：命中 ENFORCE / 未命中 SHADOW / 空租户全局兜底）；</li>
 * <li>DataAgentProperties.Authorization（清单钦定入口）与 PepAuthorizationProperties（pep 消费视图）
 * 绑定同一配置子树 spring.ai.agent.authorization.*，双视图无漂移；</li>
 * <li>PR-9 灰度开关（denyOnMissingSql/freezeLegacyVisibilityWrites）默认关闭（验收 6.6）。</li>
 * </ul>
 *
 * @author James (PR-4 配置收敛)
 */
class PepAuthorizationPropertiesTest {

	@Test
	void resolveModeDefaultsToShadowAndHonorsTenantWhitelist() {
		PepAuthorizationProperties properties = new PepAuthorizationProperties();
		properties.getEnforceTenantIds().add("999");

		// 默认 SHADOW：未命中白名单的租户与空租户都按全局模式处理
		assertEquals(PepAuthorizationMode.SHADOW, properties.resolveMode("1"));
		assertEquals(PepAuthorizationMode.SHADOW, properties.resolveMode(null));
		assertEquals(PepAuthorizationMode.SHADOW, properties.resolveMode("  "));
		// 白名单命中 ENFORCE（灰度最小单元），容忍首尾空白
		assertEquals(PepAuthorizationMode.ENFORCE, properties.resolveMode("999"));
		assertEquals(PepAuthorizationMode.ENFORCE, properties.resolveMode(" 999 "));
	}

	@Test
	void sameConfigSubtreeBindsBothViewsWithoutDrift() {
		Map<String, Object> source = new HashMap<>();
		source.put("spring.ai.agent.authorization.mode", "ENFORCE");
		source.put("spring.ai.agent.authorization.enforce-tenant-ids", "1,2");
		source.put("spring.ai.agent.authorization.deny-on-missing-sql", "true");
		source.put("spring.ai.agent.authorization.freeze-legacy-visibility-writes", "true");
		Binder binder = new Binder(new MapConfigurationPropertySource(source));

		DataAgentProperties.Authorization authorization = binder
				.bind("spring.ai.agent", Bindable.of(DataAgentProperties.class))
				.get()
				.getAuthorization();
		PepAuthorizationProperties pepView = binder
				.bind("spring.ai.agent.authorization", Bindable.of(PepAuthorizationProperties.class))
				.get();

		// 双视图同键一致：mode 与白名单不漂移（PR-9 门禁按同一份配置切换）
		assertEquals(authorization.getMode(), pepView.getMode());
		assertEquals(PepAuthorizationMode.ENFORCE, pepView.getMode());
		assertEquals(authorization.getEnforceTenantIds(), pepView.getEnforceTenantIds());
		assertTrue(pepView.getEnforceTenantIds().contains("2"));
		assertEquals(PepAuthorizationMode.ENFORCE, pepView.resolveMode("1"));
		// SQL/legacy 侧开关仅由 DataAgentProperties.Authorization 承载（pep 内核不消费）
		assertTrue(authorization.isDenyOnMissingSql());
		assertTrue(authorization.isFreezeLegacyVisibilityWrites());
	}

	@Test
	void grayReleaseSwitchesDefaultOff() {
		DataAgentProperties.Authorization authorization = new DataAgentProperties.Authorization();

		// 上线默认：SHADOW + 空白名单 + PR-9 开关全关（验收 6.6 灰度开关默认关闭）
		assertEquals(PepAuthorizationMode.SHADOW, authorization.getMode());
		assertTrue(authorization.getEnforceTenantIds().isEmpty());
		assertFalse(authorization.isDenyOnMissingSql());
		assertFalse(authorization.isFreezeLegacyVisibilityWrites());
	}

}
