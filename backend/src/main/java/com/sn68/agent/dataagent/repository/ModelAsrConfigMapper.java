/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.ModelAsrConfig;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

/**
 * 模型Asr配置Mapper服务契约。
 */
@Repository
public interface ModelAsrConfigMapper extends SuperMapper<ModelAsrConfig> {

	/**
	 * 按模型配置 ID 查询其 ASR 扩展配置（一对一），不存在返回 null。
	 */
	default ModelAsrConfig findByModelConfigId(Long modelConfigId) {
		return selectOne(new LambdaQueryWrapper<ModelAsrConfig>()
			.eq(ModelAsrConfig::getModelConfigId, modelConfigId)
			.last(" limit 1"));
	}

}
