/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

/**
 * 当前文本 Turn 对 READ Resolver 的一次性关键词覆盖，不写入 FLOW 业务上下文。
 */
public record FlowTextSearchIntent(String waitingNodeId, String resolverNodeId, String targetPath, String keyword,
		String argumentName, boolean consumed) {

	public FlowTextSearchIntent consume() {
		return new FlowTextSearchIntent(waitingNodeId, resolverNodeId, targetPath, keyword, argumentName, true);
	}

}
