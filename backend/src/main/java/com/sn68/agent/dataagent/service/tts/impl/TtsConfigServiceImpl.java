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

import com.sn68.agent.dataagent.dto.tts.TtsConfigAggregateDTO;
import com.sn68.agent.dataagent.dto.tts.TtsVoiceProfileDTO;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.dataagent.repository.ModelTtsConfigMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceProfileMapper;
import com.sn68.agent.dataagent.repository.TtsVoiceSampleMapper;
import com.sn68.agent.dataagent.service.tts.TtsConfigService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TTS 能力配置管理实现：维护模型 TTS 配置的保存、查询与默认值。
 */
@Service
@RequiredArgsConstructor
public class TtsConfigServiceImpl implements TtsConfigService {

	private final ModelTtsConfigMapper ttsConfigMapper;

	private final TtsVoiceProfileMapper voiceProfileMapper;

	private final TtsVoiceSampleMapper voiceSampleMapper;

	private final TtsDtoConverter converter;

	@Override
	public TtsConfigAggregateDTO getByModelConfigId(Long modelConfigId) {
		ModelTtsConfig config = ttsConfigMapper.findByModelConfigId(modelConfigId);
		if (config == null) {
			return TtsConfigAggregateDTO.builder().build();
		}
		return TtsConfigAggregateDTO.builder()
			.config(converter.toDTO(config))
			.voiceProfiles(toVoiceProfileDTOs(config.getId()))
			.build();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public TtsConfigAggregateDTO save(Long modelConfigId, TtsConfigAggregateDTO request) {
		if (modelConfigId == null) {
			throw CheckedException.badRequest("模型配置 ID 不能为空。");
		}
		ModelTtsConfig existing = ttsConfigMapper.findByModelConfigId(modelConfigId);
		ModelTtsConfig entity = converter.toEntity(request == null ? null : request.getConfig(), modelConfigId);
		Instant now = Instant.now();
		if (existing == null) {
			entity.setCreateTime(now);
			entity.setLastModifyTime(now);
			ttsConfigMapper.insert(entity);
		}
		else {
			entity.setId(existing.getId());
			entity.setCreateTime(existing.getCreateTime());
			entity.setLastModifyTime(now);
			ttsConfigMapper.updateById(entity);
		}
		saveVoiceProfiles(entity.getId(), request == null ? List.of() : request.getVoiceProfiles());
		return getByModelConfigId(modelConfigId);
	}

	private void saveVoiceProfiles(Long ttsConfigId, List<TtsVoiceProfileDTO> profiles) {
		if (profiles == null) {
			return;
		}
		// 一次取回本配置下已有音色，替代按 profile 逐个 findById；
		// 只查当前 ttsConfigId，「不在这份清单里」正好等价于原来的「不存在或不属于当前配置」
		Map<Long, TtsVoiceProfile> existingProfiles = voiceProfileMapper.findByTtsConfigId(ttsConfigId)
			.stream()
			.filter(profile -> profile != null && profile.getId() != null)
			.collect(Collectors.toMap(TtsVoiceProfile::getId, profile -> profile, (first, second) -> first,
					LinkedHashMap::new));
		boolean hasDefault = profiles.stream().anyMatch(item -> Boolean.TRUE.equals(item.getIsDefault()));
		for (int index = 0; index < profiles.size(); index++) {
			TtsVoiceProfile entity = converter.toEntity(profiles.get(index), ttsConfigId);
			if (!hasDefault && index == 0) {
				entity.setIsDefault(true);
			}
			Instant now = Instant.now();
			if (entity.getId() == null) {
				entity.setCreateTime(now);
				entity.setLastModifyTime(now);
				voiceProfileMapper.insert(entity);
			}
			else {
				TtsVoiceProfile existing = existingProfiles.get(entity.getId());
				if (existing == null) {
					throw CheckedException.badRequest("音色 Profile 不存在或不属于当前 TTS 配置。");
				}
				entity.setCreateTime(existing.getCreateTime());
				entity.setLastModifyTime(now);
				voiceProfileMapper.updateById(entity);
			}
			if (Boolean.TRUE.equals(entity.getIsDefault())) {
				voiceProfileMapper.clearDefault(ttsConfigId, entity.getId());
			}
		}
	}

	private List<TtsVoiceProfileDTO> toVoiceProfileDTOs(Long ttsConfigId) {
		List<TtsVoiceProfile> profiles = voiceProfileMapper.findByTtsConfigId(ttsConfigId);
		List<Long> sampleIds = profiles.stream()
			.map(TtsVoiceProfile::getSampleId)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		// 空集合直接给空 Map，selectBatchIds 拿到空集合会生成 IN () 这种非法 SQL
		Map<Long, TtsVoiceSample> samples = sampleIds.isEmpty() ? Map.of()
				: voiceSampleMapper.selectBatchIds(sampleIds).stream()
					.collect(Collectors.toMap(TtsVoiceSample::getId, sample -> sample, (first, second) -> first));
		return profiles.stream()
			.map(profile -> converter.toDTO(profile,
					profile.getSampleId() == null ? null : samples.get(profile.getSampleId())))
			.toList();
	}

}
