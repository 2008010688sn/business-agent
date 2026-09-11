/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

/**
 * Output contract negotiated with a route model. The stored value is part of a
 * Profile capability snapshot, not a property of the global model configuration.
 */
public enum RouteModelOutputProtocol {

	FUNCTION_CALL,

	STRICT_SCHEMA,

	JSON_OBJECT,

	PROMPT_JSON,

	NONE

}
