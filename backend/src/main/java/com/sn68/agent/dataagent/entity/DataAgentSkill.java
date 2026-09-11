/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Database-backed Skill catalog entry.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("data_agent_skill")
@Schema(description = "Skill catalog entry")
public class DataAgentSkill extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "Skill编码")
	private String skillCode;

	@Schema(description = "Skill名称")
	private String skillName;

	@Schema(description = "描述")
	private String description;

	@Schema(description = "分类")
	private String category;

	@Schema(description = "可见范围（TENANT等）")
	private String scope;

	@Schema(description = "Skill类型")
	private String skillKind;

	@Schema(description = "执行模式（REACT/FLOW）")
	private String executionMode;

	@Schema(description = "状态（DRAFT/PUBLISHED等）")
	private String status;

	@Schema(description = "最新草稿版本ID")
	private Long latestDraftVersionId;

	@Schema(description = "当前发布版本ID")
	private Long publishedVersionId;

	@Schema(description = "展示顺序")
	private Integer displayOrder;

}
