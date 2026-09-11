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
package com.sn68.agent.dataagent.service.tts.impl;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.enums.VoiceSourceType;
import com.sn68.agent.dataagent.repository.ModelTtsConfigMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceProfileMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceSampleMapper;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.tts.TextToSpeechService;
import com.sn68.agent.dataagent.service.tts.client.TextToSpeechClient;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 文本转语音服务实现：按模型配置路由到对应 TTS 客户端并返回合成音频。
 */
@Service
@RequiredArgsConstructor
public class TextToSpeechServiceImpl implements TextToSpeechService {

	private final ModelConfigDataService modelConfigDataService;

	private final ModelTtsConfigMapper ttsConfigMapper;

	private final TtsVoiceProfileMapper voiceProfileMapper;

	private final TtsVoiceSampleMapper voiceSampleMapper;

	private final List<TextToSpeechClient> clients;

	@Override
	public AudioSpeechResult synthesize(AudioSpeechReq request) {
		if (request == null || !StringUtils.hasText(request.getText())) {
			throw CheckedException.badRequest("播报文本不能为空。");
		}
		ModelConfigDTO modelConfig = resolveModelConfig(request.getModelConfigId());
		ModelTtsConfig ttsConfig = ttsConfigMapper.findByModelConfigId(modelConfig.getId());
		if (ttsConfig == null) {
			throw CheckedException.badRequest("未配置 TTS 能力参数。");
		}
		TtsVoiceProfile voiceProfile = resolveVoiceProfile(ttsConfig, request.getVoiceProfileId());
		TtsVoiceSample voiceSample = resolveVoiceSample(voiceProfile);
		return clients.stream()
			.filter(client -> client.supports(modelConfig, ttsConfig, voiceProfile))
			.findFirst()
			.orElseThrow(() -> CheckedException.badRequest("没有可用的 TTS 客户端。"))
			.synthesize(modelConfig, ttsConfig, voiceProfile, voiceSample, request);
	}

	private ModelConfigDTO resolveModelConfig(Long modelConfigId) {
		if (modelConfigId != null) {
			return modelConfigDataService.getRuntimeConfigById(modelConfigId, ModelType.TEXT_TO_SPEECH);
		}
		ModelConfigDTO active = modelConfigDataService.getActiveRuntimeConfigByType(ModelType.TEXT_TO_SPEECH);
		if (active == null) {
			throw CheckedException.badRequest("未配置文本转语音模型。");
		}
		return active;
	}

	private TtsVoiceProfile resolveVoiceProfile(ModelTtsConfig ttsConfig, Long voiceProfileId) {
		TtsVoiceProfile profile = voiceProfileId == null ? voiceProfileMapper.findDefaultByTtsConfigId(ttsConfig.getId())
				: voiceProfileMapper.findById(voiceProfileId);
		if (profile == null || !ttsConfig.getId().equals(profile.getTtsConfigId())
				|| Boolean.FALSE.equals(profile.getEnabled())) {
			throw CheckedException.badRequest("未配置可用的 TTS 音色 Profile。");
		}
		return profile;
	}

	private TtsVoiceSample resolveVoiceSample(TtsVoiceProfile profile) {
		if (!VoiceSourceType.LOCAL_REFERENCE.getCode().equalsIgnoreCase(profile.getVoiceSource())) {
			return null;
		}
		if (profile.getSampleId() == null) {
			throw CheckedException.badRequest("本地参考音色缺少样本。");
		}
		TtsVoiceSample sample = voiceSampleMapper.findById(profile.getSampleId());
		if (sample == null) {
			throw CheckedException.badRequest("本地参考音色样本不存在。");
		}
		if (!Boolean.TRUE.equals(sample.getConsentConfirmed())) {
			throw CheckedException.badRequest("本地参考音色样本未确认授权。");
		}
		if (!StringUtils.hasText(sample.getTranscript())) {
			throw CheckedException.badRequest("本地参考音色样本缺少转写文本。");
		}
		return sample;
	}

}
