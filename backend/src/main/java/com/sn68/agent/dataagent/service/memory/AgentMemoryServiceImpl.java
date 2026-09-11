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

import com.sn68.agent.dataagent.dto.memory.AgentMemoryCandidateSaveReq;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryExportItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemsQueryReq;
import com.sn68.agent.dataagent.dto.memory.UpdateAgentMemoryConfigReq;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.entity.AgentMemoryConfig;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryErrorDict;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.MemoryAuthorizationAdvisor;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentMemoryConfigMapper;
import com.sn68.agent.dataagent.repository.AgentMemoryMapper;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.entity.DictEnum;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent 记忆管理实现：维护长期记忆的落库、查询、状态流转与导出。
 */
@Slf4j
@Service
@AllArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

	/**
	 * 隐藏思维链、原始工具报文等禁止入库的内容标记。命中即拒绝写入：
	 * 长期记忆只允许保存提炼后的结论文本，不保存模型隐藏推理过程与原始敏感业务 payload。
	 */
	private static final List<String> FORBIDDEN_CONTENT_MARKERS = List.of("<think>", "</think>", "<thinking>",
			"<reasoning>", "chain-of-thought", "[思维链]", "\"tool_calls\"", "\"tool_call_id\"");

	/**
	 * 超过该长度的内容大概率是原始业务查询结果 payload 而非提炼结论，直接拒绝。
	 */
	private static final int MAX_MEMORY_CONTENT_LENGTH = 4000;

	private final AgentMemoryMapper memoryMapper;

	private final AgentMemoryConfigMapper configMapper;

	private final DataAgentService agentService;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final DataAgentProperties dataAgentProperties;

	private final AuthenticationContext authenticationContext;

	private final DataChatSessionService chatSessionService;

	private final RuntimeRunService runtimeRunService;

	// PR-3c 记忆线接缝：记忆读写 PDP 影子判定（SHADOW 默认只记录不拦截）。
	private final MemoryAuthorizationAdvisor memoryAuthorizationAdvisor;

	@Override
	public AgentMemoryConfigResp getConfig(Long agentId, String userId) {
		agentService.requireAgent(agentId);
		AgentMemoryConfig config = configMapper.findByAgentIdAndUserId(agentId, userId);
		return config == null ? AgentMemoryConfigResp.defaults(agentId, userId, dataAgentProperties) : toConfigDTO(config);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentMemoryConfigResp saveConfig(Long agentId, String userId, UpdateAgentMemoryConfigReq request) {
		agentService.requireAgent(agentId);
		AgentMemoryConfig existing = configMapper.findByAgentIdAndUserId(agentId, userId);
		AgentMemoryConfig config = existing == null ? AgentMemoryConfig.builder()
			.agentId(agentId)
			.userId(userId)
			.createTime(Instant.now())
			.deleted(false)
			.build() : existing;
		AgentMemoryConfigResp defaults = AgentMemoryConfigResp.defaults(agentId, userId, dataAgentProperties);
		config.setRecallEnabled(request == null || request.recallEnabled() == null ? defaults.getRecallEnabled()
				: request.recallEnabled());
		config.setWriteEnabled(request == null || request.writeEnabled() == null ? defaults.getWriteEnabled()
				: request.writeEnabled());
		List<AgentMemoryType> recallTypes = request == null || request.recallTypes() == null ? defaults.getRecallTypes()
				: request.recallTypes();
		List<AgentMemoryType> writeTypes = request == null || request.writeTypes() == null ? defaults.getWriteTypes()
				: request.writeTypes();
		config.setRecallTypes(toTypeString(recallTypes));
		config.setWriteTypes(toTypeString(writeTypes));
		config.setTopK(normalizeInt(request == null ? defaults.getTopK() : request.topK(), defaults.getTopK(), 1, 20));
		config.setSimilarityThreshold(normalizeDouble(
				request == null ? defaults.getSimilarityThreshold() : request.similarityThreshold(),
				defaults.getSimilarityThreshold(), 0.0, 1.0));
		config.setInjectionTokenBudget(normalizeInt(
				request == null ? defaults.getInjectionTokenBudget() : request.injectionTokenBudget(),
				defaults.getInjectionTokenBudget(), 100, 8000));
		config.setMinImportance(normalizeDouble(request == null ? defaults.getMinImportance() : request.minImportance(),
				defaults.getMinImportance(), 0.0, 1.0));
		config.setConfirmedOnly(request == null || request.confirmedOnly() == null ? defaults.getConfirmedOnly()
				: request.confirmedOnly());
		config.setLastModifyTime(Instant.now());
		if (config.getId() == null) {
			configMapper.insert(config);
		}
		else {
			configMapper.updateById(config);
		}
		return toConfigDTO(config);
	}

	@Override
	public List<AgentMemoryItemResp> listMemories(AgentMemoryItemsQueryReq request, String userId) {
		requireMemoryOwner(request.agentId());
		List<AgentMemoryType> memoryTypes = request.memoryTypes();
		AgentMemoryStatus status = request.status();
		return memoryMapper
			.findByAgentIdAndUserId(request.agentId(), userId, request.scope(), request.sensitivity(),
					request.consentStatus(), requireTenantId())
			.stream()
			.filter(memory -> memoryTypes == null || memoryTypes.isEmpty() || memoryTypes.contains(memory.getMemoryType()))
			.filter(memory -> status == null || status.equals(memory.getStatus()))
			.map(this::toItemDTO)
			.toList();
	}

	@Override
	public void updateStatus(Long agentId, String userId, Long memoryId, AgentMemoryStatus status) {
		agentService.requireAgent(agentId);
		if (memoryId == null || status == null) {
			throw CheckedException.badRequest("记忆ID和状态不能为空");
		}
		// 待人工审核的程序性记忆不允许经普通状态修改直接启用，必须走审核通过动作，保证审核痕迹可追溯
		if (AgentMemoryStatus.ACTIVE.equals(status)) {
			AgentMemory existing = memoryMapper.findByIdAndTenant(memoryId, requireTenantId());
			if (existing != null && AgentMemoryStatus.PENDING_REVIEW.equals(existing.getStatus())) {
				throw CheckedException.badRequest("待人工审核的记忆需通过审核通过动作启用");
			}
		}
		int rows = memoryMapper.updateStatus(agentId, userId, memoryId, status, requireTenantId());
		if (rows <= 0) {
			throw CheckedException.notFound("长期记忆不存在");
		}
	}

	@Override
	public void deleteMemory(Long agentId, String userId, Long memoryId) {
		agentService.requireAgent(agentId);
		int rows = memoryMapper.softDelete(agentId, userId, memoryId, requireTenantId());
		if (rows <= 0) {
			throw CheckedException.notFound("长期记忆不存在");
		}
	}

	@Override
	public void clearMyMemories(Long agentId, String userId) {
		agentService.requireAgent(agentId);
		memoryMapper.softDeleteByAgentIdAndUserId(agentId, userId, requireTenantId());
	}

	/**
	 * 长期记忆候选治理写入口。治理规则（方案第十二章）：
	 * <ol>
	 * <li>只有成功的根 Run 才能生成长期记忆候选：sourceRunId 必填且 rootRunSucceeded 必须为 true。</li>
	 * <li>敏感记忆（sensitivity=HIGH）必须 consentStatus=GRANTED 才可写入。</li>
	 * <li>不保存隐藏思维链与原始敏感业务 payload：对 content 做标记与长度校验。</li>
	 * <li>PROCEDURAL 记忆默认 status=PENDING_REVIEW，人工审核通过后才启用。</li>
	 * <li>同 factKey 写入按 revision 递增修订，历史修订保留可追溯。</li>
	 * <li>tenant/security context 传播：租户ID取自当前认证上下文，不信任调用方传值。</li>
	 * </ol>
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public Long saveLongTermCandidate(AgentMemoryCandidateSaveReq request) {
		if (request == null) {
			throw CheckedException.badRequest("记忆候选请求不能为空");
		}
		agentService.requireAgent(request.agentId());
		// 规则1：只有成功的根 Run 才能生成长期记忆候选。
		if (request.sourceRunId() == null) {
			throw CheckedException.badRequest("长期记忆候选必须来源于运行，sourceRunId不能为空");
		}
		if (!Boolean.TRUE.equals(request.rootRunSucceeded())) {
			throw CheckedException.badRequest("只有成功的根Run才能生成长期记忆候选");
		}
		requireSourceRunSucceeded(request.sourceRunId());
		MemoryScope scope = request.scope();
		if (scope == null || !StringUtils.hasText(request.subjectId())) {
			throw CheckedException.badRequest("记忆范围与主体ID不能为空");
		}
		if (MemoryScope.SESSION.equals(scope)) {
			throw CheckedException.badRequest("SESSION 不是长期记忆范围，短记忆走会话状态");
		}
		// PR-7 归属键收口：workspace 列已随 DDL 清理（列删除迁移见 20260818_memory_owner_ttl.sql），
		// WORKSPACE 语义重定义为数字员工共享记忆——subjectId 必须是数字员工 ID 并冗余落 digital_employee_id，
		// 不再接受任何 workspace_id 读写（读路径已随实体列映射移除收口）。
		Long digitalEmployeeId = resolveDigitalEmployeeId(scope, request.digitalEmployeeId(), request.subjectId());
		// 规则2：敏感记忆必须先取得用户同意
		MemorySensitivity sensitivity = request.sensitivity() == null ? MemorySensitivity.LOW : request.sensitivity();
		MemoryConsentStatus consentStatus = request.consentStatus() == null ? MemoryConsentStatus.UNSPECIFIED
				: request.consentStatus();
		if (sensitivity.requiresConsent() && !MemoryConsentStatus.GRANTED.equals(consentStatus)) {
			throw CheckedException.badRequest("敏感记忆必须先取得用户同意（consentStatus=GRANTED）才可写入");
		}
		// 规则3：内容红线校验
		String content = StringUtils.hasText(request.content()) ? request.content() : request.summary();
		validateMemoryContent(request.summary());
		validateMemoryContent(content);
		AgentMemoryType memoryType = request.memoryType() == null ? AgentMemoryType.SEMANTIC : request.memoryType();
		// 规则4：程序性记忆默认待人工审核
		boolean procedural = MemoryScope.PROCEDURAL.equals(scope) || AgentMemoryType.PROCEDURAL.equals(memoryType);
		AgentMemoryStatus status = procedural ? AgentMemoryStatus.PENDING_REVIEW : AgentMemoryStatus.ACTIVE;
		// 规则5：同 factKey 递增修订
		Integer revision = 1;
		if (StringUtils.hasText(request.factKey())) {
			Integer maxRevision = memoryMapper.findMaxRevision(request.agentId(), scope, request.subjectId(),
					request.factKey(), requireTenantId());
			revision = maxRevision == null ? 1 : maxRevision + 1;
		}
		// 规则6：租户上下文传播，不信任调用方传值
		String tenantId = requireTenantId();
		String currentUserId = currentUserIdOrDefault(request.subjectId());
		Instant now = Instant.now();
		AgentMemory memory = AgentMemory.builder()
			.tenantId(tenantId)
			.namespaceId(request.namespaceId())
			.agentId(request.agentId())
			.digitalEmployeeId(digitalEmployeeId)
			.userId(MemoryScope.EMPLOYEE_USER.equals(scope) ? request.subjectId() : currentUserId)
			.subjectType(scope)
			.subjectId(request.subjectId())
			.factKey(request.factKey())
			.revision(revision)
			.memoryType(memoryType)
			.summary(request.summary())
			.content(content)
			.semanticHash(semanticHash(request.agentId(), scope, request.subjectId(), request.factKey(),
					revision, request.summary()))
			.importance(normalizeDouble(request.importance(), 0.7, 0.0, 1.0))
			.confidence(normalizeDouble(request.confidence(), 0.8, 0.0, 1.0))
			.status(status)
			.provenance(request.provenance())
			.sourceRunId(request.sourceRunId())
			.sensitivity(sensitivity)
			.consentStatus(consentStatus)
			.validFrom(request.validFrom() == null ? now : request.validFrom())
			.validTo(request.validTo())
			.sourceSessionId(request.sourceSessionId())
			.sourceMessageId(request.sourceMessageId())
			.expiresAt(request.expiresAt())
			.useCount(0)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		// PR-3c 记忆写入线：现网校验全通过后做 PDP 判定（originalAllowed=TRUE 口径精确）；
		// SHADOW（默认）只记录影子决策，ENFORCE 拒绝时抛出并随事务回滚。
		// PR-7 owner 键贯穿：数字员工共享记忆（digitalEmployeeId 非空）按 DIGITAL_EMPLOYEE 主体键求值，
		// 其余保持 DATA_AGENT/CALLER 原口径（接缝位置不变，仅 owner 键按归属切换）。
		memoryAuthorizationAdvisor.checkMemoryAccess(PepDecisionContext.builder()
				.tenantId(tenantId)
				.runId(request.sourceRunId())
				.ownerType(digitalEmployeeId == null ? AuthorizationOwnerType.DATA_AGENT
						: AuthorizationOwnerType.DIGITAL_EMPLOYEE)
				.ownerId(digitalEmployeeId == null ? request.agentId() : digitalEmployeeId)
				.subjectKind(digitalEmployeeId == null ? SubjectKind.CALLER : SubjectKind.DIGITAL_EMPLOYEE)
				.subjectId(request.subjectId())
				.action(AuthorizationAction.WRITE_MEMORY)
				.build(), Boolean.TRUE);
		memoryMapper.insert(memory);
		// 日志只输出标识信息，不输出记忆内容，避免敏感 payload 进入日志
		log.info("长期记忆候选落库. memoryId={}, agentId={}, scope={}, factKey={}, revision={}, status={}, sourceRunId={}",
				memory.getId(), request.agentId(), scope, request.factKey(), revision, status,
				request.sourceRunId());
		return memory.getId();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void approveProceduralMemory(Long agentId, Long memoryId) {
		agentService.requireAgent(agentId);
		if (memoryId == null) {
			throw CheckedException.badRequest("记忆ID不能为空");
		}
		AgentMemory memory = memoryMapper.findByIdAndTenant(memoryId, requireTenantId());
		if (memory == null || !Objects.equals(memory.getAgentId(), agentId)) {
			throw CheckedException.notFound("长期记忆不存在");
		}
		if (!AgentMemoryStatus.PENDING_REVIEW.equals(memory.getStatus())) {
			throw CheckedException.badRequest("仅待人工审核状态的记忆可执行审核通过");
		}
		memory.setStatus(AgentMemoryStatus.ACTIVE);
		memory.setLastModifyTime(Instant.now());
		memoryMapper.updateById(memory);
		log.info("程序性记忆审核通过. memoryId={}, agentId={}, reviewer={}", memoryId, agentId, safeCurrentUserId());
	}

	@Override
	public List<AgentMemoryItemResp> listMemoriesByScope(Long agentId, MemoryScope scope, String subjectId) {
		return findByScopeStrict(agentId, scope, subjectId).stream().map(this::toItemDTO).toList();
	}

	@Override
	public List<AgentMemoryExportItemResp> exportMemoriesBySubject(Long agentId, MemoryScope scope, String subjectId) {
		requireExportSubjectOwnership(scope, subjectId);
		return findByScopeStrict(agentId, scope, subjectId).stream().map(this::toExportDTO).toList();
	}

	/**
	 * 用户侧记忆（EMPLOYEE_USER / EPISODIC / PROCEDURAL）只能导出当前登录用户自己的主体。
	 * SESSION 的 subjectId 是会话 ID，必须校验会话归属当前用户；WORKSPACE 的 subjectId 是数字员工 ID，
	 * 不要当成用户 ID。
	 */
	private void requireExportSubjectOwnership(MemoryScope scope, String subjectId) {
		if (scope == null) {
			return;
		}
		if (scope.userOwned()) {
			if (!StringUtils.hasText(subjectId) || !requireCurrentUserId().equals(subjectId.trim())) {
				throw CheckedException.forbidden(MemoryErrorDict.MEMORY_EXPORT_SUBJECT_FORBIDDEN.getLabel());
			}
			return;
		}
		if (MemoryScope.SESSION.equals(scope)) {
			requireSessionExportOwnership(subjectId);
		}
	}

	private void requireSessionExportOwnership(String subjectId) {
		Long sessionId = parseLongQuietly(subjectId);
		if (sessionId == null) {
			throw CheckedException.forbidden(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel());
		}
		DataChatSession session = chatSessionService.findBySessionId(sessionId);
		if (session == null || session.getUserId() == null
				|| !requireCurrentUserId().equals(String.valueOf(session.getUserId()))) {
			throw CheckedException.forbidden(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel());
		}
	}

	private String requireCurrentUserId() {
		String userId = safeCurrentUserId();
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.forbidden("无法解析当前登录用户，禁止导出记忆");
		}
		return userId.trim();
	}

	/**
	 * 按单一 scope 强制过滤查询：scope 必填且逐值匹配，用户记忆与工作空间记忆不会互相可见。
	 */
	private List<AgentMemory> findByScopeStrict(Long agentId, MemoryScope scope, String subjectId) {
		requireMemoryOwner(agentId);
		if (scope == null || !StringUtils.hasText(subjectId)) {
			throw CheckedException.badRequest("记忆范围与主体ID不能为空");
		}
		// PR-3c 记忆读取线：READ_MEMORY 走 PDP（MODEL_ONLY 策略下私有记忆不外泄），SHADOW 默认只记录。
		// PR-7 owner 键贯穿：WORKSPACE 共享记忆的 subjectId 即数字员工 ID，按 DIGITAL_EMPLOYEE 主体键求值。
		Long scopeEmployeeId = MemoryScope.WORKSPACE.equals(scope) ? parseLongQuietly(subjectId) : null;
		String tenantId = requireTenantId();
		memoryAuthorizationAdvisor.checkMemoryAccess(PepDecisionContext.builder()
				.tenantId(tenantId)
				.ownerType(scopeEmployeeId == null ? AuthorizationOwnerType.DATA_AGENT
						: AuthorizationOwnerType.DIGITAL_EMPLOYEE)
				.ownerId(scopeEmployeeId == null ? agentId : scopeEmployeeId)
				.subjectKind(scopeEmployeeId == null ? SubjectKind.CALLER : SubjectKind.DIGITAL_EMPLOYEE)
				.subjectId(subjectId)
				.action(AuthorizationAction.READ_MEMORY)
				.build(), Boolean.TRUE);
		return memoryMapper.findByScope(agentId, scope, subjectId, tenantId);
	}

	/**
	 * 内容红线：拒绝隐藏思维链标记与疑似原始业务 payload（超长文本、整段 JSON 结果）。
	 */
	private void validateMemoryContent(String text) {
		if (!StringUtils.hasText(text)) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (String marker : FORBIDDEN_CONTENT_MARKERS) {
			if (lower.contains(marker.toLowerCase(Locale.ROOT))) {
				throw CheckedException.badRequest("记忆内容禁止包含隐藏思维链或原始报文标记: " + marker);
			}
		}
		if (text.length() > MAX_MEMORY_CONTENT_LENGTH) {
			throw CheckedException.badRequest("记忆内容超过" + MAX_MEMORY_CONTENT_LENGTH + "字符，疑似原始业务payload，请提炼后写入");
		}
	}

	/**
	 * 规则1（续）：sourceRunId 在权威运行时中可查证时，以运行记录状态为准——必须已入 SUCCEEDED
	 * 终态才允许写入，非成功一律拒绝；查不到 run 时按现有参数校验放行并留 debug 日志。
	 * 迁移期语义：旧链路的 runId（遥测 run / 未镜像运行）尚不在 agent_runtime_run 中，
	 * 只能依赖调用方自报的 rootRunSucceeded，待运行链路全量切到统一运行时后收紧为 run 必须存在。
	 */
	private void requireSourceRunSucceeded(Long sourceRunId) {
		RuntimeRunState state = runtimeRunService.findRunState(currentTenantId(), sourceRunId);
		if (state == null) {
			log.debug("长期记忆候选来源 Run 不在权威运行时中, 按迁移期参数校验放行. sourceRunId={}", sourceRunId);
			return;
		}
		if (state != RuntimeRunState.SUCCEEDED) {
			throw CheckedException.badRequest("只有成功的根Run才能生成长期记忆候选, 当前运行状态: " + state.getValue());
		}
	}

	/**
	 * 记忆查询 owner：agentId 可能是 DataAgent 主键，也可能是数字员工主键（运行时合成主体）。
	 * 不改 {@code DataAgentService.requireAgent}，避免普通 Agent 接口被带偏。
	 */
	private void requireMemoryOwner(Long agentId) {
		if (agentId == null) {
			throw CheckedException.badRequest("记忆主体ID不能为空");
		}
		if (agentService.findById(agentId) != null) {
			return;
		}
		String tenantId = requireTenantId();
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(agentId, tenantId);
		if (employee != null) {
			return;
		}
		throw CheckedException.notFound(MemoryErrorDict.MEMORY_OWNER_NOT_FOUND.getValue(),
				MemoryErrorDict.MEMORY_OWNER_NOT_FOUND.getLabel());
	}

	private String currentTenantId() {
		try {
			String tenantId = authenticationContext.tenantId();
			return StringUtils.hasText(tenantId) ? tenantId.trim() : null;
		}
		catch (Exception ex) {
			log.warn("解析记忆写入租户上下文失败, 按无租户处理", ex);
			return null;
		}
	}

	private String requireTenantId() {
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问记忆");
		}
		return tenantId;
	}

	private String safeCurrentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			return null;
		}
	}

	private String currentUserIdOrDefault(String fallback) {
		String userId = safeCurrentUserId();
		return StringUtils.hasText(userId) ? userId : fallback;
	}

	/**
	 * PR-7 归属键解析：WORKSPACE（数字员工共享记忆）要求 subjectId 为数字员工 ID 并冗余落
	 * digital_employee_id；显式传入的 digitalEmployeeId 与 subjectId 不一致时拒绝（宁拒不错键）。
	 * 其余 scope 不要求数字员工归属，digitalEmployeeId 恒为 null。
	 */
	private Long resolveDigitalEmployeeId(MemoryScope scope, Long requestedEmployeeId, String subjectId) {
		if (!MemoryScope.WORKSPACE.equals(scope)) {
			if (requestedEmployeeId != null) {
				throw CheckedException.badRequest("非共享记忆范围不允许携带数字员工归属ID");
			}
			return null;
		}
		Long subjectEmployeeId = parseLongQuietly(subjectId);
		if (subjectEmployeeId == null) {
			throw CheckedException.badRequest("共享记忆主体ID必须是数字员工ID（WORKSPACE 归属键已切换）");
		}
		if (requestedEmployeeId != null && !requestedEmployeeId.equals(subjectEmployeeId)) {
			throw CheckedException.badRequest("共享记忆归属ID与主体ID不一致，拒绝写入");
		}
		return subjectEmployeeId;
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

	private String semanticHash(Long agentId, MemoryScope scope, String subjectId, String factKey, Integer revision,
			String summary) {
		String source = agentId + "|" + scope.getValue() + "|" + subjectId + "|" + factKey + "|" + revision + "|"
				+ summary;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			// SHA-256 属 JDK 必备算法，理论不可达；退化为 hashCode 仅保证非空约束
			log.error("SHA-256 不可用, 记忆语义哈希退化为 hashCode. agentId={}", agentId, ex);
			return String.valueOf(source.hashCode());
		}
	}

	private AgentMemoryExportItemResp toExportDTO(AgentMemory memory) {
		return AgentMemoryExportItemResp.builder()
			.id(memory.getId())
			.tenantId(memory.getTenantId())
			.namespaceId(memory.getNamespaceId())
			.agentId(memory.getAgentId())
			.digitalEmployeeId(memory.getDigitalEmployeeId())
			.userId(memory.getUserId())
			.subjectType(memory.getSubjectType())
			.subjectId(memory.getSubjectId())
			.factKey(memory.getFactKey())
			.revision(memory.getRevision())
			.memoryType(memory.getMemoryType())
			.summary(memory.getSummary())
			.content(memory.getContent())
			.provenance(memory.getProvenance())
			.sourceRunId(memory.getSourceRunId())
			.sensitivity(memory.getSensitivity())
			.consentStatus(memory.getConsentStatus())
			.validFrom(memory.getValidFrom())
			.validTo(memory.getValidTo())
			.sourceSessionId(memory.getSourceSessionId())
			.sourceMessageId(memory.getSourceMessageId())
			.importance(memory.getImportance())
			.confidence(memory.getConfidence())
			.status(memory.getStatus())
			.useCount(memory.getUseCount())
			.lastUsedTime(memory.getLastUsedTime())
			.expireTime(memory.getExpireTime())
			.expiresAt(memory.getExpiresAt())
			.createTime(memory.getCreateTime())
			.lastModifyTime(memory.getLastModifyTime())
			.build();
	}

	private AgentMemoryConfigResp toConfigDTO(AgentMemoryConfig config) {
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

	private AgentMemoryItemResp toItemDTO(AgentMemory memory) {
		return AgentMemoryItemResp.builder()
			.id(memory.getId())
			.agentId(memory.getAgentId())
			.userId(memory.getUserId())
			.subjectType(memory.getSubjectType())
			.subjectId(memory.getSubjectId())
			.factKey(memory.getFactKey())
			.revision(memory.getRevision())
			.memoryType(memory.getMemoryType())
			.summary(memory.getSummary())
			.sourceSessionId(memory.getSourceSessionId())
			.sensitivity(memory.getSensitivity())
			.consentStatus(memory.getConsentStatus())
			.validFrom(memory.getValidFrom())
			.validTo(memory.getValidTo())
			.confidence(memory.getConfidence())
			.importance(memory.getImportance())
			.useCount(memory.getUseCount())
			.lastUsedTime(memory.getLastUsedTime())
			.status(memory.getStatus())
			.createTime(memory.getCreateTime())
			.build();
	}

	private String toTypeString(List<AgentMemoryType> types) {
		return types == null || types.isEmpty() ? "" : DictEnum.toStr(types);
	}

	private List<AgentMemoryType> parseTypes(String types) {
		if (!StringUtils.hasText(types)) {
			return List.of();
		}
		return Arrays.stream(types.split(","))
			.map(String::trim)
			.filter(StringUtils::hasText)
			.map(value -> DictEnum.of(AgentMemoryType.class, value))
			.filter(Objects::nonNull)
			.toList();
	}

	private int normalizeInt(Integer value, int defaultValue, int min, int max) {
		int normalized = value == null ? defaultValue : value;
		return Math.max(min, Math.min(max, normalized));
	}

	private double normalizeDouble(Double value, double defaultValue, double min, double max) {
		double normalized = value == null ? defaultValue : value;
		return Math.max(min, Math.min(max, normalized));
	}

}
