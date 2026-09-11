/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultBudgetSanitizerTest {

	private static final String TRUNCATION_MARK = "\n...(结果超限已截断)";

	@Test
	void keepsResultUnchangedWhenUnderLimit() {
		String result = "{\"total\":1,\"rows\":[{\"id\":1}]}";
		assertEquals(result, ToolResultBudgetSanitizer.sanitize(result, 8000, 6000));
	}

	@Test
	void keepsNullAndEmptyResultUnchanged() {
		assertNull(ToolResultBudgetSanitizer.sanitize(null, 8000, 6000));
		assertEquals("", ToolResultBudgetSanitizer.sanitize("", 8000, 6000));
	}

	@Test
	void ignoresBudgetWhenMaxCharsNotPositive() {
		String result = "z".repeat(50);
		assertEquals(result, ToolResultBudgetSanitizer.sanitize(result, 0, 0));
	}

	@Test
	void truncatesJsonArrayFieldsAndMarksTruncated() {
		StringBuilder rows = new StringBuilder();
		for (int i = 0; i < 500; i++) {
			if (i > 0) {
				rows.append(',');
			}
			rows.append("{\"id\":").append(i).append(",\"name\":\"item-").append(i).append("\"}");
		}
		String result = "{\"total\":500,\"rows\":[" + rows + "]}";
		String sanitized = ToolResultBudgetSanitizer.sanitize(result, 2000, 1000);
		assertTrue(sanitized.length() <= 2000, "sanitized length should fit the budget: " + sanitized.length());
		assertTrue(sanitized.contains("\"truncated\":true"));
		assertTrue(sanitized.contains("\"total\":500"));
		assertTrue(sanitized.contains("\"rows\":["));
	}

	@Test
	void fallsBackToHeadTruncationWhenNotJson() {
		String result = "x".repeat(9000);
		String sanitized = ToolResultBudgetSanitizer.sanitize(result, 8000, 6000);
		assertEquals(6000 + TRUNCATION_MARK.length(), sanitized.length());
		assertTrue(sanitized.startsWith("xxxxx"));
		assertTrue(sanitized.endsWith(TRUNCATION_MARK.trim()));
	}

	@Test
	void fallsBackToHeadTruncationWhenJsonHasNoArrayField() {
		String result = "{\"text\":\"" + "y".repeat(20000) + "\"}";
		String sanitized = ToolResultBudgetSanitizer.sanitize(result, 8000, 6000);
		assertEquals(6000 + TRUNCATION_MARK.length(), sanitized.length());
		assertTrue(sanitized.endsWith(TRUNCATION_MARK.trim()));
	}

	@Test
	void fallsBackToHeadTruncationWhenSingleArrayElementStillTooLarge() {
		String result = "{\"rows\":[\"" + "y".repeat(20000) + "\"]}";
		String sanitized = ToolResultBudgetSanitizer.sanitize(result, 8000, 6000);
		assertEquals(6000 + TRUNCATION_MARK.length(), sanitized.length());
		assertTrue(sanitized.endsWith(TRUNCATION_MARK.trim()));
	}

}
