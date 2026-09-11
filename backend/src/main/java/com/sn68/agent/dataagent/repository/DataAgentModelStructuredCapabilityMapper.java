/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.DataAgentModelStructuredCapability;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * FLOW structured-output capability persistence.
 */
@Repository
public interface DataAgentModelStructuredCapabilityMapper extends SuperMapper<DataAgentModelStructuredCapability> {

	/**
	 * 查询模型在指定任务画像下、且模型指纹/编译器版本/探针版本完全一致的结构化输出能力记录
	 * （任一版本变化都视为能力缓存失效，需重新探测）。
	 */
	default List<DataAgentModelStructuredCapability> findCurrent(Long modelConfigId, String taskProfile,
			String modelFingerprint, String compilerVersion, String probeVersion) {
		return selectList(Wraps.<DataAgentModelStructuredCapability>lbQ()
			.eq(DataAgentModelStructuredCapability::getModelConfigId, modelConfigId)
			.eq(DataAgentModelStructuredCapability::getTaskProfile, taskProfile)
			.eq(DataAgentModelStructuredCapability::getModelFingerprint, modelFingerprint)
			.eq(DataAgentModelStructuredCapability::getCompilerVersion, compilerVersion)
			.eq(DataAgentModelStructuredCapability::getProbeVersion, probeVersion));
	}

}
