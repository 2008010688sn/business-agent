/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 编译后的单个执行步骤:携带固定能力版本、类型化绑定、拓扑执行序与每步预算,全字段不可变。
 */
public record CompiledPlanStep(String stepKey, String capabilityHandle, CapabilityVersionRef versionRef, String task,
		int executionOrder, List<CompiledStepBinding> bindings, List<String> dependsOn, long stepBudgetTokens) {

	public CompiledPlanStep {
		if (!StringUtils.hasText(stepKey) || !StringUtils.hasText(capabilityHandle) || !StringUtils.hasText(task)) {
			throw new IllegalArgumentException("Compiled plan step requires stepKey, capabilityHandle and task");
		}
		if (versionRef == null || executionOrder < 0 || stepBudgetTokens <= 0) {
			throw new IllegalArgumentException("Compiled plan step requires versionRef, executionOrder and budget");
		}
		stepKey = stepKey.trim();
		capabilityHandle = capabilityHandle.trim();
		task = task.trim();
		bindings = bindings == null ? List.of() : List.copyOf(bindings);
		dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
	}

}
