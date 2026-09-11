/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.sn68.agent.dataagent.flow.FlowStructuredOutputProtocol;
import java.util.Objects;
import org.springframework.ai.chat.model.ChatModel;

/**
 * FLOW-only model and the previously verified output contract used to construct it.
 */
public record FlowStructuredModel(ChatModel model, FlowStructuredOutputProtocol protocol) {

	public FlowStructuredModel {
		Objects.requireNonNull(model, "model must not be null");
		Objects.requireNonNull(protocol, "protocol must not be null");
		if (protocol == FlowStructuredOutputProtocol.NONE) {
			throw new IllegalArgumentException("FLOW structured model requires an output protocol");
		}
	}

}
