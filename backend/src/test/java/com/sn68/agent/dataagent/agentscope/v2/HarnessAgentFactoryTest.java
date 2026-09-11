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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HarnessAgentFactoryTest {

	@Test
	void nl2sqlCompactionWaitsUntilWindowPressure() {
		CompactionConfig config = factory().nl2sqlCompaction();
		assertEquals(20_000, config.getTriggerTokens());
		assertEquals(48, config.getTriggerMessages());
		assertEquals(8, config.getKeepMessages());
		assertEquals(8_000, config.getKeepTokens());
		assertEquals(2_000, config.getReserved());
		assertFalse(config.isFlushBeforeCompact());
		assertFalse(config.isOffloadBeforeCompact());
		assertNull(config.getModel());
	}

	@Test
	void toolResultEvictionFollowsContextGovernance() throws Exception {
		HarnessAgent.Builder enabled = HarnessAgentFactory.applyToolResultEviction(HarnessAgent.builder(),
				new AgentScopeV2Properties());
		Object evictionConfig = builderField(enabled, "toolResultEvictionConfig");
		assertNotNull(evictionConfig);
		assertFalse(builderFlag(enabled, "disableToolResultEviction"));

		AgentScopeV2Properties properties = new AgentScopeV2Properties();
		properties.getContextGovernance().setToolResultEvictionEnabled(false);
		HarnessAgent.Builder disabled = HarnessAgentFactory.applyToolResultEviction(HarnessAgent.builder(), properties);
		// 框架 disableToolResultEviction 只置开关不清 config 字段，开关才是生效位
		assertNotNull(builderField(disabled, "toolResultEvictionConfig"));
		assertTrue(builderFlag(disabled, "disableToolResultEviction"));
	}

	@Test
	void runtimeContextPutsAnalysisIntentWhenPresent() {
		HarnessAgentFactory factory = factory();
		AgentRequest request = AgentRequest.builder()
			.v2AnalysisIntent(V2ToolkitFilter.INTENT_FILE_ONLY)
			.threadId("100")
			.userIdSnapshot("u")
			.tenantIdSnapshot("t")
			.build();

		RuntimeContext ctx = factory.runtimeContext(request);

		assertEquals(V2ToolkitFilter.INTENT_FILE_ONLY, ctx.get(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY));
	}

	@Test
	void runtimeContextOmitsBlankAnalysisIntent() {
		assertNull(factory().runtimeContext(AgentRequest.builder().threadId("100").build())
			.get(V2ActingPermissionMiddleware.ANALYSIS_INTENT_KEY));
	}

	@Test
	void runtimeContextPutsReasoningProtocolWhenPresent() {
		RuntimeContext ctx = factory()
			.runtimeContext(AgentRequest.builder().threadId("100").build(), "THINKING_OBJECT");

		assertEquals("THINKING_OBJECT", ctx.get(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY));
	}

	@Test
	void runtimeContextOmitsBlankReasoningProtocol() {
		assertNull(factory()
			.runtimeContext(AgentRequest.builder().threadId("100").build(), " ")
			.get(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY));
		assertNull(factory().runtimeContext(AgentRequest.builder().threadId("100").build())
			.get(V2ReasoningThrottleMiddleware.REASONING_PROTOCOL_KEY));
	}

	@Test
	void nl2sqlHarnessDisablesMemoryHooksButKeepsCompaction() throws Exception {
		HarnessAgent.Builder builder = HarnessAgentFactory.applyNl2sqlHarnessDisables(HarnessAgent.builder());
		assertTrue(builderFlag(builder, "disableMemoryHooks"));
		assertTrue(builderFlag(builder, "disableMemoryTools"));
		assertTrue(builderFlag(builder, "disableFilesystemTools"));
		assertTrue(builderFlag(builder, "disableWorkspaceContext"));
		assertFalse(builderFlag(builder, "disableCompaction"));
	}

	private static boolean builderFlag(HarnessAgent.Builder builder, String fieldName) throws Exception {
		return (boolean) builderField(builder, fieldName);
	}

	private static Object builderField(HarnessAgent.Builder builder, String fieldName) throws Exception {
		Field field = HarnessAgent.Builder.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		return field.get(builder);
	}

	private static HarnessAgentFactory factory() {
		return new HarnessAgentFactory(mock(V2SpringAiChatModelAdapter.class), mock(V2EventToAgentResponseMapper.class),
				mock(DynamicModelFactory.class), mock(AgentModelConfigService.class),
				mock(ModelConfigDataService.class),
				mock(com.sn68.agent.dataagent.service.agent.DataAgentService.class), mock(V2AgentStateStore.class),
				mock(V2TenantGuardMiddleware.class), new AgentScopeV2Properties(), mock(V2BudgetMiddleware.class),
				mock(V2ActingPermissionMiddleware.class), mock(V2CompactionGuardMiddleware.class),
				mock(V2ReasoningThrottleMiddleware.class), null);
	}

}
