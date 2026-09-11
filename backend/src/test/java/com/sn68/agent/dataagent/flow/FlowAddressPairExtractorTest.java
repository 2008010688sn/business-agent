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

class FlowAddressPairExtractorTest {

	private static final List<Map<String, Object>> CONFIG = List.of(Map.of(
			"field", "addressList",
			"nameField", "siteName",
			"typeField", "type",
			"fromValue", "0",
			"toValue", "1",
			"separators", List.of("到", "至", "->", "→")));

	@Test
	void extractsFromToSitesWithoutCallingAModel() {
		Map<String, Object> extracted = FlowAddressPairExtractor.extract("杭州前进仓到杭州萧山仓", CONFIG);

		List<?> addresses = (List<?>) extracted.get("addressList");
		assertEquals(2, addresses.size());
		assertEquals("杭州前进仓", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("0", ((Map<?, ?>) addresses.get(0)).get("type"));
		assertEquals("杭州萧山仓", ((Map<?, ?>) addresses.get(1)).get("siteName"));
		assertEquals("1", ((Map<?, ?>) addresses.get(1)).get("type"));
	}

	@Test
	void stripsLeadingFromParticle() {
		Map<String, Object> extracted = FlowAddressPairExtractor.extract("从杭州前进仓到杭州萧山仓", CONFIG);

		List<?> addresses = (List<?>) extracted.get("addressList");
		assertEquals("杭州前进仓", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("杭州萧山仓", ((Map<?, ?>) addresses.get(1)).get("siteName"));
	}

	@Test
	void extractsRolePrefixedSitesWithoutSeparator() {
		Map<String, Object> extracted = FlowAddressPairExtractor.extract("发货网点是上海仓，收货网点是杭州仓", CONFIG);

		List<?> addresses = (List<?>) extracted.get("addressList");
		assertEquals(2, addresses.size());
		assertEquals("上海仓", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("0", ((Map<?, ?>) addresses.get(0)).get("type"));
		assertEquals("杭州仓", ((Map<?, ?>) addresses.get(1)).get("siteName"));
		assertEquals("1", ((Map<?, ?>) addresses.get(1)).get("type"));
	}

	@Test
	void prefersLongerSentToSeparator() {
		Map<String, Object> extracted = FlowAddressPairExtractor.extract("从客户工厂送到萧山码头", CONFIG);

		List<?> addresses = (List<?>) extracted.get("addressList");
		assertEquals("客户工厂", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("萧山码头", ((Map<?, ?>) addresses.get(1)).get("siteName"));
	}

	@Test
	void keepsLastSiteTokenWhenUtteranceHasLeadingSlots() {
		Map<String, Object> extracted = FlowAddressPairExtractor.extract("下单 送箱 杭州前进仓到杭州萧山仓", CONFIG);

		List<?> addresses = (List<?>) extracted.get("addressList");
		assertEquals("杭州前进仓", ((Map<?, ?>) addresses.get(0)).get("siteName"));
		assertEquals("杭州萧山仓", ((Map<?, ?>) addresses.get(1)).get("siteName"));
	}

	@Test
	void ignoresListingQuestionsAndBareArrivalTime() {
		assertTrue(FlowAddressPairExtractor.extract("当前客户有哪些网点", CONFIG).isEmpty());
		assertTrue(FlowAddressPairExtractor.extract("到货时间改明天下午六点", CONFIG).isEmpty());
		assertTrue(FlowAddressPairExtractor.extract("下单 送箱 趟租 客户是箱箱 到货时间今天", CONFIG).isEmpty());
		assertTrue(FlowAddressPairExtractor.extract("有哪些商品", CONFIG).isEmpty());
		assertTrue(FlowAddressPairExtractor.extract("上海仓 杭州仓", CONFIG).isEmpty());
	}
}
