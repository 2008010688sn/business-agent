/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * Skill数据源实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("skill_datasource")
@Schema(description = "Skill数据源关联")
public class SkillDatasource extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;
	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "Skill ID")
	private Long skillId;

	@Schema(description = "数据源ID")
	private Long datasourceId;

	@Schema(description = "是否启用")
	private Boolean isActive;

	@Schema(description = "关联数据源")
	@TableField(exist = false)
	private Datasource datasource;

	@Schema(description = "选中的数据表")
	@TableField(exist = false)
	private List<String> selectTables;

	@Schema(description = "按表配置的字段白名单")
	@TableField(exist = false)
	private Map<String, List<String>> selectColumns;

	public SkillDatasource(Long skillId, Long datasourceId) {
		this.skillId = skillId;
		this.datasourceId = datasourceId;
		this.isActive = true;
	}

}
