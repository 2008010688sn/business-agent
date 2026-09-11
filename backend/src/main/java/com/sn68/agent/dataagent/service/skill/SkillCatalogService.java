/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillCloneReq;
import com.sn68.agent.dataagent.dto.skill.SkillPageQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillPreviewResp;
import com.sn68.agent.dataagent.dto.skill.SkillSaveReq;
import com.sn68.agent.dataagent.dto.skill.SkillToolEditorContextResp;
import com.sn68.agent.dataagent.entity.DataAgentSkill;

/**
 * Database Skill catalog service.
 */
public interface SkillCatalogService {

	IPage<DataAgentSkill> page(SkillPageQueryReq request);

	SkillDetailResp detail(String skillCode);

	SkillPreviewResp preview(String skillCode, String version);

	SkillDetailResp create(SkillSaveReq request);

	SkillDetailResp modify(String skillCode, SkillSaveReq request);

	SkillDetailResp clonePublished(String skillCode, SkillCloneReq request);

	SkillToolEditorContextResp toolEditorContext(String skillCode, String scope, String executionMode);

	void delete(String skillCode);

	DataAgentSkill findVisible(String skillCode, String tenantId);

	DataAgentSkill requireManageable(String skillCode);

}
