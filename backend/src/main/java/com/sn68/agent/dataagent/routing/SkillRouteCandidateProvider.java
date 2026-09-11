/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.DataAgentRouteArtifact;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.flow.definition.FlowDefinition;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.SkillKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class SkillRouteCandidateProvider {

	private final DataAgentSkillBindingMapper bindingMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final DataAgentRouteArtifactMapper artifactMapper;

	private final RouteRulesService rulesService;

	private final RouteRiskResolver riskResolver;

	private final RouteArtifactChecksum artifactChecksum;

	private final ObjectMapper objectMapper;

	public SkillRouteCandidateProvider(DataAgentSkillBindingMapper bindingMapper, DataAgentSkillMapper skillMapper,
			DataAgentSkillVersionMapper versionMapper, DataAgentRouteArtifactMapper artifactMapper,
			RouteRulesService rulesService, RouteRiskResolver riskResolver, RouteArtifactChecksum artifactChecksum,
			ObjectMapper objectMapper) {
		this.bindingMapper = bindingMapper;
		this.skillMapper = skillMapper;
		this.versionMapper = versionMapper;
		this.artifactMapper = artifactMapper;
		this.rulesService = rulesService;
		this.riskResolver = riskResolver;
		this.artifactChecksum = artifactChecksum;
		this.objectMapper = objectMapper;
	}

	public List<RouteCandidate> load(RouteContext context, DataAgentRouteProfile profile, RoutePolicy policy,
			RouteDiagnostics diagnostics) {
		List<DataAgentSkillBinding> bindings = context.pinnedSkillVersionIds() != null
				? syntheticBindings(context) : bindingMapper.findEnabledByAgentId(context.ownerAgentId(),
						context.tenantId());
		if (bindings.isEmpty()) {
			return List.of();
		}
		Map<Long, DataAgentSkill> skills = skillsById(bindings);
		Map<Long, DataAgentSkillVersion> versions = versionsById(bindings);
		List<String> targetKeys = bindings.stream()
			.filter(binding -> binding.getPinnedSkillVersionId() != null && binding.getSkillId() != null)
			.map(binding -> skillTargetKey(binding.getSkillId(), binding.getPinnedSkillVersionId()))
			.distinct()
			.toList();
		String unavailableStatus = artifactUnavailableStatus(policy);
		List<DataAgentRouteArtifact> artifacts = List.of();
		if (unavailableStatus == null) {
			if (diagnostics != null) {
				diagnostics.markArtifactQueryExecuted();
			}
			artifacts = artifactMapper.findReady(policy.profileId(), context.tenantId(), targetKeys);
		}
		List<RouteCandidate> result = new ArrayList<>();
		for (DataAgentSkillBinding binding : bindings) {
			DataAgentSkill skill = skills.get(binding.getSkillId());
			DataAgentSkillVersion version = versions.get(binding.getPinnedSkillVersionId());
			requireTrustedBinding(context, binding, skill, version);
			if (driftedBinding(context, binding, skill, version, diagnostics)) {
				continue;
			}
			RouteRules rules = effectiveRules(rulesService.parse(version.getRouteRules()), version);
			RouteRisk risk = riskResolver.resolveSkill(version);
			String targetKey = skillTargetKey(skill.getId(), version.getId());
			String sourceChecksum = artifactChecksum.calculate(targetKey, context.tenantId(), version.getSkillName(),
					version.getDescription(), version.getSkillKind(), version.getExecutionMode(), rules, risk);
			RouteTargetRef target = new RouteTargetRef(RouteTargetType.SKILL, skill.getId(), version.getId(),
					binding.getId());
			ArtifactMatch match = unavailableStatus == null
					? matchingArtifact(artifacts, targetKey, sourceChecksum, policy.embeddingFingerprint())
					: new ArtifactMatch(null, unavailableStatus);
			if (diagnostics != null) {
				diagnostics.captureArtifact(target, match.status());
			}
			DataAgentRouteArtifact artifact = match.artifact();
			result.add(new RouteCandidate(target, context.tenantId(), context.ownerAgentId(), version.getSkillName(),
					version.getDescription(), version.getSkillKind(), version.getExecutionMode(),
					rules, risk, binding.getPriority() == null ? 0 : binding.getPriority(), policy.profileId(),
					artifact == null ? null : artifact.getId(), artifact == null ? null : artifact.getSourceChecksum(),
					artifact == null ? null : artifact.getEmbeddingFingerprint()));
		}
		requireAnyEligible(context, bindings, result);
		return result;
	}

	/**
	 * 数字员工 Release 快照钉死路径：按冻结的 SkillVersion 构造内存绑定，禁止再按
	 * DataAgent 绑定表（employeeId 会和真实 DataAgent 主键撞车）回源。
	 */
	private List<DataAgentSkillBinding> syntheticBindings(RouteContext context) {
		List<Long> versionIds = context.pinnedSkillVersionIds().stream().filter(Objects::nonNull).distinct().toList();
		if (versionIds.isEmpty()) {
			return List.of();
		}
		Map<Long, DataAgentSkillVersion> versions = versionMapper.selectBatchIds(versionIds).stream()
			.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity(), (left, right) -> left));
		List<DataAgentSkillBinding> bindings = new ArrayList<>();
		for (Long versionId : versionIds) {
			DataAgentSkillVersion version = versions.get(versionId);
			bindings.add(DataAgentSkillBinding.builder()
				.id(versionId)
				.agentId(context.ownerAgentId())
				.tenantId(version != null && StringUtils.hasText(version.getTenantId()) ? version.getTenantId()
						: context.tenantId())
				.skillId(version == null ? null : version.getSkillId())
				.pinnedSkillVersionId(versionId)
				.priority(0)
				.enabled(true)
				.deleted(false)
				.build());
		}
		return bindings;
	}

	/**
	 * 全部启用绑定都因生命周期漂移被跳过时，本轮没有任何可路由能力，仍按不可用返回。
	 *
	 * <p>与"Agent 根本没绑技能"（返回空列表、后续判为 NO_MATCH）区分开：这里是配置坏了，需要人工修。
	 */
	private void requireAnyEligible(RouteContext context, List<DataAgentSkillBinding> bindings,
			List<RouteCandidate> result) {
		if (!result.isEmpty()) {
			return;
		}
		log.warn("All enabled skill bindings drifted out of eligibility, agentId={}, bindingCount={}",
				context.ownerAgentId(), bindings.size());
		throw new RouteStageException("ROUTE_ELIGIBILITY_INVALID", "No eligible skill binding remains");
	}

	/**
	 * 未锁版本的绑定会带来 {@code null} ID，必须先剔除。
	 *
	 * <p>剔除后可能为空集，而 {@code selectBatchIds} 拿到空集合会生成 {@code IN ()} 非法 SQL，所以这里一并短路。
	 */
	private Map<Long, DataAgentSkill> skillsById(List<DataAgentSkillBinding> bindings) {
		List<Long> ids = distinctIds(bindings, DataAgentSkillBinding::getSkillId);
		return ids.isEmpty() ? Map.of()
				: skillMapper.selectBatchIds(ids).stream()
					.collect(Collectors.toMap(DataAgentSkill::getId, Function.identity()));
	}

	private Map<Long, DataAgentSkillVersion> versionsById(List<DataAgentSkillBinding> bindings) {
		List<Long> ids = distinctIds(bindings, DataAgentSkillBinding::getPinnedSkillVersionId);
		return ids.isEmpty() ? Map.of()
				: versionMapper.selectBatchIds(ids).stream()
					.collect(Collectors.toMap(DataAgentSkillVersion::getId, Function.identity()));
	}

	private List<Long> distinctIds(List<DataAgentSkillBinding> bindings,
			Function<DataAgentSkillBinding, Long> extractor) {
		return bindings.stream().map(extractor).filter(Objects::nonNull).distinct().toList();
	}

	private RouteRules effectiveRules(RouteRules rules, DataAgentSkillVersion version) {
		if (rules == null || !rules.allowFlowAutoSelect() || hasConfirmSubmit(version)) {
			return rules == null ? RouteRules.empty() : rules;
		}
		return new RouteRules(rules.exact(), rules.phrases(), rules.aliases(), rules.positiveExamples(),
				rules.positivePatterns(), rules.negativeExamples(), rules.hardExcludes(), rules.hardExcludePatterns(), false);
	}

	private boolean hasConfirmSubmit(DataAgentSkillVersion version) {
		if (version == null || !List.of(SkillKind.ACTION.name(), SkillKind.ORCHESTRATION.name())
			.contains(version.getSkillKind()) || !SkillExecutionMode.FLOW.name().equals(version.getExecutionMode())
			|| !StringUtils.hasText(version.getFlowDefinition())) {
			return false;
		}
		try {
			FlowDefinition definition = objectMapper.readValue(version.getFlowDefinition(), FlowDefinition.class);
			return definition.nodes() != null && definition.nodes().stream().anyMatch(this::isConfirmSubmit);
		}
		catch (JsonProcessingException | IllegalArgumentException ex) {
			return false;
		}
	}

	private boolean isConfirmSubmit(FlowNode node) {
		return node != null && "confirm-submit".equals(node.id()) && "confirm".equalsIgnoreCase(node.type());
	}

	/**
	 * 第一级：身份/归属校验，失败关闭。
	 *
	 * <p>租户串号、Skill 归属错位属越权风险，不允许降级为"跳过这条继续跑"，必须让整轮路由不可用。
	 */
	private void requireTrustedBinding(RouteContext context, DataAgentSkillBinding binding, DataAgentSkill skill,
			DataAgentSkillVersion version) {
		List<String> failures = identityFailures(context, binding, skill, version);
		if (failures.isEmpty()) {
			return;
		}
		// 调用方只拿得到 ROUTE_ELIGIBILITY_INVALID 这个 reasonCode，没有这条日志就无法知道是哪条绑定、
		// 哪个条件挂了，只能靠翻库排查。
		log.warn("Skill binding identity check failed, agentId={}, bindingId={}, skillId={}, "
				+ "pinnedSkillVersionId={}, skillScope={}, failures={}", context.ownerAgentId(), binding.getId(),
				binding.getSkillId(), binding.getPinnedSkillVersionId(), skill == null ? null : skill.getScope(),
				failures);
		throw new RouteStageException("ROUTE_ELIGIBILITY_INVALID", "Skill binding identity check failed");
	}

	private List<String> identityFailures(RouteContext context, DataAgentSkillBinding binding, DataAgentSkill skill,
			DataAgentSkillVersion version) {
		List<String> failures = new ArrayList<>();
		if (!Objects.equals(binding.getTenantId(), context.tenantId())) {
			failures.add("BINDING_TENANT_MISMATCH");
		}
		if (skill != null && !Objects.equals(skill.getTenantId(), context.tenantId())) {
			failures.add("SKILL_TENANT_MISMATCH");
		}
		// scope 决定这个 Skill 归谁所有，被改成 PLATFORM 意味着租户在用不属于自己的能力，按越权处理。
		if (skill != null && !"TENANT".equals(skill.getScope())) {
			failures.add("SKILL_SCOPE_NOT_TENANT");
		}
		if (version != null && !Objects.equals(version.getTenantId(), context.tenantId())) {
			failures.add("VERSION_TENANT_MISMATCH");
		}
		// 版本挂在别的 Skill 名下属于数据完整性破损（DB 侧有复合外键兜底），不是正常生命周期能造成的。
		if (skill != null && version != null && !Objects.equals(skill.getId(), version.getSkillId())) {
			failures.add("VERSION_SKILL_MISMATCH");
		}
		return failures;
	}

	/**
	 * 第二级：生命周期漂移，跳过该候选并记入诊断，让同 Agent 下其余技能照常可路由。
	 *
	 * <p>技能被改回草稿、版本被删、绑定没锁版本都只影响这一条绑定，不该让整个 Agent 失去应答能力。
	 *
	 * @return true 表示该绑定本轮不参与路由
	 */
	private boolean driftedBinding(RouteContext context, DataAgentSkillBinding binding, DataAgentSkill skill,
			DataAgentSkillVersion version, RouteDiagnostics diagnostics) {
		List<String> failures = lifecycleFailures(binding, skill, version);
		if (failures.isEmpty()) {
			return false;
		}
		log.warn("Skill binding skipped for this route, agentId={}, bindingId={}, skillId={}, "
				+ "pinnedSkillVersionId={}, skillStatus={}, versionStatus={}, failures={}", context.ownerAgentId(),
				binding.getId(), binding.getSkillId(), binding.getPinnedSkillVersionId(),
				skill == null ? null : skill.getStatus(), version == null ? null : version.getStatus(), failures);
		if (diagnostics != null) {
			diagnostics.captureIneligible(new RouteTargetRef(RouteTargetType.SKILL, binding.getSkillId(),
					binding.getPinnedSkillVersionId(), binding.getId()), failures);
		}
		return true;
	}

	private List<String> lifecycleFailures(DataAgentSkillBinding binding, DataAgentSkill skill,
			DataAgentSkillVersion version) {
		List<String> failures = new ArrayList<>();
		if (skill == null) {
			failures.add("SKILL_MISSING_OR_DELETED");
		}
		else {
			if (!"PUBLISHED".equals(skill.getStatus())) {
				failures.add("SKILL_NOT_PUBLISHED");
			}
			if (Boolean.TRUE.equals(skill.getDeleted())) {
				failures.add("SKILL_DELETED");
			}
		}
		// pinned_skill_version_id 是必填契约（DDL NOT NULL + 写入路径强校验），为空说明是历史脏数据。
		if (binding.getPinnedSkillVersionId() == null) {
			failures.add("VERSION_NOT_PINNED");
		}
		else if (version == null) {
			failures.add("VERSION_MISSING_OR_DELETED");
		}
		else {
			if (!"PUBLISHED".equals(version.getStatus())) {
				failures.add("VERSION_NOT_PUBLISHED");
			}
			if (Boolean.TRUE.equals(version.getDeleted())) {
				failures.add("VERSION_DELETED");
			}
		}
		return failures;
	}

	private ArtifactMatch matchingArtifact(List<DataAgentRouteArtifact> artifacts, String targetKey,
			String sourceChecksum, String embeddingFingerprint) {
		List<DataAgentRouteArtifact> targetArtifacts = artifacts.stream()
			.filter(artifact -> targetKey.equals(artifact.getTargetKey()))
			.toList();
		if (targetArtifacts.isEmpty()) {
			return new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_NOT_READY);
		}
		List<DataAgentRouteArtifact> checksumArtifacts = targetArtifacts.stream()
			.filter(artifact -> sourceChecksum.equals(artifact.getSourceChecksum()))
			.toList();
		if (checksumArtifacts.isEmpty()) {
			return new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_CHECKSUM_MISMATCH);
		}
		DataAgentRouteArtifact artifact = checksumArtifacts.stream()
			.filter(value -> Objects.equals(embeddingFingerprint, value.getEmbeddingFingerprint()))
			.findFirst()
			.orElse(null);
		return artifact == null
				? new ArtifactMatch(null, RouteDiagnostics.ARTIFACT_FINGERPRINT_MISMATCH)
				: new ArtifactMatch(artifact, RouteDiagnostics.ARTIFACT_READY);
	}

	private String artifactUnavailableStatus(RoutePolicy policy) {
		if (policy == null || !policy.semanticRecallEnabled()) {
			return RouteDiagnostics.ARTIFACT_QUERY_DISABLED;
		}
		return policy.semanticRuntimeReady() && StringUtils.hasText(policy.embeddingFingerprint())
				? null : RouteDiagnostics.ARTIFACT_PROFILE_NOT_READY;
	}

	private String skillTargetKey(Long skillId, Long versionId) {
		return "SKILL:" + skillId + ":" + versionId;
	}

	private record ArtifactMatch(DataAgentRouteArtifact artifact, String status) {
	}
}
