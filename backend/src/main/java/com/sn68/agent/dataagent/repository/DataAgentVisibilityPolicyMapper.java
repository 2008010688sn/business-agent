/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

/**
 * DataAgent可见性策略Mapper服务契约。
 */
@Repository
public interface DataAgentVisibilityPolicyMapper extends SuperMapper<DataAgentVisibilityPolicy> {

	/**
	 * 查询 Agent 的可见性策略（一对一）；agentId 为空返回 null。
	 */
	default DataAgentVisibilityPolicy findByAgentId(Long agentId) {
		if (agentId == null) {
			return null;
		}
		return selectOne(Wraps.<DataAgentVisibilityPolicy>lbQ()
			.eq(DataAgentVisibilityPolicy::getAgentId, agentId)
			.last("limit 1"));
	}

}
