/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Produces a non-secret identity for the route-model connection and its
 * configuration revision. A capability result is stale as soon as this value
 * changes.
 */
@Component
public class RouteModelFingerprint {

	public String calculate(ModelConfigDTO config) {
		if (config == null) {
			return null;
		}
		return SecureUtil.sha256(String.join("\n", text(config.getProvider()), text(config.getBaseUrl()),
				text(config.getCompletionsPath()), text(config.getModelName()), text(config.getEndpointDialect()),
				text(config.getCapabilityProfile()), text(config.getReasoningProtocol()), text(config.getReasoningMode()),
				text(config.getReasoningLevel()), text(config.getReasoningBudgetTokens()),
				text(config.getTokenLimitMode()), text(config.getTemperaturePolicy()),
				text(config.getStructuredOutputMode()), text(config.getPreservedReasoningPolicy()),
				text(config.getTemperature()), text(config.getMaxTokens()), text(config.getContextWindowTokens()),
				text(config.getProxyEnabled()), text(config.getProxyHost()), text(config.getProxyPort()),
				text(config.getProxyUsername()), text(config.getLastModifyTime())));
	}

	private String text(Object value) {
		return Objects.toString(value, "").trim();
	}

}
