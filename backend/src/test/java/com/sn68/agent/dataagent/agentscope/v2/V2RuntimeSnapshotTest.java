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
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V2RuntimeSnapshotTest {

	@Test
	void convenienceConstructorsDefaultHitlEnabledTrue() {
		V2RuntimeSnapshot nine = new V2RuntimeSnapshot("t", "t", "u", null, null, null, List.of(), "100", "run-1");
		V2RuntimeSnapshot owner = new V2RuntimeSnapshot("t", "t", "u", null, null, null, List.of(), "100", "run-1", "1",
				"DATA_AGENT", 1L, 99L);
		V2RuntimeSnapshot flow = new V2RuntimeSnapshot("t", "t", "u", null, null, null, List.of(), "100", "run-1",
				"flow-1", List.of("orders"), List.of("ws-1"));

		assertTrue(nine.hitlEnabled());
		assertTrue(owner.hitlEnabled());
		assertTrue(flow.hitlEnabled());
		assertTrue(V2RuntimeSnapshot.from(null).hitlEnabled());
	}

	@Test
	void fromStreamSearchLiveEnablesHitl() {
		AgentRequest request = AgentRequest.builder()
			.streamSearchRuntime(true)
			.executionIntent(ExecutionIntentContext.INTENT_LIVE)
			.build();

		assertTrue(V2RuntimeSnapshot.from(request).hitlEnabled());
	}

	@Test
	void fromDryRunAlwaysDisablesHitl() {
		AgentRequest request = AgentRequest.builder()
			.streamSearchRuntime(true)
			.executionIntent(ExecutionIntentContext.INTENT_DRY_RUN)
			.build();

		assertFalse(V2RuntimeSnapshot.from(request).hitlEnabled());
	}

	@Test
	void fromEmployeeStreamEnablesHitlWhileCollaboratorAndParentDisable() {
		assertTrue(V2RuntimeSnapshot.from(AgentRequest.builder()
			.streamSearchRuntime(true)
			.employeeFacadeStream(true)
			.build()).hitlEnabled());
		assertFalse(V2RuntimeSnapshot.from(AgentRequest.builder()
			.employeeFacadeStream(true)
			.build()).hitlEnabled());
		assertFalse(V2RuntimeSnapshot.from(AgentRequest.builder()
			.streamSearchRuntime(true)
			.collaboratorChild(true)
			.build()).hitlEnabled());
		assertFalse(V2RuntimeSnapshot.from(AgentRequest.builder()
			.streamSearchRuntime(true)
			.parentRuntimeRequestId("parent-1")
			.build()).hitlEnabled());
		assertFalse(V2RuntimeSnapshot.from(AgentRequest.builder().build()).hitlEnabled());
	}

	@Test
	void withAnalysisWorkspaceHandlesCopiesHitlEnabled() {
		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot
			.from(AgentRequest.builder()
				.streamSearchRuntime(true)
				.executionIntent(ExecutionIntentContext.INTENT_DRY_RUN)
				.build())
			.withAnalysisWorkspaceHandles(List.of("ws-1"));

		assertFalse(snapshot.hitlEnabled());
		assertEquals(List.of("ws-1"), snapshot.analysisWorkspaceHandles());
		assertTrue(snapshot.withHitlEnabled(true).hitlEnabled());
		assertEquals(List.of("ws-1"), snapshot.withHitlEnabled(true).analysisWorkspaceHandles());
	}

}
