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
package com.sn68.agent.dataagent.observability;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionTraceStoreTest {

	@Test
	void getTrace_returnsMatchingRuntimeRequestTraceForSameSession() {
		SessionTraceStore store = new SessionTraceStore();

		store.export(List.of(span("00000000000000000000000000000001", "0000000000000001", "session-1",
				"runtime-1", 1_000L), span("00000000000000000000000000000002", "0000000000000002",
				"session-1", "runtime-2", 2_000L)));

		assertEquals("runtime-1", store.getTrace("session-1", "runtime-1").orElseThrow().runtimeRequestId());
		assertEquals("runtime-2", store.getTrace("session-1", "runtime-2").orElseThrow().runtimeRequestId());
		assertEquals("runtime-2", store.getLatestTrace("session-1").orElseThrow().runtimeRequestId());
		assertEquals("runtime-2", store.getTrace("session-1", null).orElseThrow().runtimeRequestId());
	}

	private SpanData span(String traceId, String spanId, String sessionId, String runtimeRequestId,
			long startEpochNanos) {
		Attributes attributes = Attributes.builder()
			.put(SessionTraceStore.ATTR_THREAD_ID, sessionId)
			.put(SessionTraceStore.ATTR_RUNTIME_REQUEST_ID, runtimeRequestId)
			.put(SessionTraceStore.ATTR_AGENT_ID, "1")
			.build();
		SpanData span = mock(SpanData.class);
		when(span.getSpanContext())
			.thenReturn(SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault()));
		when(span.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
		when(span.getName()).thenReturn("root-" + runtimeRequestId);
		when(span.getKind()).thenReturn(SpanKind.INTERNAL);
		when(span.getStatus()).thenReturn(StatusData.ok());
		when(span.getStartEpochNanos()).thenReturn(startEpochNanos);
		when(span.getEndEpochNanos()).thenReturn(startEpochNanos + 1_000_000L);
		when(span.getAttributes()).thenReturn(attributes);
		when(span.getTotalAttributeCount()).thenReturn(attributes.size());
		return span;
	}

}
