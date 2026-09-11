/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.temporal.TemporalAmbiguityStrategy;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowTemporalNormalizerTest {

	private final FlowTemporalNormalizer normalizer = new FlowTemporalNormalizer(new AgentTemporalService());

	private final AgentRequest request = AgentRequest.builder()
		.temporalContext(new AgentTemporalContext(Instant.parse("2026-07-19T06:00:00Z"),
				ZoneId.of("Asia/Shanghai"), Locale.forLanguageTag("zh-CN"), LocalDate.parse("2026-07-19"),
				DayOfWeek.MONDAY, TemporalAmbiguityStrategy.ASK))
		.build();

	@Test
	void normalizesConfiguredDateAndDropsAmbiguousValue() {
		Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("arrivalTime",
				Map.of("type", "string", "x-temporal", Map.of("kind", "DATE_OR_DATE_TIME"))));

		FlowTemporalNormalizer.NormalizationResult resolved = normalizer.normalize(
				Map.of("arrivalTime", "今天"), schema, request);
		FlowTemporalNormalizer.NormalizationResult ambiguous = normalizer.normalize(
				Map.of("arrivalTime", "周五"), schema, request);

		assertEquals("2026-07-19", resolved.values().get("arrivalTime"));
		assertFalse(ambiguous.values().containsKey("arrivalTime"));
		assertEquals(1, ambiguous.invalidFields().size());
	}

}
