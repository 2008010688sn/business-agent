/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.vo;

import com.sn68.agent.dataagent.dto.agent.RuntimeChatModelDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 工作台元数据：可用 Agent、当前 Agent 与可用对话模型列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent工作台元数据")
public class AgentRunWorkbenchMetaVO {

	@Schema(description = "可用Agent列表")
	private List<DataAgent> availableAgents;

	@Schema(description = "当前Agent信息")
	private DataAgent currentAgent;

	@Schema(description = "可用对话模型列表")
	private List<RuntimeChatModelDTO> chatModels;

}
