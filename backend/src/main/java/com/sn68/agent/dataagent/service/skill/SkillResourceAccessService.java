/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.SkillKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Verifies that a request may manage resources owned by a tenant Skill.
 */
@Service
@RequiredArgsConstructor
public class SkillResourceAccessService {

	private final DataAgentSkillMapper skillMapper;

	private final SkillManagementTenantService skillManagementTenantService;

	public DataAgentSkill requireTenantSkill(Long skillId) {
		if (skillId == null) {
			throw CheckedException.badRequest("skillId不能为空");
		}
		DataAgentSkill skill = skillMapper.selectById(skillId);
		if (skill == null || Boolean.TRUE.equals(skill.getDeleted())) {
			throw CheckedException.notFound("Skill不存在");
		}
		if (!"TENANT".equals(skill.getScope()) || !StringUtils.hasText(skill.getTenantId())) {
			throw CheckedException.badRequest("Skill必须归属于一个租户才能配置资源");
		}
		String tenantId = skillManagementTenantService.effectiveTenantId();
		if (!StringUtils.hasText(tenantId) || !Objects.equals(skill.getTenantId(), tenantId)) {
			throw CheckedException.forbidden();
		}
		return skill;
	}

	public DataAgentSkill requireQueryResourceSkill(Long skillId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		if (!SkillKind.QUERY.name().equals(skill.getSkillKind())
				|| (!SkillExecutionMode.DETERMINISTIC.name().equals(skill.getExecutionMode())
						&& !SkillExecutionMode.REACT.name().equals(skill.getExecutionMode()))) {
			throw CheckedException.badRequest("只有 QUERY + DETERMINISTIC/REACT Skill 可以配置数据源和语义模型");
		}
		return skill;
	}

	public DataAgentSkill requireBusinessKnowledgeResourceSkill(Long skillId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		boolean query = SkillKind.QUERY.name().equals(skill.getSkillKind())
				&& (SkillExecutionMode.DETERMINISTIC.name().equals(skill.getExecutionMode())
						|| SkillExecutionMode.REACT.name().equals(skill.getExecutionMode()));
		boolean flow = SkillExecutionMode.FLOW.name().equals(skill.getExecutionMode())
				&& (SkillKind.ACTION.name().equals(skill.getSkillKind())
						|| SkillKind.ORCHESTRATION.name().equals(skill.getSkillKind()));
		if (!query && !flow) {
			throw CheckedException.badRequest("只有 QUERY 或 FLOW Skill 可以配置业务知识");
		}
		return skill;
	}

	/** Only QA + KNOWLEDGE owns uploaded documents, QA pairs, and FAQs. */
	public DataAgentSkill requireKnowledgeBaseResourceSkill(Long skillId) {
		DataAgentSkill skill = requireTenantSkill(skillId);
		if (!SkillKind.QA.name().equals(skill.getSkillKind())
				|| !SkillExecutionMode.KNOWLEDGE.name().equals(skill.getExecutionMode())) {
			throw CheckedException.badRequest("QA + KNOWLEDGE Skill only supports knowledge-base resources");
		}
		return skill;
	}

	public void requireResourceOwner(Long skillId, Long resourceSkillId, String resourceName) {
		requireTenantSkill(skillId);
		if (!Objects.equals(skillId, resourceSkillId)) {
			throw CheckedException.notFound(resourceName + "不存在");
		}
	}

}
