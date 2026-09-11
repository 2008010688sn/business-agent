/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.entity.DataChatSession;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

class SessionTitleServiceTest {

	@Test
	void scheduleTitleGenerationUsesLocalTruncation() {
		DataChatSessionService sessionService = mock(DataChatSessionService.class);
		SessionEventPublisher publisher = mock(SessionEventPublisher.class);
		ExecutorService executor = mock(ExecutorService.class);
		doAnswer(invocation -> {
			invocation.<Runnable>getArgument(0).run();
			return null;
		}).when(executor).execute(any(Runnable.class));
		DataChatSession session = DataChatSession.builder()
			.id(100L)
			.agentId(1L)
			.title(AgentSessionConstant.DEFAULT_SESSION_TITLE)
			.build();
		when(sessionService.findBySessionId(100L)).thenReturn(session);
		SessionTitleService service = new SessionTitleService(sessionService, publisher, executor);
		String message = "查询本月订单智能体运行情况以及异常原因并生成完整分析报告";
		String expected = message.substring(0, AgentSessionConstant.SESSION_TITLE_MAX_LENGTH);

		service.scheduleTitleGeneration(100L, message);

		verify(sessionService).renameSession(100L, expected);
		verify(publisher).publishTitleUpdated(1L, "100", expected);
	}

}
