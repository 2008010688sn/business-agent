/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.Assert;

/**
 * Schema-only Function Calling callback for FLOW extraction. A model may submit
 * a patch through it but it can never execute a business operation.
 */
public final class FlowPatchSubmissionToolCallback implements ToolCallback {

	public static final String NAME = "submit_flow_patch";

	private final ToolDefinition definition;

	public FlowPatchSubmissionToolCallback(String jsonSchema) {
		Assert.hasText(jsonSchema, "jsonSchema must not be empty");
		this.definition = ToolDefinition.builder().name(NAME)
			.description("Submit one FLOW field patch. This callback never executes a business operation.")
			.inputSchema(jsonSchema).build();
	}

	@Override
	public ToolDefinition getToolDefinition() {
		return definition;
	}

	@Override
	public String call(String toolInput) {
		throw new IllegalStateException("FLOW patch submission callback must never execute a business tool");
	}

	@Override
	public String call(String toolInput, ToolContext toolContext) {
		throw new IllegalStateException("FLOW patch submission callback must never execute a business tool");
	}

}
