/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Data;

/**
 * Skill 知识资源的接口展示视图。
 */
@Data
@Schema(description = "Skill知识资源视图")
public class SkillKnowledgeVO {

	@Schema(description = "知识ID")
	private Long id;

	@Schema(description = "所属Skill ID")
	private Long skillId;

	@Schema(description = "知识标题")
	private String title;

	@Schema(description = "知识类型")
	private String type;

	@Schema(description = "问题内容（问答类知识）")
	private String question;

	@Schema(description = "知识正文内容")
	private String content;

	@Schema(description = "是否参与召回")
	private Boolean isRecall;

	@Schema(description = "向量化状态")
	private EmbeddingStatus embeddingStatus;

	@Schema(description = "向量化失败原因")
	private String errorMsg;

	@Schema(description = "文档切分器类型")
	private String splitterType;

	@Schema(description = "源文件名")
	private String sourceFilename;

	@Schema(description = "知识文件存储路径")
	private String filePath;

	@Schema(description = "文件大小（字节）")
	private Long fileSize;

	@Schema(description = "文件类型")
	private String fileType;

	@Schema(description = "文件预览地址")
	private String filePreviewUrl;

	@Schema(description = "是否被已发布版本引用")
	private Boolean publishedReferenced;

	@Schema(description = "创建时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private Instant createdTime;

	@Schema(description = "更新时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private Instant updatedTime;

}
