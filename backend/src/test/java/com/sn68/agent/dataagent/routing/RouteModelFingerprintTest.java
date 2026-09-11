/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RouteModelFingerprintTest {

	private final RouteModelFingerprint fingerprint = new RouteModelFingerprint();

	@Test
	void requestCapabilityChangesInvalidateTheProbeFingerprint() {
		ModelConfigDTO base = config();
		ModelConfigDTO changed = config();
		changed.setCapabilityProfile("DEEPSEEK_THINKING");

		assertNotEquals(fingerprint.calculate(base), fingerprint.calculate(changed));

		changed = config();
		changed.setReasoningMode("DISABLED");
		assertNotEquals(fingerprint.calculate(base), fingerprint.calculate(changed));

		changed = config();
		changed.setMaxTokens(4096L);
		assertNotEquals(fingerprint.calculate(base), fingerprint.calculate(changed));
	}

	@Test
	void identicalRequestConfigurationHasAStableFingerprint() {
		assertEquals(fingerprint.calculate(config()), fingerprint.calculate(config()));
	}

	private ModelConfigDTO config() {
		return ModelConfigDTO.builder()
			.provider("deepseek")
			.baseUrl("https://api.deepseek.com")
			.completionsPath("/chat/completions")
			.modelName("deepseek-reasoner")
			.endpointDialect("DEEPSEEK_NATIVE")
			.capabilityProfile("AUTO")
			.reasoningProtocol("AUTO")
			.reasoningMode("AUTO")
			.temperaturePolicy("OMIT")
			.structuredOutputMode("AUTO")
			.preservedReasoningPolicy("DROP")
			.maxTokens(2000L)
			.contextWindowTokens(32768L)
			.lastModifyTime(Instant.parse("2026-08-03T00:00:00Z"))
			.build();
	}

}
