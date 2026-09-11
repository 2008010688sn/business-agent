/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ThinkingBlock;
import org.junit.jupiter.api.Test;

class OrderAgent2250ReplayFixtureTest {

	private static final String SELECT_STAR_ERROR_CODE = "SELECT_STAR_FORBIDDEN";

	private static final String POLICY_FAILURE_MESSAGE = "SQL policy rejected a wildcard projection";

	private static final String FINAL_REASONING =
			"<tool_call><function=sql_guard.check>{}</function></tool_call>";

	@Test
	void sanitizedReplayClassifiesThinkingOnlyAsProtocolErrorNotHistoricalToolFailure() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordFailure(1, SELECT_STAR_ERROR_CODE, POLICY_FAILURE_MESSAGE);
		metrics.recordFailure(2, SELECT_STAR_ERROR_CODE, POLICY_FAILURE_MESSAGE);
		Msg finalResponse = Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.content(ThinkingBlock.builder().thinking(FINAL_REASONING).build())
			.build();

		AgentRuntimeTerminalClassifier.TerminalResult result =
				new AgentRuntimeTerminalClassifier().classify(finalResponse);

		assertEquals(2, metrics.toolFailCount());
		assertEquals(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, result.outcome());
		assertEquals("MODEL_EMPTY_COMPLETION", result.errorCode());
	}

}
