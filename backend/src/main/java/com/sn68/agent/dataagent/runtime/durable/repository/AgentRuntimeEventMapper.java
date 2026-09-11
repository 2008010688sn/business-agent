/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时事件 Mapper。
 *
 * <p>追加事件使用注解原生 SQL：seq 必须在数据库端以 MAX(seq)+1 原子分配并受
 * (run_id, seq) 唯一约束保护，Wraps 无法表达 INSERT ... SELECT 语义。
 * 并发追加撞号时抛唯一约束冲突，由 Service 层重试。</p>
 */
@Repository
public interface AgentRuntimeEventMapper extends SuperMapper<AgentRuntimeEvent> {

	/**
	 * 追加事件并在数据库端分配 run 内单调 seq。
	 * event_key 命中已有事件时不插入（幂等，返回 0）；seq 撞号抛 DuplicateKeyException 由调用方重试。
	 */
	@Insert("""
			INSERT INTO agent_runtime_event
			  (tenant_id, run_id, seq, event_key, event_type, step_key, payload, occurred_at, create_time, last_modify_time, deleted)
			SELECT #{tenantId}, #{runId},
			       COALESCE((SELECT MAX(seq) FROM agent_runtime_event WHERE run_id = #{runId} AND deleted = false), 0) + 1,
			       #{eventKey}, #{eventType}, CAST(#{stepKey, jdbcType=VARCHAR} AS VARCHAR),
			       COALESCE(CAST(#{payload, jdbcType=VARCHAR} AS JSONB), '{}'::jsonb),
			       #{occurredAt}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false
			WHERE NOT EXISTS (
			  SELECT 1 FROM agent_runtime_event
			  WHERE run_id = #{runId} AND event_key = #{eventKey} AND deleted = false
			)
			""")
	int appendWithSeq(@Param("tenantId") String tenantId, @Param("runId") Long runId, @Param("eventKey") String eventKey,
			@Param("eventType") String eventType, @Param("stepKey") String stepKey, @Param("payload") String payload,
			@Param("occurredAt") Instant occurredAt);

	/**
	 * 带 fence 的追加：旧写者（fence/lease 失配）插不进新 seq。event_key 已存在时不插入。
	 */
	@Insert("""
			INSERT INTO agent_runtime_event
			  (tenant_id, run_id, seq, event_key, event_type, step_key, payload, occurred_at, create_time, last_modify_time, deleted)
			SELECT #{tenantId}, #{runId},
			       COALESCE((SELECT MAX(seq) FROM agent_runtime_event WHERE run_id = #{runId} AND deleted = false), 0) + 1,
			       #{eventKey}, #{eventType}, CAST(#{stepKey, jdbcType=VARCHAR} AS VARCHAR),
			       COALESCE(CAST(#{payload, jdbcType=VARCHAR} AS JSONB), '{}'::jsonb),
			       #{occurredAt}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false
			WHERE NOT EXISTS (
			  SELECT 1 FROM agent_runtime_event
			  WHERE run_id = #{runId} AND event_key = #{eventKey} AND deleted = false
			)
			  AND EXISTS (
			    SELECT 1 FROM agent_runtime_run r
			    WHERE r.id = #{runId}
			      AND r.deleted = false
			      AND r.fence_token = #{fenceToken}
			      AND r.lease_owner = #{leaseOwner}
			  )
			""")
	int appendWithSeqAndFence(@Param("tenantId") String tenantId, @Param("runId") Long runId,
			@Param("eventKey") String eventKey, @Param("eventType") String eventType, @Param("stepKey") String stepKey,
			@Param("payload") String payload, @Param("occurredAt") Instant occurredAt,
			@Param("fenceToken") Long fenceToken, @Param("leaseOwner") String leaseOwner);

	/**
	 * 按 (run_id, event_key) 查已有事件，用于区分幂等命中与 fence 拒绝。
	 */
	default AgentRuntimeEvent findByRunIdAndEventKey(Long runId, String eventKey) {
		if (runId == null || !org.springframework.util.StringUtils.hasText(eventKey)) {
			return null;
		}
		return selectOne(Wraps.<AgentRuntimeEvent>lbQ()
			.eq(AgentRuntimeEvent::getRunId, runId)
			.eq(AgentRuntimeEvent::getEventKey, eventKey)
			.last("LIMIT 1"));
	}

