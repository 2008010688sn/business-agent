/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeOutbox;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时 Outbox Mapper。
 *
 * <p>认领使用注解原生 SQL：依赖 FOR UPDATE SKIP LOCKED 避免多节点重复认领，Wraps 无法表达；
 * 派发器语义为 at-least-once，消费方需按 event_id / 业务键幂等。</p>
 */
@Repository
public interface AgentRuntimeOutboxMapper extends SuperMapper<AgentRuntimeOutbox> {

	/**
	 * 认领一批待派发消息（PENDING 或到期重试的 FAILED），跳过其他节点已锁定的行。
	 *
	 * <p>必须跳过租户与数据权限拦截器，两个原因缺一不可：
	 * <ul>
	 * <li>语义：派发器是系统级定时任务，无租户/用户上下文，必须跨全部租户认领；</li>
	 * <li>正确性：这两个拦截器基于 JSqlParser 做「解析 → 序列化」往返，而 JSqlParser 4.9 会把
	 * {@code FOR UPDATE} 输出到 {@code ORDER BY} / {@code LIMIT} 之前，生成 PostgreSQL 无法解析的语句。
	 * 单纯的 {@code FOR UPDATE} 不受影响，只有与 {@code ORDER BY} / {@code LIMIT} 组合时才会被改坏。</li>
	 * </ul>
	 * 删除该注解会让本方法在运行期抛 BadSqlGrammarException。
	 */
	@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
	@Select("""
			SELECT * FROM agent_runtime_outbox
			WHERE deleted = false
			  AND (state = 'PENDING' OR (state = 'FAILED' AND next_retry_at IS NOT NULL AND next_retry_at <= #{now}))
			ORDER BY id
			LIMIT #{limit}
			FOR UPDATE SKIP LOCKED
			""")
	List<AgentRuntimeOutbox> claimPending(@Param("now") Instant now, @Param("limit") int limit);

	@Update("""
			UPDATE agent_runtime_outbox
			SET state = 'DISPATCHED',
			    dispatched_at = #{dispatchedAt},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state IN ('PENDING','FAILED')
			""")
	int markDispatched(@Param("id") Long id, @Param("dispatchedAt") Instant dispatchedAt);

	@Update("""
			UPDATE agent_runtime_outbox
			SET state = #{toState},
			    retry_count = retry_count + 1,
			    next_retry_at = CAST(#{nextRetryAt, jdbcType=TIMESTAMP} AS TIMESTAMP),
			    last_error = #{lastError},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state IN ('PENDING','FAILED')
			""")
	int markFailed(@Param("id") Long id, @Param("toState") String toState, @Param("nextRetryAt") Instant nextRetryAt,
			@Param("lastError") String lastError);

}
