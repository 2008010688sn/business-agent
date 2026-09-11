/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tts.client.aliyun;

import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.AudioResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationOutput;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtime;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeAudioFormat;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeCallback;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeConfig;
import com.alibaba.dashscope.audio.qwen_tts_realtime.QwenTtsRealtimeParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Credentials;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * DashScope TTS 网关实现：封装各 API 家族的 SDK 调用与音频下载。
 */
@Component
class DashScopeAliyunTtsGateway implements AliyunDashScopeTtsGateway {

	private static final String DEFAULT_DASHSCOPE_WEBSOCKET_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";

	@Override
	public byte[] synthesizeSambert(AliyunTtsRequest request) {
		SpeechSynthesisParam param = SpeechSynthesisParam.builder()
			.apiKey(request.modelConfig().getApiKey())
			.workspace(request.workspace())
			.model(request.modelName())
			.text(request.text())
			.format(sambertFormat(request.format()))
			.sampleRate(request.sampleRate())
			.rate(floatValue(request.speechSpeed(), 1.0f))
			.pitch(floatValue(request.pitch(), 1.0f))
			.volume(volume(request))
			.parameters(filteredParameters(request.mergedOptions()))
			.build();
		ByteBuffer audio = new com.alibaba.dashscope.audio.tts.SpeechSynthesizer().call(param);
		return bytes(audio);
	}

	@Override
	public byte[] synthesizeTtsV2(AliyunTtsRequest request) {
		com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam param = com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam
			.builder()
			.apiKey(request.modelConfig().getApiKey())
			.workspace(request.workspace())
			.model(request.modelName())
			.voice(request.voiceName())
			.format(ttsV2Format(request.format(), request.sampleRate()))
			.speechRate(floatValue(request.speechSpeed(), 1.0f))
			.pitchRate(floatValue(request.pitch(), 1.0f))
			.volume(volume(request))
			.connectionTimeout(request.connectTimeoutMs())
			.firstPackageTimeout(request.firstAudioTimeoutMs())
			.parameters(filteredParameters(request.mergedOptions()))
			.build();
		com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer synthesizer = new com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer(
				param, null, endpoint(request), connectionOptions(request));
		ByteBuffer audio = synthesizer.call(request.text(), request.readTimeoutMs());
		return bytes(audio);
	}

	@Override
	public QwenTtsAudioResult synthesizeQwenTts(AliyunTtsRequest request) {
		MultiModalConversationParam param = MultiModalConversationParam.builder()
			.apiKey(request.modelConfig().getApiKey())
			.workspace(request.workspace())
			.model(request.modelName())
			.text(request.text())
			.voice(qwenVoice(request.voiceName()))
			.parameters(filteredParameters(request.mergedOptions()))
			.build();
		try {
			MultiModalConversationResult result = new MultiModalConversation("http", httpEndpoint(request),
					connectionOptions(request))
				.call(param);
			AudioResult audio = extractAudio(result);
			if (audio == null) {
				throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 响应缺少音频。");
			}
			if (StringUtils.hasText(audio.getData())) {
				return new QwenTtsAudioResult(decodeAudio(audio.getData()), audio.getUrl(), Map.of());
			}
			if (StringUtils.hasText(audio.getUrl())) {
				return new QwenTtsAudioResult(downloadAudio(audio.getUrl(), request), audio.getUrl(), Map.of());
			}
			throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 音频为空。");
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 调用异常：" + ex.getMessage());
		}
	}

