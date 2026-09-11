/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 持久运行时事件服务实现。
 *
 * <p>刻意不使用 @Transactional：并发追加撞 (run_id, seq) 唯一约束后需要在新语句中重试，
 * 单个 PostgreSQL 事务在首次冲突后即进入 aborted 状态无法继续。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeEventServiceImpl implements RuntimeEventService {

	/**
	 * seq 撞号重试上限：撞号仅发生在同一 run 并发追加时，量级为并行分支数，5 次足够。
	 */
	private static final int MAX_SEQ_RETRY = 5;

	private final AgentRuntimeEventMapper eventMapper;

	private final ObjectMapper objectMapper;

	@Override
	public boolean append(String tenantId, Long runId, String eventKey, RuntimeEventType eventType, String stepKey,
			Map<String, Object> payload) {
		if (runId == null || eventKey == null || eventKey.isBlank() || eventType == null) {
			throw CheckedException.badRequest("事件追加参数不完整");
		}
		String payloadJson = serialize(payload);
		Instant occurredAt = Instant.now();
		// 事件行的 tenant_id 是冗余元数据：事件按 (run_id, seq) 定位与回放，归属由 run 决定，
		// 读取入口（RuntimeRunService#events）已先按租户校验过 run，落 0 不构成越权读取面。
		String safeTenantId = tenantId;
		DuplicateKeyException lastConflict = null;
		for (int i = 0; i < MAX_SEQ_RETRY; i++) {
			try {
				return eventMapper.appendWithSeq(safeTenantId, runId, eventKey, eventType.getValue(), stepKey,
						payloadJson, occurredAt) > 0;
			}
			catch (DuplicateKeyException conflict) {
				// (run_id, seq) 撞号：并发追加者抢占了同一 seq，重读 MAX(seq) 重试；
				// (run_id, event_key) 命中时 SQL 的 NOT EXISTS 已保证不会走到这里。
				lastConflict = conflict;
			}
		}
		log.error("事件追加撞号重试耗尽. runId={}, eventKey={}, eventType={}", runId, eventKey, eventType, lastConflict);
		throw CheckedException.fail("运行事件追加失败，seq 竞争重试耗尽, runId=" + runId + ", eventKey=" + eventKey);
	}

	@Override
	public boolean appendFenced(String tenantId, Long runId, String eventKey, RuntimeEventType eventType,
			String stepKey, Map<String, Object> payload, Long fenceToken, String leaseOwner) {
		if (fenceToken == null || leaseOwner == null || leaseOwner.isBlank()) {
			throw CheckedException.badRequest("事件追加缺少 fence");
		}
		if (runId == null || eventKey == null || eventKey.isBlank() || eventType == null) {
			throw CheckedException.badRequest("事件追加参数不完整");
		}
		String payloadJson = serialize(payload);
		Instant occurredAt = Instant.now();
		String safeTenantId = tenantId;
		DuplicateKeyException lastConflict = null;
		for (int i = 0; i < MAX_SEQ_RETRY; i++) {
			try {
				int inserted = eventMapper.appendWithSeqAndFence(safeTenantId, runId, eventKey, eventType.getValue(),
						stepKey, payloadJson, occurredAt, fenceToken, leaseOwner);
				if (inserted > 0) {
					return true;
				}
				if (eventMapper.findByRunIdAndEventKey(runId, eventKey) != null) {
					return false;
				}
				throw CheckedException.fail("事件追加被拒绝：fence 已失效或租约不属于当前写者");
			}
			catch (DuplicateKeyException conflict) {
				lastConflict = conflict;
			}
		}
		log.error("事件追加撞号重试耗尽. runId={}, eventKey={}, eventType={}", runId, eventKey, eventType, lastConflict);
		throw CheckedException.fail("运行事件追加失败，seq 竞争重试耗尽, runId=" + runId + ", eventKey=" + eventKey);
	}

	@Override
	public List<AgentRuntimeEvent> replayAfter(Long runId, Long afterSeq, int limit) {
		if (runId == null) {
			throw CheckedException.badRequest("runId 不能为空");
		}
		return eventMapper.listAfterSeq(runId, afterSeq, limit);
	}

	@Override
	public long latestSeq(Long runId) {
		if (runId == null) {
			throw CheckedException.badRequest("runId 不能为空");
		}
		Long maxSeq = eventMapper.maxSeq(runId);
		return maxSeq == null ? 0L : maxSeq;
	}

	private String serialize(Map<String, Object> payload) {
		if (payload == null || payload.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException ex) {
			throw CheckedException.fail("运行事件负载序列化失败: " + ex.getMessage());
		}
	}

}
