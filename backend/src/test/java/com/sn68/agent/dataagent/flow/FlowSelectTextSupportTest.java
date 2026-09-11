/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowSelectTextSupportTest {

	@Test
	void formatNumbersBusinessLabelsAndSkipCommands() {
		String text = FlowSelectTextSupport.format("请选择一项。",
				List.of(Map.of("label", "云南万绿", "summary", "客户编码 YNWL")),
				List.of("不用历史数据"));

		assertTrue(text.startsWith("请选择一项。"));
		assertTrue(text.contains("1. 云南万绿 - 客户编码 YNWL"));
		assertTrue(text.contains(FlowSelectTextSupport.REPLY_HINT));
		assertTrue(text.contains("不需要时可回复：不用历史数据"));
	}

	@Test
	void tooManyOptionsAskUserToNarrow() {
		List<Map<String, Object>> options = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			options.add(Map.of("label", "候选" + i));
		}

		String text = FlowSelectTextSupport.format("请选择一项。", options);

		assertTrue(text.contains(FlowSelectTextSupport.TOO_MANY));
		assertFalse(text.contains("1. 候选0"));
	}

	@Test
	void alreadyFormattedTextIsReturnedAsIs() {
		String original = "请选择一项。\n1. 云南万绿\n" + FlowSelectTextSupport.REPLY_HINT;

		assertEquals(original, FlowSelectTextSupport.format(original, List.of(Map.of("label", "云南万绿"))));
	}

}
