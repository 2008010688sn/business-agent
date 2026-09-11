/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow.definition;

import java.util.Map;

/**
 * Restricted conditional branch.
 */
public record FlowBranch(Map<String, Object> condition, String next) {
}