	@Override
	public byte[] synthesizeQwenRealtime(AliyunTtsRequest request) {
		ByteArrayOutputStream audio = new ByteArrayOutputStream();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<RuntimeException> error = new AtomicReference<>();
		QwenTtsRealtimeParam param = QwenTtsRealtimeParam.builder()
			.apikey(request.modelConfig().getApiKey())
			.workspace(request.workspace())
			.model(request.modelName())
			.url(endpoint(request))
			.build();
		QwenTtsRealtime realtime = new QwenTtsRealtime(param, new QwenTtsRealtimeCallback() {
			@Override
			public void onEvent(JsonObject message) {
				try {
					appendRealtimeAudio(audio, message);
					if (isRealtimeDone(message)) {
						done.countDown();
					}
				}
				catch (RuntimeException ex) {
					error.set(ex);
					done.countDown();
				}
			}

			@Override
			public void onClose(int code, String reason) {
				done.countDown();
			}
		});
		try {
			realtime.connect();
			realtime.updateSession(QwenTtsRealtimeConfig.builder()
				.voice(request.voiceName())
				.responseFormat(QwenTtsRealtimeAudioFormat.PCM_24000HZ_MONO_16BIT)
				.format(request.format())
				.sampleRate(request.sampleRate())
				.speechRate(floatValue(request.speechSpeed(), 1.0f))
				.pitchRate(floatValue(request.pitch(), 1.0f))
				.volume(volume(request))
				.parameters(filteredParameters(request.mergedOptions()))
				.build());
			realtime.appendText(request.text());
			realtime.commit();
			if (!done.await(request.readTimeoutMs(), TimeUnit.MILLISECONDS)) {
				throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 实时合成超时。");
			}
			if (error.get() != null) {
				throw error.get();
			}
			byte[] result = audio.toByteArray();
			if (result.length == 0) {
				throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 实时合成未返回音频。");
			}
			return result;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 实时合成被中断。");
		}
		catch (Exception ex) {
			throw CheckedException.fail("语音合成失败：阿里 Qwen-TTS 实时调用异常：" + ex.getMessage());
		}
		finally {
			try {
				realtime.close();
			}
			catch (Exception ignored) {
				// SDK 已关闭或连接未建立时忽略清理异常。
			}
		}
	}

	private AudioResult extractAudio(MultiModalConversationResult result) {
		MultiModalConversationOutput output = result == null ? null : result.getOutput();
		return output == null ? null : output.getAudio();
	}

