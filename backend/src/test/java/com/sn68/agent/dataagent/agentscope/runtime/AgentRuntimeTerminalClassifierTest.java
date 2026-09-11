/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentRuntimeTerminalClassifierTest {

	private final AgentRuntimeTerminalClassifier classifier = new AgentRuntimeTerminalClassifier();

	@Test
	void historicalToolFailureDoesNotOverrideFinalAssistantText() {
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics();
		metrics.recordFailure("earlier datasource call failed");
		Msg response = Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.textContent("The later query succeeded.")
			.build();

		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(response);

		assertEquals(1, metrics.toolFailCount());
		assertEquals(AgentRuntimeTerminalOutcome.SUCCESS, result.outcome());
		assertEquals("The later query succeeded.", result.answer());
	}

	@Test
	void thinkingOnlyResponseIsEmptyCompletion() {
		Msg response = Msg.builder()
			.name("assistant")
			.role(MsgRole.ASSISTANT)
			.content(ThinkingBlock.builder().thinking("<tool_call>fake</tool_call>").build())
			.build();

		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(response);
		assertEquals(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, result.outcome());
		assertEquals("MODEL_EMPTY_COMPLETION", result.errorCode());
		assertEquals(AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getLabel(), result.failureMessage());
	}

	@Test
	void blankAssistantTextIsEmptyCompletion() {
		Msg response = Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent("").build();

		assertEquals("MODEL_EMPTY_COMPLETION", classifier.classify(response).errorCode());
		assertEquals(AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getLabel(),
				classifier.classify(response).failureMessage());
	}

	@Test
	void toolResultTextCannotBecomeSuccessfulAnswer() {
		Msg response = Msg.builder()
			.name("tool")
			.role(MsgRole.TOOL)
			.content(ToolResultBlock.of(TextBlock.builder().text("query rows").build()))
			.build();

		assertEquals(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, classifier.classify(response).outcome());
		assertEquals("MODEL_EMPTY_COMPLETION", classifier.classify(response).errorCode());
	}

	@Test
	void actingStopWithBusinessFailureIsFailure() {
		ToolResultBlock failure = ToolResultBlock.of("call-1", "skill.create",
				TextBlock.builder().text("missing project name").build(),
				Map.of(SpringToolCallbackAgentAdapter.METADATA_BUSINESS_FAILED, true, "errorCode", "MISSING_FIELD"));
		Msg response = Msg.builder()
			.name("tool")
			.role(MsgRole.TOOL)
			.content(failure)
			.build()
			.withGenerateReason(GenerateReason.ACTING_STOP_REQUESTED);

		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(response);

		assertEquals(AgentRuntimeTerminalOutcome.BUSINESS_FAILED, result.outcome());
		assertEquals("MISSING_FIELD", result.errorCode());
	}

	@Test
	void actingStopPreservesToolBudgetFailureCode() {
		ToolResultBlock failure = ToolResultBlock.of("call-1", "datasource.search",
				TextBlock.builder().text("已达到当前智能体的工具调用次数上限。").build(),
				Map.of(SpringToolCallbackAgentAdapter.METADATA_BUSINESS_FAILED, true, "errorCode",
						"TOOL_CALL_LIMIT_EXCEEDED"));
		Msg response = Msg.builder()
			.name("tool")
			.role(MsgRole.TOOL)
			.content(failure)
			.build()
			.withGenerateReason(GenerateReason.ACTING_STOP_REQUESTED);

		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(response);

		assertEquals(AgentRuntimeTerminalOutcome.BUSINESS_FAILED, result.outcome());
		assertEquals("TOOL_CALL_LIMIT_EXCEEDED", result.errorCode());
	}

	@Test
	void agentScopeSummaryBudgetErrorIsRuntimeFailed() {
		String text = "Maximum iterations (10) reached. Error generating summary: Agent runtime budget exceeded: "
				+ "reason=MODEL_CALLS, limit=10, current=10, attempted=1";
		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(assistantText(text));

		assertEquals(AgentRuntimeTerminalOutcome.RUNTIME_FAILED, result.outcome());
		assertEquals("BUDGET_EXCEEDED", result.errorCode());
		assertEquals(AgentRuntimeErrorCode.BUDGET_EXCEEDED.getLabel(), result.failureMessage());
	}

	@Test
	void completeToolXmlEnvelopeIsProtocolError() {
		assertProtocolError("<tool_call><name>waybill.query</name><arguments>{}</arguments></tool_call>");
	}

	@Test
	void completeToolJsonEnvelopeIsProtocolError() {
		assertProtocolError("{\"tool_calls\":[{\"function\":{\"name\":\"waybill.query\",\"arguments\":{}}}]}");
	}

	@Test
	void legacyAssistantToolEnvelopeIsProtocolError() {
		assertProtocolError("assistant to=waybill.query code\n{\"month\":\"current\"}");
	}

	@Test
	void normalBusinessJsonAndXmlRemainSuccessfulAnswers() {
		assertSuccess("{\"customer\":\"A\",\"boxCount\":12}");
		assertSuccess("<result><customer>A</customer><boxCount>12</boxCount></result>");
	}

	@Test
	void protocolExamplesInsideCodeFenceOrExplanationRemainSuccessfulAnswers() {
		assertSuccess("```json\n{\"tool_calls\":[{\"function\":{\"name\":\"demo\",\"arguments\":{}}}]}\n```");
		assertSuccess("以下内容是协议示例：\n<tool_call><name>demo</name><arguments>{}</arguments></tool_call>");
	}

	private void assertProtocolError(String text) {
		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(assistantText(text));
		assertEquals(AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR, result.outcome());
		assertEquals("MODEL_PROTOCOL_ERROR", result.errorCode());
	}

	private void assertSuccess(String text) {
		AgentRuntimeTerminalClassifier.TerminalResult result = classifier.classify(assistantText(text));
		assertEquals(AgentRuntimeTerminalOutcome.SUCCESS, result.outcome());
		assertEquals(text, result.answer());
	}

	private Msg assistantText(String text) {
		return Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(text).build();
	}

}
