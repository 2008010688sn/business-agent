/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import java.util.Objects;

/**
 * Context passed to a selected Skill executor.
 */
public record SkillExecutionContext(AgentRequest request, DataAgent agent, ModelConfigDTO modelConfig,
		RouteSelection selection, DataAgentSkill skill, DataAgentSkillVersion version, SkillVersionResources resources) {

	public boolean hasMatchingRoutedSnapshot() {
		if (request == null || selection == null || selection.target() == null || skill == null || version == null
				|| resources == null) {
			return false;
		}
		return Objects.equals(selection.target().targetId(), skill.getId())
				&& Objects.equals(selection.target().targetVersionId(), version.getId())
				&& Objects.equals(request.getRoutedSkillId(), skill.getId())
				&& Objects.equals(request.getRoutedSkillVersionId(), version.getId())
				&& Objects.equals(resources.skillId(), skill.getId())
				&& Objects.equals(resources.skillVersionId(), version.getId());
	}
}
