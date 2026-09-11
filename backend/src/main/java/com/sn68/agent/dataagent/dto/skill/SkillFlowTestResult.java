/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * FLOW 流程安全试跑（dry-run）结果，试跑过程不会创建流程实例、不会调用任何工具。
 */
@Schema(description = "Skill FLOW流程试跑结果")
public record SkillFlowTestResult(
		@Schema(description = "流程定义是否合法") boolean valid,
		@Schema(description = "起始节点ID") String startNode,
		@Schema(description = "节点预览列表") List<NodePreview> nodes,
		@Schema(description = "校验错误列表") List<String> errors) {

	/**
	 * 试跑中单个流程节点的静态预览信息。
	 */
	@Schema(description = "FLOW流程节点预览")
	public record NodePreview(
			@Schema(description = "节点ID") String nodeId,
			@Schema(description = "节点类型") String nodeType,
			@Schema(description = "节点行为描述") String behavior,
			@Schema(description = "后继节点ID") String next,
			@Schema(description = "分支节点ID列表") List<String> branches) {
	}

}
