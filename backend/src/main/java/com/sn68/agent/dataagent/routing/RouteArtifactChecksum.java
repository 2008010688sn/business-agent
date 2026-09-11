/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import org.springframework.stereotype.Component;

@Component
public class RouteArtifactChecksum {

	private final RouteRulesService rulesService;

	public RouteArtifactChecksum(RouteRulesService rulesService) {
		this.rulesService = rulesService;
	}

	public String calculate(String targetKey, String tenantId, String name, String description, String skillKind,
			String executionMode, RouteRules rules, RouteRisk risk) {
		return calculate(targetKey, tenantId, name, description, skillKind, executionMode, rules, risk, null);
	}

	public String calculate(String targetKey, String tenantId, String name, String description, String skillKind,
			String executionMode, RouteRules rules, RouteRisk risk, String delegationMode) {
		String content = String.join("\n", safe(targetKey), safe(tenantId), safe(name), safe(description),
				safe(skillKind), safe(executionMode), rulesService.toJson(rules), risk.name());
		return SecureUtil.sha256(delegationMode == null ? content : content + "\n" + safe(delegationMode));
	}

	private String safe(String value) {
		return value == null ? "" : value.trim();
	}
}
