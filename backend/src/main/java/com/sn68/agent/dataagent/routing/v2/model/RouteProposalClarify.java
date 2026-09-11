/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 模型提案中的澄清请求,仅在 CLARIFY_RESUME 模式下允许出现。
 */
public record RouteProposalClarify(String question) {

	public RouteProposalClarify {
		if (!StringUtils.hasText(question)) {
			throw new IllegalArgumentException("Route proposal clarify requires a question");
		}
		question = question.trim();
	}

}
