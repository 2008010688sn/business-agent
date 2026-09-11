/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 业务知识展示对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "业务知识展示对象")
public class BusinessKnowledgeVO {

	@Schema(description = "业务术语")
	private Long id;

	@Schema(description = "业务术语")
	private String businessTerm;

	@Schema(description = "同义词")
	private String description;

	@Schema(description = "同义词")
	private String synonyms;

	@Schema(description = "是否参与召回")
	@JsonFormat(shape = JsonFormat.Shape.BOOLEAN)
	private Boolean isRecall;

	@Schema(description = "Skill ID")
	private Long skillId;

	@Schema(description = "时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private Instant createdTime;

	@Schema(description = "时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private Instant updatedTime;

	@Schema(description = "状态")
	private String embeddingStatus;

	@Schema(description = "错误字段")
	private String errorMsg;

}
