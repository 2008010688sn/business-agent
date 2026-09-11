/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时步骤产物 Mapper。查询发生在已校验租户归属的 run 语境内。
 */
@Repository
public interface AgentRuntimeArtifactMapper extends SuperMapper<AgentRuntimeArtifact> {

	default List<AgentRuntimeArtifact> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeArtifact>lbQ()
			.eq(AgentRuntimeArtifact::getRunId, runId)
			.orderByAsc(AgentRuntimeArtifact::getId));
	}

	default AgentRuntimeArtifact findLatestByRunIdAndStepKey(Long runId, String stepKey) {
		return selectOne(Wraps.<AgentRuntimeArtifact>lbQ()
			.eq(AgentRuntimeArtifact::getRunId, runId)
			.eq(AgentRuntimeArtifact::getStepKey, stepKey)
			.orderByDesc(AgentRuntimeArtifact::getId)
			.last("LIMIT 1"));
	}

}
