/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 模型提案中的一条纯控制依赖边(from 先于 to 执行),JSON 形态为 ["from", "to"] 字符串对。
 */
public record RouteControlEdge(String fromStepKey, String toStepKey) {

	public RouteControlEdge {
		if (!StringUtils.hasText(fromStepKey) || !StringUtils.hasText(toStepKey)) {
			throw new IllegalArgumentException("Route control edge requires fromStepKey and toStepKey");
		}
		fromStepKey = fromStepKey.trim();
		toStepKey = toStepKey.trim();
	}

}
