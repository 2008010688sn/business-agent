/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves resource IDs frozen by published Skill versions.
 *
 * <p>Editable resource rows use this service before a destructive change so a published
 * version cannot observe changed or removed content.</p>
 */
@Service
@RequiredArgsConstructor
public class PublishedSkillResourceReferenceService {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final ObjectMapper objectMapper;

	public boolean isBusinessKnowledgeReferenced(Long skillId, Long knowledgeId) {
		return referencedBusinessKnowledgeIds(skillId).contains(knowledgeId);
	}

	public boolean isSemanticModelReferenced(Long skillId, Long semanticModelId) {
		return referencedSemanticModelIds(skillId).contains(semanticModelId);
	}

	public boolean isSkillKnowledgeReferenced(Long skillId, Long knowledgeId) {
		return referencedSkillKnowledgeIds(skillId).contains(knowledgeId);
	}

	public Set<Long> referencedBusinessKnowledgeIds(Long skillId) {
		return referencedIds(skillId, DataAgentSkillVersion::getKnowledgeConfig, "businessKnowledgeIds");
	}

	public Set<Long> referencedSemanticModelIds(Long skillId) {
		return referencedIds(skillId, DataAgentSkillVersion::getSemanticConfig, "semanticModelIds");
	}

	public Set<Long> referencedSkillKnowledgeIds(Long skillId) {
		return referencedIds(skillId, DataAgentSkillVersion::getKnowledgeConfig, "skillKnowledgeIds");
	}

	private Set<Long> referencedIds(Long skillId,
			java.util.function.Function<DataAgentSkillVersion, String> configExtractor, String idsKey) {
		if (skillId == null) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (DataAgentSkillVersion version : skillVersionMapper.findBySkillId(skillId)) {
			if (!"PUBLISHED".equals(version.getStatus()) || Boolean.TRUE.equals(version.getDeleted())) {
				continue;
			}
			ids.addAll(readIds(configExtractor.apply(version), idsKey));
		}
		return Set.copyOf(ids);
	}

	private List<Long> readIds(String json, String idsKey) {
		if (!StringUtils.hasText(json)) {
			return List.of();
		}
		try {
			Map<String, Object> config = objectMapper.readValue(json, MAP_TYPE);
			Object rawIds = config == null ? null : config.get(idsKey);
			if (!(rawIds instanceof List<?> values)) {
				return List.of();
			}
			return values.stream().map(this::longValue).filter(java.util.Objects::nonNull).toList();
		}
		catch (Exception ex) {
			throw CheckedException.fail("Published Skill resource snapshot is invalid");
		}
	}

	private Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