	private byte[] downloadAudio(String url, AliyunTtsRequest request) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(request.connectTimeoutMs());
		requestFactory.setReadTimeout(request.readTimeoutMs());
		ResponseEntity<byte[]> response = RestClient.builder()
			.requestFactory(requestFactory)
			.build()
			.get()
			.uri(URI.create(url))
			.retrieve()
			.toEntity(byte[].class);
		byte[] body = response.getBody();
		if (body == null || body.length == 0) {
			throw CheckedException.fail("语音合成失败：下载阿里 Qwen-TTS 音频为空。");
		}
		return body;
	}

	private ConnectionOptions connectionOptions(AliyunTtsRequest request) {
		ConnectionOptions.ConnectionOptionsBuilder<?, ?> builder = ConnectionOptions.builder()
			.connectTimeout(Duration.ofMillis(request.connectTimeoutMs()))
			.writeTimeout(Duration.ofMillis(request.readTimeoutMs()))
			.readTimeout(Duration.ofMillis(request.readTimeoutMs()));
		if (Boolean.TRUE.equals(request.modelConfig().getProxyEnabled()) && StringUtils.hasText(request.modelConfig().getProxyHost())
				&& request.modelConfig().getProxyPort() != null) {
			builder.proxyHost(request.modelConfig().getProxyHost()).proxyPort(request.modelConfig().getProxyPort());
			if (StringUtils.hasText(request.modelConfig().getProxyUsername())) {
				builder.proxyAuthenticator((route, response) -> response.request()
					.newBuilder()
					.header("Proxy-Authorization",
							Credentials.basic(request.modelConfig().getProxyUsername(),
									request.modelConfig().getProxyPassword()))
					.build());
			}
		}
		return builder.build();
	}

	private String endpoint(AliyunTtsRequest request) {
		if (StringUtils.hasText(request.endpoint())) {
			return request.endpoint();
		}
		return DEFAULT_DASHSCOPE_WEBSOCKET_URL;
	}

	private String httpEndpoint(AliyunTtsRequest request) {
		Object value = request.mergedOptions().get("httpEndpoint");
		if (StringUtils.hasText(value == null ? null : String.valueOf(value))) {
			return String.valueOf(value).trim();
		}
		value = request.mergedOptions().get("baseHttpUrl");
		return StringUtils.hasText(value == null ? null : String.valueOf(value)) ? String.valueOf(value).trim() : null;
	}

	private SpeechSynthesisAudioFormat sambertFormat(String format) {
		String normalized = normalize(format);
		return switch (normalized) {
			case "pcm" -> SpeechSynthesisAudioFormat.PCM;
			case "mp3" -> SpeechSynthesisAudioFormat.MP3;
			default -> SpeechSynthesisAudioFormat.WAV;
		};
	}

	private com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat ttsV2Format(String format, Integer sampleRate) {
		String normalized = normalize(format);
		int rate = sampleRate == null ? 24000 : sampleRate;
		for (com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat item : com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat
			.values()) {
			if (item.getSampleRate() == rate && item.getFormat().equalsIgnoreCase(normalized)) {
				return item;
			}
		}
		return com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat.MP3_24000HZ_MONO_256KBPS;
	}

	private AudioParameters.Voice qwenVoice(String voiceName) {
		String normalized = normalize(voiceName);
		for (AudioParameters.Voice voice : AudioParameters.Voice.values()) {
			if (voice.getValue().equalsIgnoreCase(normalized) || voice.name().equalsIgnoreCase(normalized)) {
				return voice;
			}
		}
		return AudioParameters.Voice.CHERRY;
	}

	private int volume(AliyunTtsRequest request) {
		BigDecimal gain = request.volumeGain();
		if (gain == null) {
			return 50;
		}
		int value = 50 + gain.intValue();
		return Math.max(0, Math.min(100, value));
	}

	private float floatValue(BigDecimal value, float fallback) {
		return value == null ? fallback : value.floatValue();
	}

	private Map<String, Object> filteredParameters(Map<String, Object> options) {
		Map<String, Object> result = new LinkedHashMap<>(options == null ? Map.of() : options);
		result.keySet()
			.removeIf(key -> key == null || key.equals("vendor") || key.equals("apiFamily")
					|| key.equals("connectTimeoutMs") || key.equals("readTimeoutMs") || key.equals("timeoutMs")
					|| key.equals("firstAudioTimeoutMs") || key.equals("audioFormat") || key.equals("workspace")
					|| key.equals("endpoint") || key.equals("websocketUrl") || key.equals("baseWebsocketUrl"));
		return result;
	}

	private byte[] bytes(ByteBuffer buffer) {
		if (buffer == null) {
			return new byte[0];
		}
		ByteBuffer copy = buffer.asReadOnlyBuffer();
		copy.rewind();
		byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}

	private byte[] decodeAudio(String value) {
		String data = value;
		int comma = data.indexOf(',');
		if (data.startsWith("data:") && comma >= 0) {
			data = data.substring(comma + 1);
		}
		return Base64.getDecoder().decode(data);
	}

	private void appendRealtimeAudio(ByteArrayOutputStream audio, JsonObject message) {
		String data = findAudioData(message);
		if (StringUtils.hasText(data)) {
			byte[] bytes = decodeAudio(data);
			audio.write(bytes, 0, bytes.length);
		}
	}

	private String findAudioData(JsonObject message) {
		for (String key : new String[] { "delta", "audio", "data" }) {
			if (!message.has(key)) {
				continue;
			}
			JsonElement element = message.get(key);
			if (element.isJsonPrimitive()) {
				return element.getAsString();
			}
			if (element.isJsonObject()) {
				String nested = findNestedAudioData(element.getAsJsonObject());
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}

	/**
	 * 在嵌套对象内按 audio/data/delta 顺序查找首个音频字符串，未命中返回 {@code null}。
	 */
	private String findNestedAudioData(JsonObject object) {
		for (String nested : new String[] { "audio", "data", "delta" }) {
			if (object.has(nested) && object.get(nested).isJsonPrimitive()) {
				return object.get(nested).getAsString();
			}
		}
		return null;
	}

	private boolean isRealtimeDone(JsonObject message) {
		if (!message.has("type")) {
			return false;
		}
		String type = message.get("type").getAsString();
		return type.endsWith(".done") || type.endsWith(".completed") || type.contains("response.done");
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

}
