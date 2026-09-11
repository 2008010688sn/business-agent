/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import org.springframework.util.StringUtils;

/** Server-resolved mapping from a predecessor's published output to one step input. */
public record RouteStepInputMapping(String sourceStepId, String sourceField, String targetField,
		RouteDependencyValueType valueType) {

	public RouteStepInputMapping {
		if (!StringUtils.hasText(sourceStepId) || !StringUtils.hasText(sourceField)
				|| !StringUtils.hasText(targetField) || valueType == null) {
			throw new IllegalArgumentException("Route step input mapping is invalid");
		}
		sourceStepId = sourceStepId.trim();
		sourceField = sourceField.trim();
		targetField = targetField.trim();
	}

}
