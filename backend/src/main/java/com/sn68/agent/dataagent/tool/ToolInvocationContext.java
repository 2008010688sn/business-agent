/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import java.util.Map;

/**
 * Server-controlled tool invocation context.
 */
public record ToolInvocationContext(Long agentId, String tenantId, Long skillVersionId, Long resourceVersionId,
		String expectedAccessMode, boolean confirmed, String idempotencyKey, Map<String, Object> arguments,
		FlowInvocationReference flowReference) {

	public ToolInvocationContext(Long agentId, String tenantId, Long skillVersionId, Long resourceVersionId,
			String expectedAccessMode, boolean confirmed, String idempotencyKey, Map<String, Object> arguments) {
		this(agentId, tenantId, skillVersionId, resourceVersionId, expectedAccessMode, confirmed, idempotencyKey,
				arguments, null);
	}
}
