/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Skill 目录信息与草稿版本内容的保存请求。
 */
@Schema(description = "Skill保存请求")
public record SkillSaveReq(
		@Schema(description = "Skill编码") String skillCode,
		@Schema(description = "Skill名称") String skillName,
		@Schema(description = "Skill描述") String description,
		@Schema(description = "Skill分类") String category,
		@Schema(description = "可见范围") String scope,
		@Schema(description = "执行模式") String executionMode,
		@Schema(description = "展示顺序") Integer displayOrder,
		@Schema(description = "Skill说明文档（Markdown）") String skillMarkdown,
		@Schema(description = "路由规则") Map<String, Object> routeRules,
		@Schema(description = "知识配置") Map<String, Object> knowledgeConfig,
		@Schema(description = "ReAct执行配置") Map<String, Object> reactConfig,
		@Schema(description = "FLOW流程定义") Map<String, Object> flowDefinition,
		@Schema(description = "变量Schema定义") Map<String, Object> variablesSchema,
		@Schema(description = "工具引用列表") List<SkillToolRefSaveDTO> toolRefs,
		@Schema(description = "FLOW运行时配置") Map<String, Object> flowRuntimeConfig,
		@Schema(description = "FLOW策略配置") Map<String, Object> flowPolicyConfig,
		@Schema(description = "Skill类型") String skillKind,
		@Schema(description = "资源需求声明") Map<String, Object> resourceRequirement,
		@Schema(description = "输入Schema定义") Map<String, Object> inputSchema,
		@Schema(description = "输出Schema定义") Map<String, Object> outputSchema,
		@Schema(description = "运行时配置") Map<String, Object> runtimeConfig,
		@Schema(description = "分析源图配置") Map<String, Object> analysisConfig) {

	public SkillSaveReq(String skillCode, String skillName, String description, String category, String scope,
			String executionMode, Integer displayOrder, String skillMarkdown, Map<String, Object> routeRules,
			Map<String, Object> knowledgeConfig, Map<String, Object> reactConfig, Map<String, Object> flowDefinition,
			Map<String, Object> variablesSchema, List<SkillToolRefSaveDTO> toolRefs,
			Map<String, Object> flowRuntimeConfig, Map<String, Object> flowPolicyConfig, String skillKind,
			Map<String, Object> resourceRequirement, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
			Map<String, Object> runtimeConfig) {
		this(skillCode, skillName, description, category, scope, executionMode, displayOrder, skillMarkdown, routeRules,
				knowledgeConfig, reactConfig, flowDefinition, variablesSchema, toolRefs, flowRuntimeConfig, flowPolicyConfig,
				skillKind, resourceRequirement, inputSchema, outputSchema, runtimeConfig, null);
	}

	public SkillSaveReq(String skillCode, String skillName, String description, String category, String scope,
			String executionMode, Integer displayOrder, String skillMarkdown, Map<String, Object> routeRules,
			Map<String, Object> knowledgeConfig, Map<String, Object> reactConfig, Map<String, Object> flowDefinition,
			Map<String, Object> variablesSchema, List<SkillToolRefSaveDTO> toolRefs,
			Map<String, Object> flowRuntimeConfig, Map<String, Object> flowPolicyConfig) {
		this(skillCode, skillName, description, category, scope, executionMode, displayOrder, skillMarkdown, routeRules,
				knowledgeConfig, reactConfig, flowDefinition, variablesSchema, toolRefs, flowRuntimeConfig, flowPolicyConfig,
				null, null, null, null, null);
	}

	public SkillSaveReq(String skillCode, String skillName, String description, String category, String scope,
			String executionMode, Integer displayOrder, String skillMarkdown, Map<String, Object> routeRules,
			Map<String, Object> knowledgeConfig, Map<String, Object> reactConfig, Map<String, Object> flowDefinition,
			Map<String, Object> variablesSchema) {
		this(skillCode, skillName, description, category, scope, executionMode, displayOrder, skillMarkdown, routeRules,
				knowledgeConfig, reactConfig, flowDefinition, variablesSchema, null, null, null, null, null, null, null,
				null);
	}

	public SkillSaveReq(String skillCode, String skillName, String description, String category, String scope,
			String executionMode, Integer displayOrder, String skillMarkdown, Map<String, Object> routeRules,
			Map<String, Object> knowledgeConfig, Map<String, Object> reactConfig, Map<String, Object> flowDefinition,
			Map<String, Object> variablesSchema, List<SkillToolRefSaveDTO> toolRefs) {
		this(skillCode, skillName, description, category, scope, executionMode, displayOrder, skillMarkdown, routeRules,
				knowledgeConfig, reactConfig, flowDefinition, variablesSchema, toolRefs, null, null, null, null, null, null,
				null);
	}
}
