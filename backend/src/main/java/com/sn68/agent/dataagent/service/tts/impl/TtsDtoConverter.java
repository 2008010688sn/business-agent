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

import com.sn68.agent.dataagent.dto.tts.ModelTtsConfigDTO;
import com.sn68.agent.dataagent.dto.tts.TtsVoiceProfileDTO;
import com.sn68.agent.dataagent.dto.tts.TtsVoiceSampleResp;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TTS 实体与 DTO 转换器：集中处理 TTS 配置、音色与样本的双向映射。
 */
@Component
@RequiredArgsConstructor
public class TtsDtoConverter {

	private final TtsJsonSupport jsonSupport;

	public ModelTtsConfigDTO toDTO(ModelTtsConfig entity) {
		if (entity == null) {
			return null;
		}
		return ModelTtsConfigDTO.builder()
			.id(entity.getId())
			.modelConfigId(entity.getModelConfigId())
			.speechPath(entity.getSpeechPath())
			.speechStreamPath(entity.getSpeechStreamPath())
			.ttsProtocol(entity.getTtsProtocol())
			.streamingEnabled(entity.getStreamingEnabled())
			.defaultFormat(entity.getDefaultFormat())
			.defaultSampleRate(entity.getDefaultSampleRate())
			.options(jsonSupport.readObject(entity.getOptions()))
			.build();
	}

	public ModelTtsConfig toEntity(ModelTtsConfigDTO dto, Long modelConfigId) {
		ModelTtsConfig entity = new ModelTtsConfig();
		entity.setId(dto == null ? null : dto.getId());
		entity.setModelConfigId(modelConfigId);
		entity.setSpeechPath(dto == null ? null : dto.getSpeechPath());
		entity.setSpeechStreamPath(dto == null ? null : dto.getSpeechStreamPath());
		entity.setTtsProtocol(firstText(dto == null ? null : dto.getTtsProtocol(), "http"));
		entity.setStreamingEnabled(Boolean.TRUE.equals(dto != null ? dto.getStreamingEnabled() : null));
		entity.setDefaultFormat(firstText(dto == null ? null : dto.getDefaultFormat(), "mp3"));
		entity.setDefaultSampleRate(dto == null || dto.getDefaultSampleRate() == null ? 24000
				: dto.getDefaultSampleRate());
		entity.setOptions(jsonSupport.writeObject(dto == null ? null : dto.getOptions()));
		entity.setDeleted(false);
		return entity;
	}

	public TtsVoiceProfileDTO toDTO(TtsVoiceProfile entity, TtsVoiceSample sample) {
		if (entity == null) {
			return null;
		}
		return TtsVoiceProfileDTO.builder()
			.id(entity.getId())
			.ttsConfigId(entity.getTtsConfigId())
			.profileName(entity.getProfileName())
			.voiceName(entity.getVoiceName())
			.voiceLabel(entity.getVoiceLabel())
			.voiceSource(entity.getVoiceSource())
			.sampleId(entity.getSampleId())
			.languageCode(entity.getLanguageCode())
			.gender(entity.getGender())
			.speechSpeed(entity.getSpeechSpeed())
			.pitch(entity.getPitch())
			.volumeGain(entity.getVolumeGain())
			.speechFormat(entity.getSpeechFormat())
			.sampleRate(entity.getSampleRate())
			.options(jsonSupport.readObject(entity.getOptions()))
			.isDefault(entity.getIsDefault())
			.enabled(entity.getEnabled())
			.sample(toDTO(sample))
			.build();
	}

	public TtsVoiceProfile toEntity(TtsVoiceProfileDTO dto, Long ttsConfigId) {
		TtsVoiceProfile entity = new TtsVoiceProfile();
		entity.setId(dto == null ? null : dto.getId());
		entity.setTtsConfigId(ttsConfigId);
		entity.setProfileName(firstText(dto == null ? null : dto.getProfileName(), "默认音色"));
		entity.setVoiceName(dto == null ? null : dto.getVoiceName());
		entity.setVoiceLabel(dto == null ? null : dto.getVoiceLabel());
		entity.setVoiceSource(firstText(dto == null ? null : dto.getVoiceSource(), "SYSTEM"));
		entity.setSampleId(dto == null ? null : dto.getSampleId());
		entity.setLanguageCode(dto == null ? null : dto.getLanguageCode());
		entity.setGender(dto == null ? null : dto.getGender());
		entity.setSpeechSpeed(dto == null || dto.getSpeechSpeed() == null ? BigDecimal.ONE : dto.getSpeechSpeed());
		entity.setPitch(dto == null || dto.getPitch() == null ? BigDecimal.ZERO : dto.getPitch());
		entity.setVolumeGain(dto == null || dto.getVolumeGain() == null ? BigDecimal.ZERO : dto.getVolumeGain());
		entity.setSpeechFormat(dto == null ? null : dto.getSpeechFormat());
		entity.setSampleRate(dto == null ? null : dto.getSampleRate());
		entity.setOptions(jsonSupport.writeObject(dto == null ? null : dto.getOptions()));
		entity.setIsDefault(Boolean.TRUE.equals(dto == null ? null : dto.getIsDefault()));
		entity.setEnabled(!Boolean.FALSE.equals(dto == null ? null : dto.getEnabled()));
		entity.setDeleted(false);
		return entity;
	}

	public TtsVoiceSampleResp toDTO(TtsVoiceSample entity) {
		if (entity == null) {
			return null;
		}
		return TtsVoiceSampleResp.builder()
			.id(entity.getId())
			.fileId(entity.getFileId())
			.fileName(entity.getFileName())
			.filePath(entity.getFilePath())
			.contentType(entity.getContentType())
			.durationMs(entity.getDurationMs())
			.sampleRate(entity.getSampleRate())
			.transcript(entity.getTranscript())
			.consentConfirmed(entity.getConsentConfirmed())
			.status(entity.getStatus())
			.errorMessage(entity.getErrorMessage())
			.createTime(entity.getCreateTime())
			.build();
	}

	private String firstText(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

}
