/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Skill 目录详情，携带当前可编辑草稿或已发布版本内容与版本历史摘要。
 */
@Schema(description = "Skill目录详情")
public record SkillDetailResp(
		@Schema(description = "Skill ID") Long id,
		@Schema(description = "租户ID") String tenantId,
		@Schema(description = "Skill编码") String skillCode,
		@Schema(description = "Skill名称") String skillName,
		@Schema(description = "Skill描述") String description,
		@Schema(description = "Skill分类") String category,
		@Schema(description = "可见范围") String scope,
		@Schema(description = "Skill类型") String skillKind,
		@Schema(description = "执行模式") String executionMode,
		@Schema(description = "Skill状态") String status,
		@Schema(description = "展示顺序") Integer displayOrder,
		@Schema(description = "最新草稿版本ID") Long latestDraftVersionId,
		@Schema(description = "已发布版本ID") Long publishedVersionId,
		@Schema(description = "当前版本内容") Version version,
		@Schema(description = "版本历史摘要列表") List<VersionSummary> versions) {

	/**
	 * Skill 版本内容。资源配置为只读发布快照，草稿响应中数据源与语义模型映射为空。
	 */
	@Schema(description = "Skill版本内容")
	public record Version(
			@Schema(description = "版本ID") Long id,
			@Schema(description = "版本号") Integer versionNo,
			@Schema(description = "版本状态") String status,
			@Schema(description = "Skill名称") String skillName,
			@Schema(description = "Skill描述") String description,
			@Schema(description = "Skill分类") String category,
			@Schema(description = "展示顺序") Integer displayOrder,
			@Schema(description = "Skill类型") String skillKind,
			@Schema(description = "执行模式") String executionMode,
			@Schema(description = "Skill说明文档（Markdown）") String skillMarkdown,
			@Schema(description = "路由规则") RouteRulesDTO routeRules,
			@Schema(description = "知识配置") Map<String, Object> knowledgeConfig,
			@Schema(description = "ReAct执行配置") Map<String, Object> reactConfig,
			@Schema(description = "FLOW流程定义") Map<String, Object> flowDefinition,
			@Schema(description = "变量Schema定义") Map<String, Object> variablesSchema,
			@Schema(description = "FLOW运行时配置") Map<String, Object> flowRuntimeConfig,
			@Schema(description = "FLOW策略配置") Map<String, Object> flowPolicyConfig,
			@Schema(description = "资源需求声明") Map<String, Object> resourceRequirement,
			@Schema(description = "输入Schema定义") Map<String, Object> inputSchema,
			@Schema(description = "输出Schema定义") Map<String, Object> outputSchema,
			@Schema(description = "数据源配置快照") Map<String, Object> datasourceConfig,
			@Schema(description = "语义模型配置快照") Map<String, Object> semanticConfig,
			@Schema(description = "运行时配置") Map<String, Object> runtimeConfig,
			@Schema(description = "分析源图配置快照") Map<String, Object> analysisConfig,
			@Schema(description = "版本内容校验和") String checksum,
			@Schema(description = "发布时间") Instant publishedAt) {

		public Version(Long id, Integer versionNo, String status, String skillKind, String executionMode,
				String skillMarkdown, RouteRulesDTO routeRules, Map<String, Object> knowledgeConfig,
				Map<String, Object> reactConfig, Map<String, Object> flowDefinition,
				Map<String, Object> variablesSchema, Map<String, Object> flowRuntimeConfig,
				Map<String, Object> flowPolicyConfig, Map<String, Object> resourceRequirement,
				Map<String, Object> inputSchema, Map<String, Object> outputSchema,
				Map<String, Object> datasourceConfig, Map<String, Object> semanticConfig,
				Map<String, Object> runtimeConfig, String checksum, Instant publishedAt) {
			this(id, versionNo, status, null, null, null, null, skillKind, executionMode, skillMarkdown, routeRules,
					knowledgeConfig, reactConfig, flowDefinition, variablesSchema, flowRuntimeConfig, flowPolicyConfig,
					resourceRequirement, inputSchema, outputSchema, datasourceConfig, semanticConfig, runtimeConfig,
					Map.of(), checksum, publishedAt);
		}

		public Version(Long id, Integer versionNo, String status, String skillMarkdown,
				RouteRulesDTO routeRules, Map<String, Object> knowledgeConfig, Map<String, Object> reactConfig,
				Map<String, Object> flowDefinition, Map<String, Object> variablesSchema, String checksum,
				Instant publishedAt) {
			this(id, versionNo, status, null, null, skillMarkdown, routeRules, knowledgeConfig, reactConfig,
					flowDefinition, variablesSchema, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
					Map.of(), checksum, publishedAt);
		}
	}

	/**
	 * Skill 版本历史摘要项。
	 */
	@Schema(description = "Skill版本历史摘要")
	public record VersionSummary(
			@Schema(description = "版本ID") Long id,
			@Schema(description = "版本号") Integer versionNo,
			@Schema(description = "版本状态") String status,
			@Schema(description = "版本内容校验和") String checksum,
			@Schema(description = "发布时间") Instant publishedAt) {
	}

}
