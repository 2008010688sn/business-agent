/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 模型提案中的一个步骤(不可信,需经 PlanCompiler 校验)。
 *
 * <p>同一 capabilityHandle 允许被多个不同 stepKey 复用;
 * 是否允许同一目标端口出现多条绑定由端口基数(PortCardinality)在编译期裁决,此处不做去重。
 */
public record RouteProposalStep(String stepKey, String capabilityHandle, String task,
		List<RouteProposalBinding> bindings) {

	public RouteProposalStep {
		if (!StringUtils.hasText(stepKey) || !StringUtils.hasText(capabilityHandle) || !StringUtils.hasText(task)) {
			throw new IllegalArgumentException("Route proposal step requires stepKey, capabilityHandle and task");
		}
		stepKey = stepKey.trim();
		capabilityHandle = capabilityHandle.trim();
		task = task.trim();
		bindings = bindings == null ? List.of() : List.copyOf(bindings);
	}

}
