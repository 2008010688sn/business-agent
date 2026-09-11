/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Deterministic planner model together with its effective output protocol.
 */
public record DeterministicPlannerModel(ChatModel model, ModelStructuredOutputMode outputMode) {

	public DeterministicPlannerModel {
		Objects.requireNonNull(model, "model must not be null");
		Objects.requireNonNull(outputMode, "outputMode must not be null");
	}

	public boolean requiresPromptJson() {
		return outputMode == ModelStructuredOutputMode.PROMPT_JSON;
	}

}
