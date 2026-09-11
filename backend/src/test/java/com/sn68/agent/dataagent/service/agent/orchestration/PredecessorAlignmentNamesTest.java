/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.agent.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.service.report.ReportDataSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PredecessorAlignmentNamesTest {

	@Test
	void extractsBusinessNamesFromDimensionColumns() {
		ReportDataSnapshot snapshot = ReportDataSnapshot.builder()
			.columns(List.of("客户名称", "签收数"))
			.rows(List.of(row("客户名称", "箱箱物流", "签收数", "12"), row("客户名称", "API_TEST_11_24", "签收数", "3")))
			.build();

		assertEquals(List.of("箱箱物流", "API_TEST_11_24"), PredecessorAlignmentNames.fromSnapshots(List.of(snapshot)));
	}

	@Test
	void ignoresMetricOnlySnapshots() {
		ReportDataSnapshot snapshot = ReportDataSnapshot.builder()
			.columns(List.of("总金额"))
			.rows(List.of(row("总金额", "100")))
			.build();

		assertTrue(PredecessorAlignmentNames.fromSnapshots(List.of(snapshot)).isEmpty());
	}

	@Test
	void capsExtractedNames() {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			rows.add(row("客户名称", "客户" + i));
		}
		ReportDataSnapshot snapshot = ReportDataSnapshot.builder().columns(List.of("客户名称")).rows(rows).build();

		assertEquals(PredecessorAlignmentNames.MAX_NAMES,
				PredecessorAlignmentNames.fromSnapshots(List.of(snapshot)).size());
	}

	private static Map<String, Object> row(String k1, Object v1) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(k1, v1);
		return row;
	}

	private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(k1, v1);
		row.put(k2, v2);
		return row;
	}

}
