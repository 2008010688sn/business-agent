/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;

/**
 * 语义模型实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("semantic_model")
@Schema(description = "Skill语义模型")
public class SemanticModel extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "Skill ID")
	private Long skillId;

	@Schema(description = "Replaced by newer semantic model resource ID")
	private Long supersededById;

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "表名")
	private String tableName;

	@Schema(description = "字段名")
	private String columnName;

	@Schema(description = "业务名称")
	private String businessName;

	@Schema(description = "同义词")
	private String synonyms;

	@Schema(description = "业务描述")
	private String businessDescription;

	@Schema(description = "字段原始注释")
	private String columnComment;

	@Schema(description = "数据类型")
	private String dataType;

	@Schema(description = "状态")
	private Boolean status;

	public String getPromptInfo() {
		return String.format("业务名称: %s, 表名: %s, 数据库字段名: %s, 字段同义词: %s, 业务描述: %s, 数据类型: %s", businessName,
				tableName, columnName, synonyms, businessDescription, dataType);
	}

}
