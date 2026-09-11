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
import com.sn68.agent.dataagent.entity.TtsVoiceSample;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Tts语音SampleMapper服务契约。
 */
@Repository
public interface TtsVoiceSampleMapper extends SuperMapper<TtsVoiceSample> {

	/**
	 * 按主键查询音色试听样本；逻辑删除自动过滤。
	 */
	default TtsVoiceSample findById(Long id) {
		return selectOne(new LambdaQueryWrapper<TtsVoiceSample>().eq(TtsVoiceSample::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 查询指定租户的音色试听样本，按创建时间倒序。
	 */
	default List<TtsVoiceSample> findAll(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<TtsVoiceSample>lbQ()
			.eq(TtsVoiceSample::getTenantId, tenantId.trim())
			.orderByDesc(TtsVoiceSample::getCreateTime));
	}

}
