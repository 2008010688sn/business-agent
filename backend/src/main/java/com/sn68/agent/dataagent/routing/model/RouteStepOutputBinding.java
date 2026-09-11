/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import org.springframework.util.StringUtils;

/** A published output field that may be consumed by a dependent route step. */
public record RouteStepOutputBinding(String field, RouteDependencyValueType valueType) {

	public RouteStepOutputBinding {
		if (!StringUtils.hasText(field) || valueType == null) {
			throw new IllegalArgumentException("Route step output binding is invalid");
		}
		field = field.trim();
	}

}
