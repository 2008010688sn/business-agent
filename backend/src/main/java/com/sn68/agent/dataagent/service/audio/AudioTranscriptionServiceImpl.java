/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.service.audio;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.InetSocketAddress;
import java.net.Socket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音转写服务实现：按模型配置选择转写客户端并执行音频转文本。
 */
@Service
@RequiredArgsConstructor
public class AudioTranscriptionServiceImpl implements AudioTranscriptionService {

	private static final long MAX_AUDIO_SIZE = 20L * 1024 * 1024;

	private static final int PROXY_CHECK_TIMEOUT_MS = 1500;

	private final ModelConfigDataService modelConfigDataService;

	private final StepFunAsrTranscriptionClient stepFunAsrTranscriptionClient;

	private final QwenAsrTranscriptionClient qwenAsrTranscriptionClient;

	private final OpenAiCompatibleAudioTranscriptionClient openAiCompatibleAudioTranscriptionClient;

	@Override
	public String transcribe(MultipartFile file) {
		return transcribe(file, null);
	}

	@Override
	public String transcribe(MultipartFile file, Long modelConfigId) {
		validateAudioFile(file);
		ModelConfigDTO modelConfig = resolveModelConfig(modelConfigId);
		validateProxyReachable(modelConfig);
		try {
			if (stepFunAsrTranscriptionClient.supports(modelConfig)) {
				return stepFunAsrTranscriptionClient.transcribe(modelConfig, file);
			}
			if (qwenAsrTranscriptionClient.supports(modelConfig)) {
				return qwenAsrTranscriptionClient.transcribe(modelConfig, file);
			}
			return openAiCompatibleAudioTranscriptionClient.transcribe(modelConfig, file);
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw normalizeTranscriptionException(ex);
		}
	}

	private ModelConfigDTO resolveModelConfig(Long modelConfigId) {
		if (modelConfigId != null) {
			return modelConfigDataService.getRuntimeConfigById(modelConfigId, ModelType.AUDIO_TRANSCRIPTION);
		}
		ModelConfigDTO modelConfig = modelConfigDataService
			.getActiveRuntimeConfigByType(ModelType.AUDIO_TRANSCRIPTION);
		if (modelConfig == null) {
			throw CheckedException.badRequest("未配置语音转写模型。");
		}
		return modelConfig;
	}

	private CheckedException normalizeTranscriptionException(Exception ex) {
		String message = ex.getMessage();
		if (message != null && message.matches("(?is).*(webm duration|EBML|ffprobe|count_token_failed).*")) {
			return CheckedException.badRequest("语音服务无法解析 WebM 录音时长，请刷新页面后重试，系统会改用 WAV 格式上传。");
		}
		if (message != null && message
			.matches("(?is).*(LLM Provider NOT provided|provider you are trying to call|upstream_error).*")) {
			return CheckedException.badRequest(
					"语音转写模型名称不被当前供应商识别，请在模型配置中填写供应商支持的语音转写模型，并按供应商要求使用 provider/model 前缀。");
		}
		return CheckedException.fail("语音转写失败：" + message);
	}

	private void validateAudioFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw CheckedException.badRequest("音频文件不能为空。");
		}
		String contentType = file.getContentType();
		if (!isSupportedAudioContentType(contentType)) {
			throw CheckedException.badRequest("仅支持 webm、wav、mpeg、mp4、ogg 音频。");
		}
		if (file.getSize() > MAX_AUDIO_SIZE) {
			throw CheckedException.badRequest("音频大小不能超过 20MB。");
		}
	}

	private boolean isSupportedAudioContentType(String contentType) {
		if (!StringUtils.hasText(contentType)) {
			return false;
		}
		String normalized = contentType.split(";", 2)[0].trim().toLowerCase();
		return "audio/webm".equals(normalized) || "video/webm".equals(normalized) || "audio/wav".equals(normalized)
				|| "audio/x-wav".equals(normalized) || "audio/mpeg".equals(normalized)
				|| "audio/mp3".equals(normalized) || "audio/mp4".equals(normalized) || "audio/ogg".equals(normalized);
	}

	private void validateProxyReachable(ModelConfigDTO modelConfig) {
		if (!Boolean.TRUE.equals(modelConfig.getProxyEnabled())) {
			return;
		}
		String proxyHost = modelConfig.getProxyHost();
		Integer proxyPort = modelConfig.getProxyPort();
		if (!StringUtils.hasText(proxyHost) || proxyPort == null || proxyPort <= 0 || proxyPort > 65535) {
			throw CheckedException.badRequest("语音转写模型代理配置不完整，请检查代理地址和端口。");
		}
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(proxyHost, proxyPort), PROXY_CHECK_TIMEOUT_MS);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("语音转写模型代理不可用，请启动代理服务或关闭模型代理配置："
					+ proxyHost + ":" + proxyPort);
		}
	}

}
