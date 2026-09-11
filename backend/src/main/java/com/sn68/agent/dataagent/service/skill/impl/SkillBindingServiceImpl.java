/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingOptionDTO;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingV2DTO;
import com.sn68.agent.dataagent.dto.skill.AgentSkillPublishedVersionOptionDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.skill.SkillBindingService;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Agent Skill pinned binding implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillBindingServiceImpl implements SkillBindingService {

	private final DataAgentSkillBindingMapper bindingMapper;

	private final com.sn68.agent.dataagent.repository.DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DataAgentService dataAgentService;

	private final AuthenticationContext authenticationContext;

	private final RouteArtifactService routeArtifactService;

	private final TransactionTemplate transactionTemplate;

	@Override
	public List<AgentSkillBindingV2DTO> list(Long agentId) {
		ensureAgent(agentId);
		return listEnabledAndDisabled(agentId).stream().map(this::toDTO).toList();
	}

	@Override
	public AgentSkillBindingEditorContextResp editorContext(Long agentId) {
		ensureAgent(agentId);
		String tenantId = requireTenantId();
		List<DataAgentSkillBinding> bindings = listEnabledAndDisabled(agentId);
		List<DataAgentSkill> visibleSkills = skillMapper.findVisible(tenantId).stream()
			.filter(skill -> isTenantPublishedSkill(skill, tenantId))
			.toList();
		Map<Long, List<DataAgentSkillVersion>> versionsBySkillId = publishedVersionsBySkillId(visibleSkills);
		List<AgentSkillBindingOptionDTO> skills = visibleSkills.stream()
			.map(skill -> toOption(skill, versionsBySkillId.getOrDefault(skill.getId(), List.of())))
			.toList();
		Set<String> availableBindings = skills.stream()
			.flatMap(skill -> skill.publishedVersions().stream()
				.map(version -> skill.skillId() + ":" + version.id()))
			.collect(Collectors.toSet());
		List<String> issues = bindings.stream()
			.filter(binding -> !availableBindings
				.contains(binding.getSkillId() + ":" + binding.getPinnedSkillVersionId()))
			.map(binding -> "已绑定Skill版本不可用, 请重新选择已发布版本: skillId=" + binding.getSkillId()
					+ ", skillVersionId=" + binding.getPinnedSkillVersionId())
			.toList();
		return new AgentSkillBindingEditorContextResp(bindings.stream().map(this::toDTO).toList(), skills, issues);
	}

	private AgentSkillBindingV2DTO toDTO(DataAgentSkillBinding binding) {
		return new AgentSkillBindingV2DTO(binding.getSkillId(), binding.getPinnedSkillVersionId(), binding.getPriority(),
				binding.getEnabled());
	}

	/**
	 * 一次取回全部可见 Skill 的已发布版本，替代按 Skill 逐个 {@code findBySkillId}。
	 *
	 * <p>{@code findPublishedBySkillIds} 已按 versionNo 倒序返回，分组后每个 Skill 内的版本顺序与逐个查询时一致。
	 */
	private Map<Long, List<DataAgentSkillVersion>> publishedVersionsBySkillId(List<DataAgentSkill> skills) {
		List<Long> skillIds = skills.stream().map(DataAgentSkill::getId).filter(Objects::nonNull).distinct().toList();
		if (skillIds.isEmpty()) {
			return Map.of();
		}
		return skillVersionMapper.findPublishedBySkillIds(skillIds).stream()
			.filter(version -> !Boolean.TRUE.equals(version.getDeleted()))
			.collect(Collectors.groupingBy(DataAgentSkillVersion::getSkillId, LinkedHashMap::new,
					Collectors.toList()));
	}

	private AgentSkillBindingOptionDTO toOption(DataAgentSkill skill, List<DataAgentSkillVersion> versions) {
		List<AgentSkillPublishedVersionOptionDTO> publishedVersions = versions.stream()
			.map(version -> new AgentSkillPublishedVersionOptionDTO(version.getId(), version.getVersionNo(),
					version.getSkillName(), version.getDescription(), version.getSkillKind(), version.getExecutionMode()))
			.toList();
		String publishedMode = publishedVersions.stream()
			.filter(version -> Objects.equals(version.id(), skill.getPublishedVersionId()))
			.map(AgentSkillPublishedVersionOptionDTO::executionMode)
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse(skill.getExecutionMode());
		return new AgentSkillBindingOptionDTO(skill.getId(), skill.getSkillCode(), skill.getSkillName(),
				skill.getDescription(), publishedMode, skill.getStatus(), skill.getDisplayOrder(),
				skill.getPublishedVersionId(), !publishedVersions.isEmpty(),
				publishedVersions.isEmpty() ? "No published version is available" : null, publishedVersions);
	}

	@Override
	public List<AgentSkillBindingV2DTO> replace(Long agentId, List<AgentSkillBindingV2DTO> bindings) {
		ensureAgent(agentId);
		String tenantId = requireTenantId();
		Map<Long, AgentSkillBindingV2DTO> normalized = normalize(bindings);
		Map<Long, DataAgentSkill> skills = skillsByIds(normalized.keySet());
		Map<Long, DataAgentSkillVersion> versions = versionsByIds(normalized.values().stream()
			.map(AgentSkillBindingV2DTO::pinnedSkillVersionId).toList());
		List<DataAgentSkillBinding> replacement = new ArrayList<>();
		for (AgentSkillBindingV2DTO item : normalized.values()) {
			DataAgentSkill skill = skills.get(item.skillId());
			if (!isTenantPublishedSkill(skill, tenantId)) {
				throw CheckedException.badRequest("只能绑定当前租户已发布的Skill");
			}
			Long versionId = item.pinnedSkillVersionId();
			if (versionId == null) {
				throw CheckedException.badRequest("绑定的Skill版本无效");
			}
			DataAgentSkillVersion version = versions.get(versionId);
			if (!isPinnablePublishedVersion(skill, version)) {
				throw CheckedException.badRequest("Pinned Skill version must belong to the selected published Skill");
			}
			routeArtifactService.prepareSkillVersion(skill, version);
			replacement.add(DataAgentSkillBinding.builder().tenantId(tenantId).agentId(agentId).skillId(skill.getId())
				.pinnedSkillVersionId(versionId).priority(item.priority() == null ? 0 : item.priority())
				.enabled(!Boolean.FALSE.equals(item.enabled())).build());
		}
		transactionTemplate.executeWithoutResult(status -> persistReplacement(agentId, tenantId, replacement));
		routeArtifactService.refreshCollaboratorsForTargetAgent(agentId);
		return list(agentId);
	}

	/**
	 * 事务内重新读一次 Skill 与版本再落库。
	 *
	 * <p>{@code prepareSkillVersion} 是事务外的耗时构建，期间 Skill 可能被改版或下线，所以这里刻意重新查库复核，
	 * 不能复用事务外那份快照 —— 复用就等于把并发窗口的检查完全去掉。
	 */
	private void persistReplacement(Long agentId, String tenantId, List<DataAgentSkillBinding> replacement) {
		Map<Long, DataAgentSkill> skills = skillsByIds(replacement.stream().map(DataAgentSkillBinding::getSkillId)
			.toList());
		Map<Long, DataAgentSkillVersion> versions = versionsByIds(replacement.stream()
			.map(DataAgentSkillBinding::getPinnedSkillVersionId).toList());
		for (DataAgentSkillBinding binding : replacement) {
			DataAgentSkill skill = skills.get(binding.getSkillId());
			DataAgentSkillVersion version = versions.get(binding.getPinnedSkillVersionId());
			if (!isTenantPublishedSkill(skill, tenantId) || !isPinnablePublishedVersion(skill, version)) {
				throw CheckedException.badRequest("Skill binding changed while route Artifact was building");
			}
		}
		bindingMapper.deleteByAgentId(agentId);
		if (!replacement.isEmpty()) {
			bindingMapper.insertBatch(replacement);
		}
	}

	private boolean isPinnablePublishedVersion(DataAgentSkill skill, DataAgentSkillVersion version) {
		return version != null && !Boolean.TRUE.equals(version.getDeleted())
				&& skill.getId().equals(version.getSkillId()) && "PUBLISHED".equals(version.getStatus());
	}

	/**
	 * 空集合必须提前返回：{@code selectBatchIds} 拿到空集合会生成 {@code IN ()} 这种非法 SQL。
	 */
	private Map<Long, DataAgentSkill> skillsByIds(Collection<Long> skillIds) {
		List<Long> ids = distinctIds(skillIds);
		if (ids.isEmpty()) {
			return Map.of();
		}
		return skillMapper.selectBatchIds(ids).stream()
			.collect(Collectors.toMap(DataAgentSkill::getId, Function.identity(), (first, second) -> first));
	}

	private Map<Long, DataAgentSkillVersion> versionsByIds(Collection<Long> versionIds) {
		List<Long> ids = distinctIds(versionIds);
		if (ids.isEmpty()) {
			return Map.of();
		}
		return skillVersionMapper.selectBatchIds(ids).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity(), (first, second) -> first));
	}

	private List<Long> distinctIds(Collection<Long> ids) {
		return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
	}

	@Override
	public List<DataAgentSkillBinding> listEnabled(Long agentId, String tenantId) {
		if (agentId == null || !StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return bindingMapper.findEnabledByAgentId(agentId, tenantId);
	}

	private List<DataAgentSkillBinding> listEnabledAndDisabled(Long agentId) {
		String tenantId = requireTenantId();
		return bindingMapper.findByAgentId(agentId).stream()
			.filter(binding -> tenantId.equals(binding.getTenantId()))
			.toList();
	}

	private Map<Long, AgentSkillBindingV2DTO> normalize(List<AgentSkillBindingV2DTO> bindings) {
		Map<Long, AgentSkillBindingV2DTO> result = new LinkedHashMap<>();
		for (AgentSkillBindingV2DTO item : bindings == null ? List.<AgentSkillBindingV2DTO>of() : bindings) {
			if (item == null || item.skillId() == null) continue;
			if (result.putIfAbsent(item.skillId(), item) != null) {
				throw CheckedException.badRequest("同一Agent不能重复绑定相同Skill");
			}
		}
		return result;
	}

	private boolean isTenantPublishedSkill(DataAgentSkill skill, String tenantId) {
		return skill != null && "TENANT".equals(skill.getScope()) && tenantId.equals(skill.getTenantId())
				&& "PUBLISHED".equals(skill.getStatus()) && skill.getPublishedVersionId() != null
				&& !Boolean.TRUE.equals(skill.getDeleted());
	}

	private void ensureAgent(Long agentId) {
		DataAgent agent = agentId == null ? null : dataAgentService.findById(agentId);
		if (agent == null) {
			throw CheckedException.notFound("Data agent does not exist");
		}
		if (!requireTenantId().equals(agent.getTenantId())) {
			throw CheckedException.forbidden();
		}
	}

	private String requireTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			// The guard below still fails closed; without this the caller cannot tell absent from unresolvable.
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次技能绑定操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required");
		}
		return tenantId;
	}

}
