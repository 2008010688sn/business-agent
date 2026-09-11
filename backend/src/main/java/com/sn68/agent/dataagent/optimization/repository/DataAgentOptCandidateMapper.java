/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.repository;

import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidate;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent 自优化候选 Mapper。
 */
@Repository
public interface DataAgentOptCandidateMapper extends SuperMapper<DataAgentOptCandidate> {

	/**
	 * 按 ID 查询未删除的优化候选；已删除返回 null。
	 */
	default DataAgentOptCandidate findActiveById(Long id) {
		return selectOne(Wraps.<DataAgentOptCandidate>lbQ()
			.eq(DataAgentOptCandidate::getDeleted, false)
			.eq(DataAgentOptCandidate::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 查询实验下全部未删除的优化候选，按 ID 倒序（最新候选在前）。
	 */
	default List<DataAgentOptCandidate> findByExperimentId(Long experimentId) {
		return selectList(Wraps.<DataAgentOptCandidate>lbQ()
			.eq(DataAgentOptCandidate::getDeleted, false)
			.eq(DataAgentOptCandidate::getExperimentId, experimentId)
			.orderByDesc(DataAgentOptCandidate::getId));
	}

}
