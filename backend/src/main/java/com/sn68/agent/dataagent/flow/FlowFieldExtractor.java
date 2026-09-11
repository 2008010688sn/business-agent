/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import java.util.Map;

/**
 * Structured extraction port. Extraction cannot choose nodes or invoke tools.
 */
public interface FlowFieldExtractor {

	Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction);

	default Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction, Map<String, Object> runtimeConfig) {
		return extract(request, modelConfig, schema, instruction);
	}

	/**
	 * Extracts a sparse patch with the already collected, user-editable values as
	 * context. Implementations must not expose internal identifiers from this map
	 * to a model request.
	 */
	default Map<String, Object> extract(AgentRequest request, ModelConfigDTO modelConfig, Map<String, Object> schema,
			String instruction, Map<String, Object> runtimeConfig, Map<String, Object> currentValues) {
		return extract(request, modelConfig, schema, instruction, runtimeConfig);
	}

}
