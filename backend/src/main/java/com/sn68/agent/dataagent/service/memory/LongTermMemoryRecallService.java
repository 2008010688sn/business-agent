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
package com.sn68.agent.dataagent.service.memory;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryRecallHitDTO;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryRecallResultDTO;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.entity.AgentMemoryConfig;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentMemoryConfigMapper;
import com.sn68.agent.dataagent.repository.AgentMemoryMapper;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.entity.DictEnum;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 长期记忆召回服务：按语义相似度检索当前用户可用的长期记忆。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryRecallService {

	private final AgentMemoryMapper memoryMapper;

	private final AgentMemoryConfigMapper configMapper;

	private final LongTermMemoryPromptAssembler promptAssembler;

	private final DataAgentProperties dataAgentProperties;

	private final AuthenticationContext authenticationContext;

	@Nullable
	private final AgentVectorStoreService vectorStoreService;

	public AgentMemoryRecallResultDTO recall(String agentId, String userId, String query, Memory memory) {
		return recall(agentId, userId, query, recentConversationTexts(memory), null, null);
	}

	public AgentMemoryRecallResultDTO recall(String agentId, String userId, String query, Memory memory,
			String tenantId) {
		return recall(agentId, userId, query, recentConversationTexts(memory), null, tenantId);
	}

	public AgentMemoryRecallResultDTO recall(String agentId, String userId, String query, List<String> recentTexts) {
		return recall(agentId, userId, query, recentTexts, null, null);
	}

	public AgentMemoryRecallResultDTO recall(String agentId, String userId, String query, List<String> recentTexts,
			AgentMemoryConfigResp suppliedConfig) {
		return recall(agentId, userId, query, recentTexts, suppliedConfig, null);
	}

	public AgentMemoryRecallResultDTO recall(String agentId, String userId, String query, List<String> recentTexts,
			AgentMemoryConfigResp suppliedConfig, String tenantId) {
		DataAgentProperties.LongTermMemory properties = longTermMemoryProperties();
		if (!properties.isEnabled()) {
			return AgentMemoryRecallResultDTO.empty(false, false);
		}
		Long numericAgentId = parseAgentId(agentId);
		if (numericAgentId == null || !StringUtils.hasText(userId)) {
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		AgentMemoryConfigResp config = suppliedConfig == null ? resolveConfig(numericAgentId, userId) : suppliedConfig;
		if (!Boolean.TRUE.equals(config.getRecallEnabled())) {
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		String resolvedTenantId = resolveTenantId(tenantId);
		List<ScoredMemory> candidates = loadUserCandidates(numericAgentId, userId, resolvedTenantId, query);
		return assembleRecallResult(agentId, userId, candidates, config, query, resolvedTenantId);
	}

	/**
	 * 数字员工召回入口（对话与无人值守任务共用）：不得召回真人记忆，
	 * 候选严格限定该数字员工的共享记忆（WORKSPACE，subjectId=数字员工ID），
	 * 不读真人维度的召回配置（记忆配置是用户个人数据，直接用默认值）。
	 */
	public AgentMemoryRecallResultDTO recallForDigitalEmployee(String agentId, String digitalEmployeeId, String query,
			Memory memory) {
		return recallForDigitalEmployee(agentId, digitalEmployeeId, query, memory, null);
	}

	public AgentMemoryRecallResultDTO recallForDigitalEmployee(String agentId, String digitalEmployeeId, String query,
			Memory memory, String tenantId) {
		return recallForDigitalEmployee(agentId, digitalEmployeeId, query, recentConversationTexts(memory), tenantId);
	}

	/**
	 * 数字员工召回入口（近期文本重载），语义同 {@link #recallForDigitalEmployee(String, String, String, Memory)}。
	 */
	public AgentMemoryRecallResultDTO recallForDigitalEmployee(String agentId, String digitalEmployeeId, String query,
			List<String> recentTexts) {
		return recallForDigitalEmployee(agentId, digitalEmployeeId, query, recentTexts, null);
	}

	public AgentMemoryRecallResultDTO recallForDigitalEmployee(String agentId, String digitalEmployeeId, String query,
			List<String> recentTexts, String tenantId) {
		DataAgentProperties.LongTermMemory properties = longTermMemoryProperties();
		if (!properties.isEnabled()) {
			return AgentMemoryRecallResultDTO.empty(false, false);
		}
		Long numericAgentId = parseAgentId(agentId);
		Long numericEmployeeId = parseAgentId(digitalEmployeeId);
		if (numericAgentId == null || numericEmployeeId == null) {
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		AgentMemoryConfigResp config = AgentMemoryConfigResp.defaults(numericAgentId,
				String.valueOf(numericEmployeeId), dataAgentProperties);
		// 共享记忆召回不跟随真人用户的 recallEnabledDefault（隐私保守默认只约束真人链路）：
		// 无人值守链路的总开关即全局 long-term-memory.enabled，defaults 仅提供召回参数。
		config.setRecallEnabled(true);
		String resolvedTenantId = resolveTenantId(tenantId);
		List<ScoredMemory> candidates = loadDigitalEmployeeCandidates(numericAgentId, numericEmployeeId,
				resolvedTenantId, query);
		return assembleRecallResult(agentId, "digital-employee:" + digitalEmployeeId, candidates, config, query,
				resolvedTenantId);
	}

	private AgentMemoryRecallResultDTO assembleRecallResult(String agentId, String recallOwner,
			List<ScoredMemory> candidates, AgentMemoryConfigResp config, String query, String tenantId) {
		long start = System.nanoTime();
		Map<String, Integer> filterReasons = new HashMap<>();
		List<AgentMemoryRecallHitDTO> hits = candidates.stream()
			.map(scored -> toHitOrFilter(scored.memory(), config, query, scored.vectorScore(), filterReasons))
			.filter(Objects::nonNull)
			.sorted(Comparator.comparingDouble(this::rankScore).reversed())
			.limit(resolveTopK(config))
			.toList();
		LongTermMemoryPromptAssembler.AssembledPrompt assembled = promptAssembler.assembleResult(hits,
				resolveInjectionTokenBudget(config));
		String promptBlock = assembled.promptBlock();
		List<Long> injectedIds = assembled.injectedIds();
		List<AgentMemoryRecallHitDTO> injectedHits = hits.stream()
			.filter(hit -> hit.id() != null && injectedIds.contains(hit.id()))
			.map(hit -> AgentMemoryRecallHitDTO.builder()
				.id(hit.id())
				.memoryType(hit.memoryType())
				.summary(hit.summary())
				.content(hit.content())
				.similarity(hit.similarity())
				.importance(hit.importance())
				.confidence(hit.confidence())
				.sourceTime(hit.sourceTime())
				.injected(true)
				.build())
			.toList();
		if (!injectedIds.isEmpty()) {
			memoryMapper.markUsed(injectedIds, tenantId);
		}
		int estimatedTokens = promptAssembler.estimateTokens(promptBlock);
		log.info(
				"Long-term memory recall. enabled=true, recallEnabled=true, agentId={}, recallOwner={}, candidates={}, injected={}, estimatedTokens={}, filterReasons={}, costMs={}",
				agentId, recallOwner, candidates.size(), injectedHits.size(), estimatedTokens, filterReasons,
				(System.nanoTime() - start) / 1_000_000L);
		return new AgentMemoryRecallResultDTO(true, true, candidates.size(), injectedHits.size(), estimatedTokens,
				promptBlock, injectedHits, filterReasons);
	}

	public AgentMemoryConfigResp resolveConfig(Long agentId, String userId) {
		AgentMemoryConfig config = configMapper.findByAgentIdAndUserId(agentId, userId);
		if (config == null) {
			return AgentMemoryConfigResp.defaults(agentId, userId, dataAgentProperties);
		}
		return AgentMemoryConfigResp.builder()
			.id(config.getId())
			.agentId(config.getAgentId())
			.userId(config.getUserId())
			.recallEnabled(Boolean.TRUE.equals(config.getRecallEnabled()))
			.writeEnabled(Boolean.TRUE.equals(config.getWriteEnabled()))
			.recallTypes(parseTypes(config.getRecallTypes()))
			.writeTypes(parseTypes(config.getWriteTypes()))
			.topK(config.getTopK())
			.similarityThreshold(config.getSimilarityThreshold())
			.injectionTokenBudget(config.getInjectionTokenBudget())
			.minImportance(config.getMinImportance())
			.confirmedOnly(Boolean.TRUE.equals(config.getConfirmedOnly()))
			.build();
	}

	private List<ScoredMemory> loadUserCandidates(Long agentId, String userId, String tenantId, String query) {
		Predicate<AgentMemory> allowed = memory -> userId.equals(memory.getUserId()) && memory.getSubjectType() != null
				&& memory.getSubjectType().userOwned();
		List<ScoredMemory> vectorHits = searchVectorThenHydrate(String.valueOf(agentId), query, tenantId, allowed);
		if (!vectorHits.isEmpty()) {
			return vectorHits;
		}
		return scoreLexically(memoryMapper.findRecallCandidates(agentId, userId, tenantId));
	}

	private List<ScoredMemory> loadDigitalEmployeeCandidates(Long agentId, Long digitalEmployeeId, String tenantId,
			String query) {
		Predicate<AgentMemory> allowed = memory -> MemoryScope.WORKSPACE.equals(memory.getSubjectType())
				&& digitalEmployeeId.equals(memory.getDigitalEmployeeId());
		List<ScoredMemory> vectorHits = searchVectorThenHydrate(String.valueOf(agentId), query, tenantId, allowed);
		if (!vectorHits.isEmpty()) {
			return vectorHits;
		}
		return scoreLexically(
				memoryMapper.findRecallCandidatesForDigitalEmployee(agentId, digitalEmployeeId, tenantId));
	}

	private List<ScoredMemory> searchVectorThenHydrate(String agentId, String query, String tenantId,
			Predicate<AgentMemory> allowed) {
		if (vectorStoreService == null || !StringUtils.hasText(query)) {
			return List.of();
		}
		List<Document> documents;
		try {
			documents = vectorStoreService.getDocumentsForAgent(agentId, query, DocumentMetadataConstant.AGENT_MEMORY,
					vectorTopK(), vectorSimilarityThreshold());
		}
		catch (RuntimeException ex) {
			log.warn("Long-term memory vector recall failed, falling back to lexical. agentId={}", agentId, ex);
			return List.of();
		}
		if (documents == null || documents.isEmpty()) {
			return List.of();
		}
		Map<Long, Double> scores = new LinkedHashMap<>();
		for (Document document : documents) {
			Long memoryId = memoryIdFrom(document);
			if (memoryId == null || scores.containsKey(memoryId)) {
				continue;
			}
			scores.put(memoryId, documentScore(document));
		}
		if (scores.isEmpty()) {
			return List.of();
		}
		Map<Long, AgentMemory> loaded = memoryMapper.findByIdsAndTenant(scores.keySet(), tenantId)
			.stream()
			.filter(allowed)
			.collect(LinkedHashMap::new, (map, memory) -> map.put(memory.getId(), memory), Map::putAll);
		List<ScoredMemory> result = new ArrayList<>();
		for (Map.Entry<Long, Double> entry : scores.entrySet()) {
			AgentMemory memory = loaded.get(entry.getKey());
			if (memory != null) {
				result.add(new ScoredMemory(memory, entry.getValue()));
			}
		}
		return result;
	}

	private List<ScoredMemory> scoreLexically(List<AgentMemory> memories) {
		if (memories == null || memories.isEmpty()) {
			return List.of();
		}
		List<ScoredMemory> scored = new ArrayList<>();
		for (AgentMemory memory : memories) {
			scored.add(new ScoredMemory(memory, null));
		}
		return scored;
	}

	private static Long memoryIdFrom(Document document) {
		if (document == null || document.getMetadata() == null) {
			return null;
		}
		Object raw = document.getMetadata().get(DocumentMetadataConstant.DB_AGENT_MEMORY_ID);
		if (raw == null) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(raw).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static double documentScore(Document document) {
		Double score = document == null ? null : document.getScore();
		return score == null || score.isNaN() ? 1.0 : score;
	}

	private int vectorTopK() {
		return Math.min(20, Math.max(5, longTermMemoryProperties().getDefaultTopK() * 3));
	}

	private double vectorSimilarityThreshold() {
		if (dataAgentProperties == null || dataAgentProperties.getVectorStore() == null) {
			return 0.2;
		}
		return dataAgentProperties.getVectorStore().getDefaultSimilarityThreshold();
	}

	private AgentMemoryRecallHitDTO toHitOrFilter(AgentMemory memory, AgentMemoryConfigResp config, String recallQuery,
			Double vectorScore, Map<String, Integer> filterReasons) {
		if (memory == null || Boolean.TRUE.equals(memory.getDeleted())) {
			increment(filterReasons, "deleted");
			return null;
		}
		if (!AgentMemoryStatus.ACTIVE.equals(memory.getStatus())) {
			increment(filterReasons, "状态禁用");
			return null;
		}
		if (memory.getExpireTime() != null && memory.getExpireTime().isBefore(Instant.now())) {
			increment(filterReasons, "已过期");
			return null;
		}
		// PR-7 存储TTL：expires_at 过期的记忆不再召回（清理任务扫描列的读取侧兑价）
		if (memory.getExpiresAt() != null && memory.getExpiresAt().isBefore(Instant.now())) {
			increment(filterReasons, "已过期");
			return null;
		}
		// TTL 治理：valid_to 过期的记忆不再召回（敏感记忆的有效期约束）
		if (memory.getValidTo() != null && memory.getValidTo().isBefore(Instant.now())) {
			increment(filterReasons, "已过期");
			return null;
		}
		if (memory.getValidFrom() != null && memory.getValidFrom().isAfter(Instant.now())) {
			increment(filterReasons, "未生效");
			return null;
		}
		List<AgentMemoryType> recallTypes = config.getRecallTypes() == null ? List.of() : config.getRecallTypes();
		if (!recallTypes.isEmpty() && !recallTypes.contains(memory.getMemoryType())) {
			increment(filterReasons, "类型未启用");
			return null;
		}
		double importance = memory.getImportance() == null ? 0.0 : memory.getImportance();
		if (importance < resolveMinImportance(config)) {
			increment(filterReasons, "重要度过低");
			return null;
		}
		double similarity = vectorScore != null ? vectorScore
				: estimateSimilarity(recallQuery, memory.getSummary() + " " + memory.getContent());
		if (vectorScore == null && similarity < resolveSimilarityThreshold(config)) {
			increment(filterReasons, "相似度过低");
			return null;
		}
		return AgentMemoryRecallHitDTO.builder()
			.id(memory.getId())
			.memoryType(memory.getMemoryType())
			.summary(memory.getSummary())
			.content(memory.getContent())
			.similarity(similarity)
			.importance(importance)
			.confidence(memory.getConfidence())
			.sourceTime(memory.getCreateTime())
			.injected(false)
			.build();
	}

	private double rankScore(AgentMemoryRecallHitDTO hit) {
		return safe(hit.similarity()) * 0.6 + safe(hit.importance()) * 0.3 + safe(hit.confidence()) * 0.1;
	}

	private double safe(Double value) {
		return value == null ? 0.0 : value;
	}

	private double estimateSimilarity(String query, String content) {
		if (!StringUtils.hasText(query) || !StringUtils.hasText(content)) {
			return 0.0;
		}
		String normalizedQuery = normalize(query);
		String normalizedContent = normalize(content);
		if (normalizedContent.contains(normalizedQuery) || normalizedQuery.contains(normalizedContent)) {
			return 1.0;
		}
		Set<String> queryTokens = tokens(normalizedQuery);
		Set<String> contentTokens = tokens(normalizedContent);
		if (queryTokens.isEmpty() || contentTokens.isEmpty()) {
			return 0.0;
		}
		int matched = 0;
		for (String token : queryTokens) {
			if (contentTokens.contains(token) || normalizedContent.contains(token)) {
				matched++;
			}
		}
		return matched == 0 ? 0.0 : Math.min(1.0, matched / (double) queryTokens.size());
	}

	private Set<String> tokens(String text) {
		Set<String> result = new LinkedHashSet<>();
		for (String token : text.split("[\\s,，。；;:：!?！？、]+")) {
			if (StringUtils.hasText(token)) {
				result.add(token);
			}
		}
		for (int i = 0; i + 2 <= text.length(); i++) {
			result.add(text.substring(i, i + 2));
		}
		return result;
	}

	private String normalize(String text) {
		return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

	private List<String> recentConversationTexts(Memory memory) {
		if (memory == null || memory.getMessages() == null) {
			return List.of();
		}
		List<Msg> messages = memory.getMessages();
		int start = Math.max(0, messages.size() - 8);
		List<String> texts = new ArrayList<>();
		for (int i = start; i < messages.size(); i++) {
			Msg msg = messages.get(i);
			if (msg != null && StringUtils.hasText(msg.getTextContent())) {
				texts.add(msg.getTextContent());
			}
		}
		return texts;
	}

	private Long parseAgentId(String agentId) {
		if (!StringUtils.hasText(agentId)) {
			return null;
		}
		try {
			return Long.valueOf(agentId);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private List<AgentMemoryType> parseTypes(String types) {
		if (!StringUtils.hasText(types)) {
			return List.of();
		}
		List<AgentMemoryType> result = new ArrayList<>();
		for (String item : types.split(",")) {
			AgentMemoryType type = DictEnum.of(AgentMemoryType.class, item.trim());
			if (type != null) {
				result.add(type);
			}
		}
		return result;
	}

	private int resolveTopK(AgentMemoryConfigResp config) {
		return config.getTopK() == null || config.getTopK() < 1 ? longTermMemoryProperties().getDefaultTopK()
				: config.getTopK();
	}

	private double resolveSimilarityThreshold(AgentMemoryConfigResp config) {
		return config.getSimilarityThreshold() == null ? longTermMemoryProperties().getDefaultSimilarityThreshold()
				: config.getSimilarityThreshold();
	}

	private int resolveInjectionTokenBudget(AgentMemoryConfigResp config) {
		return config.getInjectionTokenBudget() == null ? longTermMemoryProperties().getDefaultInjectionTokenBudget()
				: config.getInjectionTokenBudget();
	}

	private double resolveMinImportance(AgentMemoryConfigResp config) {
		return config.getMinImportance() == null ? longTermMemoryProperties().getDefaultMinImportance()
				: config.getMinImportance();
	}

	private DataAgentProperties.LongTermMemory longTermMemoryProperties() {
		if (dataAgentProperties == null || dataAgentProperties.getLongTermMemory() == null) {
			return new DataAgentProperties.LongTermMemory();
		}
		return dataAgentProperties.getLongTermMemory();
	}

	private void increment(Map<String, Integer> filterReasons, String reason) {
		filterReasons.merge(reason, 1, Integer::sum);
	}

	private String resolveTenantId(String explicitTenantId) {
		if (StringUtils.hasText(explicitTenantId)) {
			return explicitTenantId.trim();
		}
		return requireTenantId();
	}

	private String requireTenantId() {
		try {
			String tenantId = authenticationContext == null ? null : authenticationContext.tenantId();
			if (!StringUtils.hasText(tenantId)) {
				throw CheckedException.forbidden("缺少租户上下文，无法召回记忆");
			}
			return tenantId.trim();
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("缺少租户上下文，无法召回记忆");
		}
	}

	private record ScoredMemory(AgentMemory memory, Double vectorScore) {
	}

}
