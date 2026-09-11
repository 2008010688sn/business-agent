/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

class DefaultRouteSemanticServiceTest {

	@Test
	void searchUsesEmbeddingModelSelectedByProfile() {
		AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);
		RouteEmbeddingModelResolver resolver = mock(RouteEmbeddingModelResolver.class);
		EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			DefaultRouteSemanticService service = new DefaultRouteSemanticService(vectorStoreService, executor, resolver);
			RouteCandidate candidate = candidate();
			RoutePolicy policy = new RoutePolicy(1L, true, false, true, 70, 15, 0.5D, 0.85D, 0.1D,
					0.8D, 11L, 22L, "embedding-v2", true, false, null,
					RouteModelOutputProtocol.NONE);
			Duration timeout = Duration.ofMillis(250);
			when(resolver.resolve(22L, "embedding-v2", timeout)).thenReturn(embeddingModel);
			Document document = Document.builder()
				.id("route-40")
				.text("订单查询")
				.metadata(Map.of(DocumentMetadataConstant.ROUTE_ARTIFACT_ID, 40L))
				.score(0.82D)
				.build();
			when(vectorStoreService.searchRouteDocuments(eq("tenant-1"), eq(1L), eq("embedding-v2"),
					any(), eq("查询订单"), eq(6), eq(0.5D), eq(embeddingModel))).thenReturn(List.of(document));

			var matches = service.search(context(), policy, List.of(candidate), 6, timeout);

			assertEquals(1, matches.size());
			assertEquals(0.82D, matches.get(0).score());
			verify(resolver).resolve(22L, "embedding-v2", timeout);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private RouteContext context() {
		return new RouteContext("tenant-1", 10L, "DATA_ANALYSIS", 20L, "100", "request-1", "查询订单",
				null, null, null, Instant.now().plusSeconds(2), 1);
	}

	private RouteCandidate candidate() {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 30L, 31L, 32L), "tenant-1", 10L,
				"订单查询", "查询订单", "QUERY", "REACT", RouteRules.empty(), RouteRisk.READ_ONLY, 0, 1L,
				40L, "checksum", "embedding-v2");
	}

}
