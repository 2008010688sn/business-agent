/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 服务端声明的能力端口定义:端口句柄、取值类型、是否可空、基数与敏感级别。
 */
public record PortSpec(String portHandle, RoutePortValueType valueType, boolean nullable,
		PortCardinality cardinality, PortSensitivity sensitivity) {

	public PortSpec {
		if (!StringUtils.hasText(portHandle) || valueType == null || cardinality == null || sensitivity == null) {
			throw new IllegalArgumentException("Port spec requires portHandle, valueType, cardinality and sensitivity");
		}
		portHandle = portHandle.trim();
	}

}
