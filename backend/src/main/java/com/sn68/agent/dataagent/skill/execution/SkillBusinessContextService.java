/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchHit;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchRequest;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchResult;
import com.sn68.agent.dataagent.agentscope.tool.semantic.SemanticModelSearchService;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.DomainKnowledgeSearchRequest;
import com.sn68.agent.dataagent.service.knowledge.DomainKnowledgeSearchService.KnowledgeHit;
import com.sn68.agent.dataagent.skill.execution.SkillBusinessContext.BusinessKnowledgeEvidence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Prepares Skill-version-bound semantic and business knowledge once per request.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillBusinessContextService {

	private static final String RESOLUTION_NOT_CONFIGURED = "not_configured";

	private static final int MAX_SEMANTIC_HINTS = 5;

	private static final int DEFAULT_KNOWLEDGE_TOP_K = 3;

	private static final int MAX_KNOWLEDGE_TOP_K = 5;

	private static final int MAX_KNOWLEDGE_CHARS = 5000;

	private static final int MAX_KNOWLEDGE_ITEM_CHARS = 1000;

	private final SemanticModelSearchService semanticModelSearchService;

	private final DomainKnowledgeSearchService knowledgeSearchService;

	public SkillBusinessContext prepare(AgentRequest request, SkillVersionResources resources) {
		return prepare(request, resources, DEFAULT_KNOWLEDGE_TOP_K);
	}

	public SkillBusinessContext prepare(AgentRequest request, SkillVersionResources resources, int requestedTopK) {
		if (request == null || resources == null || resources.skillId() == null || resources.skillVersionId() == null) {
			return empty(request, resources);
		}
		SkillBusinessContext existing = request.getSkillBusinessContext();
		if (existing != null && existing.matches(resources.skillId(), resources.skillVersionId(), request.getQuery())) {
			return existing;
		}

		SemanticModelSearchResult semanticResult = loadSemanticHints(request, resources);
		List<SemanticModelSearchHit> semanticHints = filterSemanticHints(semanticResult, resources);
		KnowledgePreparation knowledge = loadBusinessKnowledge(request, resources, requestedTopK);
		int contextChars = semanticHints.stream().mapToInt(this::semanticChars).sum()
				+ knowledge.evidence().stream().mapToInt(item -> item.content().length()).sum();
		SkillBusinessContext prepared = new SkillBusinessContext(resources.skillId(), resources.skillVersionId(),
				request.getQuery(), resources.datasource() == null ? List.of() : resources.datasource().tables(),
				semanticResult == null ? RESOLUTION_NOT_CONFIGURED : semanticResult.getResolution(), semanticHints,
				knowledge.resolution(), knowledge.evidence(), knowledge.retrievedCount(), knowledge.duplicateCount(),
				contextChars, request.getTemporalInterval());
		request.setSkillBusinessContext(prepared);
		log.info("Skill business context prepared. skillId={}, skillVersionId={}, runtimeRequestId={}, "
				+ "semanticHitCount={}, knowledgeHitCount={}, duplicateKnowledgeCount={}, contextChars={}",
				resources.skillId(), resources.skillVersionId(), request.getRuntimeRequestId(), semanticHints.size(),
				knowledge.evidence().size(), knowledge.duplicateCount(), contextChars);
		return prepared;
	}

	/**
	 * QA Skills use a separate namespace from business knowledge. This method is
	 * intentionally not part of {@link #prepare}: QUERY/FLOW business context must
	 * never cause QA document retrieval or a legacy knowledge fallback.
	 */
	public SkillBusinessContext prepareSkillKnowledge(AgentRequest request,
			SkillVersionResources resources, int requestedTopK, Double similarityThreshold) {
		if (request == null || resources == null || resources.skillId() == null
				|| resources.skillVersionId() == null) {
			return empty(request, resources);
		}
		if (resources.skillKnowledgeIds().isEmpty()) {
			return empty(request, resources);
		}
		KnowledgePreparation knowledge = loadSkillKnowledge(request, resources, requestedTopK, similarityThreshold);
		int contextChars = knowledge.evidence().stream().mapToInt(item -> item.content().length()).sum();
		return new SkillBusinessContext(resources.skillId(), resources.skillVersionId(), request.getQuery(), List.of(),
				RESOLUTION_NOT_CONFIGURED, List.of(), knowledge.resolution(), knowledge.evidence(), knowledge.retrievedCount(),
				knowledge.duplicateCount(), contextChars, request.getTemporalInterval());
	}

	private SemanticModelSearchResult loadSemanticHints(AgentRequest request, SkillVersionResources resources) {
		if (resources.semanticModelIds().isEmpty()) {
			return SemanticModelSearchResult.builder().resolution(RESOLUTION_NOT_CONFIGURED).summary("No semantic model configured")
				.build();
		}
		SemanticModelSearchRequest searchRequest = new SemanticModelSearchRequest();
		searchRequest.setQuery(request.getQuery());
		return semanticModelSearchService.search(searchRequest, resources, request);
	}

	private List<SemanticModelSearchHit> filterSemanticHints(SemanticModelSearchResult result,
			SkillVersionResources resources) {
		if (result == null || result.getHits() == null || result.getHits().isEmpty() || resources.datasource() == null) {
			return List.of();
		}
		Set<String> allowedColumns = new LinkedHashSet<>();
		for (SkillVersionResources.TableScope table : resources.datasource().tables()) {
			for (String column : table.columns()) {
				allowedColumns.add(columnKey(table.table(), column));
			}
		}
		return result.getHits().stream()
			.filter(java.util.Objects::nonNull)
			.filter(hit -> allowedColumns.contains(columnKey(hit.getTableName(), hit.getColumnName())))
			.limit(MAX_SEMANTIC_HINTS)
			.toList();
	}

	private KnowledgePreparation loadBusinessKnowledge(AgentRequest request, SkillVersionResources resources,
			int requestedTopK) {
		List<Long> allowedKnowledgeIds = resources.businessKnowledgeIds();
		if (allowedKnowledgeIds.isEmpty()) {
			return new KnowledgePreparation(RESOLUTION_NOT_CONFIGURED, List.of(), 0, 0);
		}
		int topK = Math.max(1, Math.min(requestedTopK, MAX_KNOWLEDGE_TOP_K));
		var result = knowledgeSearchService.searchSkillBusinessKnowledge(resources.skillId(), allowedKnowledgeIds,
				new DomainKnowledgeSearchRequest(request.getQuery(), List.of("businessKnowledge"), topK, null), request);
		List<KnowledgeHit> hits = result == null || result.hits() == null ? List.of() : result.hits();
		Set<String> allowedIds = allowedKnowledgeIds.stream().map(String::valueOf)
			.collect(java.util.stream.Collectors.toSet());
		List<BusinessKnowledgeEvidence> evidence = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int duplicateCount = 0;
		int totalChars = 0;
		for (KnowledgeHit hit : hits) {
			if (hit == null || !"businessKnowledge".equalsIgnoreCase(hit.vectorType())
					|| !allowedIds.contains(hit.knowledgeId())) {
				continue;
			}
			String content = firstText(hit.snippet(), hit.summary());
			if (!StringUtils.hasText(content)) {
				continue;
			}
			String key = hit.knowledgeId() + "\n" + content;
			if (!seen.add(key)) {
				duplicateCount++;
				continue;
			}
			content = abbreviate(content, MAX_KNOWLEDGE_ITEM_CHARS);
			int remaining = MAX_KNOWLEDGE_CHARS - totalChars;
			if (remaining <= 0) {
				break;
			}
			content = abbreviate(content, remaining);
			evidence.add(new BusinessKnowledgeEvidence(evidence.size() + 1,
					firstText(hit.title(), "Business reference"), "businessKnowledge", content));
			totalChars += content.length();
			if (evidence.size() >= topK) {
				break;
			}
		}
		return new KnowledgePreparation(result == null ? "no_hit" : result.resolution(), List.copyOf(evidence),
				hits.size(), duplicateCount);
	}

	private KnowledgePreparation loadSkillKnowledge(AgentRequest request, SkillVersionResources resources,
			int requestedTopK, Double similarityThreshold) {
		int topK = Math.max(1, Math.min(requestedTopK, MAX_KNOWLEDGE_TOP_K));
		var result = knowledgeSearchService.searchSkillKnowledge(resources.skillId(), resources.skillKnowledgeIds(),
				new DomainKnowledgeSearchRequest(request.getQuery(), List.of("DOCUMENT", "QA", "FAQ"), topK,
						similarityThreshold), request);
		List<KnowledgeHit> hits = result == null || result.hits() == null ? List.of() : result.hits();
		Set<String> allowedIds = resources.skillKnowledgeIds().stream().map(String::valueOf)
			.collect(java.util.stream.Collectors.toSet());
		List<BusinessKnowledgeEvidence> evidence = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		int duplicateCount = 0;
		int totalChars = 0;
		for (KnowledgeHit hit : hits) {
			if (hit == null || !"skillKnowledge".equalsIgnoreCase(hit.vectorType())
					|| !allowedIds.contains(hit.knowledgeId())) {
				continue;
			}
			String content = firstText(hit.snippet(), hit.summary());
			if (!StringUtils.hasText(content)) {
				continue;
			}
			String key = hit.knowledgeId() + "\n" + content;
			if (!seen.add(key)) {
				duplicateCount++;
				continue;
			}
			int remaining = MAX_KNOWLEDGE_CHARS - totalChars;
			if (remaining <= 0) {
				break;
			}
			content = abbreviate(content, Math.min(MAX_KNOWLEDGE_ITEM_CHARS, remaining));
			evidence.add(new BusinessKnowledgeEvidence(evidence.size() + 1,
					firstText(hit.title(), "Knowledge reference"), "skillKnowledge", content));
			totalChars += content.length();
			if (evidence.size() >= topK) {
				break;
			}
		}
		return new KnowledgePreparation(result == null ? "no_hit" : result.resolution(), List.copyOf(evidence),
			hits.size(), duplicateCount);
	}

	private SkillBusinessContext empty(AgentRequest request, SkillVersionResources resources) {
		return new SkillBusinessContext(resources == null ? null : resources.skillId(),
				resources == null ? null : resources.skillVersionId(), request == null ? null : request.getQuery(), List.of(),
				RESOLUTION_NOT_CONFIGURED, List.of(), RESOLUTION_NOT_CONFIGURED, List.of(), 0, 0, 0,
				request == null ? null : request.getTemporalInterval());
	}

	private int semanticChars(SemanticModelSearchHit hit) {
		return firstText(hit.getTableName()).length() + firstText(hit.getColumnName()).length()
				+ firstText(hit.getBusinessName()).length() + firstText(hit.getBusinessDescription()).length();
	}

	private String columnKey(String table, String column) {
		return firstText(table).toLowerCase(Locale.ROOT) + "." + firstText(column).toLowerCase(Locale.ROOT);
	}

	private String abbreviate(String value, int maxChars) {
		String text = firstText(value);
		return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars - 3)) + "...";
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private record KnowledgePreparation(String resolution, List<BusinessKnowledgeEvidence> evidence,
			int retrievedCount, int duplicateCount) {
	}

}
