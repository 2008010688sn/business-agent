/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 编译后的类型化绑定:valueType 取自服务端端口定义(目标输入端口的声明类型),不采信提案。
 */
public record CompiledStepBinding(String targetPortHandle, RouteBindingSourceKind sourceKind, String sourceStepKey,
		String sourcePortHandle, RoutePortValueType valueType) {

	public CompiledStepBinding {
		if (!StringUtils.hasText(targetPortHandle) || sourceKind == null || valueType == null) {
			throw new IllegalArgumentException("Compiled step binding requires targetPortHandle, sourceKind and valueType");
		}
		targetPortHandle = targetPortHandle.trim();
		if (sourceKind == RouteBindingSourceKind.STEP_OUTPUT) {
			if (!StringUtils.hasText(sourceStepKey) || !StringUtils.hasText(sourcePortHandle)) {
				throw new IllegalArgumentException("Compiled STEP_OUTPUT binding requires sourceStepKey and sourcePortHandle");
			}
			sourceStepKey = sourceStepKey.trim();
			sourcePortHandle = sourcePortHandle.trim();
		}
		else if (sourceStepKey != null || sourcePortHandle != null) {
			throw new IllegalArgumentException("Compiled " + sourceKind + " binding must not reference a source step");
		}
	}

}
