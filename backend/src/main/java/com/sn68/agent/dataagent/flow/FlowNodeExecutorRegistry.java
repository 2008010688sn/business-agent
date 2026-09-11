/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Registry of intentionally supported generic FLOW node types.
 */
@Component
public class FlowNodeExecutorRegistry {

	private static final Set<String> TYPES = Set.of("extract", "collect", "review", "resolve", "select", "merge",
			"validate", "switch", "confirm", "execute", "present", "handoff", "end", "error");

	public String requireSupported(String type) {
		String normalized = StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : "";
		if (!TYPES.contains(normalized)) {
			throw CheckedException.badRequest("Unsupported FLOW node type: " + type);
		}
		return normalized;
	}

}
