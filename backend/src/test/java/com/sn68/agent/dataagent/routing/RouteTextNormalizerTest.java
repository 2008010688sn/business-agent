/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RouteTextNormalizerTest {

	private final RouteTextNormalizer normalizer = new RouteTextNormalizer();

	@Test
	void normalizesUnicodeCaseWhitespaceAndPunctuationWithoutDroppingBusinessTokens() {
		assertEquals("订单_ab-12 2026/07 3.5吨", normalizer.normalize("  订单＿ＡＢ－12，2026/07；3.5吨！ "));
	}

}
