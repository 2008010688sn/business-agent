/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingV2DTO;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import java.util.List;

/**
 * Agent Skill pinned-version binding service.
 */
public interface SkillBindingService {

	List<AgentSkillBindingV2DTO> list(Long agentId);

	List<AgentSkillBindingV2DTO> replace(Long agentId, List<AgentSkillBindingV2DTO> bindings);

	AgentSkillBindingEditorContextResp editorContext(Long agentId);

	List<DataAgentSkillBinding> listEnabled(Long agentId, String tenantId);

}
