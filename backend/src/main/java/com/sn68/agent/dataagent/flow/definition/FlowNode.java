/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow.definition;

import java.util.List;
import java.util.Map;

/**
 * A generic FLOW node. Business semantics live only in config.
 */
public record FlowNode(String id, String type, String next, Map<String, Object> config, List<FlowBranch> branches) {
}
