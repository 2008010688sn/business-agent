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
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.dto.GroundedFacts;
import com.sn68.agent.dataagent.agentscope.dto.GroundedKey;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinkContextHookTest {

	@Test
	void fromReturnsNullWhenGroundedFactsMissing() {
		assertNull(LinkContextHook.from(AgentRequest.builder().query("hello").build()));
		assertNull(LinkContextHook.from(null));
	}

	@Test
	void injectsSystemDirectiveWithWrappedKeys() {
		GroundedFacts facts = GroundedFacts.builder()
			.ownOrigin(true)
			.keys(List.of(new GroundedKey("id", "2087735796997206016", GroundedKey.TRUST_OWN_ORIGIN),
					new GroundedKey("id", "999", GroundedKey.TRUST_EXTERNAL)))
			.build();
		AgentRequest request = AgentRequest.builder().groundedFacts(facts).build();
		LinkContextHook hook = LinkContextHook.from(request);
		assertNotNull(hook);

		PreReasoningEvent event = new PreReasoningEvent(mock(Agent.class), "test-model", mock(GenerateOptions.class),
				List.of(Msg.builder().name("user").role(MsgRole.USER).textContent("分析").build()));
		hook.onEvent(event).block();

		assertEquals(MsgRole.SYSTEM, event.getInputMessages().get(0).getRole());
		String text = event.getInputMessages().get(0).getTextContent() == null ? ""
				: event.getInputMessages().get(0).getTextContent();
		assertTrue(text.contains("当前技能已绑定的白名单表"));
		assertTrue(text.contains("不要打开本系统 URL"));
		assertTrue(text.contains("trust=external"));
		assertTrue(text.contains("不要把第一行当成用户要的对象"));
		assertFalse(text.contains("账单"));
		assertTrue(text.contains(UntrustedContentBoundary.SENTINEL));
		assertTrue(text.contains("2087735796997206016"));
	}

	@Test
	void factoryRegistersHookOnlyWhenGroundedFactsPresent() {
		AgentScopeHookFactory factory = new AgentScopeHookFactory(mock(AnswerTraceExplainStore.class),
				mock(AnalysisReportService.class));
		List<Hook> withoutFacts = factory.create(AgentRequest.builder().query("hello").build(), published -> {
		}, mock(AgentRuntimeToolMetrics.class), 4, Duration.ZERO);
		assertTrue(withoutFacts.stream().noneMatch(LinkContextHook.class::isInstance));

		AgentRequest withFacts = AgentRequest.builder()
			.groundedFacts(GroundedFacts.builder()
				.keys(List.of(new GroundedKey("id", "1", GroundedKey.TRUST_PAGE_CONTEXT)))
				.build())
			.build();
		List<Hook> withHook = factory.create(withFacts, published -> {
		}, mock(AgentRuntimeToolMetrics.class), 4, Duration.ZERO);
		assertEquals(1, withHook.size());
		assertTrue(withHook.get(0) instanceof LinkContextHook);
	}

}
