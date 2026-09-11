/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RouteProposalV2Parser 严格解析测试:完整解析后处理,未知字段/类型错误/重复键/尾随内容一律失败。
 */
class RouteProposalV2ParserTest {

	private final RouteProposalV2Parser parser = new RouteProposalV2Parser(new ObjectMapper());

	@Test
	void parsesMinimalChatProposal() {
		RouteProposalV2 proposal = parser.parse("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\"}");

		assertEquals(RouteProposalMode.CHAT, proposal.mode());
		assertTrue(proposal.steps().isEmpty());
		assertTrue(proposal.controlEdges().isEmpty());
		assertNull(proposal.clarify());
	}

	@Test
	void parsesStepsBindingsEdgesAndClarify() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"ORCHESTRATION\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-a\",\"task\":\"查询客户\"},"
				+ "{\"stepKey\":\"s2\",\"capabilityHandle\":\"cap-b\",\"task\":\"创建订单\",\"bindings\":["
				+ "{\"targetPortHandle\":\"customer\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s1\","
				+ "\"sourcePortHandle\":\"customerRef\"}]}],"
				+ "\"controlEdges\":[[\"s1\",\"s2\"]],\"clarify\":null}";

		RouteProposalV2 proposal = parser.parse(json);

		assertEquals(2, proposal.steps().size());
		assertEquals("s1", proposal.steps().get(0).stepKey());
		assertEquals(RouteBindingSourceKind.STEP_OUTPUT, proposal.steps().get(1).bindings().get(0).sourceKind());
		assertEquals("s1", proposal.controlEdges().get(0).fromStepKey());
		assertEquals("s2", proposal.controlEdges().get(0).toStepKey());
	}

	@Test
	void parsesClarifyObject() {
		String json = "{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CLARIFY_RESUME\","
				+ "\"clarify\":{\"question\":\"要查哪个客户\"}}";

		RouteProposalV2 proposal = parser.parse(json);

		assertEquals("要查哪个客户", proposal.clarify().question());
	}

	@Test
	void rejectsEmptyInput() {
		assertParseFails("", PlanCompileException.PROPOSAL_JSON_INVALID);
		assertParseFails("   ", PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	@Test
	void rejectsMalformedJson() {
		assertParseFails("{\"schemaVersion\":", PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	@Test
	void rejectsTrailingTokens() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\"} {}",
				PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	@Test
	void rejectsDuplicateJsonKeys() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"mode\":\"DIRECT\"}",
				PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	@Test
	void rejectsNonObjectRoot() {
		assertParseFails("[1,2,3]", PlanCompileException.PROPOSAL_JSON_INVALID);
	}

	@Test
	void rejectsUnknownRootField() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"note\":\"x\"}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsWrongSchemaVersion() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v1\",\"mode\":\"CHAT\"}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsUnknownMode() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"SUPER\"}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsTypeErrors() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"steps\":{}}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":123}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"clarify\":\"why\"}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsBlankStepKey() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"DIRECT\",\"steps\":["
				+ "{\"stepKey\":\" \",\"capabilityHandle\":\"cap-a\",\"task\":\"查询客户\"}]}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsUserInputBindingWithSourceStep() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"DIRECT\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-a\",\"task\":\"查询客户\",\"bindings\":["
				+ "{\"targetPortHandle\":\"keyword\",\"sourceKind\":\"USER_INPUT\",\"sourceStepKey\":\"s0\","
				+ "\"sourcePortHandle\":\"out\"}]}]}", PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsStepOutputBindingWithoutSourcePort() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"DIRECT\",\"steps\":["
				+ "{\"stepKey\":\"s1\",\"capabilityHandle\":\"cap-a\",\"task\":\"查询客户\",\"bindings\":["
				+ "{\"targetPortHandle\":\"keyword\",\"sourceKind\":\"STEP_OUTPUT\",\"sourceStepKey\":\"s0\"}]}]}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	@Test
	void rejectsMalformedControlEdge() {
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"controlEdges\":[[\"s1\"]]}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
		assertParseFails("{\"schemaVersion\":\"route-proposal-v2\",\"mode\":\"CHAT\",\"controlEdges\":[\"s1\"]}",
				PlanCompileException.PROPOSAL_SCHEMA_INVALID);
	}

	private void assertParseFails(String json, String reasonCode) {
		PlanCompileException ex = assertThrows(PlanCompileException.class, () -> parser.parse(json));
		assertEquals(reasonCode, ex.reasonCode());
	}

}
