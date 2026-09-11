/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.skilltool.SkillBoundToolCatalogService;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Creates the only resource tool set available to a routed Skill execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRuntimeToolCatalogService {

	private final List<SkillResourceToolProvider> resourceProviders;

	private final SkillBoundToolCatalogService skillBoundToolCatalogService;

	public Map<String, ToolCallback> getToolCallbacks(AgentRequest request, RouteSelection selection,
			DataAgentSkill skill, DataAgentSkillVersion version) {
		if (!matchesRoutedSnapshot(request, selection, skill, version)) {
			return Map.of();
		}
		SkillVersionResources resources = request.getRoutedSkillResources();
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		for (SkillResourceToolProvider provider : resourceProviders) {
			Map<String, ToolCallback> provided = provider.getSkillToolCallbacks(resources);
			if (provided == null || provided.isEmpty()) {
				continue;
			}
			provided.forEach((name, callback) -> register(callbacks, name, callback, provider));
		}
		Map<String, ToolCallback> explicitTools = skillBoundToolCatalogService.getToolCallbacks(request.getAgentId(),
				skill.getSkillCode(), version.getId());
		if (explicitTools != null) {
			explicitTools.forEach((name, callback) -> register(callbacks, name, callback, skillBoundToolCatalogService));
		}
		return Map.copyOf(callbacks);
	}

	private boolean matchesRoutedSnapshot(AgentRequest request, RouteSelection selection, DataAgentSkill skill,
			DataAgentSkillVersion version) {
		if (request == null || selection == null || selection.target() == null || skill == null || version == null
				|| request.getRoutedSkillResources() == null || !StringUtils.hasText(skill.getSkillCode())) {
			return false;
		}
		SkillVersionResources resources = request.getRoutedSkillResources();
		return Objects.equals(selection.target().targetId(), skill.getId())
				&& Objects.equals(selection.target().targetVersionId(), version.getId())
				&& Objects.equals(request.getRoutedSkillId(), skill.getId())
				&& Objects.equals(request.getRoutedSkillVersionId(), version.getId())
				&& Objects.equals(resources.skillId(), skill.getId())
				&& Objects.equals(resources.skillVersionId(), version.getId());
	}

	private void register(Map<String, ToolCallback> callbacks, String name, ToolCallback callback, Object source) {
		if (!StringUtils.hasText(name) || callback == null) {
			return;
		}
		ToolCallback previous = callbacks.putIfAbsent(name, callback);
		if (previous != null && previous != callback) {
			log.warn("Duplicate Skill resource tool name detected, keep first one. tool={}, provider={}", name,
					source.getClass().getName());
		}
	}

}