	/**
	 * afterSeq 回放查询：按 seq 升序返回大于游标的事件，配合唯一 (run_id, seq) 保证无遗漏、无乱序、无重复。
	 */
	default List<AgentRuntimeEvent> listAfterSeq(Long runId, Long afterSeq, int limit) {
		return selectList(Wraps.<AgentRuntimeEvent>lbQ()
			.eq(AgentRuntimeEvent::getRunId, runId)
			.gt(AgentRuntimeEvent::getSeq, afterSeq == null ? 0L : afterSeq)
			.orderByAsc(AgentRuntimeEvent::getSeq)
			.last("LIMIT " + Math.max(1, limit)));
	}

	@Select("""
			SELECT COALESCE(MAX(seq), 0)
			FROM agent_runtime_event
			WHERE run_id = #{runId}
			  AND deleted = false
			""")
	Long maxSeq(@Param("runId") Long runId);

	/**
	 * 追加授权决策影子事件（PR-3c）：同 appendWithSeq 的 seq 原子分配与 event_key 幂等语义，
	 * 额外携带决策审计四列（decision_id/policy_hash/subject_kind/reason_code）与明细 JSONB。
	 * 审计写入属旁路观测，失败由调用方（ShadowRecorder）quiet 吞掉，不破坏主流程。
	 */
	@Insert("""
			INSERT INTO agent_runtime_event
			  (tenant_id, run_id, seq, event_key, event_type, step_key, payload, occurred_at,
			   create_time, last_modify_time, deleted,
			   decision_id, policy_hash, subject_kind, reason_code, detailed_decision_log)
			SELECT #{tenantId}, #{runId},
			       COALESCE((SELECT MAX(seq) FROM agent_runtime_event WHERE run_id = #{runId} AND deleted = false), 0) + 1,
			       #{eventKey}, #{eventType}, CAST(#{stepKey, jdbcType=VARCHAR} AS VARCHAR),
			       '{}'::jsonb,
			       #{occurredAt}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, false,
			       CAST(#{decisionId, jdbcType=VARCHAR} AS VARCHAR),
			       CAST(#{policyHash, jdbcType=VARCHAR} AS VARCHAR),
			       CAST(#{subjectKind, jdbcType=VARCHAR} AS VARCHAR),
			       CAST(#{reasonCode, jdbcType=VARCHAR} AS VARCHAR),
			       COALESCE(CAST(#{detailedLogJson, jdbcType=VARCHAR} AS JSONB), '{}'::jsonb)
			WHERE NOT EXISTS (
			  SELECT 1 FROM agent_runtime_event
			  WHERE run_id = #{runId} AND event_key = #{eventKey} AND deleted = false
			)
			""")
	int appendAuthorizationDecision(@Param("tenantId") String tenantId, @Param("runId") Long runId,
			@Param("eventKey") String eventKey, @Param("eventType") String eventType,
			@Param("stepKey") String stepKey, @Param("detailedLogJson") String detailedLogJson,
			@Param("decisionId") String decisionId, @Param("policyHash") String policyHash,
			@Param("subjectKind") String subjectKind, @Param("reasonCode") String reasonCode,
			@Param("occurredAt") Instant occurredAt);

	/**
	 * 按决策 ID + 租户读取授权决策影子事件（权限中心审计回显；租户隔离）。
	 */
	default AgentRuntimeEvent findAuthorizationDecision(String decisionId, String tenantId) {
		if (!org.springframework.util.StringUtils.hasText(decisionId) || tenantId == null) {
			return null;
		}
		return selectOne(Wraps.<AgentRuntimeEvent>lbQ()
			.eq(AgentRuntimeEvent::getDeleted, false)
			.eq(AgentRuntimeEvent::getTenantId, tenantId)
			.eq(AgentRuntimeEvent::getDecisionId, decisionId.trim())
			.eq(AgentRuntimeEvent::getEventType, "AUTHORIZATION_DECISION")
			.orderByDesc(AgentRuntimeEvent::getOccurredAt)
			.last("LIMIT 1"));
	}

}
