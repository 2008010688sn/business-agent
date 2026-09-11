/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.MemoryAuthorizationAdvisor;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/** Skill-version-bound business and QA knowledge retrieval. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DomainKnowledgeSearchServiceImpl implements DomainKnowledgeSearchService {

	private static final int DEFAULT_TOP_K = 5;

	private static final int MAX_TOP_K = 8;

	private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.2D;

	private static final int MAX_SUMMARY_LENGTH = 180;

	/**
	 * 片段上限对齐下游 {@code SkillBusinessContextService.MAX_KNOWLEDGE_ITEM_CHARS}（1000 字），也与
	 * 分块上限（1000 token，中文约 500-1000 字）同量级，一个 chunk 基本能整条带走。
	 *
	 * <p>此前是 320，比下游预算小三倍，那道预算因此永远轮不到生效，而向量命中的 chunk 只有开头能进
	 * 提示词。总量仍由下游的 5000 字预算兜底。
	 */
	private static final int MAX_SNIPPET_LENGTH = 1000;

	private static final String ELLIPSIS = "...";

	/** 词边界：中英文标点与空白，与 {@code LongTermMemoryRecallService} 的分词口径一致。 */
	private static final String TERM_DELIMITERS = "[\\s,，。；;:：!?！？、]+";

	private static final int MIN_TERM_LENGTH = 2;

	private static final String CJK_BIGRAM = "[\\u4e00-\\u9fa5]{2}";

	private static final List<KeywordExpansionRule> QUERY_EXPANSION_RULES = List.of(
			new KeywordExpansionRule(List.of("生鲜", "冷链", "箱子", "保鲜", "冷藏", "保温", "果蔬", "冷冻"),
					"生鲜箱 冷链箱 保温箱 冷藏箱 果蔬箱 保鲜箱 冷链循环包装 可折叠循环箱 多温区 实时温度监控 产品系列 型号 产品 解决方案 核心产品 特色应用场景 包装方案"),
			new KeywordExpansionRule(List.of("产品", "商品", "方案", "解决方案"),
					"产品 解决方案 服务能力 核心产品 特色应用场景 包装方案"),
			new KeywordExpansionRule(List.of("公司", "企业", "介绍", "定位", "概况", "主营", "主业", "做什么", "做啥", "干什么", "干啥"),
					"公司概况 品牌定位 核心定位 使命愿景 总部地址 主营业务 服务能力"),
			new KeywordExpansionRule(List.of("联系", "邮箱", "合作", "商务", "销售", "市场", "媒体"),
					"联系 商务合作 销售对接 媒体合作 市场合作 邮箱"));

	private final AgentVectorStoreService agentVectorStoreService;

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final SkillKnowledgeMapper skillKnowledgeMapper;

	// PR-7 知识线接缝：检索前 PDP 判定（READ_KNOWLEDGE），SHADOW 默认只记录不拦截。
	private final MemoryAuthorizationAdvisor memoryAuthorizationAdvisor;

	private final AuthenticationContext authenticationContext;

	@Override
	public DomainKnowledgeSearchResult searchSkillBusinessKnowledge(Long skillId, List<Long> allowedKnowledgeIds,
			DomainKnowledgeSearchRequest request, @Nullable AgentRequest agentRequest) {
		Assert.notNull(skillId, "SkillId cannot be empty");
		Assert.notNull(request, "Search request cannot be null");
		String query = requireText(request.query());
		Set<Long> allowedIds = normalizedIds(allowedKnowledgeIds);
		requireBusinessKnowledgeSnapshot(skillId, allowedIds, agentRequest);
		if (suppressKnowledgeRetrievalByPolicy(skillId, agentRequest)) {
			return record(agentRequest, List.of(), List.of(), "no_match");
		}
		if (allowedIds.isEmpty()) {
			return record(agentRequest, List.of(), List.of(), "no_match");
		}

		Map<Long, BusinessKnowledge> knowledgeById = new LinkedHashMap<>();
		businessKnowledgeMapper.selectByIds(List.copyOf(allowedIds)).stream()
			.filter(Objects::nonNull)
			.filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
			.filter(item -> Objects.equals(skillId, item.getSkillId()))
			.filter(item -> Boolean.TRUE.equals(item.getIsRecall()))
			.filter(item -> item.getEmbeddingStatus() == EmbeddingStatus.COMPLETED)
			.forEach(item -> knowledgeById.put(item.getId(), item));
		warnOnUnusableRows(skillId, DocumentMetadataConstant.BUSINESS_TERM, allowedIds, knowledgeById.keySet());
		List<Document> documents = agentVectorStoreService.getDocumentsForSkill(String.valueOf(skillId),
				buildVectorQuery(query), DocumentMetadataConstant.BUSINESS_TERM, normalizeTopK(request.topK()),
				resolveEffectiveThreshold(query, normalizeThreshold(request.similarityThreshold())),
				knowledgeById.keySet());
		List<KnowledgeHit> hits = new ArrayList<>();
		for (Document document : safeDocuments(documents)) {
			Long knowledgeId = asLong(document.getMetadata().get(DocumentMetadataConstant.DB_BUSINESS_TERM_ID));
			BusinessKnowledge knowledge = knowledgeById.get(knowledgeId);
			if (knowledge == null) {
				continue;
			}
			hits.add(new KnowledgeHit("businessKnowledge", String.valueOf(knowledgeId), knowledge.getBusinessTerm(),
					abbreviate(knowledge.getDescription(), MAX_SUMMARY_LENGTH),
					snippetAroundHit(document.getText(), query, MAX_SNIPPET_LENGTH),
					"businessKnowledge#" + knowledgeId, null));
		}
		List<String> warnings = hits.isEmpty() ? List.of("未检索到匹配的业务知识，请缩短问题或换一种业务说法重试。") : List.of();
		return record(agentRequest, hits, warnings, hits.isEmpty() ? "no_match" : "matched");
	}

	@Override
	public DomainKnowledgeSearchResult searchSkillKnowledge(Long skillId, List<Long> allowedKnowledgeIds,
			DomainKnowledgeSearchRequest request, @Nullable AgentRequest agentRequest) {
		Assert.notNull(skillId, "SkillId cannot be empty");
		Assert.notNull(request, "Search request cannot be null");
		String query = requireText(request.query());
		Set<Long> allowedIds = normalizedIds(allowedKnowledgeIds);
		requireSkillKnowledgeSnapshot(skillId, allowedIds, agentRequest);
		if (suppressKnowledgeRetrievalByPolicy(skillId, agentRequest)) {
			return record(agentRequest, List.of(), List.of(), "no_match");
		}
		if (allowedIds.isEmpty()) {
			return record(agentRequest, List.of(), List.of(), "no_match");
		}

		Map<Long, SkillKnowledge> knowledgeById = new LinkedHashMap<>();
		skillKnowledgeMapper.selectByIds(List.copyOf(allowedIds)).stream()
			.filter(Objects::nonNull)
			.filter(item -> !Boolean.TRUE.equals(item.getDeleted()))
			.filter(item -> Objects.equals(skillId, item.getSkillId()))
			.filter(item -> Boolean.TRUE.equals(item.getIsRecall()))
			.filter(item -> item.getEmbeddingStatus() == EmbeddingStatus.COMPLETED)
			.forEach(item -> knowledgeById.put(item.getId(), item));
		warnOnUnusableRows(skillId, DocumentMetadataConstant.SKILL_KNOWLEDGE, allowedIds, knowledgeById.keySet());
		List<Document> documents = agentVectorStoreService.getDocumentsForSkill(String.valueOf(skillId),
				buildVectorQuery(query), DocumentMetadataConstant.SKILL_KNOWLEDGE, normalizeTopK(request.topK()),
				normalizeThreshold(request.similarityThreshold()), knowledgeById.keySet());
		List<KnowledgeHit> hits = new ArrayList<>();
		for (Document document : safeDocuments(documents)) {
			Long knowledgeId = asLong(document.getMetadata().get(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID));
			SkillKnowledge knowledge = knowledgeById.get(knowledgeId);
			if (knowledge == null) {
				continue;
			}
			String title = firstNonBlank(knowledge.getTitle(), knowledge.getQuestion(), "Knowledge resource");
			String summary = abbreviate(firstNonBlank(knowledge.getContent(), knowledge.getQuestion()),
					MAX_SUMMARY_LENGTH);
			String snippet = snippetAroundHit(
					firstNonBlank(document.getText(), knowledge.getContent(), knowledge.getQuestion()), query,
					MAX_SNIPPET_LENGTH);
			hits.add(new KnowledgeHit(DocumentMetadataConstant.SKILL_KNOWLEDGE, String.valueOf(knowledgeId), title,
					summary, snippet, "skillKnowledge#" + knowledgeId,
					knowledge.getType() == null ? null : knowledge.getType().getCode()));
		}
		return record(agentRequest, hits, List.of(), hits.isEmpty() ? "no_match" : "matched");
	}

	/**
	 * PR-7 知识线 PDP 判定：ENFORCE 下策略拒绝（DENY）或 MODEL_ONLY（CAPABILITY_ALLOWED，
	 * 纯模型动作放行口径——知识检索属能力消费非纯模型动作）不检索，返回 no_match 空结果；
	 * SHADOW（默认）只记录影子决策，检索行为不变。ENFORCE 拒绝抛出的 CheckedException
	 * 在此兑价为空结果（fail-closed 且不打断对话主链路）。
	 */
	private boolean suppressKnowledgeRetrievalByPolicy(Long skillId, @Nullable AgentRequest agentRequest) {
		PepDecisionContext context = knowledgeDecisionContext(skillId, agentRequest);
		PepDecisionResult result;
		try {
			result = memoryAuthorizationAdvisor.checkMemoryAccess(context, Boolean.TRUE);
		}
		catch (CheckedException ex) {
			log.warn("知识检索被授权策略拒绝, 返回空结果. skillId={}, ownerId={}, tenantId={}, message={}", skillId,
					context.getOwnerId(), context.getTenantId(), ex.getMessage());
			return true;
		}
		if (result == null || result.getEffectiveMode() == null || !result.getEffectiveMode().enforce()) {
			return false;
		}
		if (!result.allowed()) {
			return true;
		}
		return DecisionReasonCode.CAPABILITY_ALLOWED == result.reasonCode();
	}

	/**
	 * 知识线决策上下文：owner 键贯穿（PR-7）——数字员工链路（ownerType=DIGITAL_EMPLOYEE）
	 * 按 DIGITAL_EMPLOYEE 主体键求值，其余按 DATA_AGENT/CALLER 原口径。
	 */
	private PepDecisionContext knowledgeDecisionContext(Long skillId, @Nullable AgentRequest agentRequest) {
		boolean digitalEmployee = agentRequest != null
				&& "DIGITAL_EMPLOYEE".equals(agentRequest.getOwnerType())
				&& agentRequest.getOwnerId() != null;
		Long ownerId = digitalEmployee ? agentRequest.getOwnerId() : parseLongQuietly(agentRequest == null
				? null : agentRequest.getAgentId());
		String subjectId = digitalEmployee ? String.valueOf(agentRequest.getOwnerId())
				: (agentRequest == null ? null : agentRequest.getUserIdSnapshot());
		return PepDecisionContext.builder()
			.tenantId(resolveKnowledgeTenantId(agentRequest))
			.ownerType(digitalEmployee ? AuthorizationOwnerType.DIGITAL_EMPLOYEE : AuthorizationOwnerType.DATA_AGENT)
			.ownerId(ownerId)
			.subjectKind(digitalEmployee ? SubjectKind.DIGITAL_EMPLOYEE : SubjectKind.CALLER)
			.subjectId(subjectId)
			.action(AuthorizationAction.READ_KNOWLEDGE)
			.build();
	}

	private String resolveKnowledgeTenantId(@Nullable AgentRequest agentRequest) {
		if (agentRequest != null && StringUtils.hasText(agentRequest.getTenantIdSnapshot())) {
			return agentRequest.getTenantIdSnapshot();
		}
		try {
			String tenantId = authenticationContext.tenantId();
			return StringUtils.hasText(tenantId) ? tenantId : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private Long parseLongQuietly(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 发布快照里列了知识，但没有一条是「存活 + 已召回 + 向量化完成」时，检索必然是空手而归。
	 *
	 * <p>这种情况以前只表现为一句「未检索到匹配」，与「问法不对」无法区分，运维也就无从知道该跑重建。
	 * 这里把差集直接说出来，代价是零——两个集合都已经在手上，不需要额外查询。
	 */
	private void warnOnUnusableRows(Long skillId, String vectorType, Set<Long> snapshotIds, Set<Long> usableIds) {
		if (snapshotIds.isEmpty() || !usableIds.isEmpty()) {
			return;
		}
		log.warn(
				"Skill {} has {} {} rows in its published snapshot but none of them is live, recalled and embedded; "
						+ "rebuild the Skill knowledge vectors. unusableIds={}",
				skillId, snapshotIds.size(), vectorType, snapshotIds);
	}

	private void requireBusinessKnowledgeSnapshot(Long skillId, Set<Long> allowedIds,
			@Nullable AgentRequest request) {
		SkillVersionResources resources = routedResources(skillId, request);
		if (!Objects.equals(Set.copyOf(resources.businessKnowledgeIds()), allowedIds)) {
			throw new IllegalArgumentException("Published Skill business knowledge snapshot is required");
		}
	}

	private void requireSkillKnowledgeSnapshot(Long skillId, Set<Long> allowedIds, @Nullable AgentRequest request) {
		SkillVersionResources resources = routedResources(skillId, request);
		if (!Objects.equals(Set.copyOf(resources.skillKnowledgeIds()), allowedIds)) {
			throw new IllegalArgumentException("Published Skill knowledge snapshot is required");
		}
	}

	private SkillVersionResources routedResources(Long skillId, @Nullable AgentRequest request) {
		SkillVersionResources resources = request == null ? null : request.getRoutedSkillResources();
		if (resources == null || !Objects.equals(request.getRoutedSkillId(), skillId)
				|| !Objects.equals(request.getRoutedSkillVersionId(), resources.skillVersionId())
				|| !Objects.equals(skillId, resources.skillId())) {
			throw new IllegalArgumentException("Published Skill resource snapshot is required");
		}
		return resources;
	}

	private DomainKnowledgeSearchResult record(@Nullable AgentRequest request, List<KnowledgeHit> hits,
			List<String> warnings, String resolution) {
		DomainKnowledgeSearchResult result = new DomainKnowledgeSearchResult(List.copyOf(hits), List.copyOf(warnings),
				resolution);
		if (request == null) {
			answerTraceExplainStore.recordKnowledgeSearch(result);
		}
		else {
			answerTraceExplainStore.recordKnowledgeSearch(request, result);
		}
		return result;
	}

	private Set<Long> normalizedIds(List<Long> ids) {
		Set<Long> result = new LinkedHashSet<>();
		if (ids != null) {
			ids.stream().filter(Objects::nonNull).forEach(result::add);
		}
		return Set.copyOf(result);
	}

	private List<Document> safeDocuments(List<Document> documents) {
		return documents == null ? List.of() : documents;
	}

	private String buildVectorQuery(String rawQuery) {
		String query = rawQuery.trim();
		for (KeywordExpansionRule rule : QUERY_EXPANSION_RULES) {
			if (rule.matches(query)) {
				return query + " " + rule.expansion();
			}
		}
		if (!isShortCodeQuery(query)) {
			return query;
		}
		return "业务术语 " + query + " 的定义、说明、同义词、FAQ、SOP、历史案例";
	}

	private double resolveEffectiveThreshold(String query, double threshold) {
		if (!isShortCodeQuery(query)) {
			return threshold;
		}
		return query.trim().length() <= 4 ? 0.0D : Math.min(threshold, 0.05D);
	}

	private boolean isShortCodeQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return false;
		}
		String normalized = query.trim();
		return normalized.length() <= 8 && !normalized.contains(" ")
				&& !normalized.matches(".*[\\u4e00-\\u9fa5].*") && normalized.matches("[A-Za-z0-9_-]+");
	}

	private int normalizeTopK(Integer topK) {
		return topK == null || topK <= 0 ? DEFAULT_TOP_K : Math.min(topK, MAX_TOP_K);
	}

	private double normalizeThreshold(Double threshold) {
		if (threshold == null) {
			return DEFAULT_SIMILARITY_THRESHOLD;
		}
		return Math.max(0.0D, Math.min(1.0D, threshold));
	}

	private String requireText(String value) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest("查询内容不能为空");
		}
		return value.trim();
	}

	private Long asLong(Object value) {
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

	private String abbreviate(String value, int maxLength) {
		String normalized = normalizeWhitespace(value);
		return normalized.length() <= maxLength ? normalized
				: normalized.substring(0, Math.max(0, maxLength - 1)).trim() + ELLIPSIS;
	}

	/**
	 * 以命中位置为中心取窗口，而不是从片段开头截。
	 *
	 * <p>向量检索只告诉我们「哪个 chunk 命中」，不给字符偏移，所以这里用查询词在 chunk 内反查位置。
	 * 命中内容落在片段中后部时，从头截断会把它整段丢掉，而检索日志仍然是 matched，排查时几乎必然被
	 * 误判成召回失败。定位不到（纯语义命中、同义词命中）时回落到从头截断，与改动前一致。
	 *
	 * <p>返回值长度不超过 {@code maxLength}，被截掉的一侧带省略号，让模型知道自己看到的不是全文。
	 */
	private String snippetAroundHit(String value, String query, int maxLength) {
		String normalized = normalizeWhitespace(value);
		if (normalized.length() <= maxLength) {
			return normalized;
		}
		int anchor = locateQueryAnchor(normalized, query, maxLength);
		if (anchor < 0) {
			return abbreviate(normalized, maxLength);
		}
		// 尾部必然被截，先扣一个省略号；命中点之前留 1/4 窗口做上文，命中贴在窗口边缘时模型缺少语境
		int window = maxLength - ELLIPSIS.length();
		if (anchor - window / 4 > 0) {
			window -= ELLIPSIS.length();
		}
		int start = Math.max(0, anchor - window / 4);
		int end = Math.min(normalized.length(), start + window);
		start = Math.max(0, end - window);
		return (start > 0 ? ELLIPSIS : "") + normalized.substring(start, end).trim()
				+ (end < normalized.length() ? ELLIPSIS : "");
	}

	/**
	 * 多个查询词都命中时选覆盖词数最多的位置：正文里相关词通常聚在一起，只认第一个命中词容易被文首
	 * 偶然出现的同形词带偏。一个都定位不到时返回 -1。
	 */
	private int locateQueryAnchor(String text, String query, int window) {
		String haystack = text.toLowerCase(Locale.ROOT);
		List<Integer> positions = new ArrayList<>();
		for (String term : queryTerms(query)) {
			int index = haystack.indexOf(term);
			if (index >= 0) {
				positions.add(index);
			}
		}
		if (positions.isEmpty()) {
			return -1;
		}
		Collections.sort(positions);
		int anchor = positions.get(0);
		int bestCovered = 0;
		for (int i = 0; i < positions.size(); i++) {
			int covered = 0;
			while (i + covered < positions.size() && positions.get(i + covered) - positions.get(i) <= window) {
				covered++;
			}
			if (covered > bestCovered) {
				bestCovered = covered;
				anchor = positions.get(i);
			}
		}
		return anchor;
	}

	/**
	 * 查询词 = 按中英文标点切出的词 + 汉字双字滑窗（中文没有词边界，整句 indexOf 基本不会命中）。
	 * 滑窗只对汉字取，英文保留整词，否则 "select" 会拆出 "se"、"el" 这类噪声词把窗口锚到无关位置。
	 */
	private Set<String> queryTerms(String query) {
		String normalized = normalizeWhitespace(query).toLowerCase(Locale.ROOT);
		Set<String> terms = new LinkedHashSet<>();
		for (String token : normalized.split(TERM_DELIMITERS)) {
			if (token.length() >= MIN_TERM_LENGTH) {
				terms.add(token);
			}
		}
		for (int i = 0; i + MIN_TERM_LENGTH <= normalized.length(); i++) {
			String bigram = normalized.substring(i, i + MIN_TERM_LENGTH);
			if (bigram.matches(CJK_BIGRAM)) {
				terms.add(bigram);
			}
		}
		return terms;
	}

	private String normalizeWhitespace(String value) {
		return value == null ? "" : value.replaceAll("\\s+", " ").trim();
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private record KeywordExpansionRule(List<String> keywords, String expansion) {

		private boolean matches(String query) {
			return keywords.stream().anyMatch(query::contains);
		}

	}

}
