/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.v2.V2ConfirmCredentialStore;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeApprovalState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeApprovalMapper;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.ui.ToolConfirmUiAssembler;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 持久运行时审批服务实现。
 *
 * <p>审批与 outbox 消息同事务写入（Outbox 模式）；懒惰过期在读取 / 决定 / 消费路径执行，
 * 分页列表返回存储态、不逐行改库。</p>
 *
 * <p>createPending 刻意不加方法级 @Transactional，而是把「插入审批 + 追加 outbox」这一段放进
 * {@link TransactionTemplate}：部分唯一索引
 * {@code agent_runtime_approval_uk_tenant_approval_key_pending} 会让并发插入冲突，
 * 而单个 PostgreSQL 事务在首次冲突后即进入 aborted 状态无法继续回查；内层事务回滚后由外层
 * 非事务语句回查复用既有 PENDING，「查-建非原子」由此变成真幂等。
 * 与 {@code RuntimeRunServiceImpl} / {@code RuntimeInvocationServiceImpl} 的幂等创建同一套路。
 * 前提：调用方不得把 createPending 包在自己的事务里（当前调用方——能力网关与任务发起器——均无事务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApprovalServiceImpl implements AgentApprovalService {

	/** 审批默认有效时长：过期未处理 / 未消费的审批懒惰置 EXPIRED，必须重新发起。 */
	static final Duration DEFAULT_APPROVAL_TTL = Duration.ofHours(24);

	/** 走审批链路的能力均为高风险（写操作 / 需人工确认 / ASSISTED 任务）。 */
	private static final String RISK_LEVEL_HIGH = "HIGH";

	/**
	 * 遗留兼容值：PR-1 拆除 workspace 域后 agent_runtime_approval.workspace_id 列仅作 NOT NULL 兼容落 0，
	 * 审批作用域由 approvalKey 的 owner/run 段编码，列清理随后续 PR 的 DDL 执行。
	 */
	private static final long LEGACY_WORKSPACE_ID = 0L;
	
	/** 无关联运行（管理动作）的作用域取值：表列 NOT NULL。 */
	private static final long NO_RUN_ID = 0L;
	
	/** 审批键中「既无 Run 也无来源关联ID」的占位段，避免不同管理动作因缺作用域而串键。 */
	private static final String SCOPE_NONE = "none";
	
	/** 审批键中「无运行主体」的 owner 占位段。 */
	private static final String OWNER_SCOPE_NONE = "none";
	
	private static final String PAYLOAD_CAPABILITY_CODE = "capabilityCode";
	
	private static final String PAYLOAD_OWNER_TYPE = "ownerType";
	
	private static final String PAYLOAD_OWNER_ID = "ownerId";

	private static final String PAYLOAD_PLAN_HASH = "planHash";

	private static final String PAYLOAD_SOURCE = "source";

	private static final String PAYLOAD_SOURCE_REF_ID = "sourceRefId";

	private final AgentRuntimeApprovalMapper approvalMapper;

	private final RuntimeOutboxService runtimeOutboxService;

	private final RuntimeEventService runtimeEventService;

	private final ObjectMapper objectMapper;

	private final TransactionTemplate transactionTemplate;

	private final ObjectProvider<V2ConfirmCredentialStore> confirmStoreProvider;

	@Override
	public AgentRuntimeApproval createPending(ApprovalCreateRequest request) {
		if (request == null || !StringUtils.hasText(request.capabilityCode())
				|| !StringUtils.hasText(request.paramsHash())) {
			throw CheckedException.badRequest("创建审批请求不完整：capabilityCode 与 paramsHash 必填");
		}
		// 租户解析失败失败关闭：落 0 的审批没有任何管理端能查到（审批入口一律按当前租户过滤），
		// 能力被永久锁死；同时所有解析失败的请求会共用同一个 0 号批复池，等同跨来源互相放行。
		requireTenant(request.tenantId());
		String tenantId = request.tenantId().trim();
		long runId = request.runId() == null ? NO_RUN_ID : request.runId();
		String approvalKey = approvalKey(request.ownerType(), request.ownerId(), runId, request.sourceRefId(),
				request.capabilityCode(), request.paramsHash());
		Instant now = Instant.now();
		AgentRuntimeApproval existing = findLatestByState(tenantId, runId, approvalKey,
				RuntimeApprovalState.PENDING);
		if (existing != null) {
			if (!expired(existing, now)) {
				return existing;
			}
			approvalMapper.expire(existing.getId(), now);
		}
		try {
			return insertPendingWithOutbox(request, tenantId, runId, approvalKey, now);
		}
		catch (DuplicateKeyException conflict) {
			// 部分唯一索引兜底：同（租户 + approvalKey）并发只会建成一条 PENDING，
			// 冲突方回查复用既有记录，审批人任批一条即可，不会出现重复审批卡片。
			AgentRuntimeApproval winner = findLatestByState(tenantId, runId, approvalKey,
					RuntimeApprovalState.PENDING);
			if (winner == null) {
				throw CheckedException.fail("审批幂等创建冲突后回查失败, approvalKey=" + approvalKey);
			}
			log.info("审批并发创建冲突, 复用已有待审批记录. approvalId={}, approvalKey={}", winner.getId(),
					approvalKey);
			return winner;
		}
	}

	/** 审批行与 outbox 消息必须同事务落库（Outbox 模式），冲突时整体回滚由外层回查兜底。 */
	private AgentRuntimeApproval insertPendingWithOutbox(ApprovalCreateRequest request, String tenantId, long runId,
			String approvalKey, Instant now) {
		return transactionTemplate.execute(status -> {
			AgentRuntimeApproval approval = AgentRuntimeApproval.builder()
				.tenantId(tenantId)
				// PR-1 遗留兼容：workspace_id 列 NOT NULL 但作用域已改由 approvalKey 编码，一律落 0。
				.workspaceId(LEGACY_WORKSPACE_ID)
				.runId(runId)
				.stepKey(request.stepKey())
				.approvalKey(approvalKey)
				.riskLevel(RISK_LEVEL_HIGH)
				.state(RuntimeApprovalState.PENDING.getValue())
				.requestDigest(request.paramsHash().trim())
				.payload(displayPayloadJson(request))
				.requestedBy(request.requestedBy())
				.expiresAt(request.expiresAt() == null ? now.plus(DEFAULT_APPROVAL_TTL) : request.expiresAt())
				.createTime(now)
				.lastModifyTime(now)
				.deleted(false)
				.build();
			approvalMapper.insert(approval);
			Map<String, Object> eventPayload = new LinkedHashMap<>();
			eventPayload.put("approvalId", approval.getId());
			eventPayload.put(PAYLOAD_CAPABILITY_CODE, request.capabilityCode().trim());
			eventPayload.put("paramsHash", request.paramsHash().trim());
			eventPayload.put("riskLevel", approval.getRiskLevel());
			eventPayload.put("expiresAt", approval.getExpiresAt().toString());
			putIfPresent(eventPayload, "stepKey", request.stepKey());
			putIfPresent(eventPayload, "requestedBy", request.requestedBy());
			putIfPresent(eventPayload, PAYLOAD_SOURCE, request.source());
			putIfPresent(eventPayload, PAYLOAD_SOURCE_REF_ID, request.sourceRefId());
			runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(tenantId, null,
					runId > NO_RUN_ID ? runId : null, null,
					RuntimeEventType.APPROVAL_REQUESTED.getValue(), "approval-requested:" + approval.getId(),
					eventPayload));
			appendRuntimeEventQuietly(tenantId, runId, "approval-requested:" + approval.getId(),
					RuntimeEventType.APPROVAL_REQUESTED, request.stepKey(), eventPayload);
			log.info("审批请求已创建. approvalId={}, tenantId={}, owner={}:{}, runId={}, approvalKey={}, "
					+ "expiresAt={}", approval.getId(), tenantId, request.ownerType(), request.ownerId(), runId,
					approvalKey, approval.getExpiresAt());
			return approval;
		});
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void approve(String tenantId, String approver, Long id, String comment) {
		decide(tenantId, approver, id, comment, RuntimeApprovalState.APPROVED);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void reject(String tenantId, String approver, Long id, String comment) {
		if (!StringUtils.hasText(comment)) {
			throw CheckedException.badRequest("驳回审批必须填写审批意见");
		}
		decide(tenantId, approver, id, comment, RuntimeApprovalState.REJECTED);
	}

	@Override
	public AgentRuntimeApproval findConsumableApproved(String tenantId, String ownerType, Long ownerId, Long runId,
			String capabilityCode, String paramsHash) {
		if (!StringUtils.hasText(capabilityCode) || !StringUtils.hasText(paramsHash)) {
			return null;
		}
		// 与 createPending 同一口径：租户解析失败不查 0 号池，直接失败关闭。
		requireTenant(tenantId);
		long run = runId == null ? NO_RUN_ID : runId;
		Instant now = Instant.now();
		AgentRuntimeApproval approved = findLatestByState(tenantId, run,
				approvalKey(ownerType, ownerId, run, null, capabilityCode, paramsHash),
				RuntimeApprovalState.APPROVED);
		if (approved == null) {
			return null;
		}
		if (expired(approved, now)) {
			approvalMapper.expire(approved.getId(), now);
			log.info("已通过审批超过有效期未消费, 懒惰置 EXPIRED. approvalId={}", approved.getId());
			return null;
		}
		return approved;
	}

	@Override
	public boolean consume(Long id) {
		if (id == null) {
			return false;
		}
		return approvalMapper.consume(id, Instant.now()) > 0;
	}

	@Override
	public boolean consumeMatching(String tenantId, Long approvalId, String ownerType, Long ownerId, String sourceRefId,
			String capabilityCode, String paramsHash) {
		if (approvalId == null || !StringUtils.hasText(capabilityCode) || !StringUtils.hasText(paramsHash)
				|| !StringUtils.hasText(sourceRefId)) {
			return false;
		}
		requireTenant(tenantId);
		AgentRuntimeApproval approval = approvalMapper.findByIdAndTenantId(approvalId, tenantId);
		if (approval == null) {
			return false;
		}
		Instant now = Instant.now();
		if (expired(approval, now)) {
			approvalMapper.expire(approval.getId(), now);
			return false;
		}
		if (!RuntimeApprovalState.APPROVED.getValue().equals(approval.getState())) {
			return false;
		}
		String storedRef = readDisplayPayload(approval).path(PAYLOAD_SOURCE_REF_ID).asText(null);
		if (!sourceRefId.trim().equals(storedRef)) {
			log.warn("审批来源实例不匹配，拒绝消费. approvalId={}, sourceRefId={}", approvalId, sourceRefId);
			return false;
		}
		long runId = approval.getRunId() == null ? NO_RUN_ID : approval.getRunId();
		String expectedKey = approvalKey(ownerType, ownerId, runId, sourceRefId, capabilityCode, paramsHash);
		if (!expectedKey.equals(approval.getApprovalKey())) {
			log.warn("审批指纹或作用域不匹配，拒绝消费. approvalId={}, expectedKey={}", approvalId, expectedKey);
			return false;
		}
		return consume(approval.getId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void cancel(String tenantId, String actor, Long id, String comment) {
		if (!StringUtils.hasText(actor)) {
			throw CheckedException.badRequest("审批作废操作人不能为空");
		}
		AgentRuntimeApproval approval = requireApproval(tenantId, id);
		Instant now = Instant.now();
		if (expired(approval, now) && approvalMapper.expire(approval.getId(), now) > 0) {
			throw CheckedException.badRequest("审批已过期, 无法作废, approvalId=" + id);
		}
		if (approvalMapper.cancel(id, actor.trim(), now,
				StringUtils.hasText(comment) ? comment.trim() : "FLOW 撤回管理端审批") == 0) {
			throw CheckedException.badRequest("审批无法作废（已消费、已驳回或不存在）, approvalId=" + id);
		}
		log.info("审批已作废. approvalId={}, actor={}", id, actor.trim());
	}

	@Override
	public IPage<RuntimeApprovalResp> page(String tenantId, RuntimeApprovalPageQueryReq req) {
		requireTenant(tenantId);
		RuntimeApprovalPageQueryReq query = req == null ? new RuntimeApprovalPageQueryReq() : req;
		IPage<AgentRuntimeApproval> page = approvalMapper.selectPage(query.buildPage(),
				Wraps.<AgentRuntimeApproval>lbQ()
					.eq(AgentRuntimeApproval::getTenantId, tenantId)
					.eq(AgentRuntimeApproval::getState, query.getState())
					.eq(AgentRuntimeApproval::getRunId, query.getRunId())
					.eq(AgentRuntimeApproval::getStepKey, query.getStepKey())
					.eq(AgentRuntimeApproval::getRiskLevel, query.getRiskLevel())
					.eq(AgentRuntimeApproval::getApprovalKey, query.getApprovalKey())
					.orderByDesc(AgentRuntimeApproval::getId));
		return page.convert(RuntimeApprovalResp::from);
	}

	@Override
	public RuntimeApprovalResp detail(String tenantId, Long id) {
		AgentRuntimeApproval approval = requireApproval(tenantId, id);
		if (expired(approval, Instant.now()) && approvalMapper.expire(approval.getId(), Instant.now()) > 0) {
			approval = approvalMapper.selectById(approval.getId());
		}
		return RuntimeApprovalResp.from(approval);
	}

	@Override
	public void decideToolConfirm(String tenantId, String actor, String toolCallId, String toolName,
			String paramFingerprint, boolean approved, String comment) {
		requireTenant(tenantId);
		if (!StringUtils.hasText(actor)) {
			throw CheckedException.badRequest("审批人不能为空");
		}
		if (!StringUtils.hasText(toolCallId) || !StringUtils.hasText(paramFingerprint)) {
			throw CheckedException.badRequest("写工具确认缺少 toolCallId 或参数指纹");
		}
		if (!approved && !StringUtils.hasText(comment)) {
			throw CheckedException.badRequest("驳回审批必须填写审批意见");
		}
		String capabilityCode = StringUtils.hasText(toolName) ? toolName.trim() : "tool-confirm";
		String fingerprint = paramFingerprint.trim();
		String callId = toolCallId.trim();
		String approvalKey = approvalKey(null, null, NO_RUN_ID, callId, capabilityCode, fingerprint);
		Instant now = Instant.now();
		AgentRuntimeApproval existing = approvalMapper.findLatestByApprovalKey(tenantId.trim(), approvalKey);
		if (existing != null) {
			if (expired(existing, now)) {
				approvalMapper.expire(existing.getId(), now);
				throw CheckedException.badRequest("写工具确认已过期, 请重新发起, toolCallId=" + callId);
			}
			String state = existing.getState();
			if (RuntimeApprovalState.PENDING.getValue().equals(state)) {
				applyToolConfirmDecision(tenantId, actor, existing.getId(), approved, comment);
				publishConfirmCredential(tenantId, actor, callId, capabilityCode, fingerprint, approved);
				return;
			}
			if (approved && (RuntimeApprovalState.APPROVED.getValue().equals(state)
					|| RuntimeApprovalState.CONSUMED.getValue().equals(state))) {
				publishConfirmCredential(tenantId, actor, callId, capabilityCode, fingerprint, true);
				return;
			}
			if (!approved && RuntimeApprovalState.REJECTED.getValue().equals(state)) {
				publishConfirmCredential(tenantId, actor, callId, capabilityCode, fingerprint, false);
				return;
			}
			throw CheckedException.badRequest("写工具确认已被处理, state=" + state + ", toolCallId=" + callId);
		}
		AgentRuntimeApproval pending = createPending(new ApprovalCreateRequest(tenantId.trim(), null, null, null, null,
				capabilityCode, fingerprint, null, actor.trim(), now.plus(ToolConfirmUiAssembler.CONFIRM_TTL),
				ToolConfirmUiAssembler.SOURCE_TOOL_CONFIRM, callId));
		applyToolConfirmDecision(tenantId, actor, pending.getId(), approved, comment);
		publishConfirmCredential(tenantId, actor, callId, capabilityCode, fingerprint, approved);
	}

	private void publishConfirmCredential(String tenantId, String actor, String toolCallId, String toolName,
			String fingerprint, boolean approved) {
		V2ConfirmCredentialStore store = confirmStoreProvider == null ? null : confirmStoreProvider.getIfAvailable();
		if (store == null) {
			return;
		}
		store.markDecision(tenantId, actor, toolCallId, toolName, fingerprint, approved);
	}

	private void applyToolConfirmDecision(String tenantId, String actor, Long id, boolean approved, String comment) {
		if (approved) {
			approve(tenantId, actor, id, comment);
			return;
		}
		reject(tenantId, actor, id, comment);
	}

	/**
	 * 审批决定（PENDING → APPROVED/REJECTED）：懒惰过期 → 状态谓词 CAS
	 * 保证一条审批只被决定一次。审批人身份闸门随 workspace 域拆除（PR-1），
	 * 平台级审批人校验由数字员工域（PR-5）重建，过渡期由 Sa-Token 权限码约束 Web/IM 入口。
	 */
	private void decide(String tenantId, String approver, Long id, String comment, RuntimeApprovalState target) {
		if (!StringUtils.hasText(approver)) {
			throw CheckedException.badRequest("审批人不能为空");
		}
		AgentRuntimeApproval approval = requireApproval(tenantId, id);
		Instant now = Instant.now();
		if (expired(approval, now)) {
			approvalMapper.expire(approval.getId(), now);
			throw CheckedException.badRequest("审批已过期, 无法" + target.getLabel() + ", 请重新发起审批, approvalId=" + id);
		}
		if (!RuntimeApprovalState.PENDING.getValue().equals(approval.getState())) {
			throw CheckedException.badRequest("非法审批状态流转: " + approval.getState() + " → " + target.getValue()
					+ ", 仅待审批记录可决定, approvalId=" + id);
		}
		if (approvalMapper.decide(id, target.getValue(), approver.trim(), now, comment) == 0) {
			throw CheckedException.badRequest("审批已被并发处理, 请刷新后重试, approvalId=" + id);
		}
		Map<String, Object> eventPayload = decisionEventPayload(approval, target, approver.trim());
		runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(approval.getTenantId(), null,
				approval.getRunId() != null && approval.getRunId() > NO_RUN_ID ? approval.getRunId() : null,
				null, RuntimeEventType.APPROVAL_DECIDED.getValue(), "approval-decided:" + id, eventPayload));
		appendRuntimeEventQuietly(approval.getTenantId(), approval.getRunId(), "approval-decided:" + id,
				RuntimeEventType.APPROVAL_DECIDED, approval.getStepKey(), eventPayload);
		log.info("审批已决定. approvalId={}, decision={}, approver={}", id, target.getValue(), approver.trim());
	}

	private Map<String, Object> decisionEventPayload(AgentRuntimeApproval approval, RuntimeApprovalState target,
			String approver) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("approvalId", approval.getId());
		payload.put("state", target.getValue());
		payload.put("approver", approver);
		payload.put("approvalKey", approval.getApprovalKey());
		putIfPresent(payload, "stepKey", approval.getStepKey());
		JsonNode display = readDisplayPayload(approval);
		putIfPresent(payload, PAYLOAD_CAPABILITY_CODE, display.path(PAYLOAD_CAPABILITY_CODE).asText(null));
		putIfPresent(payload, PAYLOAD_SOURCE, display.path(PAYLOAD_SOURCE).asText(null));
		putIfPresent(payload, PAYLOAD_SOURCE_REF_ID, display.path(PAYLOAD_SOURCE_REF_ID).asText(null));
		return payload;
	}

	/** 展示负载只放脱敏元数据（能力编码、owner、来源、计划哈希），绝不落原始调用参数与凭据。 */
	private String displayPayloadJson(ApprovalCreateRequest request) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put(PAYLOAD_CAPABILITY_CODE, request.capabilityCode().trim());
		putIfPresent(payload, PAYLOAD_OWNER_TYPE, request.ownerType());
		if (request.ownerId() != null) {
			payload.put(PAYLOAD_OWNER_ID, request.ownerId());
		}
		putIfPresent(payload, PAYLOAD_PLAN_HASH, request.planHash());
		putIfPresent(payload, PAYLOAD_SOURCE, request.source());
		putIfPresent(payload, PAYLOAD_SOURCE_REF_ID, request.sourceRefId());
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (Exception ex) {
			throw CheckedException.fail("审批展示负载序列化失败：" + ex.getMessage());
		}
	}

	private JsonNode readDisplayPayload(AgentRuntimeApproval approval) {
		try {
			return objectMapper.readTree(StringUtils.hasText(approval.getPayload()) ? approval.getPayload() : "{}");
		}
		catch (Exception ex) {
			log.warn("审批展示负载解析失败, 事件负载降级为基础字段. approvalId={}", approval.getId());
			return objectMapper.createObjectNode();
		}
	}
	
	private AgentRuntimeApproval requireApproval(String tenantId, Long id) {
		requireTenant(tenantId);
		if (id == null) {
			throw CheckedException.badRequest("审批ID不能为空");
		}
		AgentRuntimeApproval approval = approvalMapper.findByIdAndTenantId(id, tenantId);
		if (approval == null) {
			throw CheckedException.notFound("审批记录不存在或无权访问: " + id);
		}
		return approval;
	}

	private void requireTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("租户上下文缺失或非法，审批链路拒绝执行, tenantId=" + tenantId);
		}
	}

	private boolean expired(AgentRuntimeApproval approval, Instant now) {
		return approval.getExpiresAt() != null && !approval.getExpiresAt().isAfter(now);
	}

	/** 运行事件流是审批的附属观测记录，失败可见但不回滚审批主流程；无关联运行（runId=0）不写。 */
	private void appendRuntimeEventQuietly(String tenantId, Long runId, String eventKey, RuntimeEventType eventType,
			String stepKey, Map<String, Object> payload) {
		if (runId == null || runId <= 0) {
			return;
		}
		try {
			runtimeEventService.append(tenantId, runId, eventKey, eventType, stepKey, payload);
		}
		catch (RuntimeException ex) {
			log.error("审批运行事件追加失败. runId={}, eventKey={}", runId, eventKey, ex);
		}
	}

	private AgentRuntimeApproval findLatestByState(String tenantId, long runId, String approvalKey,
			RuntimeApprovalState state) {
		return approvalMapper.findLatestByRunAndApprovalKeyAndState(tenantId, runId, approvalKey,
				state.getValue());
	}

	/**
	 * 审批幂等键（PR-1 起 owner 维度）：{@code owner:{作用域}|run:{作用域}|{capabilityCode}:{paramsHash}}。
	 *
	 * <p>owner 与运行必须入键，否则 owner A 的批复会被 owner B 的自动任务在 TTL 内复用消费掉
	 * （反向亦然：合法 Run 的批复被别的 Run 抢先消费）。运行段取值优先级：
	 * <ol>
	 * <li>有 Run：{@code run:{runId}}；</li>
	 * <li>无 Run 但有来源关联ID（如 taskRunId）：{@code ref:{sourceRefId}}；</li>
	 * <li>两者皆无的管理动作：常量 {@code none}——同租户下的同能力同参数视为同一次管理动作，
	 * 这是该场景可接受的最细粒度。</li>
	 * </ol>
	 * owner 段取值：ownerType 与 ownerId 同时在才入键，任一缺失按 {@code none} 兑底（租户隔离仍在）。
	 * 消费侧（findConsumableApproved）不传 sourceRefId：批复的消费方是能力网关，只按 Run 作用域匹配；
	 * 带 sourceRefId 的审批由其来源方（任务发起器）自行按 approvalId 消费。</p>
	 */
	private String approvalKey(String ownerType, Long ownerId, long runId, String sourceRefId, String capabilityCode,
			String paramsHash) {
		return "owner:" + ownerScope(ownerType, ownerId) + "|run:" + runScope(runId, sourceRefId) + "|"
				+ capabilityCode.trim() + ":" + paramsHash.trim();
	}

	private String ownerScope(String ownerType, Long ownerId) {
		if (StringUtils.hasText(ownerType) && ownerId != null && ownerId > 0L) {
			return ownerType.trim() + ":" + ownerId;
		}
		return OWNER_SCOPE_NONE;
	}

	private String runScope(long runId, String sourceRefId) {
		if (runId > NO_RUN_ID) {
			return String.valueOf(runId);
		}
		return StringUtils.hasText(sourceRefId) ? "ref:" + sourceRefId.trim() : SCOPE_NONE;
	}

	private void putIfPresent(Map<String, Object> payload, String key, String value) {
		if (StringUtils.hasText(value)) {
			payload.put(key, value.trim());
		}
	}

}
