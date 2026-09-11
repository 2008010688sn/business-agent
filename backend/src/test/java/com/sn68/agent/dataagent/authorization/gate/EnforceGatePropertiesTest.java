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
package com.sn68.agent.dataagent.authorization.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * PR-9 门禁配置加载测试（任务验证项「配置加载单测」）。
 *
 * <ul>
 * <li>enforce-gate 子树默认值：enabled=true（fail-closed 保护默认在）、minComparable=1
 * （零可比样本护栏，v1.2 清单未钦定数值的保守默认）；</li>
 * <li>spring.ai.agent.authorization.enforce-gate.* 绑定生效；</li>
 * <li>enforce-gate 子树不影响 pep 消费视图（PepAuthorizationProperties 忽略未知键，
 * mode/白名单双视图无漂移）。</li>
 * </ul>
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
class EnforceGatePropertiesTest {

	@Test
	void enforceGateDefaultsAreFailClosed() {
		DataAgentProperties.Authorization.EnforceGate gate = new DataAgentProperties.Authorization.EnforceGate();

		// 默认：门禁开启 + 必须有可比样本（Chris 复验收口：零样本不放行）
		assertTrue(gate.isEnabled());
		assertEquals(1L, gate.getMinComparable());
		// 灰度开关默认关闭（验收 6.6）：SHADOW + 空白名单，门禁配置不改变运行时行为
		DataAgentProperties.Authorization authorization = new DataAgentProperties.Authorization();
		assertEquals(PepAuthorizationMode.SHADOW, authorization.getMode());
		assertTrue(authorization.getEnforceTenantIds().isEmpty());
		assertFalse(authorization.isDenyOnMissingSql());
		assertFalse(authorization.isFreezeLegacyVisibilityWrites());
	}

	@Test
	void enforceGateBindsFromConfigSubtree() {
		Map<String, Object> source = new HashMap<>();
		source.put("spring.ai.agent.authorization.enforce-gate.enabled", "false");
		source.put("spring.ai.agent.authorization.enforce-gate.min-comparable", "100");
		Binder binder = new Binder(new MapConfigurationPropertySource(source));

		DataAgentProperties.Authorization authorization = binder
			.bind("spring.ai.agent", Bindable.of(DataAgentProperties.class))
			.get()
			.getAuthorization();

		assertFalse(authorization.getEnforceGate().isEnabled());
		assertEquals(100L, authorization.getEnforceGate().getMinComparable());
	}

	@Test
	void enforceGateSubtreeDoesNotDriftPepView() {
		Map<String, Object> source = new HashMap<>();
		source.put("spring.ai.agent.authorization.enforce-tenant-ids", "1");
		source.put("spring.ai.agent.authorization.enforce-gate.min-comparable", "50");
		Binder binder = new Binder(new MapConfigurationPropertySource(source));

		PepAuthorizationProperties pepView = binder
			.bind("spring.ai.agent.authorization", Bindable.of(PepAuthorizationProperties.class))
			.get();

		// pep 消费视图忽略 enforce-gate 未知键，白名单与模式解析不受门禁子树影响
		assertEquals(PepAuthorizationMode.SHADOW, pepView.getMode());
		assertEquals(PepAuthorizationMode.ENFORCE, pepView.resolveMode("1"));
		assertTrue(pepView.getEnforceTenantIds().contains("1"));
	}

}
