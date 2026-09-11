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
package com.sn68.agent.dataagent.service.tts;

import com.sn68.agent.dataagent.dto.tts.TtsConfigAggregateDTO;

/**
 * Tts配置服务契约。
 */
public interface TtsConfigService {

	/**
	 * 查询Tts配置。
	 */
	TtsConfigAggregateDTO getByModelConfigId(Long modelConfigId);

	/**
	 * 保存Tts配置。
	 */
	TtsConfigAggregateDTO save(Long modelConfigId, TtsConfigAggregateDTO request);

}
