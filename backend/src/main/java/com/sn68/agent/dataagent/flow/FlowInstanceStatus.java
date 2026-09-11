/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import java.util.List;

/**
 * Persistent FLOW instance status.
 */
public enum FlowInstanceStatus {

	RUNNING,

	PROCESSING,

	WAITING,

	EXECUTING,

	UNKNOWN,

	SUCCEEDED,

	FAILED,

	SUSPENDED,

	CANCELLED;

	public boolean active() {
		return this == RUNNING || this == PROCESSING || this == WAITING || this == EXECUTING || this == UNKNOWN
				|| this == SUSPENDED;
	}

	public static List<String> activeStatuses() {
		return List.of(RUNNING.name(), PROCESSING.name(), WAITING.name(), EXECUTING.name(), UNKNOWN.name(),
				SUSPENDED.name());
	}

}
