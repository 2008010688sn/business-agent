/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRunFeedback;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 运行人工反馈 Mapper。
 */
@Repository
public interface AgentRuntimeRunFeedbackMapper extends SuperMapper<AgentRuntimeRunFeedback> {

	default AgentRuntimeRunFeedback findByTenantRunUser(String tenantId, Long runId, String userId) {
		if (!StringUtils.hasText(tenantId) || runId == null || !StringUtils.hasText(userId)) {
			return null;
		}
		return selectOne(Wraps.<AgentRuntimeRunFeedback>lbQ()
			.eq(AgentRuntimeRunFeedback::getTenantId, tenantId.trim())
			.eq(AgentRuntimeRunFeedback::getRunId, runId)
			.eq(AgentRuntimeRunFeedback::getUserId, userId.trim())
			.last(" limit 1"));
	}

	default List<AgentRuntimeRunFeedback> listByTenantAndRun(String tenantId, Long runId) {
		if (!StringUtils.hasText(tenantId) || runId == null) {
			return List.of();
		}
		return selectList(Wraps.<AgentRuntimeRunFeedback>lbQ()
			.eq(AgentRuntimeRunFeedback::getTenantId, tenantId.trim())
			.eq(AgentRuntimeRunFeedback::getRunId, runId)
			.orderByDesc(AgentRuntimeRunFeedback::getId));
	}

	default long countDownByRunIds(String tenantId, Collection<Long> runIds) {
		if (!StringUtils.hasText(tenantId) || runIds == null || runIds.isEmpty()) {
			return 0L;
		}
		Long count = selectCount(Wraps.<AgentRuntimeRunFeedback>lbQ()
			.eq(AgentRuntimeRunFeedback::getTenantId, tenantId.trim())
			.in(AgentRuntimeRunFeedback::getRunId, runIds)
			.eq(AgentRuntimeRunFeedback::getRating, "DOWN"));
		return count == null ? 0L : count;
	}

}
