/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;

class AgentRequestPublicContractTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void explicitRouteTargetIsNotPartOfThePublicRequestContract() throws Exception {
		AgentRequest request = objectMapper.readValue("""
				{
				  "agentId": "10",
				  "threadId": "20",
				  "runtimeRequestId": "runtime-1",
				  "query": "查询订单",
				  "explicitRouteTarget": {
				    "targetType": "SKILL",
				    "targetId": 30,
				    "targetVersionId": 31,
				    "routeArtifactId": 40,
				    "routeProfileId": 1
				  }
				}
				""", AgentRequest.class);

		assertNull(request.getExplicitRouteTarget());
		request.setExplicitRouteTarget(new ExplicitRouteTarget(RouteTargetType.SKILL, 30L, 31L, 40L, 1L));
		assertFalse(objectMapper.writeValueAsString(request).contains("explicitRouteTarget"));
		Schema schema = AgentRequest.class.getDeclaredField("explicitRouteTarget").getAnnotation(Schema.class);
		assertTrue(schema.hidden());
	}

	@Test
	void employeeFacadeStreamAndOwnerTypeAreNotClientWritable() throws Exception {
		AgentRequest request = objectMapper.readValue("""
				{
				  "agentId": "10",
				  "threadId": "20",
				  "query": "查询订单",
				  "employeeFacadeStream": true,
				  "streamSearchRuntime": true,
				  "v2AnalysisIntent": "FILE_ONLY",
				  "ownerType": "DIGITAL_EMPLOYEE",
				  "ownerId": 9
				}
				""", AgentRequest.class);

		assertFalse(request.isEmployeeFacadeStream());
		assertFalse(request.isStreamSearchRuntime());
		assertNull(request.getV2AnalysisIntent());
		assertNull(request.getOwnerType());
		assertNull(request.getOwnerId());
		request.setEmployeeFacadeStream(true);
		request.setStreamSearchRuntime(true);
		request.setV2AnalysisIntent("FILE_ONLY");
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setDurableRunId(88L);
		request.setFenceToken(3L);
		request.setLeaseOwner("chat-node-1");
		String json = objectMapper.writeValueAsString(request);
		assertFalse(json.contains("employeeFacadeStream"));
		assertFalse(json.contains("streamSearchRuntime"));
		assertFalse(json.contains("v2AnalysisIntent"));
		assertFalse(json.contains("FILE_ONLY"));
		assertFalse(json.contains("DIGITAL_EMPLOYEE"));
		assertFalse(json.contains("durableRunId"));
		assertFalse(json.contains("fenceToken"));
		assertFalse(json.contains("leaseOwner"));
	}

	@Test
	void pageContextIsClientWritableAndGroundedFactsAreServerOnly() throws Exception {
		AgentRequest request = objectMapper.readValue("""
				{
				  "agentId": "10",
				  "query": "分析需求和运单",
				  "pageContext": {
				    "keys": { "id": "2087735796997206016", "costCode": "CC-1" },
				    "objectType": "receivable"
				  },
				  "groundedFacts": {
				    "ownOrigin": true,
				    "keys": [ { "name": "id", "value": "1", "trust": "own_origin" } ]
				  }
				}
				""", AgentRequest.class);

		assertEquals("2087735796997206016", request.getPageContext().getKeys().get("id"));
		assertEquals("CC-1", request.getPageContext().getKeys().get("costCode"));
		assertEquals("receivable", request.getPageContext().getObjectType());
		assertNull(request.getGroundedFacts());
		request.setGroundedFacts(new GroundedFacts());
		assertFalse(objectMapper.writeValueAsString(request).contains("groundedFacts"));
	}

}
