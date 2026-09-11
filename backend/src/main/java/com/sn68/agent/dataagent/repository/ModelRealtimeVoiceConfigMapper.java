/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.ModelRealtimeVoiceConfig;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

/**
 * 模型实时语音配置Mapper服务契约。
 */
@Repository
public interface ModelRealtimeVoiceConfigMapper extends SuperMapper<ModelRealtimeVoiceConfig> {

	/**
	 * 按模型配置 ID 查询其实时语音扩展配置（一对一），不存在返回 null。
	 */
	default ModelRealtimeVoiceConfig findByModelConfigId(Long modelConfigId) {
		return selectOne(new LambdaQueryWrapper<ModelRealtimeVoiceConfig>()
			.eq(ModelRealtimeVoiceConfig::getModelConfigId, modelConfigId)
			.last(" limit 1"));
	}

}
