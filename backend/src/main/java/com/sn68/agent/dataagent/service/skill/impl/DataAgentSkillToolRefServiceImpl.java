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
package com.sn68.agent.dataagent.service.skill.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.skill.AgentSkillToolRefDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.skill.DataAgentSkillToolRefService;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Manages immutable Skill-version to tool-version references.
 */
@Service
@RequiredArgsConstructor
public class DataAgentSkillToolRefServiceImpl implements DataAgentSkillToolRefService {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentSkillToolRefMapper mapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final SkillCatalogService skillCatalogService;

	private final ObjectMapper objectMapper;

	@Override
	public List<AgentSkillToolRefDTO> listRefs(String skillId) {
		DataAgentSkill skill = requireSkill(skillId);
		Long versionId = skill.getLatestDraftVersionId() == null ? skill.getPublishedVersionId()
				: skill.getLatestDraftVersionId();
		return toDTOs(skill.getSkillCode(), versionId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<AgentSkillToolRefDTO> saveRefs(String skillId, List<AgentSkillToolRefDTO> refs) {
		DataAgentSkill skill = requireSkill(skillId);
		DataAgentSkillVersion draft = requireDraft(skill);
		List<AgentSkillToolRefDTO> requested = refs == null ? List.of() : refs.stream().filter(Objects::nonNull).toList();
		Set<Long> requestedIds = new HashSet<>();
		for (AgentSkillToolRefDTO ref : requested) {
			if (ref.resourceVersionId() == null) {
				throw CheckedException.badRequest("Tool version reference is required");
			}
			requestedIds.add(ref.resourceVersionId());
		}
		Map<Long, AgentExecutionResourceVersion> versions = requestedIds.isEmpty() ? Map.of()
				: resourceVersionMapper.selectList(com.sn68.agent.framework.db.mybatisplus.wrap.Wraps
					.<AgentExecutionResourceVersion>lbQ()
					.in(AgentExecutionResourceVersion::getId, requestedIds)
					.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED")
					.eq(AgentExecutionResourceVersion::getDeleted, false)).stream()
					.collect(Collectors.toMap(AgentExecutionResourceVersion::getId, Function.identity()));
		Map<Long, DataAgentSkillToolRef> existing = mapper.findBySkillVersionId(draft.getId()).stream()
			.filter(item -> item.getResourceVersionId() != null)
			.collect(Collectors.toMap(DataAgentSkillToolRef::getResourceVersionId, Function.identity(),
				(left, right) -> left));
		Set<Long> resourceVersionIds = new HashSet<>();
		List<DataAgentSkillToolRef> replacement = new ArrayList<>();
		SkillExecutionMode mode = SkillExecutionMode.valueOf(draft.getExecutionMode());
		if (!requested.isEmpty() && mode != SkillExecutionMode.REACT && mode != SkillExecutionMode.FLOW) {
			throw CheckedException.badRequest("Only REACT and FLOW Skills can bind tool versions");
		}
		int generatedOrder = 0;
		for (AgentSkillToolRefDTO ref : requested) {
			AgentExecutionResourceVersion resource = versions.get(ref.resourceVersionId());
			if (resource == null) {
				throw CheckedException.notFound("Published tool version does not exist: " + ref.resourceVersionId());
			}
			validateScope(skill, resource);
			validateMode(mode, resource);
			if (!resourceVersionIds.add(resource.getId())) {
				throw CheckedException.badRequest("Duplicate tool version reference: " + resource.getResourceKey());
			}
			DataAgentSkillToolRef old = existing.get(resource.getId());
			DataAgentSkillToolRef entity = DataAgentSkillToolRef.builder()
				.tenantId(skill.getTenantId())
				.skillVersionId(draft.getId())
				.resourceVersionId(resource.getId())
				.resourceKey(resource.getResourceKey())
				.usage(trim(ref.usage()))
				.status(firstText(old == null ? null : old.getStatus(), "enabled"))
				.displayOrder(ref.displayOrder() == null ? generatedOrder += 10 : ref.displayOrder())
				.extConfig(old == null ? null : old.getExtConfig())
				.build();
			replacement.add(entity);
		}
		mapper.deleteBySkillVersionId(draft.getId());
		replacement.forEach(mapper::insert);
		return toDTOs(skill.getSkillCode(), draft.getId());
	}

	private List<AgentSkillToolRefDTO> toDTOs(String skillCode, Long versionId) {
		if (versionId == null) {
			return List.of();
		}
		return mapper.findBySkillVersionId(versionId).stream().map(entity -> toDTO(skillCode, entity)).toList();
	}

	private DataAgentSkill requireSkill(String skillCode) {
		if (!StringUtils.hasText(skillCode)) {
			throw CheckedException.badRequest("Skill code is required");
		}
		return skillCatalogService.requireManageable(skillCode.trim());
	}

	private DataAgentSkillVersion requireDraft(DataAgentSkill skill) {
		DataAgentSkillVersion draft = skill.getLatestDraftVersionId() == null ? null
				: skillVersionMapper.selectById(skill.getLatestDraftVersionId());
		if (draft == null || !"DRAFT".equals(draft.getStatus()) || Boolean.TRUE.equals(draft.getDeleted())) {
			throw CheckedException.badRequest("Modify the Skill to create a draft before changing tool references");
		}
		return draft;
	}

	private void validateScope(DataAgentSkill skill, AgentExecutionResourceVersion resource) {
		if (skill.getTenantId() == null && resource.getTenantId() != null) {
			throw CheckedException.badRequest("A platform Skill cannot bind a tenant tool version");
		}
		if (skill.getTenantId() != null && resource.getTenantId() != null
				&& !Objects.equals(skill.getTenantId(), resource.getTenantId())) {
			throw CheckedException.badRequest("A Skill cannot bind another tenant's tool version");
		}
	}

	private void validateMode(SkillExecutionMode mode, AgentExecutionResourceVersion resource) {
		if ("WRITE".equals(resource.getAccessMode()) && "MODEL".equals(resource.getExposureMode())) {
			throw CheckedException.badRequest("WRITE + MODEL tool configuration is forbidden");
		}
		if (mode == SkillExecutionMode.REACT
				&& !("READ".equals(resource.getAccessMode()) && "MODEL".equals(resource.getExposureMode()))) {
			throw CheckedException.badRequest("REACT Skill can bind only READ + MODEL tools");
		}
	}

	private AgentSkillToolRefDTO toDTO(String skillCode, DataAgentSkillToolRef entity) {
		return new AgentSkillToolRefDTO(entity.getId(), skillCode, entity.getSkillVersionId(), entity.getResourceKey(),
				entity.getResourceVersionId(), entity.getUsage(), entity.getStatus(), entity.getDisplayOrder(),
				readJsonObject(entity.getExtConfig()));
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : map;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid persisted Skill tool reference config", ex);
		}
	}

	private String firstText(String... values) {
		for (String value : values == null ? new String[0] : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
