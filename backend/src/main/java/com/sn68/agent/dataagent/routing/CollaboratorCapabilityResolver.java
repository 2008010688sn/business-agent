/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the effective risk of the published capabilities bound to a collaborator Agent.
 */
@Component
public class CollaboratorCapabilityResolver {

	private final DataAgentSkillBindingMapper bindingMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final RouteRiskResolver riskResolver;

	public CollaboratorCapabilityResolver(DataAgentSkillBindingMapper bindingMapper, DataAgentSkillMapper skillMapper,
			DataAgentSkillVersionMapper versionMapper, RouteRiskResolver riskResolver) {
		this.bindingMapper = bindingMapper;
		this.skillMapper = skillMapper;
		this.versionMapper = versionMapper;
		this.riskResolver = riskResolver;
	}

	public CollaboratorCapability resolve(Long collaboratorAgentId, String tenantId) {
		if (collaboratorAgentId == null || !StringUtils.hasText(tenantId)) {
			return CollaboratorCapability.unknown();
		}
		List<DataAgentSkillBinding> bindings = bindingMapper.findEnabledByAgentId(collaboratorAgentId, tenantId);
		if (bindings.isEmpty()) {
			return CollaboratorCapability.unknown();
		}
		Map<Long, DataAgentSkill> skills = skillMapper.selectBatchIds(bindings.stream()
			.map(DataAgentSkillBinding::getSkillId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkill::getId, Function.identity()));
		Map<Long, DataAgentSkillVersion> versions = versionMapper.selectBatchIds(bindings.stream()
			.map(DataAgentSkillBinding::getPinnedSkillVersionId).filter(Objects::nonNull).distinct().toList()).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity()));
		List<DataAgentSkillVersion> publishedVersions = bindings.stream().map(binding -> {
			DataAgentSkill skill = skills.get(binding.getSkillId());
			DataAgentSkillVersion version = versions.get(binding.getPinnedSkillVersionId());
			return isPublishedBinding(binding, skill, version, tenantId) ? version : null;
		}).toList();
		if (publishedVersions.stream().anyMatch(Objects::isNull)) {
			return CollaboratorCapability.unknown();
		}
		List<RouteRisk> risks = publishedVersions.stream().map(riskResolver::resolveSkill).toList();
		if (risks.stream().anyMatch(risk -> risk == null || risk == RouteRisk.UNKNOWN || risk == RouteRisk.DELEGATED)) {
			return CollaboratorCapability.unknown();
		}
		RouteRisk effectiveRisk = effectiveRisk(risks);
		DataAgentSkillVersion onlyVersion = publishedVersions.size() == 1 ? publishedVersions.get(0) : null;
		boolean planConfirmEligible = effectiveRisk == RouteRisk.WRITE && onlyVersion != null
				&& !SkillExecutionMode.FLOW.name().equals(onlyVersion.getExecutionMode());
		return new CollaboratorCapability(effectiveRisk, publishedVersions.size(), planConfirmEligible);
	}

	public boolean supports(DelegationMode mode, CollaboratorCapability capability) {
		DelegationMode effectiveMode = mode == null ? DelegationMode.INTERACTIVE : mode;
		CollaboratorCapability effectiveCapability = capability == null ? CollaboratorCapability.unknown() : capability;
		return switch (effectiveMode) {
			case AUTO_READ_ONLY -> effectiveCapability.effectiveRisk() == RouteRisk.READ_ONLY;
			case PLAN_CONFIRM -> effectiveCapability.planConfirmEligible();
			case INTERACTIVE -> effectiveCapability.effectiveRisk() != RouteRisk.UNKNOWN
					&& effectiveCapability.effectiveRisk() != RouteRisk.DELEGATED;
		};
	}

	public void requireSupported(DelegationMode mode, CollaboratorCapability capability) {
		if (supports(mode, capability)) {
			return;
		}
		DelegationMode effectiveMode = mode == null ? DelegationMode.INTERACTIVE : mode;
		throw new IllegalArgumentException(switch (effectiveMode) {
			case AUTO_READ_ONLY -> "AUTO_READ_ONLY requires every enabled published collaborator capability to be read-only";
			case PLAN_CONFIRM -> "PLAN_CONFIRM requires exactly one enabled published non-FLOW write capability";
			case INTERACTIVE -> "Collaborator must have at least one valid enabled published capability";
		});
	}

	private RouteRisk effectiveRisk(List<RouteRisk> risks) {
		if (risks.stream().anyMatch(risk -> risk == RouteRisk.FLOW)) {
			return RouteRisk.FLOW;
		}
		if (risks.stream().anyMatch(risk -> risk == RouteRisk.WRITE)) {
			return RouteRisk.WRITE;
		}
		return risks.stream().allMatch(risk -> risk == RouteRisk.READ_ONLY) ? RouteRisk.READ_ONLY : RouteRisk.UNKNOWN;
	}

	private boolean isPublishedBinding(DataAgentSkillBinding binding, DataAgentSkill skill,
			DataAgentSkillVersion version, String tenantId) {
		return binding != null && skill != null && version != null && !Boolean.TRUE.equals(binding.getDeleted())
				&& !Boolean.TRUE.equals(skill.getDeleted()) && !Boolean.TRUE.equals(version.getDeleted())
				&& Objects.equals(binding.getTenantId(), tenantId) && Objects.equals(skill.getTenantId(), tenantId)
				&& Objects.equals(version.getTenantId(), tenantId) && "TENANT".equals(skill.getScope())
				&& "PUBLISHED".equals(skill.getStatus()) && "PUBLISHED".equals(version.getStatus())
				&& Objects.equals(binding.getSkillId(), skill.getId())
				&& Objects.equals(binding.getPinnedSkillVersionId(), version.getId())
				&& Objects.equals(skill.getId(), version.getSkillId());
	}

	public record CollaboratorCapability(RouteRisk effectiveRisk, int publishedCapabilityCount,
			boolean planConfirmEligible) {

		public static CollaboratorCapability unknown() {
			return new CollaboratorCapability(RouteRisk.UNKNOWN, 0, false);
		}
	}

}
