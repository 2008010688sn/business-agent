/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowConditionEvaluatorTest {

	private final FlowConditionEvaluator evaluator = new FlowConditionEvaluator(new FlowContextMapper());

	@Test
	void evaluatesOnlyAllowedOperatorsAgainstJsonPointer() {
		Map<String, Object> context = Map.of("input", Map.of("customerId", "C1", "count", 2));

		assertTrue(evaluator.evaluate(Map.of("op", "eq", "path", "/input/customerId", "value", "C1"), context));
		assertTrue(evaluator.evaluate(Map.of("op", "all", "conditions", List.of(
				Map.of("op", "notEmpty", "path", "/input/customerId"),
				Map.of("op", "gte", "path", "/input/count", "value", 2))), context));
		assertFalse(evaluator.evaluate(Map.of("op", "script", "path", "/input/count", "value", 2), context));
	}

}
