/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeInterruption;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时中断请求 Mapper。
 *
 * <p>按 runtimeRequestId 的查询是系统级路径：runtimeRequestId 为全局唯一 UUID，
 * 内存注册表在无用户会话的运行时线程上回源，不带 tenant_id 条件。</p>
 */
@Repository
public interface AgentRuntimeInterruptionMapper extends SuperMapper<AgentRuntimeInterruption> {

	default boolean existsCancelByRuntimeRequestId(String runtimeRequestId) {
		return selectCount(Wraps.<AgentRuntimeInterruption>lbQ()
			.eq(AgentRuntimeInterruption::getRuntimeRequestId, runtimeRequestId)
			.eq(AgentRuntimeInterruption::getInterruptionType, "CANCEL")) > 0;
	}

	default List<AgentRuntimeInterruption> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeInterruption>lbQ()
			.eq(AgentRuntimeInterruption::getRunId, runId)
			.orderByAsc(AgentRuntimeInterruption::getId));
	}

	/**
	 * 中断落地（REQUESTED → APPLIED）。属状态谓词保护的原生 SQL，Wraps 不适用。
	 */
	@Update("""
			UPDATE agent_runtime_interruption
			SET state = 'APPLIED',
			    applied_at = #{appliedAt},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state = 'REQUESTED'
			""")
	int markApplied(@Param("id") Long id, @Param("appliedAt") Instant appliedAt);

}
