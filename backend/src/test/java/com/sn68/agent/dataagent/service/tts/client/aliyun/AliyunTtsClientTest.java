/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.service.tts.impl.TtsJsonSupport;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AliyunTtsClientTest {

	private final CapturingGateway gateway = new CapturingGateway();

	private final AliyunTtsClient client = new AliyunTtsClient(new TtsJsonSupport(new ObjectMapper()),
			List.of(new SambertAliyunTtsEngine(gateway), new CosyVoiceAliyunTtsEngine(gateway),
					new QwenAliyunTtsEngine(gateway), new QwenRealtimeAliyunTtsEngine(gateway)));

	@Test
	void synthesize_routesSambertByExplicitApiFamily() {
		AudioSpeechResult result = client.synthesize(model("sambert-zhiqian-v1"), ttsConfig("sambert"),
				voice("sambert-zhiqian-v1", "mp3", 48000), null, request());

		assertEquals("sambert", gateway.calls.get(0));
		assertEquals("audio/mpeg", result.contentType());
		assertEquals("mp3", result.format());
		assertEquals("sambert-zhiqian-v1", gateway.last.voiceName());
		assertEquals(48000, gateway.last.sampleRate());
		assertEquals(BigDecimal.valueOf(1.20), gateway.last.speechSpeed());
	}

	@Test
	void synthesize_routesCosyVoiceByApiFamily() {
		AudioSpeechResult result = client.synthesize(model("cosyvoice-v2"), ttsConfig("cosyvoice"),
				voice("longxiaochun", "wav", 24000), null, request());

		assertEquals("cosyvoice", gateway.calls.get(0));
		assertEquals("audio/wav", result.contentType());
		assertEquals("wav", result.format());
	}

	@Test
	void synthesize_routesQwenTtsByApiFamily() {
		AudioSpeechResult result = client.synthesize(model("qwen-tts-latest"), ttsConfig("qwen-tts"),
				voice("Cherry", "mp3", 24000), null, request());

		assertEquals("qwen-tts", gateway.calls.get(0));
		assertEquals("audio/mpeg", result.contentType());
		assertEquals("qwen-audio", new String(result.audio(), StandardCharsets.UTF_8));
	}

	@Test
	void synthesize_routesQwenRealtimeByApiFamily() {
		AudioSpeechResult result = client.synthesize(model("qwen-tts-realtime"), ttsConfig("qwen-tts-realtime"),
				voice("Cherry", "pcm", 24000), null, request());

		assertEquals("qwen-tts-realtime", gateway.calls.get(0));
		assertEquals("audio/pcm", result.contentType());
		assertEquals("pcm", result.format());
	}

	@Test
	void supports_infersAliyunFamilyFromModelName() {
		assertTrue(client.supports(model("sambert-zhiqian-v1"), ttsConfig(null), voice("sambert-zhiqian-v1", "mp3", 48000)));
		assertTrue(client.supports(model("qwen-tts-latest"), ttsConfig(null), voice("Cherry", "mp3", 24000)));
		assertFalse(client.supports(model("tts-1"), ttsConfig(null), voice("alloy", "mp3", 24000)));
	}

	private ModelConfigDTO model(String modelName) {
		return ModelConfigDTO.builder()
			.provider("qwen")
			.apiKey("sk-test")
			.baseUrl("https://dashscope.aliyuncs.com")
			.modelName(modelName)
			.modelType("TEXT_TO_SPEECH")
			.proxyEnabled(false)
			.build();
	}

	private ModelTtsConfig ttsConfig(String apiFamily) {
		ModelTtsConfig config = new ModelTtsConfig();
		config.setId(1L);
		config.setModelConfigId(1L);
		config.setDefaultFormat("mp3");
		config.setDefaultSampleRate(24000);
		if (apiFamily == null) {
			config.setOptions("{\"vendor\":\"aliyun\"}");
		}
		else {
			config.setOptions("{\"vendor\":\"aliyun\",\"apiFamily\":\"" + apiFamily
					+ "\",\"connectTimeoutMs\":1234,\"readTimeoutMs\":5678,\"firstAudioTimeoutMs\":3456}");
		}
		return config;
	}

	private TtsVoiceProfile voice(String voiceName, String format, Integer sampleRate) {
		TtsVoiceProfile profile = new TtsVoiceProfile();
		profile.setId(1L);
		profile.setTtsConfigId(1L);
		profile.setVoiceName(voiceName);
		profile.setSpeechFormat(format);
		profile.setSampleRate(sampleRate);
		profile.setSpeechSpeed(BigDecimal.valueOf(1.20));
		profile.setPitch(BigDecimal.ONE);
		profile.setVolumeGain(BigDecimal.ZERO);
		profile.setEnabled(true);
		profile.setOptions("{}");
		return profile;
	}

	private AudioSpeechReq request() {
		return AudioSpeechReq.builder().text("你好，请介绍一下服务。").build();
	}

	private static class CapturingGateway implements AliyunDashScopeTtsGateway {

		private final List<String> calls = new ArrayList<>();

		private AliyunTtsRequest last;

		@Override
		public byte[] synthesizeSambert(AliyunTtsRequest request) {
			return capture("sambert", request);
		}

		@Override
		public byte[] synthesizeTtsV2(AliyunTtsRequest request) {
			return capture("cosyvoice", request);
		}

		@Override
		public QwenTtsAudioResult synthesizeQwenTts(AliyunTtsRequest request) {
			calls.add("qwen-tts");
			last = request;
			return new QwenTtsAudioResult("qwen-audio".getBytes(StandardCharsets.UTF_8), null, null);
		}

		@Override
		public byte[] synthesizeQwenRealtime(AliyunTtsRequest request) {
			return capture("qwen-tts-realtime", request);
		}

		private byte[] capture(String family, AliyunTtsRequest request) {
			calls.add(family);
			last = request;
			return (family + "-audio").getBytes(StandardCharsets.UTF_8);
		}

	}

}
