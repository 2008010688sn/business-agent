/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

/**
 * Independently persisted availability of one optional routing capability.
 */
public enum RouteCapabilityState {

	NOT_PROBED,

	SUPPORTED,

	UNSUPPORTED,

	UNAVAILABLE,

	STALE

}
