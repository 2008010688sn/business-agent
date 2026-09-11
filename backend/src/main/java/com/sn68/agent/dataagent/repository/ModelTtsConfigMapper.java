/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.ModelTtsConfig;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 模型Tts配置Mapper服务契约。
 */
@Repository
public interface ModelTtsConfigMapper extends SuperMapper<ModelTtsConfig> {

	/**
	 * 按主键查询 TTS 扩展配置；逻辑删除自动过滤。
	 */
	default ModelTtsConfig findById(Long id) {
		return selectOne(new LambdaQueryWrapper<ModelTtsConfig>().eq(ModelTtsConfig::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按模型配置 ID 查询其 TTS 扩展配置（一对一），不存在返回 null。
	 */
	default ModelTtsConfig findByModelConfigId(Long modelConfigId) {
		return selectOne(new LambdaQueryWrapper<ModelTtsConfig>().eq(ModelTtsConfig::getModelConfigId, modelConfigId)
			.last(" limit 1"));
	}

	/**
	 * 查询全部未删除的 TTS 扩展配置（逻辑删除由 {@code @TableLogic} 过滤，即"active"语义）。
	 */
	default List<ModelTtsConfig> findAllActive() {
		return selectList(Wraps.<ModelTtsConfig>lbQ());
	}

}
