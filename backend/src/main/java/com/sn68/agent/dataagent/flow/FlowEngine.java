/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;

/**
 * Generic deterministic FLOW engine.
 */
public interface FlowEngine {

	FlowExecutionResult execute(AgentRequest request, DataAgentSkill skill, DataAgentSkillVersion version,
			ModelConfigDTO modelConfig);

	FlowExecutionResult cancel(AgentRequest request);

}
