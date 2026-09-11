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

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentMemoryConfigMapper;
import com.sn68.agent.dataagent.repository.AgentMemoryMapper;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 长期记忆抽取服务：从会话内容中识别候选记忆并生成待确认条目。
 */
@Slf4j
@Service
public class LongTermMemoryExtractionService {

	private static final List<String> SENSITIVE_RESULT_KEYWORDS = List.of("金额", "排行", "排名", "订单", "账单",
			"明细", "库存", "租金", "元", "收入", "利润", "成本", "单价", "折扣", "毛利率", "余额", "欠款",
			"均价", "提成", "top", "TOP");

	private static final Pattern PII_ID_CARD = Pattern.compile(
			"(?<![0-9])[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx](?![0-9])");

	private static final Pattern PII_MOBILE = Pattern.compile("(?<![0-9])1[3-9]\\d{9}(?![0-9])");

	private final AgentMemoryMapper memoryMapper;

	private final AgentMemoryConfigMapper configMapper;

	private final AgentVectorStoreService vectorStoreService;

	private final DataAgentProperties dataAgentProperties;

	private final ExecutorService executorService;

	private final AuthenticationContext authenticationContext;

	private final RuntimeRunService runtimeRunService;

	public LongTermMemoryExtractionService(AgentMemoryMapper memoryMapper, AgentMemoryConfigMapper configMapper,
			AgentVectorStoreService vectorStoreService, DataAgentProperties dataAgentProperties,
			@Qualifier("dbOperationExecutor") ExecutorService executorService,
			AuthenticationContext authenticationContext, RuntimeRunService runtimeRunService) {
		this.memoryMapper = memoryMapper;
		this.configMapper = configMapper;
		this.vectorStoreService = vectorStoreService;
		this.dataAgentProperties = dataAgentProperties;
		this.executorService = executorService;
		this.authenticationContext = authenticationContext;
		this.runtimeRunService = runtimeRunService;
	}

	/**
	 * 必须跑在 {@code dbOperationExecutor} 上：它经 {@code TtlExecutors} 包装，能把
	 * {@code ThreadLocalHolder} 里的登录人/租户带过线程边界。换成 {@code ForkJoinPool.commonPool}
	 * 会让 {@code AuthenticationContext} 判定为匿名，`data_agent_memory` 落库时 tenant_id 与
	 * createBy/createName 全部为空，且阻塞式 DB / 向量写入会占满 commonPool。
	 */
	public void extractAndSaveAsync(String agentId, String userId, String sessionId, String sourceMessageId,
			String userQuery, String answer) {
		extractAndSaveAsync(agentId, userId, sessionId, sourceMessageId, userQuery, answer, null);
	}

	/**
	 * W7 集成点：携带来源根 Run ID 的抽取入口，写入前按权威运行时做「来源 Run 必须终态成功」查证。
	 */
	public void extractAndSaveAsync(String agentId, String userId, String sessionId, String sourceMessageId,
			String userQuery, String answer, Long sourceRunId) {
		CompletableFuture
			.runAsync(() -> extractAndSave(agentId, userId, sessionId, sourceMessageId, userQuery, answer,
					sourceRunId), executorService)
			.exceptionally(throwable -> {
				log.error("Long-term memory extraction failed. agentId={}, userId={}, sessionId={}", agentId, userId,
						sessionId, throwable);
				return null;
			});
	}

	public void extractAndSave(String agentId, String userId, String sessionId, String sourceMessageId, String userQuery,
			String answer) {
		extractAndSave(agentId, userId, sessionId, sourceMessageId, userQuery, answer, null);
	}

