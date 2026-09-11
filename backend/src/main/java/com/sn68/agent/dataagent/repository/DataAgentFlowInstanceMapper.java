/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.flow.FlowInstanceStatus;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * FLOW instance mapper.
 */
@Repository
public interface DataAgentFlowInstanceMapper extends SuperMapper<DataAgentFlowInstance> {

	/**
	 * 查询用户在指定 Agent 会话线程下最新的活跃流程实例（活跃状态集见
	 * {@code FlowInstanceStatus.activeStatuses()}），按创建时间倒序取一条；逻辑删除自动过滤。
	 * 已过 {@code expires_at} 的实例不视为活跃（空值视为未过期）。
	 */
	default DataAgentFlowInstance findActive(String tenantId, Long agentId, String threadId, String userId) {
		List<String> statuses = FlowInstanceStatus.activeStatuses();
		return selectOne(notExpired(tenantScoped(Wraps.lbQ(), tenantId))
			.eq(DataAgentFlowInstance::getAgentId, agentId)
			.eq(DataAgentFlowInstance::getThreadId, threadId)
			.eq(DataAgentFlowInstance::getUserId, userId)
			.in(DataAgentFlowInstance::getStatus, statuses)
			.orderByDesc(DataAgentFlowInstance::getCreateTime)
			.last("LIMIT 1"));
	}

	/**
	 * 按实例 ID 查询活跃流程实例，同时校验租户/Agent/线程/用户归属，防止跨会话恢复他人实例；id 为空返回 null。
	 * 已过 {@code expires_at} 的实例不视为活跃（空值视为未过期）。
	 */
	default DataAgentFlowInstance findActiveById(Long id, String tenantId, Long agentId, String threadId,
			String userId) {
		if (id == null) {
			return null;
		}
		List<String> statuses = FlowInstanceStatus.activeStatuses();
		return selectOne(notExpired(tenantScoped(Wraps.lbQ(), tenantId))
			.eq(DataAgentFlowInstance::getId, id)
			.eq(DataAgentFlowInstance::getAgentId, agentId)
			.eq(DataAgentFlowInstance::getThreadId, threadId)
			.eq(DataAgentFlowInstance::getUserId, userId)
			.in(DataAgentFlowInstance::getStatus, statuses)
			.last("LIMIT 1"));
	}

	/**
	 * 流程实例推进（CAS 原生 SQL 先例，保留注解维护）：以 lock_version 乐观锁 + 租户条件整体推进
	 * 状态/当前节点/上下文/等待载荷等运行时列，命中即 lock_version 自增；返回 0 表示并发冲突，调用方需重读重试。
	 */
	@Update("""
			UPDATE data_agent_flow_instance
			SET status = #{status},
			    current_node_id = #{currentNodeId},
			    context_data = CAST(#{contextData} AS jsonb),
			    waiting_payload = CAST(#{waitingPayload} AS jsonb),
			    idempotency_key = #{idempotencyKey},
			    input_hash = #{inputHash},
			    context_revision = #{contextRevision},
			    resume_version = #{resumeVersion},
			    last_runtime_request_id = #{runtimeRequestId},
			    error_code = #{errorCode},
			    error_message = #{errorMessage},
			    finished_at = #{finishedAt},
			    lock_version = lock_version + 1,
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND tenant_id = #{tenantId}
			  AND lock_version = #{expectedVersion}
			  AND deleted = FALSE
			""")
	int advance(@Param("id") Long id, @Param("tenantId") String tenantId,
			@Param("expectedVersion") Integer expectedVersion, @Param("status") String status,
			@Param("currentNodeId") String currentNodeId, @Param("contextData") String contextData,
			@Param("waitingPayload") String waitingPayload, @Param("idempotencyKey") String idempotencyKey,
			@Param("inputHash") String inputHash, @Param("contextRevision") Long contextRevision,
			@Param("resumeVersion") Integer resumeVersion, @Param("runtimeRequestId") String runtimeRequestId,
			@Param("errorCode") String errorCode, @Param("errorMessage") String errorMessage,
			@Param("finishedAt") Instant finishedAt);

	private static LbqWrapper<DataAgentFlowInstance> tenantScoped(LbqWrapper<DataAgentFlowInstance> wrapper,
			String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问流程会话");
		}
		return wrapper.eq(DataAgentFlowInstance::getTenantId, tenantId.trim());
	}

	/** 活跃查询排除已过期实例：expires_at 为空或仍大于当前时刻。 */
	private static LbqWrapper<DataAgentFlowInstance> notExpired(LbqWrapper<DataAgentFlowInstance> wrapper) {
		return wrapper.and(w -> w.isNull(DataAgentFlowInstance::getExpiresAt)
			.or()
			.gt(DataAgentFlowInstance::getExpiresAt, Instant.now()));
	}

}
