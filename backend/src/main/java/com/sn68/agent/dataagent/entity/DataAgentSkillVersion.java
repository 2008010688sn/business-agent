/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Immutable Skill version after publishing.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_skill_version", autoResultMap = true)
public class DataAgentSkillVersion extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 租户ID（空表示平台级）。 */
	private String tenantId;

	/** 所属Skill ID。 */
	private Long skillId;

	/** Skill名称（版本快照）。 */
	private String skillName;

	/** 描述（版本快照）。 */
	private String description;

	/** 分类（版本快照）。 */
	private String category;

	/** 展示顺序（版本快照）。 */
	private Integer displayOrder;

	/** Skill类型（版本快照）。 */
	private String skillKind;

	/** 执行模式（REACT/FLOW）。 */
	private String executionMode;

	/** 版本号（同一Skill内递增）。 */
	private Integer versionNo;

	/** 版本状态（DRAFT/PUBLISHED等）。 */
	private String status;

	/** Skill 主体 Markdown（提示词/说明）。 */
	private String skillMarkdown;

	/** 路由规则（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String routeRules;

	/** 知识库配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String knowledgeConfig;

	/** REACT 模式配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String reactConfig;

	/** FLOW 流程定义（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String flowDefinition;

	/** 变量 Schema（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String variablesSchema;

	/** FLOW 运行时配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String flowRuntimeConfig;

	/** FLOW 策略配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String flowPolicyConfig;

	/** 依赖的工具/资源需求（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String resourceRequirement;

	/** 入参 JSON Schema。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String inputSchema;

	/** 出参 JSON Schema。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String outputSchema;

	/** 数据源配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String datasourceConfig;

	/** 语义模型配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String semanticConfig;

	/** 分析源图配置（JSON）。发布进版本快照后不可变。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String analysisConfig;

	/** 运行时配置（JSON）。 */
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String runtimeConfig;

	/** 版本内容校验和。 */
	private String checksum;

	/** 发布时间。 */
	private Instant publishedAt;

	/** 发布人。 */
	private String publishedBy;

	/**
	 * 平台来源版本：从能力市场安装时记录的 skill_market_listing_version.id，本地创建的版本为空。
	 */
	private Long sourceListingVersionId;

	/**
	 * 市场内容哈希：安装时从来源市场版本记录的 content_hash，用于校验来源内容一致性。
	 */
	private String contentHash;

	/**
	 * 撤销标记：平台来源版本被市场撤销后级联置 true，运行时/安装校验拒绝使用。
	 */
	private Boolean revoked;

}
