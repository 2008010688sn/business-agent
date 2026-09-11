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
package com.sn68.agent.dataagent.agentscope.service.impl;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextHook;
import com.sn68.agent.dataagent.agentscope.runtime.AgentScopeStreamingHook;
import com.sn68.agent.dataagent.agentscope.runtime.HumanFeedbackHook;
import com.sn68.agent.dataagent.agentscope.runtime.LinkContextHook;
import com.sn68.agent.dataagent.agentscope.template.AgentRuntimeExtensions;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import io.agentscope.core.hook.Hook;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AiAgentRuntimeSystemPromptOverlayTest {

	private static final String LIVE = "live-prompt";

	private static final String OVERLAY = "overlay-prompt";

	@Test
	void dryRunShadowSourceUsesOverlay() {
		AgentRequest request = AgentRequest.builder()
			.requestSource("SHADOW")
			.executionIntent(ExecutionIntentContext.INTENT_DRY_RUN)
			.evalSystemInstructionOverride(OVERLAY)
			.build();

		assertEquals(OVERLAY, AiAgentRuntimeServiceImpl.resolveEvalOrLiveSystemPrompt(request, LIVE));
	}

	@Test
	void dryRunEvalSourceUsesOverlay() {
		AgentRequest request = AgentRequest.builder()
			.requestSource("EVAL")
			.executionIntent(ExecutionIntentContext.INTENT_DRY_RUN)
			.evalSystemInstructionOverride(OVERLAY)
			.build();

		assertEquals(OVERLAY, AiAgentRuntimeServiceImpl.resolveEvalOrLiveSystemPrompt(request, LIVE));
	}

	@Test
	void liveIgnoresOverlayEvenWhenSourceIsEval() {
		AgentRequest request = AgentRequest.builder()
			.requestSource("EVAL")
			.executionIntent(ExecutionIntentContext.INTENT_LIVE)
			.evalSystemInstructionOverride(OVERLAY)
			.build();

		assertEquals(LIVE, AiAgentRuntimeServiceImpl.resolveEvalOrLiveSystemPrompt(request, LIVE));
	}

	@Test
	void blankIntentIgnoresOverlay() {
		AgentRequest request = AgentRequest.builder()
			.requestSource("SHADOW")
			.evalSystemInstructionOverride(OVERLAY)
			.build();

		assertEquals(LIVE, AiAgentRuntimeServiceImpl.resolveEvalOrLiveSystemPrompt(request, LIVE));
	}

	@Test
	void harnessHooksDropStreamingAndAutoContextKeepHumanFeedback() {
		HumanFeedbackHook feedback = HumanFeedbackHook
			.from(AgentRequest.builder().humanFeedbackContent("请按修正后的方案执行").build());
		AgentRuntimeExtensions extensions = new AgentRuntimeExtensions(null, null, null, null,
				List.of(mock(AgentScopeStreamingHook.class), mock(AutoContextHook.class), feedback), null, null, null,
				null, 0, null, null, null);

		List<Hook> hooks = AiAgentRuntimeServiceImpl.harnessHooks(extensions);

		assertEquals(1, hooks.size());
		assertTrue(hooks.get(0) instanceof HumanFeedbackHook);
		assertFalse(hooks.stream().anyMatch(AgentScopeStreamingHook.class::isInstance));
		assertFalse(hooks.stream().anyMatch(AutoContextHook.class::isInstance));
	}

	@Test
	void harnessHooksKeepLinkContextHook() {
		LinkContextHook linkContextHook = LinkContextHook.from(AgentRequest.builder()
			.groundedFacts(new com.sn68.agent.dataagent.agentscope.dto.GroundedFacts())
			.build());
		AgentRuntimeExtensions extensions = new AgentRuntimeExtensions(null, null, null, null,
				List.of(mock(AgentScopeStreamingHook.class), linkContextHook), null, null, null, null, 0, null, null,
				null);

		List<Hook> hooks = AiAgentRuntimeServiceImpl.harnessHooks(extensions);

		assertEquals(1, hooks.size());
		assertTrue(hooks.get(0) instanceof LinkContextHook);
	}

}