	/**
	 * W7 集成点（已接线）：调用方（AiAgentRuntimeServiceImpl#extractLongTermMemorySafely）传入当次
	 * runtimeRequestId 对应的权威 Run ID，本入口与治理写入口 {@code AgentMemoryService#saveLongTermCandidate}
	 * 使用同一「来源 Run 必须终态成功」查证；迁移期未镜像的运行（sourceRunId 为空或权威表查不到）按现有
	 * 参数校验放行。
	 */
	public void extractAndSave(String agentId, String userId, String sessionId, String sourceMessageId, String userQuery,
			String answer, Long sourceRunId) {
		DataAgentProperties.LongTermMemory properties = longTermMemoryProperties();
		if (!properties.isEnabled() || !StringUtils.hasText(agentId) || !StringUtils.hasText(userId)) {
			return;
		}
		if (!sourceRunAllowsMemoryWrite(agentId, userId, sourceRunId)) {
			return;
		}
		Long numericAgentId = parseAgentId(agentId);
		if (numericAgentId == null) {
			return;
		}
		AgentMemoryConfigResp config = resolveConfig(numericAgentId, userId);
		if (!Boolean.TRUE.equals(config.getWriteEnabled())) {
			log.debug("Long-term memory extraction skipped because write disabled. agentId={}, userId={}", agentId,
					userId);
			return;
		}
		long start = System.nanoTime();
		Optional<MemoryCandidate> candidateOptional = extractCandidate(userQuery, answer);
		if (candidateOptional.isEmpty()) {
			log.info("Long-term memory extraction. agentId={}, userId={}, candidates=0, saved=0, reason=no_candidate",
					agentId, userId);
			return;
		}
		MemoryCandidate candidate = candidateOptional.get();
		if (!config.getWriteTypes().contains(candidate.memoryType())) {
			log.info("Long-term memory extraction. agentId={}, userId={}, candidates=1, saved=0, reason=类型不允许",
					agentId, userId);
			return;
		}
		if (candidate.confidence() < properties.getMinExtractionConfidence()) {
			log.info("Long-term memory extraction. agentId={}, userId={}, candidates=1, saved=0, reason=置信度过低",
					agentId, userId);
			return;
		}
		// 敏感记忆治理：sensitivity=HIGH 必须有用户同意（显式"记住"指令视为同意）才可写入
		if (candidate.sensitive() && !candidate.explicitRemember()) {
			log.info("Long-term memory extraction. agentId={}, userId={}, candidates=1, saved=0, reason=敏感记忆缺少用户同意",
					agentId, userId);
			return;
		}
		String semanticHash = semanticHash(numericAgentId, userId, candidate.memoryType(), candidate.summary());
		AgentMemory existing = memoryMapper == null ? null
				: memoryMapper.findByAgentIdUserIdAndHash(numericAgentId, userId, semanticHash, currentTenantId());
		AgentMemory saved = existing == null ? newMemory(numericAgentId, userId, sessionId, sourceMessageId, candidate,
				semanticHash, sourceRunId) : updateMemory(existing, sourceMessageId, candidate, sourceRunId);
		if (memoryMapper != null) {
			if (saved.getId() == null) {
				memoryMapper.insert(saved);
			}
			else {
				memoryMapper.updateById(saved);
			}
		}
		addVectorDocumentSafely(saved);
		log.info(
				"Long-term memory extraction. agentId={}, userId={}, candidates=1, saved=1, type={}, costMs={}",
				agentId, userId, candidate.memoryType(), (System.nanoTime() - start) / 1_000_000L);
	}

	public Optional<MemoryCandidate> extractCandidate(String userQuery, String answer) {
		String source = ((userQuery == null ? "" : userQuery) + "\n" + (answer == null ? "" : answer)).trim();
		if (!StringUtils.hasText(source)) {
			return Optional.empty();
		}
		if (containsPii(source)) {
			return Optional.empty();
		}
		boolean explicitRemember = containsAny(source, "记住", "记一下", "以后记得", "请记住", "保存这个结论");
		boolean sensitiveResult = containsSensitiveBusinessResult(source);
		if (sensitiveResult && explicitRemember) {
			return Optional.empty();
		}
		if (sensitiveResult && !longTermMemoryProperties().isAutoWriteSensitiveResultEnabled()) {
			return Optional.empty();
		}
		AgentMemoryType type = classify(source, explicitRemember);
		if (type == null) {
			return Optional.empty();
		}
		if (AgentMemoryType.EPISODIC.equals(type) && !explicitRemember
				&& !longTermMemoryProperties().isAutoWriteEpisodicEnabled()) {
			return Optional.empty();
		}
		String summary = summarize(source, type);
		double confidence = explicitRemember ? 0.95 : 0.82;
		double importance = AgentMemoryType.PREFERENCE.equals(type) || AgentMemoryType.SEMANTIC.equals(type) ? 0.8
				: 0.7;
		return Optional.of(new MemoryCandidate(type, summary, summary, importance, confidence, sensitiveResult,
				explicitRemember));
	}

