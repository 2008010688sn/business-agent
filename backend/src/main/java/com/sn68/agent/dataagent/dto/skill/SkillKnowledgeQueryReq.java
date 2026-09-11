/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Skill 知识资源分页查询过滤条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SkillKnowledgeQueryReq extends PageRequest {

	@Schema(description = "知识标题（模糊匹配）")
	private String title;

	@Schema(description = "知识类型")
	private String type;

	@Schema(description = "向量化状态")
	private String embeddingStatus;

}
