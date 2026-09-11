/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Skill 知识资源可编辑文本字段的更新请求。
 */
@Data
public class SkillKnowledgeUpdateReq {

	@Schema(description = "知识标题")
	private String title;

	@Schema(description = "知识正文内容")
	private String content;

	@Schema(description = "问题内容（问答类知识）")
	private String question;

}
