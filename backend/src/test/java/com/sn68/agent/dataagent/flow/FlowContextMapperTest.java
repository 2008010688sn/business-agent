/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowContextMapperTest {

	private final FlowContextMapper mapper = new FlowContextMapper();

	@Test
	void writesAndReadsArrayPaths() {
		Map<String, Object> context = new LinkedHashMap<>();

		mapper.set(context, "/input/productList/0/productId", "P1");
		mapper.set(context, "/input/productList/0/num", 10);

		assertEquals("P1", mapper.get(context, "/input/productList/0/productId"));
		assertEquals(10, mapper.get(context, "/input/productList/0/num"));
	}

	@Test
	void mergeMissingPreservesExplicitValuesAndMergePresentResolvesMasterData() {
		Map<String, Object> context = new LinkedHashMap<>();
		mapper.set(context, "/input/productList", List.of(new LinkedHashMap<>(Map.of(
				"productName", "User name", "num", 10))));

		mapper.mergeMissing(context, "/input/productList/0", Map.of("productName", "History name", "productId", "H1"));
		assertEquals("User name", mapper.get(context, "/input/productList/0/productName"));
		assertEquals("H1", mapper.get(context, "/input/productList/0/productId"));

		mapper.mergePresent(context, "/input/productList/0", Map.of("productName", "Master name", "productId", "P1"));
		assertEquals("Master name", mapper.get(context, "/input/productList/0/productName"));
		assertEquals("P1", mapper.get(context, "/input/productList/0/productId"));
		assertEquals(10, mapper.get(context, "/input/productList/0/num"));
	}

}
