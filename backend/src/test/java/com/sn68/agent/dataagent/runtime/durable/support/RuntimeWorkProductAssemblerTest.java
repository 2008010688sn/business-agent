/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeWorkProductAssemblerTest {

	private AgentRuntimeArtifactMapper artifactMapper;

	private RuntimeWorkProductAssembler assembler;

	@BeforeEach
	void setUp() {
		artifactMapper = mock(AgentRuntimeArtifactMapper.class);
		assembler = new RuntimeWorkProductAssembler(artifactMapper, new ObjectMapper());
	}

	@Test
	void sensitiveArtifactHidesData() {
		when(artifactMapper.listByRunId(9L)).thenReturn(List.of(AgentRuntimeArtifact.builder()
			.id(1L)
			.runId(9L)
			.stepKey("single-turn")
			.schemaVersion("single-turn-answer/v1")
			.data("{\"answer\":\"秘密\"}")
			.sensitivity("SENSITIVE")
			.build()));

		List<RuntimeArtifactResp> artifacts = assembler.listArtifacts(9L);

		assertEquals(1, artifacts.size());
		assertNull(artifacts.get(0).data());
		assertEquals("SENSITIVE", artifacts.get(0).sensitivity());
	}

	@Test
	void resolvePrefersFinalAnswer() {
		RuntimeArtifactResp artifact = new RuntimeArtifactResp(1L, "single-turn", "single-turn-answer/v1",
				"{\"answer\":\"产物\"}", "INTERNAL");
		assertEquals("权威回答", assembler.resolveFinalAnswer("权威回答", List.of(artifact)));
	}

	@Test
	void resolveFallsBackToSingleTurnAnswer() {
		RuntimeArtifactResp artifact = new RuntimeArtifactResp(1L, "single-turn", "single-turn-answer/v1",
				"{\"answer\":\"产物回答\",\"runtimeRequestId\":\"rr-1\"}", "INTERNAL");
		assertEquals("产物回答", assembler.resolveFinalAnswer(null, List.of(artifact)));
	}

	@Test
	void resolveSkipsStructuredWorkProductsWhenFallingBack() {
		RuntimeArtifactResp table = new RuntimeArtifactResp(2L, "single-turn", "query-result/v1",
				"{\"snapshots\":[]}", "INTERNAL");
		RuntimeArtifactResp answer = new RuntimeArtifactResp(1L, "single-turn", "single-turn-answer/v1",
				"{\"answer\":\"产物回答\"}", "INTERNAL");
		assertEquals("产物回答", assembler.resolveFinalAnswer(null, List.of(answer, table)));
	}

}
