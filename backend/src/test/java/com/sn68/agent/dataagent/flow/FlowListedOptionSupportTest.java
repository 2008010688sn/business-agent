/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowListedOptionSupportTest {

	@Test
	void exactLabelWins() {
		Map<String, Object> matched = FlowListedOptionSupport.matchUnique(options(), "绿箱");

		assertEquals("option-1", matched.get("value"));
	}

	@Test
	void productNoContainedInQuerySelectsUniqueOption() {
		Map<String, Object> matched = FlowListedOptionSupport.matchUnique(options(), "of330绿箱");

		assertEquals("option-1", matched.get("value"));
	}

	@Test
	void ambiguousTokenDoesNotSelect() {
		assertTrue(FlowListedOptionSupport.matchUnique(options(), "箱").isEmpty());
	}

	@Test
	void matchSoleReturnsTheOnlyOption() {
		Map<String, Object> matched = FlowListedOptionSupport.matchSole(List.of(options().get(0)));

		assertEquals("option-1", matched.get("value"));
	}

	@Test
	void matchSoleDoesNotPickAmongMany() {
		assertTrue(FlowListedOptionSupport.matchSole(options()).isEmpty());
	}

	private static List<Map<String, Object>> options() {
		return List.of(
				Map.of("label", "绿箱", "value", "option-1",
						"rawData", Map.of("productId", "A-1", "productNo", "330", "productName", "绿箱")),
				Map.of("label", "灰箱", "value", "option-2",
						"rawData", Map.of("productId", "B-1", "productNo", "331", "productName", "灰箱")));
	}

}
