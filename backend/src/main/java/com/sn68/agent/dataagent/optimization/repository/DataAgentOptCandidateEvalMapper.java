/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.repository;

import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidateEval;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

/**
 * Agent 自优化候选评估 Mapper。
 */
@Repository
public interface DataAgentOptCandidateEvalMapper extends SuperMapper<DataAgentOptCandidateEval> {

	/**
	 * 查询候选最近一次评估记录（ID 最大的一条）；无记录返回 null。
	 */
	default DataAgentOptCandidateEval findLatestByCandidateId(Long candidateId) {
		return selectOne(Wraps.<DataAgentOptCandidateEval>lbQ()
			.eq(DataAgentOptCandidateEval::getDeleted, false)
			.eq(DataAgentOptCandidateEval::getCandidateId, candidateId)
			.orderByDesc(DataAgentOptCandidateEval::getId)
			.last(" limit 1"));
	}

}
