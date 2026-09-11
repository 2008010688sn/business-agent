/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStepAttempt;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时步骤执行尝试 Mapper。查询发生在已校验租户归属的 run 语境内。
 */
@Repository
public interface AgentRuntimeStepAttemptMapper extends SuperMapper<AgentRuntimeStepAttempt> {

	default List<AgentRuntimeStepAttempt> listByStepId(Long stepId) {
		return selectList(Wraps.<AgentRuntimeStepAttempt>lbQ()
			.eq(AgentRuntimeStepAttempt::getStepId, stepId)
			.orderByAsc(AgentRuntimeStepAttempt::getAttemptNo));
	}

	/**
	 * 收尾尝试记录：仅未终态的尝试可收尾（终态不可覆盖），CAS 语义由 state 谓词承担，
	 * 属 Wraps 不适用的原生 SQL 场景。
	 */
	@Update("""
			UPDATE agent_runtime_step_attempt
			SET state = #{toState},
			    error_code = CAST(#{errorCode, jdbcType=VARCHAR} AS VARCHAR),
			    error_message = CAST(#{errorMessage, jdbcType=VARCHAR} AS TEXT),
			    finished_at = #{finishedAt},
			    last_modify_time = CURRENT_TIMESTAMP
			WHERE id = #{id}
			  AND deleted = false
			  AND state = 'RUNNING'
			""")
	int finishAttempt(@Param("id") Long id, @Param("toState") String toState, @Param("errorCode") String errorCode,
			@Param("errorMessage") String errorMessage, @Param("finishedAt") Instant finishedAt);

}
