/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

/**
 * Skill 草稿或已发布版本的只读运行时预览。
 */
@Schema(description = "Skill运行时预览")
public record SkillPreviewResp(
		@Schema(description = "Skill编码") String skillCode,
		@Schema(description = "Skill名称") String skillName,
		@Schema(description = "执行模式") String executionMode,
		@Schema(description = "版本ID") Long versionId,
		@Schema(description = "版本号") Integer versionNo,
		@Schema(description = "版本状态") String status,
		@Schema(description = "Skill说明文档（Markdown）") String skillMarkdown,
		@Schema(description = "路由规则") RouteRulesDTO routeRules,
		@Schema(description = "Skill清单信息") Map<String, Object> manifest,
		@Schema(description = "FLOW流程定义") Map<String, Object> flowDefinition,
		@Schema(description = "FLOW运行时配置") Map<String, Object> flowRuntimeConfig,
		@Schema(description = "FLOW策略配置") Map<String, Object> flowPolicyConfig,
		@Schema(description = "工具预览列表") List<ToolPreview> tools,
		@Schema(description = "流程节点预览列表") List<FlowNodePreview> nodes,
		@Schema(description = "模型参与情况列表") List<ModelParticipation> modelParticipation,
		@Schema(description = "错误信息列表") List<String> errors,
		@Schema(description = "警告信息列表") List<String> warnings,
		@Schema(description = "资源汇总信息") ResourceSummary resourceSummary,
		@Schema(description = "生效的ReAct预算配置") Map<String, Object> effectiveReactBudget,
		@Schema(description = "生效的确定性执行策略") Map<String, Object> effectiveDeterministicPolicy) {

	/**
	 * 预览中引用的工具版本信息。
	 */
	@Schema(description = "Skill工具预览")
	public record ToolPreview(
			@Schema(description = "工具版本ID") Long resourceVersionId,
			@Schema(description = "工具资源Key") String resourceKey,
			@Schema(description = "版本号") Integer versionNo,
			@Schema(description = "访问模式") String accessMode,
			@Schema(description = "暴露模式") String exposureMode,
			@Schema(description = "权限编码") String permissionCode,
			@Schema(description = "是否写操作") Boolean write,
			@Schema(description = "是否需要人工确认") Boolean confirmRequired,
			@Schema(description = "是否要求幂等") Boolean idempotencyRequired) {
	}

	/**
	 * 预览中单个 FLOW 节点的静态信息。
	 */
	@Schema(description = "FLOW节点预览")
	public record FlowNodePreview(
			@Schema(description = "节点ID") String nodeId,
			@Schema(description = "节点类型") String nodeType,
			@Schema(description = "节点绑定的工具版本ID") Long resourceVersionId,
			@Schema(description = "后继节点ID") String next,
			@Schema(description = "分支节点ID列表") List<String> branches,
			@Schema(description = "模型是否参与该节点") boolean modelParticipates,
			@Schema(description = "节点是否产生写操作") boolean writes,
			@Schema(description = "是否需要人工确认") boolean requiresConfirmation,
			@Schema(description = "失败时跳转的节点ID") String failureNext) {
	}

	/**
	 * 单个节点的模型参与情况说明。
	 */
	@Schema(description = "模型参与情况")
	public record ModelParticipation(
			@Schema(description = "节点ID") String nodeId,
			@Schema(description = "模型是否参与") boolean participates,
			@Schema(description = "参与/不参与原因") String reason) {
	}

	/**
	 * 版本引用资源的数量汇总。
	 */
	@Schema(description = "Skill资源汇总")
	public record ResourceSummary(
			@Schema(description = "数据源数量") int datasourceCount,
			@Schema(description = "语义模型数量") int semanticModelCount,
			@Schema(description = "业务知识数量") int businessKnowledgeCount,
			@Schema(description = "Skill知识数量") int skillKnowledgeCount,
			@Schema(description = "模型参与阶段列表") List<String> modelStages,
			@Schema(description = "资源快照状态") String snapshotStatus) {
	}
}
