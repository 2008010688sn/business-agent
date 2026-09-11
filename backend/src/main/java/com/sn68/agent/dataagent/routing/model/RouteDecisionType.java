/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

public enum RouteDecisionType {

	SELECT,

	MULTI_SELECT,

	CLARIFY,

	CONFIRM_REQUIRED,

	DIRECT,

	NO_MATCH,

	ROUTE_UNAVAILABLE

}