	private AgentMemoryType classify(String source, boolean explicitRemember) {
		if (explicitRemember) {
			return AgentMemoryType.EPISODIC;
		}
		if (containsAny(source, "偏好", "习惯", "以后", "默认", "优先按", "都按", "按客户", "按项目")) {
			return AgentMemoryType.PREFERENCE;
		}
		if (containsAny(source, "口径", "定义", "规则", "包含", "不包含", "计算方式", "统计方式")) {
			return AgentMemoryType.SEMANTIC;
		}
		if (containsAny(source, "每次", "先查", "不要展示", "输出格式", "报告格式", "工作方式", "流程")) {
			return AgentMemoryType.PROCEDURAL;
		}
		return null;
	}

	private boolean containsSensitiveBusinessResult(String source) {
		return SENSITIVE_RESULT_KEYWORDS.stream().anyMatch(source::contains) && source.matches("(?s).*[0-9].*");
	}

	private boolean containsPii(String source) {
		if (!StringUtils.hasText(source)) {
			return false;
		}
		if (PII_ID_CARD.matcher(source).find() || PII_MOBILE.matcher(source).find()) {
			return true;
		}
		return containsAny(source, "银行卡", "信用卡", "卡号") && source.matches("(?s).*\\d{16,19}.*");
	}

