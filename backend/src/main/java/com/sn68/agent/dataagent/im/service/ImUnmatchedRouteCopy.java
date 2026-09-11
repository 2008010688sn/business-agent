/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * IM 文本通道在路由 NO_MATCH 时的开口引导：描述已绑定技能能办的事，不列出技能名让用户点选。
 */
@Component
@RequiredArgsConstructor
public class ImUnmatchedRouteCopy {

	static final String ASK_TASK = "请直接说要办的事，例如客户、商品、时间或期望结果。";

	static final String ASK_UNCLEAR = "当前问题还不够明确。" + ASK_TASK;

	static final String NO_BINDING = "当前对话还没有可办理的业务，请联系管理员完成技能绑定后再说明要办的事。";

	private static final String HEADER = "我可以协助办理：";

	private static final int MAX_HINTS = 8;

	private static final int MAX_HINT_LENGTH = 80;

	private static final Pattern CODE_LIKE = Pattern.compile("[A-Za-z][A-Za-z0-9_./-]{0,63}");

	private final DataAgentSkillBindingMapper bindingMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	public String build(AgentRequest request) {
		if (request == null || !request.supportsTextCommands()) {
			return null;
		}
		List<CapabilityHint> hints = loadHints(request);
		if (hints == null) {
			return ASK_UNCLEAR;
		}
		if (hints.isEmpty() && request.getPinnedSkillVersionIds() == null) {
			return NO_BINDING;
		}
		List<String> descriptions = uniqueDescriptions(hints);
		if (descriptions.isEmpty()) {
			return ASK_UNCLEAR;
		}
		StringBuilder text = new StringBuilder(HEADER);
		for (String description : descriptions) {
			text.append("\n- ").append(description);
		}
		return text.append("\n\n").append(ASK_TASK).toString();
	}

	private List<CapabilityHint> loadHints(AgentRequest request) {
		String tenantId = request.getTenantIdSnapshot();
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		if (request.getPinnedSkillVersionIds() != null) {
			return hintsFromPinnedVersions(request.getPinnedSkillVersionIds(), tenantId);
		}
		Long agentId = parseAgentId(request.getAgentId());
		if (agentId == null) {
			return null;
		}
		List<DataAgentSkillBinding> bindings = bindingMapper.findEnabledByAgentId(agentId, tenantId);
		if (bindings == null || bindings.isEmpty()) {
			return List.of();
		}
		Map<Long, DataAgentSkill> skills = skillsById(distinctIds(bindings, DataAgentSkillBinding::getSkillId));
		Map<Long, DataAgentSkillVersion> versions = versionsById(
				distinctIds(bindings, DataAgentSkillBinding::getPinnedSkillVersionId));
		List<CapabilityHint> hints = new ArrayList<>();
		for (DataAgentSkillBinding binding : bindings) {
			hints.add(new CapabilityHint(skills.get(binding.getSkillId()),
					versions.get(binding.getPinnedSkillVersionId())));
		}
		return hints;
	}

	private List<CapabilityHint> hintsFromPinnedVersions(List<Long> pinnedIds, String tenantId) {
		List<Long> versionIds = pinnedIds.stream().filter(Objects::nonNull).distinct().toList();
		if (versionIds.isEmpty()) {
			return List.of();
		}
		Map<Long, DataAgentSkillVersion> versions = versionsById(versionIds);
		List<DataAgentSkillVersion> ordered = new ArrayList<>();
		for (Long versionId : versionIds) {
			DataAgentSkillVersion version = versions.get(versionId);
			if (version == null) {
				continue;
			}
			if (StringUtils.hasText(version.getTenantId()) && !tenantId.equals(version.getTenantId())) {
				continue;
			}
			ordered.add(version);
		}
		Map<Long, DataAgentSkill> skills = skillsById(
				ordered.stream().map(DataAgentSkillVersion::getSkillId).filter(Objects::nonNull).distinct().toList());
		List<CapabilityHint> hints = new ArrayList<>();
		for (DataAgentSkillVersion version : ordered) {
			hints.add(new CapabilityHint(skills.get(version.getSkillId()), version));
		}
		return hints;
	}

	private List<String> uniqueDescriptions(List<CapabilityHint> hints) {
		Set<String> unique = new LinkedHashSet<>();
		for (CapabilityHint hint : hints) {
			String description = capabilityText(hint);
			if (StringUtils.hasText(description)) {
				unique.add(description);
			}
			if (unique.size() >= MAX_HINTS) {
				break;
			}
		}
		return List.copyOf(unique);
	}

	private String capabilityText(CapabilityHint hint) {
		DataAgentSkill skill = hint.skill();
		DataAgentSkillVersion version = hint.version();
		String skillName = version != null && StringUtils.hasText(version.getSkillName()) ? version.getSkillName()
				: skill == null ? null : skill.getSkillName();
		String skillCode = skill == null ? null : skill.getSkillCode();
		String versionDescription = version == null ? null : version.getDescription();
		if (usableCapabilityText(versionDescription, skillName, skillCode)) {
			return shorten(versionDescription);
		}
		String skillDescription = skill == null ? null : skill.getDescription();
		if (usableCapabilityText(skillDescription, skillName, skillCode)) {
			return shorten(skillDescription);
		}
		return null;
	}

	private boolean usableCapabilityText(String text, String skillName, String skillCode) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.length() < 4) {
			return false;
		}
		if (trimmed.equalsIgnoreCase(defaultText(skillName)) || trimmed.equalsIgnoreCase(defaultText(skillCode))) {
			return false;
		}
		return !CODE_LIKE.matcher(trimmed).matches();
	}

	private String shorten(String text) {
		String firstLine = text.trim().split("\\R", 2)[0].trim();
		if (firstLine.length() <= MAX_HINT_LENGTH) {
			return firstLine;
		}
		return firstLine.substring(0, MAX_HINT_LENGTH) + "…";
	}

	private Map<Long, DataAgentSkill> skillsById(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		return skillMapper.selectBatchIds(ids).stream()
			.collect(Collectors.toMap(DataAgentSkill::getId, Function.identity(), (left, right) -> left));
	}

	private Map<Long, DataAgentSkillVersion> versionsById(List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		return versionMapper.selectBatchIds(ids).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity(), (left, right) -> left));
	}

	private List<Long> distinctIds(List<DataAgentSkillBinding> bindings,
			Function<DataAgentSkillBinding, Long> extractor) {
		return bindings.stream().map(extractor).filter(Objects::nonNull).distinct().toList();
	}

	private Long parseAgentId(String agentId) {
		if (!StringUtils.hasText(agentId)) {
			return null;
		}
		try {
			return Long.valueOf(agentId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String defaultText(String value) {
		return value == null ? "" : value;
	}

	private record CapabilityHint(DataAgentSkill skill, DataAgentSkillVersion version) {
	}

}
