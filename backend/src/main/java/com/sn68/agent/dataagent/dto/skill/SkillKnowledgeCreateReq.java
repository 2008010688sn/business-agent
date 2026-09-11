/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.annotation.InEnum;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * QA Skill 知识创建请求，支持文档、问答对、FAQ 三类知识资源。
 */
@Data
public class SkillKnowledgeCreateReq {

	@Schema(description = "知识标题")
	@NotBlank
	private String title;

	@Schema(description = "知识类型")
	@NotBlank
	@InEnum(value = KnowledgeType.class)
	private String type;

	@Schema(description = "问题内容（问答类知识）")
	private String question;

	@Schema(description = "知识正文内容")
	private String content;

	@Schema(description = "知识文件存储路径")
	private String filePath;

	@Schema(description = "源文件名")
	private String sourceFilename;

	@Schema(description = "文件大小（字节）")
	private Long fileSize;

	@Schema(description = "文件类型")
	private String fileType;

	@Schema(description = "文档切分器类型")
	private String splitterType;

}
