/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * FLOW 结构化校验问题定位测试。
 */
class FlowValidationIssueTest {

	@Test
	void locatesUiActionIssueOnNodeConfig() {
		FlowValidationIssue issue = FlowValidationIssue.error(
				"uiActions label must not be blank on node confirm-order");

		assertEquals("confirm-order", issue.nodeId());
		assertEquals("flowDefinition.nodes.confirm-order.config.uiActions", issue.path());
	}

	@Test
	void ignoresResolverNodeFieldNameWhenLocatingNode() {
		FlowValidationIssue issue = FlowValidationIssue.error(
				"textSearch.resolverNode must reference a resolve node on select-customer");

		assertEquals("select-customer", issue.nodeId());
		assertEquals("flowDefinition.nodes.select-customer.config.textSearch", issue.path());
	}
}
