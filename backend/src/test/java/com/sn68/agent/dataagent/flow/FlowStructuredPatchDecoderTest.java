/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.service.tokenusage.AgentStructuredModelCallResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

class FlowStructuredPatchDecoderTest {

	private final FlowStructuredPatchDecoder decoder = new FlowStructuredPatchDecoder(new ObjectMapper(),
			new FlowSchemaValidator());

	@Test
	void acceptsOnlyOneNamedFunctionCallAndItsSetPatch() {
		AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-1", "function",
				FlowPatchSubmissionToolCallback.NAME, "{\"set\":{\"companyName\":\"Acme\"}}");

		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.FUNCTION_CALL,
				result("ignored", List.of(call)), patchSchema());

		assertEquals(Map.of("companyName", "Acme"), patch);
	}

	@Test
	void acceptsSetWrapperForEveryProtocolIncludingJsonObjectFallback() {
		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"set\":{\"companyName\":\"Acme\"}}", List.of()), patchSchema());
		assertEquals(Map.of("companyName", "Acme"), patch);

		Map<String, Object> strict = decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				result("{\"set\":{\"companyName\":\"Acme\"}}", List.of()), patchSchema());
		assertEquals(Map.of("companyName", "Acme"), strict);
	}

	@Test
	void acceptsBarePatchAndIgnoresUnknownTopLevelFields() {
		assertEquals(Map.of("companyName", "Acme"), decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"companyName\":\"Acme\"}", List.of()), patchSchema()));
		assertEquals(Map.of("companyName", "Acme"), decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"set\":{\"companyName\":\"Acme\"},\"unexpected\":true}", List.of()), patchSchema()));
		assertEquals(Map.of("companyName", "Acme"), decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				result("{\"companyName\":\"Acme\"}", List.of()), patchSchema()));
	}

	@Test
	void stripsControlFieldsAndKeepsWritableSet() {
		assertEquals(Map.of("companyName", "Acme"), decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				result("{\"set\":{\"companyName\":\"Acme\"},\"clear\":[\"companyName\"]}", List.of()),
				patchSchema()));
		assertEquals(Map.of("companyName", "Acme"), decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				result("{\"set\":{\"companyName\":\"Acme\"},\"note\":\"extra\"}", List.of()), patchSchema()));
		FlowExtractionException controlOnly = assertThrows(FlowExtractionException.class,
				() -> decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
						result("{\"clear\":[\"companyName\"]}", List.of()), patchSchema()));
		assertEquals(FlowExtractionException.INVALID_RESPONSE, controlOnly.errorCode());
	}

	@Test
	void stripsIdentifierFieldsAndKeepsWritableNeighbors() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("companyName", Map.of("type", "string"), "addressList", Map.of("type", "array", "items",
						Map.of("type", "object", "additionalProperties", false, "properties",
								Map.of("siteName", Map.of("type", "string"), "type", Map.of("type", "string"))))));

		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"set\":{\"companyName\":\"Acme\",\"companyId\":\"9527\",\"addressList\":["
						+ "{\"siteName\":\"杭州前进仓\",\"type\":\"0\",\"siteId\":\"S1\"},"
						+ "{\"siteName\":\"杭州萧山仓\",\"type\":1}]}}", List.of()), schema);

		assertEquals("Acme", patch.get("companyName"));
		assertFalse(patch.containsKey("companyId"));
		List<?> addresses = (List<?>) patch.get("addressList");
		assertEquals(2, addresses.size());
		assertEquals("杭州前进仓", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("0", ((Map<?, ?>) addresses.get(0)).get("type"));
		assertFalse(((Map<?, ?>) addresses.get(0)).containsKey("siteId"));
		assertEquals("1", ((Map<?, ?>) addresses.get(1)).get("type"));
	}

	@Test
	void identifierOnlyPatchIsRejected() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("companyName", Map.of("type", "string")));
		FlowExtractionException failure = assertThrows(FlowExtractionException.class,
				() -> decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
						result("{\"set\":{\"companyId\":\"9527\"}}", List.of()), schema));
		assertEquals(FlowExtractionException.INVALID_RESPONSE, failure.errorCode());
	}

	@Test
	void acceptsMarkdownFencedJsonObject() {
		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("```json\n{\"set\":{\"companyName\":\"Acme\"}}\n```", List.of()), patchSchema());
		assertEquals(Map.of("companyName", "Acme"), patch);
	}

	@Test
	void intentOnlyPatchIsAcceptedWhenTurnFieldsAreInSchema() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("companyName", Map.of("type", "string"),
						"turnAction", Map.of("type", "string", "enum",
								List.of("fill", "list", "proceed", "cancel", "ask", "chitchat")),
						"listLabel", Map.of("type", "string")));

		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"set\":{\"turnAction\":\"list\",\"listLabel\":\"商品\"}}", List.of()), schema);

		assertEquals("list", patch.get("turnAction"));
		assertEquals("商品", patch.get("listLabel"));
		assertFalse(patch.containsKey("companyName"));
	}

	@Test
	void integerFieldAcceptsLeadingDigitsInString() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("quantity", Map.of("type", "integer", "minimum", 1)));

		Map<String, Object> patch = decoder.decode(FlowStructuredOutputProtocol.JSON_OBJECT,
				result("{\"set\":{\"quantity\":\"10个\"}}", List.of()), schema);

		assertEquals(10L, patch.get("quantity"));
	}

	@Test
	void rejectsValuesViolatingLengthEnumAndRange() {
		Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("companyName", Map.of("type", "string", "maxLength", 4),
						"level", Map.of("type", "string", "enum", List.of("A", "B")),
						"quantity", Map.of("type", "integer", "minimum", 1)));

		assertEquals(FlowExtractionException.INVALID_RESPONSE, assertThrows(FlowExtractionException.class,
				() -> decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
						result("{\"set\":{\"companyName\":\"超过四个字符了\"}}", List.of()), schema)).errorCode());
		assertEquals(FlowExtractionException.INVALID_RESPONSE, assertThrows(FlowExtractionException.class,
				() -> decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
						result("{\"set\":{\"level\":\"C\"}}", List.of()), schema)).errorCode());
		assertEquals(FlowExtractionException.INVALID_RESPONSE, assertThrows(FlowExtractionException.class,
				() -> decoder.decode(FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
						result("{\"set\":{\"quantity\":0}}", List.of()), schema)).errorCode());
	}

	private AgentStructuredModelCallResult result(String text, List<AssistantMessage.ToolCall> toolCalls) {
		return new AgentStructuredModelCallResult(null, text, toolCalls, "stop", 1L, 1L, 2L, "ACTUAL", 1L);
	}

	private Map<String, Object> patchSchema() {
		return Map.of("type", "object", "additionalProperties", false, "properties",
				Map.of("companyName", Map.of("type", "string")));
	}

}
