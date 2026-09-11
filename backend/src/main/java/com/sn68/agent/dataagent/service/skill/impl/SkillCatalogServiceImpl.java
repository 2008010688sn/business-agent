/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillCloneReq;
import com.sn68.agent.dataagent.dto.skill.SkillImportReq;
import com.sn68.agent.dataagent.dto.skill.SkillPageQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillPreviewResp;
import com.sn68.agent.dataagent.dto.skill.SkillSaveReq;
import com.sn68.agent.dataagent.dto.skill.SkillToolEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.SkillToolRefSaveDTO;
import com.sn68.agent.dataagent.dto.skill.SkillValidationResult;
import com.sn68.agent.dataagent.dto.skill.ToolVersionOptionDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.flow.FlowDefinitionValidator;
import com.sn68.agent.dataagent.flow.FlowValidationIssue;
import com.sn68.agent.dataagent.flow.definition.FlowDefinition;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeCascadeService;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeCascadeService.DeletedSkillKnowledge;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.dataagent.service.skill.SkillImportExportService;
import com.sn68.agent.dataagent.service.skill.SkillManagementTenantService;
import com.sn68.agent.dataagent.service.skill.SkillPublishService;
import com.sn68.agent.dataagent.service.skill.SkillVersionResourceSnapshotService;
import com.sn68.agent.dataagent.service.skill.SkillVersionResourceSnapshotService.ResourceSnapshot;
import com.sn68.agent.dataagent.service.skill.SkillValidationService;
import com.sn68.agent.dataagent.service.skill.SkillVersionService;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.SkillManagerApproval;
import com.sn68.agent.dataagent.skill.execution.DeterministicRuntimePolicy;
import com.sn68.agent.dataagent.skill.execution.ReactRuntimeBudgetPolicy;
import com.sn68.agent.dataagent.skill.SkillKind;
import com.sn68.agent.dataagent.skill.SkillScope;
import com.sn68.agent.dataagent.skill.SkillVersionStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Database-backed Skill catalog, version, validation and import/export implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillCatalogServiceImpl implements SkillCatalogService, SkillVersionService, SkillPublishService,
		SkillValidationService, SkillImportExportService {

	private static final Pattern SKILL_CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,127}");

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper versionMapper;

	private final DataAgentSkillToolRefMapper toolRefMapper;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final DataAgentSkillBindingMapper bindingMapper;

	private final RouteRulesService routeRulesService;

	private final SkillManagementTenantService skillManagementTenantService;

	private final SkillVersionResourceSnapshotService resourceSnapshotService;

	private final FlowDefinitionValidator flowDefinitionValidator;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	private final RouteArtifactService routeArtifactService;

	private final TransactionTemplate transactionTemplate;

	private final DataAgentProperties dataAgentProperties;

	private final SkillKnowledgeCascadeService skillKnowledgeCascadeService;

	@Override
	public IPage<DataAgentSkill> page(SkillPageQueryReq request) {
		SkillPageQueryReq query = request == null ? new SkillPageQueryReq() : request;
		return skillMapper.selectVisiblePage(query.buildPage(), query, requireEffectiveTenantId());
	}

	@Override
	public SkillDetailResp detail(String skillCode) {
		DataAgentSkill skill = requireManageable(skillCode);
		DataAgentSkillVersion selected = selectedVersion(skill);
		return toDetail(skill, selected);
	}

	@Override
	public SkillPreviewResp preview(String skillCode, String requestedVersion) {
		DataAgentSkill skill = requireManageable(skillCode);
		DataAgentSkillVersion version = previewVersion(skill, requestedVersion);
		Map<String, Object> flowDefinition = readMap(version.getFlowDefinition());
		List<DataAgentSkillToolRef> refs = toolRefMapper.findBySkillVersionId(version.getId());
		Set<Long> resourceVersionIds = refs.stream().map(DataAgentSkillToolRef::getResourceVersionId)
			.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		Map<Long, AgentExecutionResourceVersion> resourceVersions = findPublishedToolVersions(resourceVersionIds);
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		Map<String, Object> datasourceConfig = readMap(version.getDatasourceConfig());
		Map<String, Object> semanticConfig = readMap(version.getSemanticConfig());
		Map<String, Object> knowledgeConfig = readMap(version.getKnowledgeConfig());
		if (SkillVersionStatus.DRAFT.name().equals(version.getStatus())) {
			SkillValidationResult validation = validate(skillCode);
			errors.addAll(validation.errors());
			warnings.addAll(validation.warnings());
			ResourceSnapshot draftSnapshot = resourceSnapshotService.capture(skill, readMap(version.getKnowledgeConfig()),
					readMap(version.getRuntimeConfig()), readMap(version.getAnalysisConfig()));
			datasourceConfig = draftSnapshot.datasourceConfig();
			semanticConfig = draftSnapshot.semanticConfig();
			knowledgeConfig = draftSnapshot.knowledgeConfig();
		}
		List<SkillPreviewResp.ToolPreview> tools = refs.stream().map(ref -> {
			AgentExecutionResourceVersion resource = resourceVersions.get(ref.getResourceVersionId());
			if (resource == null) {
				warnings.add("工具版本不存在、未发布或已退役：" + ref.getResourceKey());
				return new SkillPreviewResp.ToolPreview(ref.getResourceVersionId(), ref.getResourceKey(), null, null, null,
						null, null, null, null);
			}
			return new SkillPreviewResp.ToolPreview(resource.getId(), resource.getResourceKey(), resource.getVersionNo(),
					resource.getAccessMode(), resource.getExposureMode(), resource.getPermissionCode(),
					"WRITE".equals(resource.getAccessMode()), resource.getConfirmRequired(),
					resource.getIdempotencyRequired());
		}).toList();
		List<SkillPreviewResp.FlowNodePreview> nodes = previewNodes(flowDefinition, warnings);
		List<SkillPreviewResp.ModelParticipation> modelParticipation = modelParticipation(version, nodes);
		var routeRules = routeRulesService.toResponseDTO(routeRulesService.parse(version.getRouteRules()));
		Map<String, Object> manifest = previewManifest(skill, version);
		SkillPreviewResp.ResourceSummary resourceSummary = resourceSummary(version, datasourceConfig, semanticConfig,
				knowledgeConfig);
		ReactRuntimeBudgetPolicy.Budget platformBudget = ReactRuntimeBudgetPolicy
				.resolve(dataAgentProperties.getRuntime(), null, null, null, null);
		Map<String, Object> effectiveReactBudget = ReactRuntimeBudgetPolicy
				.tighten(platformBudget, readMap(version.getReactConfig())).toMap();
		Map<String, Object> effectiveDeterministicPolicy = SkillExecutionMode.DETERMINISTIC.name()
			.equals(versionExecutionMode(version))
					? DeterministicRuntimePolicy.resolve(dataAgentProperties.getRuntime(),
							readMap(version.getRuntimeConfig())).toMap()
					: Map.of();
		return new SkillPreviewResp(skill.getSkillCode(), version.getSkillName(), versionExecutionMode(version), version.getId(),
				version.getVersionNo(), version.getStatus(), version.getSkillMarkdown(), routeRules, manifest, flowDefinition,
				readMap(version.getFlowRuntimeConfig()), readMap(version.getFlowPolicyConfig()), tools, nodes,
				modelParticipation, List.copyOf(errors), List.copyOf(warnings), resourceSummary, effectiveReactBudget,
				effectiveDeterministicPolicy);
	}

	private int datasourceCount(Map<String, Object> datasourceConfig) {
		if (datasourceConfig == null || datasourceConfig.isEmpty()) {
			return 0;
		}
		Object datasources = datasourceConfig.get("datasources");
		if (datasources instanceof List<?> list && !list.isEmpty()) {
			return list.size();
		}
		return datasourceConfig.get("datasourceId") != null ? 1 : 0;
	}

	private SkillPreviewResp.ResourceSummary resourceSummary(DataAgentSkillVersion version,
			Map<String, Object> datasourceConfig, Map<String, Object> semanticConfig,
			Map<String, Object> knowledgeConfig) {
		int datasourceCount = datasourceCount(datasourceConfig);
		int semanticModelCount = listValue(semanticConfig == null ? null : semanticConfig.get("semanticModelIds")).size();
		int businessKnowledgeCount = listValue(knowledgeConfig == null ? null : knowledgeConfig.get("businessKnowledgeIds"))
				.size();
		int skillKnowledgeCount = listValue(knowledgeConfig == null ? null : knowledgeConfig.get("skillKnowledgeIds"))
				.size();
		String mode = versionExecutionMode(version);
		List<String> modelStages = new ArrayList<>();
		if (SkillExecutionMode.DETERMINISTIC.name().equals(mode)) {
			modelStages.add("SQL规划前");
		}
		else if (SkillExecutionMode.REACT.name().equals(mode)) {
			modelStages.add("ReAct启动前");
		}
		else if (SkillExecutionMode.KNOWLEDGE.name().equals(mode)) {
			modelStages.add("回答模型前");
		}
		else if (businessKnowledgeCount > 0) {
			modelStages.add("FLOW模型字段抽取前（仅模型节点）");
		}
		String snapshotStatus = SkillVersionStatus.PUBLISHED.name().equals(version.getStatus())
				? "PUBLISHED_SNAPSHOT" : "DRAFT_RESOURCES";
		return new SkillPreviewResp.ResourceSummary(datasourceCount, semanticModelCount, businessKnowledgeCount,
				skillKnowledgeCount, List.copyOf(modelStages), snapshotStatus);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDetailResp create(SkillSaveReq request) {
		validateCatalogRequest(request, null);
		String tenantId = tenantIdForScope(request.scope());
		if (skillMapper.findVisibleByCode(request.skillCode(), tenantId) != null) {
			throw CheckedException.badRequest("Skill code already exists: " + request.skillCode());
		}
		DataAgentSkill skill = DataAgentSkill.builder()
			.tenantId(tenantId)
			.skillCode(request.skillCode().trim())
			.skillName(request.skillName().trim())
			.description(trim(request.description()))
			.category(trim(request.category()))
			.scope(SkillScope.TENANT.name())
			.skillKind(normalizeKind(request.skillKind()))
			.executionMode(normalizeMode(request.executionMode()))
			.status("DRAFT")
			.displayOrder(request.displayOrder() == null ? 0 : request.displayOrder())
			.build();
		skillMapper.insert(skill);
		DataAgentSkillVersion draft = createDraft(skill, request);
		applyToolRefsIfPresent(skill, draft, request.toolRefs());
		skill.setLatestDraftVersionId(draft.getId());
		skillMapper.updateById(skill);
		return toDetail(skill, draft);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDetailResp modify(String skillCode, SkillSaveReq request) {
		DataAgentSkill skill = requireManageable(skillCode);
		validateCatalogRequest(request, skillCode);
		if (request.skillCode() != null && !skill.getSkillCode().equals(request.skillCode().trim())) {
			throw CheckedException.badRequest("Published Skill code cannot be renamed");
		}
		if (!SkillScope.TENANT.name().equals(skill.getScope()) || !Objects.equals(skill.getTenantId(), effectiveTenantId())) {
			throw CheckedException.forbidden();
		}
		skill.setSkillName(request.skillName().trim());
		skill.setDescription(trim(request.description()));
		skill.setCategory(trim(request.category()));
		skill.setSkillKind(normalizeKind(request.skillKind()));
		skill.setExecutionMode(normalizeMode(request.executionMode()));
		skill.setDisplayOrder(request.displayOrder() == null ? skill.getDisplayOrder() : request.displayOrder());
		DataAgentSkillVersion draft = versionMapper.findLatestDraft(skill.getId());
		if (draft == null) {
			draft = createDraft(skill, request);
			skill.setLatestDraftVersionId(draft.getId());
		}
		else {
			applyDraft(draft, request);
			versionMapper.updateById(draft);
		}
		applyToolRefsIfPresent(skill, draft, request.toolRefs());
		skill.setStatus(skill.getPublishedVersionId() == null ? "DRAFT" : "PUBLISHED");
		skillMapper.updateById(skill);
		return toDetail(skill, draft);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDetailResp clonePublished(String skillCode, SkillCloneReq request) {
		DataAgentSkill source = requireManageable(skillCode);
		if (request == null || !StringUtils.hasText(request.skillCode())) {
			throw CheckedException.badRequest("克隆后的 Skill 编码不能为空");
		}
		DataAgentSkillVersion published = getPublished(source, null);
		List<SkillToolRefSaveDTO> refs = toolRefMapper.findBySkillVersionId(published.getId()).stream()
			.map(ref -> new SkillToolRefSaveDTO(ref.getResourceVersionId(), ref.getUsage(), ref.getDisplayOrder()))
			.toList();
		SkillSaveReq cloneRequest = new SkillSaveReq(request.skillCode(),
				firstText(request.skillName(), published.getSkillName() + " 副本"), published.getDescription(),
				published.getCategory(), SkillScope.TENANT.name(), versionExecutionMode(published),
				published.getDisplayOrder(), published.getSkillMarkdown(),
				readMap(published.getRouteRules()), readMap(published.getKnowledgeConfig()), readMap(published.getReactConfig()),
				readMap(published.getFlowDefinition()), readMap(published.getVariablesSchema()), refs,
				readMap(published.getFlowRuntimeConfig()), readMap(published.getFlowPolicyConfig()), versionSkillKind(published),
				readMap(published.getResourceRequirement()), readMap(published.getInputSchema()),
				readMap(published.getOutputSchema()), readMap(published.getRuntimeConfig()),
				readMap(published.getAnalysisConfig()));
		return create(cloneRequest);
	}

	@Override
	public SkillToolEditorContextResp toolEditorContext(String skillCode, String scope, String executionMode) {
		DataAgentSkill skill = StringUtils.hasText(skillCode) ? requireManageable(skillCode) : null;
		String mode = normalizeMode(executionMode);
		if (!SkillExecutionMode.REACT.name().equals(mode) && !SkillExecutionMode.FLOW.name().equals(mode)) {
			throw CheckedException.badRequest("Only REACT and FLOW Skills can bind tool versions");
		}
		String tenantId;
		if (skill != null) {
			tenantId = skill.getTenantId();
		}
		else {
			tenantId = tenantIdForScope(scope);
		}
		Long versionId = skill == null ? null
				: (skill.getLatestDraftVersionId() == null ? skill.getPublishedVersionId() : skill.getLatestDraftVersionId());
		List<DataAgentSkillToolRef> entities = versionId == null ? List.of() : toolRefMapper.findBySkillVersionId(versionId);
		List<SkillToolRefSaveDTO> refs = entities.stream()
			.map(item -> new SkillToolRefSaveDTO(item.getResourceVersionId(), item.getUsage(), item.getDisplayOrder()))
			.toList();
		Set<Long> selectedIds = entities.stream().map(DataAgentSkillToolRef::getResourceVersionId)
			.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		List<ToolVersionOptionDTO> options = resourceVersionMapper
			.findSkillToolEditorOptions(tenantId, false, mode, selectedIds).stream()
			.map(row -> toToolVersionOption(row, tenantId, mode,
					selectedIds.contains(rowLong(row, "resource_version_id"))))
			.filter(Objects::nonNull)
			.toList();
		return new SkillToolEditorContextResp(refs, options);
	}

	/**
	 * 删除未被绑定的 Skill，并级联清理它名下的知识行与知识向量。
	 *
	 * <p>刻意不加 {@code @Transactional}：向量清理是远程调用，放进事务既拉长事务，又会在回滚时留下
	 * 「Skill 还在、知识却检索不到」的静默降级。这里改为短事务内先删知识行与 Skill 主行，提交后再清向量，
	 * 顺序与失败方向的取舍见 {@link SkillKnowledgeCascadeService}。
	 *
	 * <p><b>非事务补偿点</b>：向量清理失败会抛错上报，此时 Skill 与知识行都已删除，残留向量不可召回但也
	 * 无入口可清，需按 skillId 手工处理。
	 */
	@Override
	public void delete(String skillCode) {
		DataAgentSkill skill = requireManageable(skillCode);
		if (!bindingMapper.selectList(com.sn68.agent.framework.db.mybatisplus.wrap.Wraps
			.<com.sn68.agent.dataagent.entity.DataAgentSkillBinding>lbQ()
			.eq(com.sn68.agent.dataagent.entity.DataAgentSkillBinding::getSkillId, skill.getId())
			.eq(com.sn68.agent.dataagent.entity.DataAgentSkillBinding::getDeleted, false)).isEmpty()) {
			throw CheckedException.badRequest("Skill is still bound to an Agent: " + skillCode);
		}
		DeletedSkillKnowledge deletedKnowledge = transactionTemplate.execute(status -> {
			DeletedSkillKnowledge rows = skillKnowledgeCascadeService.deleteKnowledgeRows(skill.getId());
			if (skillMapper.deleteById(skill.getId()) <= 0) {
				throw CheckedException.notFound("Skill does not exist: " + skillCode);
			}
			return rows;
		});
		skillKnowledgeCascadeService.purgeKnowledgeVectors(skill.getId(), deletedKnowledge);
	}

	@Override
	public DataAgentSkill findVisible(String skillCode, String tenantId) {
		return skillMapper.findVisibleByCode(skillCode, tenantId);
	}

	@Override
	public DataAgentSkill requireManageable(String skillCode) {
		DataAgentSkill skill = requireVisible(skillCode);
		if (!SkillScope.TENANT.name().equals(skill.getScope()) || !StringUtils.hasText(skill.getTenantId())
				|| !Objects.equals(skill.getTenantId(), effectiveTenantId())) {
			throw CheckedException.forbidden();
		}
		return skill;
	}

	@Override
	public DataAgentSkillVersion getRequired(Long versionId) {
		DataAgentSkillVersion version = versionId == null ? null : versionMapper.selectById(versionId);
		if (version == null || Boolean.TRUE.equals(version.getDeleted())) {
			throw CheckedException.notFound("Skill version does not exist");
		}
		return version;
	}

	@Override
	public DataAgentSkillVersion getPublished(DataAgentSkill skill, Long pinnedVersionId) {
		if (skill == null) {
			throw CheckedException.notFound("Skill does not exist");
		}
		Long versionId = pinnedVersionId == null ? skill.getPublishedVersionId() : pinnedVersionId;
		DataAgentSkillVersion version = getRequired(versionId);
		if (!Objects.equals(skill.getId(), version.getSkillId())
				|| !SkillVersionStatus.PUBLISHED.name().equals(version.getStatus())) {
			throw CheckedException.badRequest("Agent binding must pin a published version of the same Skill");
		}
		return version;
	}

	@Override
	public SkillDetailResp publish(String skillCode) {
		DataAgentSkill skill = requireManageable(skillCode);
		DataAgentSkillVersion draft = versionMapper.findLatestDraft(skill.getId());
		if (draft == null) {
			throw CheckedException.badRequest("No draft version is available to publish");
		}
		SkillValidationResult validation = validate(skillCode);
		if (!validation.valid()) {
			throw CheckedException.badRequest(String.join("; ", validation.errors()));
		}
		ResourceSnapshot snapshot = resourceSnapshotService.capture(skill, readMap(draft.getKnowledgeConfig()),
				readMap(draft.getRuntimeConfig()), readMap(draft.getAnalysisConfig()));
		if (!snapshot.valid()) {
			throw CheckedException.badRequest(String.join("; ", snapshot.errors()));
		}
		applyResourceSnapshot(draft, snapshot);
		routeArtifactService.prepareSkillVersion(skill, draft);
		String expectedDraftChecksum = draft.getChecksum();
		Instant expectedSkillLastModifyTime = skill.getLastModifyTime();
		String publishedBy = currentUserId();
		return transactionTemplate.execute(status -> {
			DataAgentSkill currentSkill = skillMapper.selectById(skill.getId());
			DataAgentSkillVersion currentDraft = versionMapper.selectById(draft.getId());
			if (currentSkill == null || currentDraft == null
					|| !Objects.equals(currentSkill.getLatestDraftVersionId(), currentDraft.getId())
					|| !Objects.equals(expectedSkillLastModifyTime, currentSkill.getLastModifyTime())
					|| !Objects.equals(expectedDraftChecksum, currentDraft.getChecksum())
					|| !SkillVersionStatus.DRAFT.name().equals(currentDraft.getStatus())) {
				throw CheckedException.badRequest("Skill draft changed while route Artifact was building");
			}
			applyResourceSnapshot(currentDraft, snapshot);
			currentDraft.setStatus(SkillVersionStatus.PUBLISHED.name());
			currentDraft.setPublishedAt(Instant.now());
			currentDraft.setPublishedBy(publishedBy);
			currentDraft.setChecksum(checksum(currentDraft));
			versionMapper.updateById(currentDraft);
			currentSkill.setPublishedVersionId(currentDraft.getId());
			currentSkill.setLatestDraftVersionId(null);
			currentSkill.setStatus("PUBLISHED");
			skillMapper.update(null, Wraps.<DataAgentSkill>lbU()
				.eq(DataAgentSkill::getId, currentSkill.getId())
				.set(DataAgentSkill::getPublishedVersionId, currentDraft.getId())
				.set(DataAgentSkill::getLatestDraftVersionId, null)
				.set(DataAgentSkill::getStatus, "PUBLISHED")
				.set(DataAgentSkill::getLastModifyTime, Instant.now()));
			return toDetail(currentSkill, currentDraft);
		});
	}

	@Override
	public SkillValidationResult validate(String skillCode) {
		DataAgentSkill skill = requireManageable(skillCode);
		DataAgentSkillVersion draft = versionMapper.findLatestDraft(skill.getId());
		if (draft == null) {
			String message = "No draft version is available";
			return new SkillValidationResult(false, List.of(message), List.of(),
					List.of(new FlowValidationIssue("SKILL_DRAFT_MISSING", "version", null, "ERROR", message)));
		}
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<FlowValidationIssue> issues = new ArrayList<>();
		String draftKind = versionSkillKind(draft);
		String draftExecutionMode = versionExecutionMode(draft);
		validateKindMode(draftKind, draftExecutionMode);
		if (!Objects.equals(skill.getSkillKind(), draftKind)
				|| !Objects.equals(skill.getExecutionMode(), draftExecutionMode)) {
			errors.add("Skill catalog type does not match its draft version snapshot");
		}
		Map<String, Object> routeRules = readMap(draft.getRouteRules());
		var normalizedRouteRules = routeRulesService.normalize(routeRules);
		if (normalizedRouteRules.allowFlowAutoSelect()
				&& (!SkillExecutionMode.FLOW.name().equals(draftExecutionMode)
						|| !Set.of(SkillKind.ACTION.name(), SkillKind.ORCHESTRATION.name()).contains(draftKind))) {
			errors.add("allowFlowAutoSelect is only valid for ACTION or ORCHESTRATION FLOW Skills");
		}
		if (routeRules.isEmpty()) {
			warnings.add("No deterministic route rules are configured; routing will depend on fallback classification");
		}
		SkillExecutionMode mode = SkillExecutionMode.valueOf(draftExecutionMode);
		if (SkillManagerApproval.enabled(readMap(draft.getRuntimeConfig())) && mode != SkillExecutionMode.FLOW) {
			errors.add("requireManagerApproval is only supported for FLOW skills");
		}
		errors.addAll(ReactRuntimeBudgetPolicy.validate(readMap(draft.getReactConfig())));
		if (mode == SkillExecutionMode.DETERMINISTIC) {
			errors.addAll(DeterministicRuntimePolicy.validate(dataAgentProperties.getRuntime(),
					readMap(draft.getRuntimeConfig())));
		}
		ResourceSnapshot snapshot = resourceSnapshotService.capture(skill, readMap(draft.getKnowledgeConfig()),
				readMap(draft.getRuntimeConfig()), readMap(draft.getAnalysisConfig()));
		errors.addAll(snapshot.errors());
		warnings.addAll(snapshot.warnings());
		if (mode == SkillExecutionMode.REACT || mode == SkillExecutionMode.FLOW
				|| mode == SkillExecutionMode.DETERMINISTIC) {
			if (snapshot.datasourceConfig().isEmpty() && snapshot.semanticConfig().isEmpty()
					&& snapshot.knowledgeConfig().isEmpty() && snapshot.analysisConfig().isEmpty()) {
				warnings.add("No execution resources configured for this Skill version");
			}
		}
		if (mode == SkillExecutionMode.FLOW) {
			Map<String, Object> flowDefinition = readMap(draft.getFlowDefinition());
			List<String> flowErrors = flowDefinitionValidator.validate(flowDefinition);
			errors.addAll(flowErrors);
			issues.addAll(flowErrors.stream().map(FlowValidationIssue::error).toList());
			previewNodes(flowDefinition, warnings);
		}
		List<DataAgentSkillToolRef> refs = toolRefMapper.findBySkillVersionId(draft.getId());
		int toolErrorStart = errors.size();
		validateToolRefs(mode, draft, refs, errors, warnings);
		for (int index = toolErrorStart; index < errors.size(); index++) {
			String message = errors.get(index);
			issues.add(new FlowValidationIssue("SKILL_TOOL_REF_INVALID", "toolRefs", null, "ERROR", message));
		}
		return new SkillValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings),
				List.copyOf(issues));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SkillDetailResp importBundle(SkillImportReq request) {
		Map<String, Object> bundle = request == null ? Map.of() : mapValue(request.bundle());
		Map<String, Object> manifest = mapValue(bundle.get("manifest.yaml"));
		if (!"skill-package/v1".equals(text(manifest.get("schemaVersion")))) {
			throw CheckedException.badRequest("Skill package schemaVersion must be skill-package/v1");
		}
		SkillSaveReq saveRequest = new SkillSaveReq(text(manifest.get("skillCode")),
				text(manifest.get("name")), text(manifest.get("description")), text(manifest.get("category")),
				text(manifest.get("scope")), text(manifest.get("executionMode")), integer(manifest.get("displayOrder")),
				text(bundle.get("SKILL.md")), importRouteRules(manifest.get("routeRules")),
				mapValue(manifest.get("knowledgeConfig")), mapValue(manifest.get("reactConfig")),
				mapValue(bundle.get("flow.yaml")), mapValue(manifest.get("variablesSchema")), null,
				mapValue(manifest.get("flowRuntimeConfig")), mapValue(manifest.get("flowPolicyConfig")),
				text(manifest.get("skillKind")), mapValue(bundle.get("resource-requirement.json")),
				mapValue(bundle.get("input-schema.json")), mapValue(bundle.get("output-schema.json")),
				mapValue(manifest.get("runtimeConfig")), mapValue(manifest.get("analysisConfig")));
		SkillDetailResp detail = create(saveRequest);
		importToolRefs(detail, listValue(bundle.get("tool-refs.json")));
		return detail(detail.skillCode());
	}

	@Override
	public Map<String, Object> exportBundle(String skillCode) {
		DataAgentSkill skill = requireManageable(skillCode);
		DataAgentSkillVersion selected = selectedVersion(skill);
		SkillDetailResp detail = toDetail(skill, selected);
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", "skill-package/v1");
		manifest.put("skillCode", detail.skillCode());
		manifest.put("name", selected == null ? detail.skillName() : selected.getSkillName());
		manifest.put("description", selected == null ? detail.description() : selected.getDescription());
		manifest.put("category", selected == null ? detail.category() : selected.getCategory());
		manifest.put("scope", detail.scope());
		manifest.put("skillKind", detail.version() == null ? detail.skillKind() : detail.version().skillKind());
		manifest.put("executionMode", detail.version() == null ? detail.executionMode() : detail.version().executionMode());
		manifest.put("displayOrder", selected == null ? detail.displayOrder() : selected.getDisplayOrder());
		manifest.put("routeRules", selected == null ? Map.of()
				: routeRulesService.toMap(routeRulesService.parse(selected.getRouteRules())));
		manifest.put("knowledgeConfig", detail.version() == null ? Map.of()
				: sanitizeDraftKnowledgeConfig(detail.version().knowledgeConfig()));
		manifest.put("reactConfig", detail.version() == null ? Map.of() : detail.version().reactConfig());
		manifest.put("variablesSchema", detail.version() == null ? Map.of() : detail.version().variablesSchema());
		manifest.put("flowRuntimeConfig", detail.version() == null ? Map.of() : detail.version().flowRuntimeConfig());
		manifest.put("flowPolicyConfig", detail.version() == null ? Map.of() : detail.version().flowPolicyConfig());
		manifest.put("runtimeConfig", detail.version() == null ? Map.of() : detail.version().runtimeConfig());
		manifest.put("analysisConfig", detail.version() == null ? Map.of() : detail.version().analysisConfig());
		Map<String, Object> bundle = new LinkedHashMap<>();
		bundle.put("manifest.yaml", manifest);
		bundle.put("SKILL.md", detail.version() == null ? "" : detail.version().skillMarkdown());
		bundle.put("resource-requirement.json", detail.version() == null ? Map.of() : detail.version().resourceRequirement());
		bundle.put("input-schema.json", detail.version() == null ? Map.of() : detail.version().inputSchema());
		bundle.put("output-schema.json", detail.version() == null ? Map.of() : detail.version().outputSchema());
		if (detail.version() != null && "FLOW".equals(detail.version().executionMode())) {
			bundle.put("flow.yaml", detail.version().flowDefinition());
		}
		if (detail.version() != null) {
			List<DataAgentSkillToolRef> refs = toolRefMapper.findBySkillVersionId(detail.version().id());
			Set<Long> resourceVersionIds = refs.stream().map(DataAgentSkillToolRef::getResourceVersionId)
				.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
			Map<Long, AgentExecutionResourceVersion> versions = findPublishedToolVersions(resourceVersionIds);
			List<Map<String, Object>> toolRefs = refs.stream()
				.map(ref -> exportToolRef(ref, versions.get(ref.getResourceVersionId())))
				.filter(Objects::nonNull)
				.toList();
			bundle.put("tool-refs.json", toolRefs);
		}
		return bundle;
	}

	private DataAgentSkillVersion previewVersion(DataAgentSkill skill, String requestedVersion) {
		String selector = firstText(requestedVersion, "draft");
		if ("draft".equalsIgnoreCase(selector)) {
			DataAgentSkillVersion draft = versionMapper.findLatestDraft(skill.getId());
			if (draft == null) {
				throw CheckedException.notFound("Skill 草稿版本不存在");
			}
			return draft;
		}
		if ("published".equalsIgnoreCase(selector)) {
			if (skill.getPublishedVersionId() == null) {
				throw CheckedException.notFound("Skill 已发布版本不存在");
			}
			DataAgentSkillVersion published = getRequired(skill.getPublishedVersionId());
			if (!SkillVersionStatus.PUBLISHED.name().equals(published.getStatus())) {
				throw CheckedException.badRequest("Skill 发布版本状态无效");
			}
			return published;
		}
		throw CheckedException.badRequest("version 仅支持 draft 或 published");
	}

	private Map<String, Object> previewManifest(DataAgentSkill skill, DataAgentSkillVersion version) {
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", "skill-package/v1");
		manifest.put("skillCode", skill.getSkillCode());
		manifest.put("name", version.getSkillName());
		manifest.put("description", version.getDescription());
		manifest.put("category", version.getCategory());
		manifest.put("scope", skill.getScope());
		manifest.put("skillKind", versionSkillKind(version));
		manifest.put("executionMode", versionExecutionMode(version));
		manifest.put("displayOrder", version.getDisplayOrder());
		manifest.put("routeRules", routeRulesService.toResponseMap(routeRulesService.parse(version.getRouteRules())));
		manifest.put("knowledgeConfig", readMap(version.getKnowledgeConfig()));
		manifest.put("reactConfig", readMap(version.getReactConfig()));
		manifest.put("variablesSchema", readMap(version.getVariablesSchema()));
		manifest.put("flowRuntimeConfig", readMap(version.getFlowRuntimeConfig()));
		manifest.put("flowPolicyConfig", readMap(version.getFlowPolicyConfig()));
		manifest.put("runtimeConfig", readMap(version.getRuntimeConfig()));
		return manifest;
	}

	private List<SkillPreviewResp.FlowNodePreview> previewNodes(Map<String, Object> flowDefinition,
			List<String> warnings) {
		List<SkillPreviewResp.FlowNodePreview> result = new ArrayList<>();
		for (Object rawNode : listValue(flowDefinition.get("nodes"))) {
			Map<String, Object> node = mapValue(rawNode);
			String nodeId = text(node.get("id"));
			String nodeType = text(node.get("type"));
			Map<String, Object> config = mapValue(node.get("config"));
			List<String> branches = listValue(node.get("branches")).stream().map(this::mapValue)
				.map(branch -> firstText(text(branch.get("next")), text(branch.get("target"))))
				.filter(StringUtils::hasText).toList();
			String normalizedType = StringUtils.hasText(nodeType) ? nodeType.toLowerCase(Locale.ROOT) : "";
			boolean modelParticipates = Set.of("extract", "collect", "review", "validate").contains(normalizedType)
					|| "resolve".equals(normalizedType) && StringUtils.hasText(text(config.get("forEach")));
			boolean writes = "execute".equals(normalizedType);
			boolean requiresConfirmation = "confirm".equals(normalizedType) || writes;
			String failureNext = firstText(text(config.get("invalidNext")), text(config.get("emptyNext")));
			validateImPreview(nodeId, normalizedType, config, warnings);
			result.add(new SkillPreviewResp.FlowNodePreview(nodeId, nodeType, longValue(config.get("resourceVersionId")),
					text(node.get("next")), branches,
					modelParticipates, writes, requiresConfirmation, failureNext));
		}
		return List.copyOf(result);
	}

	private void validateImPreview(String nodeId, String nodeType, Map<String, Object> config,
			List<String> warnings) {
		if ("collect".equals(nodeType) && mapValue(config.get("collectionPresentation")).isEmpty()
				&& !StringUtils.hasText(text(config.get("prompt")))) {
			addWarning(warnings, "IM 兼容：collect 节点 " + nodeId + " 缺少可读提问或 collectionPresentation");
		}
		if ("select".equals(nodeType) && mapValue(config.get("textSearch")).isEmpty()) {
			addWarning(warnings, "IM 兼容：select 节点 " + nodeId + " 未配置 textSearch，IM 只能按序号或当前候选精确文本选择");
		}
		if ("review".equals(nodeType) && mapValue(mapValue(config.get("schema")).get("properties")).isEmpty()) {
			addWarning(warnings, "IM 兼容：review 节点 " + nodeId + " 缺少可用于文本修改抽取的 schema.properties");
		}
		List<Map<String, Object>> actions = listValue(config.get("uiActions")).stream().map(this::mapValue).toList();
		if ("confirm".equals(nodeType) && actions.stream()
				.noneMatch(action -> "CONFIRM".equalsIgnoreCase(text(action.get("type"))))) {
			addWarning(warnings, "IM 兼容：confirm 节点 " + nodeId + " 缺少 CONFIRM 文本动作");
		}
		if ("select".equals(nodeType) && Boolean.TRUE.equals(config.get("allowSkip"))
				&& listValue(config.get("skipCommands")).isEmpty()
				&& actions.stream().noneMatch(action -> "SKIP".equalsIgnoreCase(text(action.get("type"))))) {
			addWarning(warnings, "IM 兼容：select 节点 " + nodeId + " 允许跳过，但缺少 skipCommands 或 SKIP 文本动作");
		}
		if ("FORM".equalsIgnoreCase(text(config.get("uiMode")))
				&& mapValue(mapValue(config.get("schema")).get("properties")).isEmpty()) {
			addWarning(warnings, "IM 兼容：FORM 节点 " + nodeId + " 缺少文本通道可用的 schema.properties");
		}
	}

	private void addWarning(List<String> warnings, String warning) {
		if (!warnings.contains(warning)) {
			warnings.add(warning);
		}
	}

	private List<SkillPreviewResp.ModelParticipation> modelParticipation(DataAgentSkillVersion version,
			List<SkillPreviewResp.FlowNodePreview> nodes) {
		List<SkillPreviewResp.ModelParticipation> result = new ArrayList<>();
		if (SkillExecutionMode.REACT.name().equals(versionExecutionMode(version))) {
			result.add(new SkillPreviewResp.ModelParticipation("REACT", true, "模型仅能在已绑定工具范围内选择 READ Tool"));
		}
		for (SkillPreviewResp.FlowNodePreview node : nodes) {
			String reason = switch (String.valueOf(node.nodeType()).toLowerCase(Locale.ROOT)) {
				case "extract" -> "模型按配置路径抽取字段";
				case "collect" -> "模型只按节点 Schema 抽取用户补充字段";
				case "review" -> "模型只抽取用户修改的允许字段";
				case "resolve" -> node.modelParticipates() ? "集合 Resolver 只按节点 Schema 抽取当前条目字段"
						: "节点由后端确定性执行";
				case "validate" -> "模型只参与配置允许的修正内容抽取";
				default -> "节点由后端确定性执行";
			};
			result.add(new SkillPreviewResp.ModelParticipation(node.nodeId(), node.modelParticipates(), reason));
		}
		return List.copyOf(result);
	}

	private Map<String, Object> exportToolRef(DataAgentSkillToolRef ref, AgentExecutionResourceVersion version) {
		if (version == null) {
			return null;
		}
		Map<String, Object> exported = new LinkedHashMap<>();
		exported.put("resourceKey", version.getResourceKey());
		exported.put("resourceVersionNo", version.getVersionNo());
		exported.put("usage", ref.getUsage());
		exported.put("status", ref.getStatus());
		exported.put("displayOrder", ref.getDisplayOrder());
		exported.put("extConfig", readMap(ref.getExtConfig()));
		return exported;
	}

	private void importToolRefs(SkillDetailResp detail, List<?> rawRefs) {
		if (rawRefs.isEmpty() || detail.version() == null) {
			return;
		}
		// 先按 resourceKey 批量取回已发布版本，替代循环内逐条 findPublished；
		// 这里只做收集不做校验，逐条的必填/存在性/租户校验仍留在下面的循环里，报错顺序与原实现一致
		Map<String, AgentExecutionResourceVersion> publishedVersions = findPublishedToolVersionsByKey(rawRefs.stream()
			.map(rawRef -> text(mapValue(rawRef).get("resourceKey")))
			.filter(StringUtils::hasText)
			.distinct()
			.toList());
		Set<Long> versionIds = new HashSet<>();
		List<DataAgentSkillToolRef> imported = new ArrayList<>();
		for (Object rawRef : rawRefs) {
			Map<String, Object> ref = mapValue(rawRef);
			String resourceKey = text(ref.get("resourceKey"));
			Integer versionNo = integer(ref.get("resourceVersionNo"));
			if (!StringUtils.hasText(resourceKey) || versionNo == null) {
				throw CheckedException.badRequest("Imported tool reference requires resourceKey and resourceVersionNo");
			}
			AgentExecutionResourceVersion resourceVersion = publishedVersions
				.get(toolVersionKey(resourceKey, versionNo));
			if (resourceVersion == null) {
				throw CheckedException.notFound("Published tool version does not exist: " + resourceKey + " v" + versionNo);
			}
			if (detail.tenantId() == null && resourceVersion.getTenantId() != null
					|| detail.tenantId() != null && resourceVersion.getTenantId() != null
							&& !detail.tenantId().equals(resourceVersion.getTenantId())) {
				throw CheckedException.badRequest("Imported Skill cannot bind this tenant tool version: " + resourceKey);
			}
			if (!versionIds.add(resourceVersion.getId())) {
				throw CheckedException.badRequest("Duplicate imported tool version: " + resourceKey + " v" + versionNo);
			}
			DataAgentSkillToolRef entity = DataAgentSkillToolRef.builder()
				.tenantId(detail.tenantId())
				.skillVersionId(detail.version().id())
				.resourceVersionId(resourceVersion.getId())
				.resourceKey(resourceVersion.getResourceKey())
				.usage(text(ref.get("usage")))
				.status(firstText(text(ref.get("status")), "enabled"))
				.displayOrder(integer(ref.get("displayOrder")) == null ? 0 : integer(ref.get("displayOrder")))
				.extConfig(writeJson(mapValue(ref.get("extConfig"))))
				.build();
			imported.add(entity);
		}
		if (!imported.isEmpty()) {
			toolRefMapper.insertBatch(imported);
		}
	}

	private Map<String, AgentExecutionResourceVersion> findPublishedToolVersionsByKey(List<String> resourceKeys) {
		if (resourceKeys.isEmpty()) {
			// Wraps 会跳过空集合条件，不提前返回就会退化成「查全部已发布版本」
			return Map.of();
		}
		return resourceVersionMapper.selectList(
				com.sn68.agent.framework.db.mybatisplus.wrap.Wraps.<AgentExecutionResourceVersion>lbQ()
					.in(AgentExecutionResourceVersion::getResourceKey, resourceKeys)
					.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED")).stream()
			.filter(version -> version.getResourceKey() != null && version.getVersionNo() != null)
			.collect(java.util.stream.Collectors.toMap(
					version -> toolVersionKey(version.getResourceKey(), version.getVersionNo()), item -> item,
					(left, right) -> left));
	}

	private String toolVersionKey(String resourceKey, Integer versionNo) {
		return resourceKey + "\u0000" + versionNo;
	}

	private void validateToolRefs(SkillExecutionMode mode, DataAgentSkillVersion draft,
			List<DataAgentSkillToolRef> refs, List<String> errors, List<String> warnings) {
		Set<Long> resourceVersionIds = refs.stream().map(DataAgentSkillToolRef::getResourceVersionId)
			.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		Map<Long, AgentExecutionResourceVersion> versions = findPublishedToolVersions(resourceVersionIds);
		for (DataAgentSkillToolRef ref : refs) {
			AgentExecutionResourceVersion resourceVersion = versions.get(ref.getResourceVersionId());
			if (resourceVersion == null) {
				errors.add("Tool reference is not pinned to a published resource version: " + ref.getResourceKey());
				continue;
			}
			if ("WRITE".equals(resourceVersion.getAccessMode()) && "MODEL".equals(resourceVersion.getExposureMode())) {
				errors.add("WRITE + MODEL tool configuration is forbidden: " + resourceVersion.getResourceKey());
			}
			if (mode == SkillExecutionMode.REACT
					&& !("READ".equals(resourceVersion.getAccessMode())
							&& "MODEL".equals(resourceVersion.getExposureMode()))) {
				errors.add("REACT Skill can bind only READ + MODEL tools: " + resourceVersion.getResourceKey());
			}
		}
		if (mode == SkillExecutionMode.FLOW) {
			FlowDefinition definition = objectMapper.convertValue(readMap(draft.getFlowDefinition()), FlowDefinition.class);
			for (FlowNode node : definition.nodes() == null ? List.<FlowNode>of() : definition.nodes()) {
				if (node == null) {
					continue;
				}
				Long resourceVersionId = longValue(node.config() == null ? null : node.config().get("resourceVersionId"));
				AgentExecutionResourceVersion resource = resourceVersionId == null ? null : versions.get(resourceVersionId);
				if (resourceVersionId != null) {
					if (resource == null) {
						errors.add(node.type() + " node " + node.id()
								+ " references a tool version not bound to this Skill");
					}
					else if ("execute".equalsIgnoreCase(node.type()) && !("WRITE".equals(resource.getAccessMode())
							&& "FLOW_ONLY".equals(resource.getExposureMode()))) {
						errors.add("execute node " + node.id() + " must use WRITE + FLOW_ONLY tool");
					}
				}
				if ("execute".equalsIgnoreCase(node.type())) {
					validateResultQuery(node, resource, versions, errors);
				}
			}
		}
		if ((mode == SkillExecutionMode.REACT || mode == SkillExecutionMode.FLOW) && refs.isEmpty()) {
			warnings.add(mode + " Skill does not bind any tool versions");
		}
	}

	private Map<Long, AgentExecutionResourceVersion> findPublishedToolVersions(Set<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		return resourceVersionMapper.selectList(
				com.sn68.agent.framework.db.mybatisplus.wrap.Wraps.<AgentExecutionResourceVersion>lbQ()
					.in(AgentExecutionResourceVersion::getId, ids)
					.eq(AgentExecutionResourceVersion::getStatus, "PUBLISHED")
					.eq(AgentExecutionResourceVersion::getDeleted, false)).stream()
			.collect(java.util.stream.Collectors.toMap(AgentExecutionResourceVersion::getId, item -> item));
	}

	private void validateResultQuery(FlowNode node, AgentExecutionResourceVersion executeResource,
			Map<Long, AgentExecutionResourceVersion> versions, List<String> errors) {
		Object value = node.config() == null ? null : node.config().get("resultQuery");
		Map<?, ?> resultQuery = value instanceof Map<?, ?> map ? map : Map.of();
		if (resultQuery.isEmpty()) {
			if (executeResource != null && Boolean.TRUE.equals(executeResource.getIdempotencyRequired())) {
				errors.add("idempotent execute node " + node.id() + " must define resultQuery");
			}
			return;
		}
		Long resourceVersionId = longValue(resultQuery.get("resourceVersionId"));
		AgentExecutionResourceVersion resource = versions.get(resourceVersionId);
		if (resource == null) {
			errors.add("execute node " + node.id() + " resultQuery references a tool version not bound to this Skill");
		}
		else if (!("READ".equals(resource.getAccessMode()) && "FLOW_ONLY".equals(resource.getExposureMode()))) {
			errors.add("execute node " + node.id() + " resultQuery must use READ + FLOW_ONLY tool");
		}
	}

	private DataAgentSkillVersion createDraft(DataAgentSkill skill, SkillSaveReq request) {
		DataAgentSkillVersion draft = new DataAgentSkillVersion();
		draft.setTenantId(skill.getTenantId());
		draft.setSkillId(skill.getId());
		draft.setVersionNo(versionMapper.nextVersionNo(skill.getId()));
		draft.setStatus(SkillVersionStatus.DRAFT.name());
		if (skill.getPublishedVersionId() != null) {
			DataAgentSkillVersion published = getPublished(skill, null);
			draft.setRuntimeConfig(StringUtils.hasText(published.getRuntimeConfig()) ? published.getRuntimeConfig() : "{}");
			draft.setAnalysisConfig(StringUtils.hasText(published.getAnalysisConfig()) ? published.getAnalysisConfig() : "{}");
		}
		applyDraft(draft, request);
		versionMapper.insert(draft);
		copyPublishedToolRefs(skill, draft);
		return draft;
	}

	private void copyPublishedToolRefs(DataAgentSkill skill, DataAgentSkillVersion draft) {
		if (skill.getPublishedVersionId() == null) {
			return;
		}
		List<DataAgentSkillToolRef> copies = toolRefMapper.findBySkillVersionId(skill.getPublishedVersionId()).stream()
			.<DataAgentSkillToolRef>map(source -> DataAgentSkillToolRef.builder()
				.tenantId(source.getTenantId())
				.skillVersionId(draft.getId())
				.resourceVersionId(source.getResourceVersionId())
				.resourceKey(source.getResourceKey())
				.usage(source.getUsage())
				.status(source.getStatus())
				.displayOrder(source.getDisplayOrder())
				.extConfig(source.getExtConfig())
				.build())
			.toList();
		if (!copies.isEmpty()) {
			toolRefMapper.insertBatch(copies);
		}
	}

	private void applyToolRefsIfPresent(DataAgentSkill skill, DataAgentSkillVersion draft,
			List<SkillToolRefSaveDTO> requested) {
		if (requested == null) {
			return;
		}
		SkillExecutionMode mode = SkillExecutionMode.valueOf(versionExecutionMode(draft));
		if (mode != SkillExecutionMode.REACT && mode != SkillExecutionMode.FLOW && !requested.isEmpty()) {
			throw CheckedException.badRequest("Only REACT and FLOW Skills can bind tool versions");
		}
		Set<Long> ids = requested.stream().filter(Objects::nonNull).map(SkillToolRefSaveDTO::resourceVersionId)
			.filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
		Map<Long, AgentExecutionResourceVersion> versions = new LinkedHashMap<>();
		if (!ids.isEmpty()) {
			for (AgentExecutionResourceVersion version : resourceVersionMapper.selectList(
					com.sn68.agent.framework.db.mybatisplus.wrap.Wraps.<AgentExecutionResourceVersion>lbQ()
						.in(AgentExecutionResourceVersion::getId, ids)
						.eq(AgentExecutionResourceVersion::getStatus, SkillVersionStatus.PUBLISHED.name())
						.eq(AgentExecutionResourceVersion::getDeleted, false))) {
				versions.put(version.getId(), version);
			}
		}
		Map<Long, DataAgentSkillToolRef> existing = toolRefMapper.findBySkillVersionId(draft.getId()).stream()
			.filter(item -> item.getResourceVersionId() != null)
			.collect(java.util.stream.Collectors.toMap(DataAgentSkillToolRef::getResourceVersionId,
					item -> item, (left, right) -> left));
		List<DataAgentSkillToolRef> replacement = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		int generatedOrder = 0;
		for (SkillToolRefSaveDTO item : requested) {
			if (item == null) {
				continue;
			}
			Long resourceVersionId = item.resourceVersionId();
			if (resourceVersionId == null || !seen.add(resourceVersionId)) {
				throw CheckedException.badRequest("Tool version reference is missing or duplicated");
			}
			AgentExecutionResourceVersion version = versions.get(resourceVersionId);
			if (version == null) {
				throw CheckedException.notFound("Published tool version does not exist: " + resourceVersionId);
			}
			validateToolRefScope(skill, version);
			validateToolRefMode(mode, version);
			DataAgentSkillToolRef old = existing.get(resourceVersionId);
			replacement.add(DataAgentSkillToolRef.builder()
				.tenantId(skill.getTenantId())
				.skillVersionId(draft.getId())
				.resourceVersionId(resourceVersionId)
				.resourceKey(version.getResourceKey())
				.usage(trim(item.usage()))
				.status(firstText(old == null ? null : old.getStatus(), "enabled"))
				.displayOrder(item.displayOrder() == null ? generatedOrder += 10 : item.displayOrder())
				.extConfig(old == null ? null : old.getExtConfig())
				.build());
		}
		toolRefMapper.deleteBySkillVersionId(draft.getId());
		if (!replacement.isEmpty()) {
			toolRefMapper.insertBatch(replacement);
		}
	}

	private void validateToolRefScope(DataAgentSkill skill, AgentExecutionResourceVersion version) {
		if (skill.getTenantId() == null && version.getTenantId() != null) {
			throw CheckedException.badRequest("A platform Skill cannot bind a tenant tool version");
		}
		if (skill.getTenantId() != null && version.getTenantId() != null
				&& !Objects.equals(skill.getTenantId(), version.getTenantId())) {
			throw CheckedException.badRequest("A Skill cannot bind another tenant's tool version");
		}
	}

	private void validateToolRefMode(SkillExecutionMode mode, AgentExecutionResourceVersion version) {
		if ("WRITE".equals(version.getAccessMode()) && "MODEL".equals(version.getExposureMode())) {
			throw CheckedException.badRequest("WRITE + MODEL tool configuration is forbidden");
		}
		if (mode == SkillExecutionMode.REACT
				&& !("READ".equals(version.getAccessMode()) && "MODEL".equals(version.getExposureMode()))) {
			throw CheckedException.badRequest("REACT Skill can bind only READ + MODEL tools");
		}
	}

	private ToolVersionOptionDTO toToolVersionOption(Map<String, Object> row, String tenantId, String mode,
			boolean selected) {
		Long id = rowLong(row, "resource_version_id");
		if (id == null) {
			return null;
		}
		String resourceTenantId = rowText(row, "resource_tenant_id");
		boolean visibleScope = resourceTenantId == null || Objects.equals(resourceTenantId, tenantId);
		boolean publishedVersion = SkillVersionStatus.PUBLISHED.name()
			.equals(rowText(row, "resource_version_status")) && !rowBoolean(row, "resource_version_deleted");
		boolean active = rowBoolean(row, "resource_enabled") && !rowBoolean(row, "resource_deleted")
				&& !"disabled".equalsIgnoreCase(rowText(row, "resource_status"));
		String accessMode = rowText(row, "access_mode");
		String exposureMode = rowText(row, "exposure_mode");
		boolean modeAllowed = SkillExecutionMode.REACT.name().equals(mode)
				? "READ".equals(accessMode) && "MODEL".equals(exposureMode)
				: !("WRITE".equals(accessMode) && "MODEL".equals(exposureMode));
		boolean selectable = publishedVersion && visibleScope && active && modeAllowed;
		String unavailableReason = selectable ? null
				: !publishedVersion ? "工具版本已停用或已删除"
						: !visibleScope ? "不属于当前 Skill 可见范围"
						: !active ? "工具已停用或已删除" : "当前执行模式不支持";
		return new ToolVersionOptionDTO(id, rowText(row, "resource_key"),
				firstText(rowText(row, "resource_name"), rowText(row, "resource_key")), rowInteger(row, "version_no"),
				accessMode, exposureMode, selectable, selected && !selectable ? unavailableReason : null);
	}

	private Long rowLong(Map<String, Object> row, String key) {
		Object value = row.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(value.toString());
		}
		catch (NumberFormatException ex) {
			log.warn("Unable to parse a long from the result row, treating it as null. column={}, value={}", key,
					value);
			return null;
		}
	}

	private Integer rowInteger(Map<String, Object> row, String key) {
		Long value = rowLong(row, key);
		return value == null ? null : value.intValue();
	}

	private boolean rowBoolean(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
	}

	private String rowText(Map<String, Object> row, String key) {
		Object value = row.get(key);
		return value == null ? null : value.toString();
	}

	private void applyDraft(DataAgentSkillVersion draft, SkillSaveReq request) {
		draft.setSkillName(request.skillName().trim());
		draft.setDescription(trim(request.description()));
		draft.setCategory(trim(request.category()));
		draft.setDisplayOrder(request.displayOrder() == null
				? (draft.getDisplayOrder() == null ? 0 : draft.getDisplayOrder()) : request.displayOrder());
		draft.setSkillKind(normalizeKind(request.skillKind()));
		draft.setExecutionMode(normalizeMode(request.executionMode()));
		draft.setSkillMarkdown(request.skillMarkdown());
		draft.setRouteRules(routeRulesService.toJson(routeRulesService.normalize(request.routeRules())));
		draft.setKnowledgeConfig(writeJson(sanitizeDraftKnowledgeConfig(request.knowledgeConfig())));
		draft.setReactConfig(writeJson(request.reactConfig()));
		if (request.flowRuntimeConfig() != null) {
			draft.setFlowRuntimeConfig(writeJson(request.flowRuntimeConfig()));
		}
		if (request.flowPolicyConfig() != null) {
			draft.setFlowPolicyConfig(writeJson(request.flowPolicyConfig()));
		}
		boolean topVariablesPresent = request.variablesSchema() != null;
		Map<String, Object> topVariables = topVariablesPresent ? request.variablesSchema() : Map.of();
		Map<String, Object> flowDefinition = request.flowDefinition();
		if (flowDefinition != null) {
			Map<String, Object> normalizedFlow = new LinkedHashMap<>(flowDefinition);
			boolean nestedVariablesPresent = flowDefinition.containsKey("variablesSchema");
			Map<String, Object> nestedVariables = mapValue(flowDefinition.get("variablesSchema"));
			if (topVariablesPresent && nestedVariablesPresent && !nestedVariables.equals(topVariables)) {
				throw CheckedException.badRequest("FLOW variablesSchema and top-level variablesSchema must match");
			}
			Map<String, Object> effectiveVariables = nestedVariablesPresent ? nestedVariables : topVariables;
			normalizedFlow.put("variablesSchema", effectiveVariables);
			draft.setFlowDefinition(writeNullableJson(normalizedFlow));
			draft.setVariablesSchema(writeJson(effectiveVariables));
		}
		else {
			draft.setFlowDefinition(null);
			draft.setVariablesSchema(writeJson(topVariables));
		}
		if (draft.getFlowRuntimeConfig() == null) {
			draft.setFlowRuntimeConfig("{}");
		}
		if (draft.getFlowPolicyConfig() == null) {
			draft.setFlowPolicyConfig("{}");
		}
		draft.setResourceRequirement(writeJson(request.resourceRequirement()));
		draft.setInputSchema(writeJson(request.inputSchema()));
		draft.setOutputSchema(writeJson(request.outputSchema()));
		// Resource identities and whitelists are created only when publishing from Skill-owned rows.
		draft.setDatasourceConfig(writeJson(Map.of()));
		draft.setSemanticConfig(writeJson(Map.of()));
		if (request.runtimeConfig() != null) {
			draft.setRuntimeConfig(writeJson(request.runtimeConfig()));
		}
		if (request.analysisConfig() != null) {
			draft.setAnalysisConfig(writeJson(request.analysisConfig()));
		}
		else if (draft.getAnalysisConfig() == null) {
			draft.setAnalysisConfig("{}");
		}
		draft.setChecksum(checksum(draft));
	}

	private void applyResourceSnapshot(DataAgentSkillVersion draft, ResourceSnapshot snapshot) {
		draft.setDatasourceConfig(writeJson(snapshot.datasourceConfig()));
		draft.setSemanticConfig(writeJson(snapshot.semanticConfig()));
		draft.setKnowledgeConfig(writeJson(snapshot.knowledgeConfig()));
		draft.setAnalysisConfig(writeJson(snapshot.analysisConfig()));
	}

	private Map<String, Object> sanitizeDraftKnowledgeConfig(Map<String, Object> config) {
		if (config == null || config.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		copyIfPresent(config, result, "topK");
		copyIfPresent(config, result, "similarityThreshold");
		return Map.copyOf(result);
	}

	private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
		if (source.containsKey(key) && source.get(key) != null) {
			target.put(key, source.get(key));
		}
	}

	private SkillDetailResp toDetail(DataAgentSkill skill, DataAgentSkillVersion selected) {
		List<SkillDetailResp.VersionSummary> versions = versionMapper.findBySkillId(skill.getId()).stream()
			.map(item -> new SkillDetailResp.VersionSummary(item.getId(), item.getVersionNo(), item.getStatus(),
					item.getChecksum(), item.getPublishedAt()))
			.toList();
		boolean publishedSnapshot = selected != null && SkillVersionStatus.PUBLISHED.name().equals(selected.getStatus());
		SkillDetailResp.Version version = selected == null ? null : new SkillDetailResp.Version(selected.getId(),
				selected.getVersionNo(), selected.getStatus(), selected.getSkillName(), selected.getDescription(),
				selected.getCategory(), selected.getDisplayOrder(), selected.getSkillKind(), selected.getExecutionMode(),
				selected.getSkillMarkdown(), routeRulesService.toResponseDTO(routeRulesService.parse(selected.getRouteRules())),
				readMap(selected.getKnowledgeConfig()), readMap(selected.getReactConfig()),
				readMap(selected.getFlowDefinition()), readMap(selected.getVariablesSchema()),
				readMap(selected.getFlowRuntimeConfig()), readMap(selected.getFlowPolicyConfig()),
				readMap(selected.getResourceRequirement()), readMap(selected.getInputSchema()), readMap(selected.getOutputSchema()),
				publishedSnapshot ? readMap(selected.getDatasourceConfig()) : Map.of(),
				publishedSnapshot ? readMap(selected.getSemanticConfig()) : Map.of(), readMap(selected.getRuntimeConfig()),
				readMap(selected.getAnalysisConfig()), selected.getChecksum(), selected.getPublishedAt());
		return new SkillDetailResp(skill.getId(), skill.getTenantId(), skill.getSkillCode(), skill.getSkillName(),
				skill.getDescription(), skill.getCategory(), skill.getScope(), skill.getSkillKind(), skill.getExecutionMode(),
				skill.getStatus(), skill.getDisplayOrder(), skill.getLatestDraftVersionId(), skill.getPublishedVersionId(),
				version, versions);
	}

	private DataAgentSkillVersion selectedVersion(DataAgentSkill skill) {
		Long selectedId = skill.getLatestDraftVersionId() == null ? skill.getPublishedVersionId()
				: skill.getLatestDraftVersionId();
		return selectedId == null ? null : versionMapper.selectById(selectedId);
	}

	private DataAgentSkill requireVisible(String skillCode) {
		DataAgentSkill skill = skillMapper.findVisibleByCode(requireCode(skillCode), effectiveTenantId());
		if (skill == null) {
			throw CheckedException.notFound("Skill does not exist: " + skillCode);
		}
		return skill;
	}

	private void validateCatalogRequest(SkillSaveReq request, String currentCode) {
		if (request == null) {
			throw CheckedException.badRequest("Skill request is required");
		}
		String code = currentCode == null ? requireCode(request.skillCode()) : requireCode(currentCode);
		if (!SKILL_CODE.matcher(code).matches()) {
			throw CheckedException.badRequest("Skill code must use lowercase letters, digits and hyphens");
		}
		if (!StringUtils.hasText(request.skillName())) {
			throw CheckedException.badRequest("Skill name is required");
		}
		String kind = normalizeKind(request.skillKind());
		String mode = normalizeMode(request.executionMode());
		validateKindMode(kind, mode);
		if (currentCode == null) {
			normalizeScope(request.scope());
		}
	}

	private String checksum(DataAgentSkillVersion version) {
		return SecureUtil.sha256(String.join("\n", nullToEmpty(version.getSkillName()),
				nullToEmpty(version.getDescription()), nullToEmpty(version.getCategory()),
				nullToEmpty(version.getDisplayOrder() == null ? null : version.getDisplayOrder().toString()),
				nullToEmpty(version.getSkillKind()),
				nullToEmpty(version.getExecutionMode()), nullToEmpty(version.getSkillMarkdown()),
				nullToEmpty(version.getRouteRules()), nullToEmpty(version.getKnowledgeConfig()),
				nullToEmpty(version.getReactConfig()), nullToEmpty(version.getFlowDefinition()),
				nullToEmpty(version.getVariablesSchema()), nullToEmpty(version.getFlowRuntimeConfig()),
				nullToEmpty(version.getFlowPolicyConfig()), nullToEmpty(version.getResourceRequirement()),
				nullToEmpty(version.getInputSchema()), nullToEmpty(version.getOutputSchema()),
				nullToEmpty(version.getDatasourceConfig()), nullToEmpty(version.getSemanticConfig()),
				nullToEmpty(version.getRuntimeConfig()), nullToEmpty(version.getAnalysisConfig())));
	}

	private Map<String, Object> readMap(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : map;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid persisted Skill JSON", ex);
		}
	}

	private String writeJson(Map<String, Object> value) {
		return write(value == null ? Map.of() : value);
	}

	private String writeNullableJson(Map<String, Object> value) {
		return value == null ? null : write(value);
	}

	private String write(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("Skill JSON configuration is invalid");
		}
	}

	private String normalizeKind(String value) {
		try {
			return com.sn68.agent.dataagent.skill.SkillKind
				.valueOf(requireText(value, "skillKind").toUpperCase(Locale.ROOT)).name();
		}
		catch (IllegalArgumentException ex) {
			throw CheckedException.badRequest("Unsupported Skill kind: " + value);
		}
	}

	private String normalizeMode(String value) {
		try {
			return SkillExecutionMode.valueOf(requireText(value, "executionMode").toUpperCase(Locale.ROOT)).name();
		}
		catch (IllegalArgumentException ex) {
			throw CheckedException.badRequest("Unsupported Skill execution mode: " + value);
		}
	}

	private String versionSkillKind(DataAgentSkillVersion version) {
		return normalizeKind(version == null ? null : version.getSkillKind());
	}

	private String versionExecutionMode(DataAgentSkillVersion version) {
		return normalizeMode(version == null ? null : version.getExecutionMode());
	}

	private void validateKindMode(String skillKind, String executionMode) {
		boolean valid = (SkillKind.QA.name().equals(skillKind) && SkillExecutionMode.KNOWLEDGE.name().equals(executionMode))
				|| (SkillKind.QUERY.name().equals(skillKind)
						&& (SkillExecutionMode.DETERMINISTIC.name().equals(executionMode)
								|| SkillExecutionMode.REACT.name().equals(executionMode)))
				|| (SkillKind.ACTION.name().equals(skillKind) && SkillExecutionMode.FLOW.name().equals(executionMode))
				|| (SkillKind.ORCHESTRATION.name().equals(skillKind)
						&& SkillExecutionMode.FLOW.name().equals(executionMode));
		if (!valid) {
			throw CheckedException.badRequest("Unsupported Skill kind and execution mode combination: " + skillKind
					+ " + " + executionMode);
		}
	}

	private String normalizeScope(String value) {
		if (!StringUtils.hasText(value)) {
			return SkillScope.TENANT.name();
		}
		String normalized = requireText(value, "scope").toUpperCase(Locale.ROOT);
		if (SkillScope.TENANT.name().equals(normalized) || "PLATFORM".equals(normalized)) {
			return SkillScope.TENANT.name();
		}
		throw CheckedException.badRequest("Unsupported Skill scope: " + value);
	}

	private String tenantIdForScope(String scope) {
		normalizeScope(scope);
		String tenantId = effectiveTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required for a tenant Skill");
		}
		return tenantId;
	}

	private String effectiveTenantId() {
		return skillManagementTenantService.effectiveTenantId();
	}

	private String requireEffectiveTenantId() {
		String tenantId = effectiveTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("Tenant context is required for a tenant Skill");
		}
		return tenantId;
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			// 取不到用户即丢失发布人归属，审计链路会缺一环
			log.warn("Unable to resolve current user id, publish attribution will be empty", ex);
			return null;
		}
	}

	private String requireCode(String value) {
		return requireText(value, "skillCode").trim();
	}

	private String requireText(String value, String name) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest(name + " is required");
		}
		return value.trim();
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value != null) {
			try {
				return Long.valueOf(String.valueOf(value));
			}
			catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}

	private Map<String, Object> mapValue(Object value) {
		if (!(value instanceof Map<?, ?> map)) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		map.forEach((key, item) -> result.put(String.valueOf(key), item));
		return result;
	}

	private Map<String, Object> importRouteRules(Object value) {
		if (value == null) {
			return Map.of();
		}
		if (!(value instanceof Map<?, ?>)) {
			throw CheckedException.badRequest("Imported routeRules must be a JSON object");
		}
		return mapValue(value);
	}

	private List<?> listValue(Object value) {
		return value instanceof List<?> list ? list : List.of();
	}

	private String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private Integer integer(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		try {
			return value == null ? null : Integer.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
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

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
