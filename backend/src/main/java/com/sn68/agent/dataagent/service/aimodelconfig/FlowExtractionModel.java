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
 * FLOW field extraction model together with the concrete response protocol.
 */
public record FlowExtractionModel(ChatModel model, ModelStructuredOutputMode outputMode) {

	public FlowExtractionModel {
		Objects.requireNonNull(model, "model must not be null");
		Objects.requireNonNull(outputMode, "outputMode must not be null");
		if (outputMode != ModelStructuredOutputMode.STRICT_JSON_SCHEMA
				&& outputMode != ModelStructuredOutputMode.JSON_OBJECT
				&& outputMode != ModelStructuredOutputMode.PROMPT_JSON) {
			throw new IllegalArgumentException("FLOW extraction requires a JSON response protocol");
		}
	}

}
