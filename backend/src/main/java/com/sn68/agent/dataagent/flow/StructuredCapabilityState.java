/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

/**
 * Persistable lifecycle state for one task-profile protocol probe.
 */
public enum StructuredCapabilityState {

	NOT_PROBED,

	SUPPORTED,

	UNSUPPORTED,

	UNAVAILABLE,

	STALE

}
