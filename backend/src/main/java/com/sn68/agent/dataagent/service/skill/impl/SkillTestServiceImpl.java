/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.dto.routing.RouteRulesDTO;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillFlowTestResult;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestReq;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestResult;
import com.sn68.agent.dataagent.flow.FlowDefinitionValidator;
import com.sn68.agent.dataagent.flow.definition.FlowDefinition;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.routing.RouteRiskResolver;
import com.sn68.agent.dataagent.routing.RouteScorer;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.dataagent.service.skill.SkillTestService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Evaluates the current editable Skill version without executing runtime side effects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTestServiceImpl implements SkillTestService {

	private final SkillCatalogService skillCatalogService;

	private final RouteScorer routeScorer;

	private final RouteRiskResolver routeRiskResolver;

	private final FlowDefinitionValidator flowDefinitionValidator;

	private final ObjectMapper objectMapper;

	@Override
	public SkillRouteTestResult testRoute(String skillCode, SkillRouteTestReq request) {
		if (request == null || !StringUtils.hasText(request.query())) {
			throw CheckedException.badRequest("Route test query is required");
		}
		SkillDetailResp detail = skillCatalogService.detail(skillCode);
		requireDraft(detail);
		RouteRules rules = responseRules(detail.version());
		DataAgentSkillVersion version = toEntity(detail);
		SkillDetailResp.Version source = detail.version();
		RouteCandidate candidate = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, detail.id(),
				detail.version().id(), null), detail.tenantId(), null,
				source.skillName() == null ? detail.skillName() : source.skillName(),
				source.description() == null ? detail.description() : source.description(),
				source.skillKind() == null ? detail.skillKind() : source.skillKind(),
				source.executionMode() == null ? detail.executionMode() : source.executionMode(), rules,
				routeRiskResolver.resolveSkill(version),
				source.displayOrder() == null ? (detail.displayOrder() == null ? 0 : detail.displayOrder())
					: source.displayOrder(), null, null, null, null);
		RouteContext context = new RouteContext(detail.tenantId(), null, null, null, null, "route-test",
				request.query(), null, null, null, Instant.now().plusSeconds(1), 1);
		ScoredCandidate result = routeScorer.score(context, List.of(candidate)).get(0);
		String reasonCode = result.excluded() ? "HARD_EXCLUDED" : result.exact() ? "EXACT"
				: result.relevant() ? "LEXICAL_SIGNAL" : "NO_RELEVANT_SIGNAL";
		return new SkillRouteTestResult(result.relevant(), detail.skillCode(), reasonCode, result.lexicalScore(),
				result.exact(), result.excluded(), result.matchedSignals());
	}

	@Override
	public SkillFlowTestResult testFlow(String skillCode) {
		SkillDetailResp detail = skillCatalogService.detail(skillCode);
		requireDraft(detail);
		if (detail.version() == null || !"FLOW".equalsIgnoreCase(detail.version().executionMode())) {
			throw CheckedException.badRequest("Only FLOW Skills support a FLOW dry-run");
		}
		Map<String, Object> rawDefinition = detail.version() == null ? Map.of() : detail.version().flowDefinition();
		List<String> errors = flowDefinitionValidator.validate(rawDefinition);
		FlowDefinition definition = convert(rawDefinition);
		List<SkillFlowTestResult.NodePreview> nodes = definition == null || definition.nodes() == null ? List.of()
				: definition.nodes().stream().filter(node -> node != null).map(this::preview).toList();
		return new SkillFlowTestResult(errors.isEmpty(), definition == null ? null : definition.startNode(), nodes,
				errors);
	}

	private FlowDefinition convert(Map<String, Object> rawDefinition) {
		try {
			return objectMapper.convertValue(rawDefinition, FlowDefinition.class);
		}
		catch (IllegalArgumentException ex) {
			// 返回 null 只会让调用方知道"转换失败"，丢掉了具体是哪个字段不匹配
			log.warn("Unable to convert the raw FLOW definition, the test preview will be empty", ex);
			return null;
		}
	}

	private void requireDraft(SkillDetailResp detail) {
		if (detail.latestDraftVersionId() == null || detail.version() == null
				|| !detail.latestDraftVersionId().equals(detail.version().id())) {
			throw CheckedException.badRequest("Modify the Skill to create a draft before testing");
		}
	}

	private DataAgentSkillVersion toEntity(SkillDetailResp detail) {
		SkillDetailResp.Version source = detail.version();
		DataAgentSkillVersion version = new DataAgentSkillVersion();
		version.setId(source.id());
		version.setSkillId(detail.id());
		version.setSkillName(source.skillName() == null ? detail.skillName() : source.skillName());
		version.setDescription(source.description() == null ? detail.description() : source.description());
		version.setCategory(source.category() == null ? detail.category() : source.category());
		version.setDisplayOrder(source.displayOrder() == null ? detail.displayOrder() : source.displayOrder());
		version.setSkillKind(source.skillKind() == null ? detail.skillKind() : source.skillKind());
		version.setExecutionMode(source.executionMode() == null ? detail.executionMode() : source.executionMode());
		return version;
	}

	private RouteRules responseRules(SkillDetailResp.Version version) {
		if (version == null || version.routeRules() == null) {
			return RouteRules.empty();
		}
		RouteRulesDTO rules = version.routeRules();
		return new RouteRules(rules.exact(), rules.phrases(), rules.aliases(), rules.positiveExamples(),
				rules.positivePatterns(), rules.negativeExamples(), rules.hardExcludes(),
				rules.hardExcludePatterns(), rules.allowFlowAutoSelect());
	}

	private SkillFlowTestResult.NodePreview preview(FlowNode node) {
		String type = normalize(node.type());
		String behavior = switch (type) {
			case "resolve" -> "READ_TOOL";
			case "execute" -> "BLOCKED_WRITE";
			case "collect", "select", "confirm" -> "WAIT_USER";
			case "end", "error" -> "TERMINAL";
			default -> "LOCAL";
		};
		List<String> branches = node.branches() == null ? List.of() : node.branches().stream()
			.filter(branch -> branch != null && StringUtils.hasText(branch.next()))
			.map(branch -> branch.next().trim())
			.toList();
		return new SkillFlowTestResult.NodePreview(node.id(), type, behavior, node.next(), branches);
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
	}

}
