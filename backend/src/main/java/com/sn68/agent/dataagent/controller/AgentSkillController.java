/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingV2DTO;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingsUpdateReq;
import com.sn68.agent.dataagent.service.skill.SkillBindingService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 与 Skill 锁定版本绑定关系的管理接口。
 */
@RestController
@RequestMapping("/data-agent/{agentId}/skill-bindings")
@RequiredArgsConstructor
@Tag(name = "Agent Skill绑定", description = "维护Agent与Skill锁定版本的绑定关系")
public class AgentSkillController {

	private final SkillBindingService skillBindingService;

	@GetMapping
	@Operation(summary = "查询Agent Skill绑定列表")
	public List<AgentSkillBindingV2DTO> list(@PathVariable Long agentId) {
		return skillBindingService.list(agentId);
	}

	@GetMapping("/editor-context")
	@Operation(summary = "查询 Agent Skill 绑定编辑上下文")
	public AgentSkillBindingEditorContextResp editorContext(@PathVariable Long agentId) {
		return skillBindingService.editorContext(agentId);
	}

	@PutMapping
	@AccessLog(module = "Agent Skill 绑定", description = "替换 Agent Skill 绑定")
	@Operation(summary = "整体替换Agent Skill绑定")
	public List<AgentSkillBindingV2DTO> replace(@PathVariable Long agentId,
			@RequestBody AgentSkillBindingsUpdateReq request) {
		return skillBindingService.replace(agentId, request == null ? List.of() : request.bindings());
	}

}
