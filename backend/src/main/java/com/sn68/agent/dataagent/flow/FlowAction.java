/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.Map;

/**
 * Precise client action used to resume a waiting FLOW node.
 */
public record FlowAction(String actionId, String type, Object value, Map<String, Object> payload) {
}
