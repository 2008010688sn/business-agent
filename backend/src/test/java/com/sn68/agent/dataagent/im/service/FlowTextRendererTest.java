/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.service.agent.AgentInvocationResult;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowTextRendererTest {

	private final FlowTextRenderer renderer = new FlowTextRenderer();

	@Test
	void selectRendersPublicFieldsWithoutRawData() {
		AgentUiMessage message = message("SELECT", List.of(
				Map.of("label", "云南万绿", "value", "option-1", "summary", "客户编码 YNWL",
						"rawData", Map.of("companyId", "secret-id"))));

		String text = renderer.render(new AgentInvocationResult("", message));

		assertTrue(text.contains("1. 云南万绿 - 客户编码 YNWL"));
		assertTrue(text.contains("请回复序号"));
		assertFalse(text.contains("secret-id"));
	}

	@Test
	void tooManyOptionsAreNotSilentlyTruncated() {
		List<Map<String, Object>> options = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			options.add(Map.of("label", "候选" + i, "value", "option-" + (i + 1)));
		}

		String text = renderer.render(new AgentInvocationResult("", message("SELECT", options)));

		assertTrue(text.contains("匹配项较多"));
		assertFalse(text.contains("1. 候选0"));
	}

	@Test
	void alreadyNumberedSelectTextIsNotDuplicated() {
		AgentUiMessage message = new AgentUiMessage("agent-ui/v2", "skill-flow", "req-1", null,
				new AgentUiMessage.Content("markdown", "请选择一项。\n1. 云南万绿\n请回复序号、候选名称或业务编码。"),
				new AgentUiMessage.Payload("SELECT", Map.of(),
						List.of(Map.of("label", "云南万绿", "value", "option-1"))),
				List.of(), null);

		String text = renderer.render(new AgentInvocationResult("请选择一项。", message));

		assertEquals(1, text.split("1\\. 云南万绿", -1).length - 1);
		assertEquals(1, text.split("请回复序号", -1).length - 1);
	}

	@Test
	void reviewRendersConfiguredCommandsAndDirectEditHint() {
		AgentUiMessage message = new AgentUiMessage("agent-ui/v2", "skill-flow", "req-1", null,
				new AgentUiMessage.Content("markdown", "当前信息"),
				new AgentUiMessage.Payload("REVIEW", Map.of(), List.of()),
				List.of(new AgentUiMessage.Action("review-submit", "SUBMIT", "使用当前信息继续下单", true, Map.of()),
						new AgentUiMessage.Action("clear-reference", "CLEAR_REFERENCE", "清除历史参考", null, Map.of())),
				null);

		String text = renderer.render(new AgentInvocationResult("", message));

		assertTrue(text.contains("使用当前信息继续下单"));
		assertTrue(text.contains("清除历史参考"));
	}

	private AgentUiMessage message(String action, List<Map<String, Object>> options) {
		return new AgentUiMessage("agent-ui/v2", "skill-flow", "req-1", null,
				new AgentUiMessage.Content("markdown", "请选择"),
				new AgentUiMessage.Payload(action, Map.of(), options), List.of(), null);
	}

}
