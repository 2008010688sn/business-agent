/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.repository;

import com.sn68.agent.dataagent.optimization.entity.DataAgentOptRelease;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Agent 自优化发布 Mapper。
 */
@Repository
public interface DataAgentOptReleaseMapper extends SuperMapper<DataAgentOptRelease> {

	/**
	 * 按 ID 查询未删除的优化发布记录；已删除返回 null。
	 */
	default DataAgentOptRelease findActiveById(Long id) {
		return selectOne(Wraps.<DataAgentOptRelease>lbQ()
			.eq(DataAgentOptRelease::getDeleted, false)
			.eq(DataAgentOptRelease::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按实验查询未删除的优化发布记录（自进化 LOOP 状态编排用）。
	 */
	default List<DataAgentOptRelease> findByExperimentId(Long experimentId) {
		return selectList(Wraps.<DataAgentOptRelease>lbQ()
			.eq(DataAgentOptRelease::getDeleted, false)
			.eq(DataAgentOptRelease::getExperimentId, experimentId)
			.orderByAsc(DataAgentOptRelease::getId));
	}

}
