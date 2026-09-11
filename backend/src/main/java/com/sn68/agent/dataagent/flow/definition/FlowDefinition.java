/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow.definition;

import java.util.List;
import java.util.Map;

/**
 * Deterministic Skill FLOW definition.
 */
public record FlowDefinition(String schemaVersion, String startNode, String interruptPolicy,
		Map<String, Object> variablesSchema, List<FlowNode> nodes) {
}
