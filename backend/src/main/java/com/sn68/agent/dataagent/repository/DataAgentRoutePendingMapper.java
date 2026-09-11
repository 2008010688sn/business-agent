/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentRoutePending;
import com.sn68.agent.dataagent.service.routing.RoutePendingService;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/**
 * 路由待续接（pending continuation）Mapper：管理澄清/确认等中断后待恢复的路由绑定记录。
 *
 * <p>状态流转 PENDING → CONSUMED → 终态执行状态，或 PENDING → EXPIRED；
 * consume/finishClaimed 通过条件更新实现单次消费与幂等，命中 0 行即表示已被并发消费或过期。
 */
@Repository
public interface DataAgentRoutePendingMapper extends SuperMapper<DataAgentRoutePending> {

	/**
	 * 将已过期（expiresAt <= now）的 PENDING 记录批量置为 EXPIRED；now 为空时不执行。
	 */
	default int expirePending(Instant now) {
		if (now == null) {
			return 0;
		}
		return update(null, Wraps.<DataAgentRoutePending>lbU()
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.PENDING)
			.le(DataAgentRoutePending::getExpiresAt, now)
			.set(DataAgentRoutePending::getStatus, RoutePendingStatus.EXPIRED)
			.set(DataAgentRoutePending::getExecutionState, RoutePendingService.EXECUTION_EXPIRED)
			.set(DataAgentRoutePending::getLastModifyTime, now));
	}

	/**
	 * 按租户/智能体/用户/会话取最新一条未过期 PENDING，供无 token 的选项文案对齐。
	 */
	default DataAgentRoutePending findLatestPending(String tenantId, Long ownerAgentId, String userId, String threadId,
			Instant now) {
		if (now == null || ownerAgentId == null || tenantId == null || tenantId.isBlank() || userId == null
				|| userId.isBlank() || threadId == null || threadId.isBlank()) {
			return null;
		}
		return selectOne(Wraps.<DataAgentRoutePending>lbQ()
			.eq(DataAgentRoutePending::getTenantId, tenantId)
			.eq(DataAgentRoutePending::getOwnerAgentId, ownerAgentId)
			.eq(DataAgentRoutePending::getUserId, userId)
			.eq(DataAgentRoutePending::getThreadId, threadId)
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.PENDING)
			.gt(DataAgentRoutePending::getExpiresAt, now)
			.orderByDesc(DataAgentRoutePending::getCreateTime)
			.last("LIMIT 1"));
	}

	/**
	 * 将未消费 PENDING 标为过期/取消，避免选项文案被当成新问句。
	 */
	default int cancelPending(Long id, Instant now) {
		if (id == null || now == null) {
			return 0;
		}
		return update(null, Wraps.<DataAgentRoutePending>lbU()
			.eq(DataAgentRoutePending::getId, id)
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.PENDING)
			.set(DataAgentRoutePending::getStatus, RoutePendingStatus.EXPIRED)
			.set(DataAgentRoutePending::getExecutionState, RoutePendingService.EXECUTION_CANCELLED)
			.set(DataAgentRoutePending::getLastModifyTime, now));
	}

	/**
	 * 按续接令牌哈希查询未过期的 PENDING 记录；参数缺失时直接返回 null。
	 */
	default DataAgentRoutePending findActive(String tokenHash, Instant now) {
		if (tokenHash == null || tokenHash.isBlank() || now == null) {
			return null;
		}
		return selectOne(Wraps.<DataAgentRoutePending>lbQ()
			.eq(DataAgentRoutePending::getTokenHash, tokenHash)
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.PENDING)
			.gt(DataAgentRoutePending::getExpiresAt, now)
			.last("LIMIT 1"));
	}

	/**
	 * 单次消费续接记录：以 ID + 令牌 + 租户/用户/线程等全字段做条件更新（PENDING 且未过期才命中），
	 * 置为 CONSUMED 并登记消费方 runtimeRequestId；返回 0 表示已被并发消费、过期或参数不完整。
	 */
	default int consume(DataAgentRoutePending binding, String consumedRuntimeRequestId, Instant now) {
		if (binding == null || binding.getId() == null || binding.getOwnerAgentId() == null || now == null
				|| binding.getTokenHash() == null || binding.getTokenHash().isBlank()
				|| binding.getTenantId() == null || binding.getTenantId().isBlank()
				|| binding.getUserId() == null || binding.getUserId().isBlank()
				|| binding.getThreadId() == null || binding.getThreadId().isBlank()
				|| binding.getRuntimeRequestId() == null || binding.getRuntimeRequestId().isBlank()
				|| consumedRuntimeRequestId == null || consumedRuntimeRequestId.isBlank()) {
			return 0;
		}
		return update(null, Wraps.<DataAgentRoutePending>lbU()
			.eq(DataAgentRoutePending::getId, binding.getId())
			.eq(DataAgentRoutePending::getTokenHash, binding.getTokenHash())
			.eq(DataAgentRoutePending::getTenantId, binding.getTenantId())
			.eq(DataAgentRoutePending::getUserId, binding.getUserId())
			.eq(DataAgentRoutePending::getOwnerAgentId, binding.getOwnerAgentId())
			.eq(DataAgentRoutePending::getThreadId, binding.getThreadId())
			.eq(DataAgentRoutePending::getRuntimeRequestId, binding.getRuntimeRequestId())
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.PENDING)
			.gt(DataAgentRoutePending::getExpiresAt, now)
			.set(DataAgentRoutePending::getStatus, RoutePendingStatus.CONSUMED)
			.set(DataAgentRoutePending::getExecutionState, RoutePendingService.EXECUTION_CLAIMED)
			.set(DataAgentRoutePending::getConsumedAt, now)
			.set(DataAgentRoutePending::getConsumedRuntimeRequestId, consumedRuntimeRequestId)
			.set(DataAgentRoutePending::getLastModifyTime, now));
	}

	/**
	 * 将已被本 runtimeRequestId 认领（CONSUMED + CLAIMED）的记录落到终态执行状态并写结果引用；
	 * 仅命中认领方本人的记录，参数不完整时直接返回 0。
	 */
	default int finishClaimed(String tenantId, Long ownerAgentId, String userId, String threadId,
			String consumedRuntimeRequestId, String executionState, String resultReference, Instant now) {
		if (ownerAgentId == null || tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()
				|| threadId == null || threadId.isBlank() || consumedRuntimeRequestId == null
				|| consumedRuntimeRequestId.isBlank() || executionState == null || executionState.isBlank() || now == null) {
			return 0;
		}
		return update(null, Wraps.<DataAgentRoutePending>lbU()
			.eq(DataAgentRoutePending::getTenantId, tenantId)
			.eq(DataAgentRoutePending::getOwnerAgentId, ownerAgentId)
			.eq(DataAgentRoutePending::getUserId, userId)
			.eq(DataAgentRoutePending::getThreadId, threadId)
			.eq(DataAgentRoutePending::getConsumedRuntimeRequestId, consumedRuntimeRequestId)
			.eq(DataAgentRoutePending::getStatus, RoutePendingStatus.CONSUMED)
			.eq(DataAgentRoutePending::getExecutionState, RoutePendingService.EXECUTION_CLAIMED)
			.set(DataAgentRoutePending::getExecutionState, executionState)
			.set(DataAgentRoutePending::getResultReference, resultReference)
			.set(DataAgentRoutePending::getLastModifyTime, now));
	}

}
