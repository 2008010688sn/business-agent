/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.flow.FlowSelectTextSupport;
import com.sn68.agent.dataagent.service.agent.AgentInvocationResult;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 将公开 FLOW UI 消息渲染为 IM 文本，不读取 rawData 或内部上下文。
 */
@Component
public class FlowTextRenderer {

	public String render(AgentInvocationResult result) {
		if (result == null || result.uiMessage() == null) {
			return result == null ? "" : result.answer();
		}
		AgentUiMessage ui = result.uiMessage();
		String text = ui.content() == null ? result.answer() : ui.content().text();
		if (ui.payload() == null) {
			return text;
		}
		String action = ui.payload().action();
		if ("SELECT".equalsIgnoreCase(action)) {
			return renderSelect(text, ui.payload().options());
		}
		if ("REVIEW".equalsIgnoreCase(action)) {
			return appendCommands(text, ui.actions(), "需要修改时直接输入修改内容");
		}
		if ("CONFIRM".equalsIgnoreCase(action)) {
			return appendCommands(text, ui.actions(), "确认提交请回复“确认提交”，修改请直接说明");
		}
		if ("CANCEL_CONFIRM".equalsIgnoreCase(action)) {
			return appendCommands(text, ui.actions(), "请回复“确认取消”或“继续”");
		}
		if ("COLLECT".equalsIgnoreCase(action) || "VALIDATION".equalsIgnoreCase(action)) {
			return appendIfMissing(text, "请直接输入需要补充或修改的信息。");
		}
		return text;
	}

	private String renderSelect(String text, List<Map<String, Object>> options) {
		return FlowSelectTextSupport.format(defaultText(text), options);
	}

	private String appendCommands(String text, List<AgentUiMessage.Action> actions, String fallback) {
		List<String> labels = new ArrayList<>();
		if (actions != null) {
			for (AgentUiMessage.Action action : actions) {
				if (action != null && StringUtils.hasText(action.label())) {
					labels.add(action.label());
				}
			}
		}
		return appendIfMissing(text, labels.isEmpty() ? fallback : "可回复：" + String.join("、", labels));
	}

	private String appendIfMissing(String text, String suffix) {
		String base = defaultText(text);
		return base.contains(suffix) ? base : base + (base.isEmpty() ? "" : "\n") + suffix;
	}

	private String defaultText(String value) {
		return value == null ? "" : value;
	}

}
