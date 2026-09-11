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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import io.agentscope.core.hook.Hook;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentScopeHookFactoryTest {

	private final AnswerTraceExplainStore explainStore = mock(AnswerTraceExplainStore.class);

	private final AnalysisReportService analysisReportService = mock(AnalysisReportService.class);

	private final AgentScopeHookFactory factory = new AgentScopeHookFactory(explainStore, analysisReportService);

	@Test
	void emitSearchResultSetPublishesWhenExplainHasTable() {
		AgentRequest request = request();
		when(explainStore.getExplain("100", "run-1")).thenReturn(Optional.of(explain()));
		when(analysisReportService.buildPublicResultSetJson(any()))
			.thenReturn("{\"resultSet\":{\"column\":[\"项目\"],\"data\":[{\"项目\":\"A\"},{\"项目\":\"B\"}]}}");
		List<AgentResponse> published = new ArrayList<>();

		factory.emitSearchResultSet(request, published::add);

		assertEquals(1, published.size());
		assertEquals(TextType.RESULT_SET, published.get(0).getTextType());
		assertTrue(published.get(0).getText().contains("项目"));
	}

	@Test
	void emitSearchResultSetSkipsWhenJsonEmpty() {
		AgentRequest request = request();
		when(explainStore.getExplain("100", "run-1")).thenReturn(Optional.of(explain()));
		when(analysisReportService.buildPublicResultSetJson(any())).thenReturn("");
		List<AgentResponse> published = new ArrayList<>();

		factory.emitSearchResultSet(request, published::add);

		assertTrue(published.isEmpty());
	}

	@Test
	void createDoesNotRegisterStreamingHook() {
		AgentRequest request = AgentRequest.builder()
			.agentId("1")
			.threadId("100")
			.humanFeedbackContent("按修正执行")
			.build();

		List<Hook> hooks = factory.create(request, published -> {
		}, mock(AgentRuntimeToolMetrics.class), 4, Duration.ZERO);

		assertEquals(1, hooks.size());
		assertTrue(hooks.get(0) instanceof HumanFeedbackHook);
		assertTrue(hooks.stream().noneMatch(AgentScopeStreamingHook.class::isInstance));
	}

	@Test
	void emitSearchResultSetSkipsWhenPublisherMissing() {
		factory.emitSearchResultSet(request(), null);
		verify(analysisReportService, never()).buildPublicResultSetJson(any());
	}

	private static AgentRequest request() {
		return AgentRequest.builder().agentId("1").threadId("100").runtimeRequestId("run-1").build();
	}

	private static AnswerTraceExplainStore.AnswerTraceExplainView explain() {
		return AnswerTraceExplainStore.AnswerTraceExplainView.builder().runtimeRequestId("run-1").build();
	}

}
