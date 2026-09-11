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
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.channel.enums.ChannelSessionErrorDict;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeEventResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunEventStreamReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.chat.SessionEventPublisher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * 用户态运行事件流：会话属主校验与重连过滤 ASSISTANT_DELTA。
 */
class SessionEventControllerTest {

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final SessionEventController controller = new SessionEventController(mock(SessionEventPublisher.class),
			runtimeRunService, chatSessionService, authenticationContext);



	@Test
	void authorizeSessionOwnerForbidsNonOwner() {
		when(authenticationContext.userId()).thenReturn("8");
		when(chatSessionService.findBySessionId(100L)).thenReturn(session(7L, "7", "WEB"));

		CheckedException ex = assertThrows(CheckedException.class, () -> controller.authorizeSessionOwner(run("100")));

		assertEquals(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel(), ex.getMessage());
	}

	@Test
	void authorizeSessionOwnerAllowsUserIdMatchOnEmployeeChannel() {
		when(authenticationContext.userId()).thenReturn("7");
		when(chatSessionService.findBySessionId(100L)).thenReturn(session(7L, "9", "EMPLOYEE"));

		controller.authorizeSessionOwner(run("100"));
	}

	@Test
	void authorizeSessionOwnerAllowsCreateByMatch() {
		when(authenticationContext.userId()).thenReturn("7");
		when(chatSessionService.findBySessionId(100L)).thenReturn(session(null, "7", "WEB"));

		controller.authorizeSessionOwner(run("100"));
	}

	@Test
	void streamRuntimeRunEventsForbidsNonOwnerBeforeSubscribe() {
		when(authenticationContext.tenantId()).thenReturn("t1");
		when(authenticationContext.userId()).thenReturn("8");
		when(runtimeRunService.detail("t1", 9L)).thenReturn(detail("100"));
		when(chatSessionService.findBySessionId(100L)).thenReturn(session(7L, "7", "WEB"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> controller.streamRuntimeRunEvents(new RuntimeRunEventStreamReq(9L, 0L)));

		assertEquals(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel(), ex.getMessage());
		verify(runtimeRunService, never()).events(any(), any(), any(), any());
	}

	@Test
	void streamRuntimeRunEventsOwnerProceedsPastAuth() {
		when(authenticationContext.tenantId()).thenReturn("t1");
		when(authenticationContext.userId()).thenReturn("7");
		when(runtimeRunService.detail("t1", 9L)).thenReturn(detail("100"));
		when(chatSessionService.findBySessionId(100L)).thenReturn(session(7L, "7", "WEB"));

		Flux<?> flux = controller.streamRuntimeRunEvents(new RuntimeRunEventStreamReq(9L, 0L));

		assertNotNull(flux);
		verify(chatSessionService).findBySessionId(100L);
		verify(runtimeRunService, never()).events(any(), any(), any(), any());
	}

	@Test
	void shouldReplayRuntimeEventSkipsAssistantDelta() {
		assertFalse(controller.shouldReplayRuntimeEvent(event("ASSISTANT_DELTA")));
		assertTrue(controller.shouldReplayRuntimeEvent(event("ASSISTANT_SNAPSHOT")));
		assertTrue(controller.shouldReplayRuntimeEvent(event("RUN_SUCCEEDED")));
	}

	private static RuntimeRunResp run(String threadId) {
		return new RuntimeRunResp(9L, "CALLER", 7L, null, null, 1L, "c1", threadId, null, "CHAT", "CHAT", "q", "RUNNING",
				1L, 0L, null, null, null, null, null, null, "7", null, null, "CALLER", null);
	}

	private static RuntimeRunDetailResp detail(String threadId) {
		return new RuntimeRunDetailResp(run(threadId), null, null, null, List.of(), 0L);
	}

	private static DataChatSession session(Long userId, String createBy, String channelType) {
		return DataChatSession.builder().userId(userId).createBy(createBy).channelType(channelType).build();
	}

	private static RuntimeEventResp event(String eventType) {
		return new RuntimeEventResp(1L, "k", eventType, null, "{}", Instant.EPOCH);
	}

}
