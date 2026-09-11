/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.List;

/** Internal representation of a server-owned business clarification. */
public record RouteClarification(String prompt, String title, List<RouteClarificationOption> options,
		boolean allowFreeText, String riskLevel) {

	public RouteClarification {
		options = options == null ? List.of() : List.copyOf(options);
	}

	public static RouteClarification open(String prompt) {
		return new RouteClarification(prompt, "请补充业务信息", List.of(), true, "medium");
	}

}
