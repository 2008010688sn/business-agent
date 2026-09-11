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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.ModelConfigIdReq;
import com.sn68.agent.dataagent.dto.tts.TtsConfigAggregateDTO;
import com.sn68.agent.dataagent.service.tts.TtsConfigService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护语音合成模型配置聚合信息。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/model-config/tts")
@Tag(name = "TTS 模型配置", description = "维护语音合成模型配置聚合信息")
public class ModelTtsConfigController {

	private final TtsConfigService ttsConfigService;

	@Operation(summary = "查询TTS 模型配置清单", description = "查询TTS 模型配置清单，用于TTS 模型配置相关管理和运行场景。")
	@PostMapping("/query")
	public TtsConfigAggregateDTO get(@RequestBody ModelConfigIdReq request) {
		return ttsConfigService.getByModelConfigId(requireModelConfigId(request == null ? null : request.modelConfigId()));
	}

	@Operation(summary = "修改TTS 模型配置", description = "修改TTS 模型配置，用于TTS 模型配置相关管理和运行场景。")
	@AccessLog(module = "TTS 模型配置", description = "修改TTS 模型配置")
	@PutMapping("/modify")
	public TtsConfigAggregateDTO save(@RequestBody TtsConfigAggregateDTO request) {
		Long modelConfigId = request == null || request.getConfig() == null ? null
				: request.getConfig().getModelConfigId();
		return ttsConfigService.save(requireModelConfigId(modelConfigId), request);
	}

	private Long requireModelConfigId(Long modelConfigId) {
		if (modelConfigId == null) {
			throw CheckedException.badRequest("modelConfigId不能为空");
		}
		return modelConfigId;
	}

}
