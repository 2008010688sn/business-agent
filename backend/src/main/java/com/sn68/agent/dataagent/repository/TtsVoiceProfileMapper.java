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
import com.sn68.agent.dataagent.entity.TtsVoiceProfile;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Tts语音ProfileMapper服务契约。
 */
@Repository
public interface TtsVoiceProfileMapper extends SuperMapper<TtsVoiceProfile> {

	/**
	 * 按主键查询音色配置；逻辑删除自动过滤。
	 */
	default TtsVoiceProfile findById(Long id) {
		return selectOne(new LambdaQueryWrapper<TtsVoiceProfile>().eq(TtsVoiceProfile::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 查询 TTS 配置下全部音色（含停用），默认音色优先、ID 升序。
	 */
	default List<TtsVoiceProfile> findByTtsConfigId(Long ttsConfigId) {
		return selectList(new LambdaQueryWrapper<TtsVoiceProfile>().eq(TtsVoiceProfile::getTtsConfigId, ttsConfigId)
			.orderByDesc(TtsVoiceProfile::getIsDefault)
			.orderByAsc(TtsVoiceProfile::getId));
	}

	/**
	 * 查询 TTS 配置下已启用的默认音色（isDefault=true 且 enabled=true），不存在返回 null。
	 */
	default TtsVoiceProfile findDefaultByTtsConfigId(Long ttsConfigId) {
		return selectOne(new LambdaQueryWrapper<TtsVoiceProfile>().eq(TtsVoiceProfile::getTtsConfigId, ttsConfigId)
			.eq(TtsVoiceProfile::getEnabled, true)
			.eq(TtsVoiceProfile::getIsDefault, true)
			.last(" limit 1"));
	}

	/**
	 * 清除同一 TTS 配置下其它音色的默认标记。
	 */
	default void clearDefault(Long ttsConfigId, Long keepId) {
		if (ttsConfigId == null) {
			// Wraps 会跳过空值条件，缺少 ttsConfigId 时这条 UPDATE 会清空全平台所有音色的默认标记。
			throw CheckedException.badRequest("ttsConfigId 不能为空，无法清除默认音色标记");
		}
		update(null, Wraps.<TtsVoiceProfile>lbU().eq(TtsVoiceProfile::getTtsConfigId, ttsConfigId)
			.ne(keepId != null, TtsVoiceProfile::getId, keepId)
			.set(TtsVoiceProfile::getIsDefault, false)
			.set(TtsVoiceProfile::getLastModifyTime, Instant.now()));
	}

}
