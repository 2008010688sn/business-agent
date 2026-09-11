/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RouteRiskResolver {

	private final DataAgentSkillToolRefMapper toolRefMapper;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	public RouteRiskResolver(DataAgentSkillToolRefMapper toolRefMapper,
			AgentExecutionResourceVersionMapper resourceVersionMapper) {
		this.toolRefMapper = toolRefMapper;
		this.resourceVersionMapper = resourceVersionMapper;
	}

	public RouteRisk resolveSkill(DataAgentSkillVersion version) {
		if (version == null) {
			return RouteRisk.UNKNOWN;
		}
		if ("FLOW".equals(version.getExecutionMode())) {
			return RouteRisk.FLOW;
		}
		List<Long> resourceIds = toolRefMapper.findBySkillVersionId(version.getId()).stream()
			.map(DataAgentSkillToolRef::getResourceVersionId).filter(Objects::nonNull).distinct().toList();
		if (!resourceIds.isEmpty()) {
			List<AgentExecutionResourceVersion> resources = resourceVersionMapper.selectBatchIds(resourceIds);
			if (resources.size() != resourceIds.size()) {
				return RouteRisk.UNKNOWN;
			}
			if (resources.stream().anyMatch(resource -> "WRITE".equalsIgnoreCase(resource.getAccessMode()))) {
				return RouteRisk.WRITE;
			}
		}
		return List.of("QA", "QUERY").contains(version.getSkillKind()) ? RouteRisk.READ_ONLY : RouteRisk.UNKNOWN;
	}
}
