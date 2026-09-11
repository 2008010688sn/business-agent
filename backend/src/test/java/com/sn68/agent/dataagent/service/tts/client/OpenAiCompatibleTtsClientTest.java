/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenAiCompatibleTtsClientTest {

	private final OpenAiCompatibleTtsClient client = new OpenAiCompatibleTtsClient(mock(DynamicModelFactory.class),
			new TtsJsonSupport(new ObjectMapper()));

	@Test
	void supports_rejectsAliyunTtsFamilies() {
		assertFalse(client.supports(model("qwen"), ttsConfig("{\"vendor\":\"aliyun\",\"apiFamily\":\"sambert\"}"),
				new TtsVoiceProfile()));
		assertFalse(client.supports(model("qwen"), ttsConfig("{\"vendor\":\"aliyun\",\"apiFamily\":\"qwen-tts\"}"),
				new TtsVoiceProfile()));
	}

	@Test
	void supports_acceptsExplicitOpenAiCompatibleConfig() {
		assertTrue(client.supports(model("custom"), ttsConfig("{\"apiFamily\":\"openai-compatible\"}"),
				new TtsVoiceProfile()));
		assertTrue(client.supports(model("openai"), ttsConfig("{}"), new TtsVoiceProfile()));
	}

	private ModelConfigDTO model(String provider) {
		return ModelConfigDTO.builder()
			.provider(provider)
			.baseUrl("https://api.example.com")
			.apiKey("sk-test")
			.modelName("tts-1")
			.modelType("TEXT_TO_SPEECH")
			.build();
	}

	private ModelTtsConfig ttsConfig(String options) {
		ModelTtsConfig config = new ModelTtsConfig();
		config.setOptions(options);
		return config;
	}

}