	private boolean containsAny(String source, String... keywords) {
		if (!StringUtils.hasText(source)) {
			return false;
		}
		String lower = source.toLowerCase(Locale.ROOT);
		for (String keyword : keywords) {
			if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	private String summarize(String source, AgentMemoryType type) {
		String compact = source.replaceAll("\\s+", " ").trim();
		if (compact.length() > 160) {
			compact = compact.substring(0, 160);
		}
		return switch (type) {
			case PREFERENCE -> "用户偏好：" + compact;
			case SEMANTIC -> "业务口径：" + compact;
			case PROCEDURAL -> "工作方式：" + compact;
			case EPISODIC -> "历史结论：" + compact;
		};
	}

	/**
	 * W7 终态查证：来源 Run 在权威运行时中可查证时，必须已入 SUCCEEDED 终态才允许写入记忆；
	 * 非成功一律跳过并留可见日志。sourceRunId 为空或权威表查不到（迁移期未镜像链路、
	 * 租户上下文缺失）按现有参数校验放行，语义与 AgentMemoryService#saveLongTermCandidate 对齐。
	 */
	private boolean sourceRunAllowsMemoryWrite(String agentId, String userId, Long sourceRunId) {
		if (sourceRunId == null || runtimeRunService == null) {
			return true;
		}
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			log.debug("长期记忆抽取缺少租户上下文, 跳过权威运行时查证. sourceRunId={}", sourceRunId);
			return true;
		}
		RuntimeRunState state = runtimeRunService.findRunState(tenantId, sourceRunId);
		if (state == null) {
			log.debug("长期记忆抽取来源 Run 不在权威运行时中, 按迁移期参数校验放行. sourceRunId={}", sourceRunId);
			return true;
		}
		if (state != RuntimeRunState.SUCCEEDED) {
			log.info("Long-term memory extraction. agentId={}, userId={}, candidates=0, saved=0, reason=来源Run非成功终态({})",
					agentId, userId, state.getValue());
			return false;
		}
		return true;
	}

	private AgentMemory newMemory(Long agentId, String userId, String sessionId, String sourceMessageId,
			MemoryCandidate candidate, String semanticHash, Long sourceRunId) {
		Instant now = Instant.now();
		// PROCEDURAL（工作方式）记忆只能由人工审核后启用，落库即待审核；其余类型直接生效
		AgentMemoryStatus status = AgentMemoryType.PROCEDURAL.equals(candidate.memoryType())
				? AgentMemoryStatus.PENDING_REVIEW : AgentMemoryStatus.ACTIVE;
		// 显式"记住"指令视为用户同意；敏感候选无同意在 extractAndSave 已被拦截
		MemoryConsentStatus consentStatus = candidate.explicitRemember() ? MemoryConsentStatus.GRANTED
				: MemoryConsentStatus.UNSPECIFIED;
		return AgentMemory.builder()
			.tenantId(currentTenantId())
			.agentId(agentId)
			.userId(userId)
			.subjectType(memoryScopeOf(candidate.memoryType()))
			.subjectId(userId)
			.revision(1)
			.memoryType(candidate.memoryType())
			.summary(candidate.summary())
			.content(candidate.content())
			.semanticHash(semanticHash)
			.importance(candidate.importance())
			.confidence(candidate.confidence())
			.status(status)
			.sensitivity(candidate.sensitive() ? MemorySensitivity.HIGH : MemorySensitivity.LOW)
			.consentStatus(consentStatus)
			.validFrom(now)
			.sourceSessionId(sessionId)
			.sourceMessageId(sourceMessageId)
			.sourceRunId(sourceRunId)
			.useCount(0)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
	}

	private AgentMemory updateMemory(AgentMemory existing, String sourceMessageId, MemoryCandidate candidate,
			Long sourceRunId) {
		existing.setSummary(candidate.summary());
		existing.setContent(candidate.content());
		existing.setImportance(Math.max(existing.getImportance() == null ? 0.0 : existing.getImportance(),
				candidate.importance()));
		existing.setConfidence(Math.max(existing.getConfidence() == null ? 0.0 : existing.getConfidence(),
				candidate.confidence()));
		existing.setSourceMessageId(sourceMessageId);
		if (sourceRunId != null) {
			existing.setSourceRunId(sourceRunId);
		}
		// 待人工审核的记忆不能因语义去重命中而被自动激活，必须保留待审核状态
		if (!AgentMemoryStatus.PENDING_REVIEW.equals(existing.getStatus())) {
			existing.setStatus(AgentMemoryStatus.ACTIVE);
		}
		existing.setLastModifyTime(Instant.now());
		return existing;
	}

	/**
	 * 抽取链路的记忆范围映射：抽取产物都挂在当前用户主体上，
	 * PREFERENCE/SEMANTIC 归 EMPLOYEE_USER，EPISODIC/PROCEDURAL 归对应同名范围。
	 */
	private MemoryScope memoryScopeOf(AgentMemoryType type) {
		return switch (type) {
			case EPISODIC -> MemoryScope.EPISODIC;
			case PROCEDURAL -> MemoryScope.PROCEDURAL;
			case PREFERENCE, SEMANTIC -> MemoryScope.EMPLOYEE_USER;
		};
	}

	/**
	 * 租户/安全上下文传播：抽取跑在 dbOperationExecutor（TTL 包装）上，
	 * ThreadLocalHolder 中的租户与登录人可跨线程边界取到；取不到时记 warn 并落空，
	 * 不伪造租户，便于排查上下文丢失问题。
	 */
	private String currentTenantId() {
		try {
			String tenantId = authenticationContext.tenantId();
			if (!StringUtils.hasText(tenantId)) {
				log.warn("长期记忆抽取缺少租户上下文, tenant_id 落空, 请检查调用链是否经 TTL 线程池传播上下文");
				return null;
			}
			return tenantId;
		}
		catch (Exception ex) {
			log.warn("长期记忆抽取解析租户上下文失败, tenant_id 落空", ex);
			return null;
		}
	}

	private AgentMemoryConfigResp resolveConfig(Long agentId, String userId) {
		if (configMapper == null) {
			return AgentMemoryConfigResp.defaults(agentId, userId, dataAgentProperties);
		}
		var config = configMapper.findByAgentIdAndUserId(agentId, userId);
		if (config == null) {
			return AgentMemoryConfigResp.defaults(agentId, userId, dataAgentProperties);
		}
		return AgentMemoryConfigResp.builder()
			.agentId(agentId)
			.userId(userId)
			.recallEnabled(Boolean.TRUE.equals(config.getRecallEnabled()))
			.writeEnabled(Boolean.TRUE.equals(config.getWriteEnabled()))
			.writeTypes(parseTypes(config.getWriteTypes()))
			.recallTypes(parseTypes(config.getRecallTypes()))
			.topK(config.getTopK())
			.similarityThreshold(config.getSimilarityThreshold())
			.injectionTokenBudget(config.getInjectionTokenBudget())
			.minImportance(config.getMinImportance())
			.confirmedOnly(Boolean.TRUE.equals(config.getConfirmedOnly()))
			.build();
	}

	private List<AgentMemoryType> parseTypes(String types) {
		if (!StringUtils.hasText(types)) {
			return AgentMemoryConfigResp.defaults(null, null, dataAgentProperties).getWriteTypes();
		}
		return java.util.Arrays.stream(types.split(","))
			.map(String::trim)
			.map(value -> com.sn68.agent.framework.commons.entity.DictEnum.of(AgentMemoryType.class, value))
			.filter(java.util.Objects::nonNull)
			.toList();
	}

	private Long parseAgentId(String agentId) {
		try {
			return Long.valueOf(agentId);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String semanticHash(Long agentId, String userId, AgentMemoryType type, String summary) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest((agentId + "|" + userId + "|" + type.getValue() + "|" + summary)
				.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		}
		catch (NoSuchAlgorithmException ex) {
			// hashCode collides far more readily than SHA-256, so memory dedup would start merging unrelated entries.
			log.error("SHA-256 digest is unavailable, long-term memory dedup falls back to hashCode. agentId={}",
					agentId, ex);
			return String.valueOf((agentId + userId + type.getValue() + summary).hashCode());
		}
	}

	private void addVectorDocumentSafely(AgentMemory memory) {
		if (vectorStoreService == null || memory == null || memory.getId() == null) {
			return;
		}
		try {
			Map<String, Object> metadata = new java.util.HashMap<>();
			metadata.put(Constant.AGENT_ID, memory.getAgentId().toString());
			metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.AGENT_MEMORY);
			metadata.put(DocumentMetadataConstant.DB_AGENT_MEMORY_ID, memory.getId().toString());
			metadata.put(DocumentMetadataConstant.AGENT_MEMORY_USER_ID, memory.getUserId());
			metadata.put(DocumentMetadataConstant.AGENT_MEMORY_TYPE, memory.getMemoryType().getValue());
			if (StringUtils.hasText(memory.getTenantId())) {
				metadata.put(DocumentMetadataConstant.AGENT_MEMORY_TENANT_ID, memory.getTenantId());
			}
			if (memory.getSubjectType() != null) {
				metadata.put(DocumentMetadataConstant.AGENT_MEMORY_SUBJECT_TYPE, memory.getSubjectType().getValue());
			}
			vectorStoreService.addDocuments(memory.getAgentId().toString(), List.of(new Document(memory.getSummary(),
					metadata)));
		}
		catch (RuntimeException ex) {
			log.warn("Add long-term memory vector document failed. memoryId={}", memory.getId(), ex);
		}
	}

	private DataAgentProperties.LongTermMemory longTermMemoryProperties() {
		if (dataAgentProperties == null || dataAgentProperties.getLongTermMemory() == null) {
			return new DataAgentProperties.LongTermMemory();
		}
		return dataAgentProperties.getLongTermMemory();
	}

	public record MemoryCandidate(AgentMemoryType memoryType, String summary, String content, double importance,
			double confidence, boolean sensitive, boolean explicitRemember) {
	}

}
