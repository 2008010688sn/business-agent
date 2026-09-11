/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.dto.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Agent 协作者（子 Agent）保存请求。
 *
 * <p>routingRules 使用原始 Map 保留全部路由规则键，交由 {@code RouteRulesService} 做严格校验。
 */
@Schema(description = "Agent协作者保存请求")
public record AgentCollaboratorSaveReq(
		@Schema(description = "协作关系ID（新增时为空）") Long id,
		@Schema(description = "主Agent ID") Long agentId,
		@Schema(description = "协作者Agent ID") Long collaboratorAgentId,
		@Schema(description = "协作角色名称") String roleName,
		@Schema(description = "能力描述") String capabilityDescription,
		@Schema(description = "路由规则") Map<String, Object> routingRules,
		@Schema(description = "协作优先级") Integer priority,
		@Schema(description = "是否启用") Boolean enabled,
		@Schema(description = "委派模式") String delegationMode) {

	public AgentCollaboratorSaveReq(Long id, Long agentId, Long collaboratorAgentId, String roleName,
			String capabilityDescription, Map<String, Object> routingRules, Integer priority, Boolean enabled) {
		this(id, agentId, collaboratorAgentId, roleName, capabilityDescription, routingRules, priority, enabled, null);
	}
}
