/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePreviewRespTest {

	@Test
	void jsonContainsOnlyPublicDiagnosticsContract() throws Exception {
		RoutePreviewResp response = new RoutePreviewResp("SELECT", "LEXICAL_HIGH_CONFIDENCE", "NONE", 1L,
				false, List.of(new RoutePreviewResp.Candidate(1, RouteTargetType.SKILL, 2L, 3L, 4L,
						"订单查询", 70, 0.81D, List.of("phrase:订单"), RouteRisk.READ_ONLY)),
				new RouteTiming(1, 2, 3, 0, 6));

		String json = new ObjectMapper().writeValueAsString(response);

		assertTrue(json.contains("\"vectorScore\":0.81"));
		assertTrue(json.contains("\"reasonCode\":\"LEXICAL_HIGH_CONFIDENCE\""));
		assertFalse(json.contains("routeDecision"));
		assertFalse(json.contains("selections"));
	}

}
