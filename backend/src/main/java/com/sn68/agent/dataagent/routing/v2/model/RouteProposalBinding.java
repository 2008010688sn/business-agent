/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 模型提案中的一条输入端口绑定(不可信,需经 PlanCompiler 校验)。
 *
 * <p>STEP_OUTPUT 必须携带 sourceStepKey 与 sourcePortHandle;USER_INPUT / CONSTANT
 * 禁止引用来源步骤,实际取值由服务端在执行期绑定。
 */
public record RouteProposalBinding(String targetPortHandle, RouteBindingSourceKind sourceKind, String sourceStepKey,
		String sourcePortHandle) {

	public RouteProposalBinding {
		if (!StringUtils.hasText(targetPortHandle) || sourceKind == null) {
			throw new IllegalArgumentException("Route proposal binding requires targetPortHandle and sourceKind");
		}
		targetPortHandle = targetPortHandle.trim();
		if (sourceKind == RouteBindingSourceKind.STEP_OUTPUT) {
			if (!StringUtils.hasText(sourceStepKey) || !StringUtils.hasText(sourcePortHandle)) {
				throw new IllegalArgumentException("STEP_OUTPUT binding requires sourceStepKey and sourcePortHandle");
			}
			sourceStepKey = sourceStepKey.trim();
			sourcePortHandle = sourcePortHandle.trim();
		}
		else {
			if (StringUtils.hasText(sourceStepKey) || StringUtils.hasText(sourcePortHandle)) {
				throw new IllegalArgumentException(sourceKind + " binding must not reference a source step or port");
			}
			sourceStepKey = null;
			sourcePortHandle = null;
		}
	}

}
