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

import com.sn68.agent.dataagent.dto.tts.RealtimeSignalReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeSignalResp;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionReq;
import com.sn68.agent.dataagent.dto.tts.RealtimeVoiceSessionVO;
import com.sn68.agent.dataagent.service.realtime.RealtimeVoiceSessionService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理实时语音会话、信令、打断和释放。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/realtime-voice/sessions")
@Tag(name = "实时语音会话", description = "管理实时语音会话、信令、打断和释放")
public class RealtimeVoiceController {

	private final RealtimeVoiceSessionService realtimeVoiceSessionService;

	@Operation(summary = "创建实时语音会话", description = "创建实时语音会话，用于实时语音会话相关管理和运行场景。")
	@AccessLog(module = "实时语音会话", description = "创建实时语音会话")
	@PostMapping("/create")
	public RealtimeVoiceSessionVO create(@RequestBody RealtimeVoiceSessionReq request) {
		return realtimeVoiceSessionService.create(request);
	}

	@Operation(summary = "查询实时语音会话", description = "查询实时语音会话，用于实时语音会话相关管理和运行场景。")
	@PostMapping("/{id}/offer")
	public RealtimeSignalResp offer(@PathVariable Long id, @RequestBody RealtimeSignalReq request) {
		return realtimeVoiceSessionService.offer(id, request);
	}

	@Operation(summary = "查询实时语音会话候选方案", description = "查询实时语音会话候选方案，用于实时语音会话相关管理和运行场景。")
	@PostMapping("/{id}/ice-candidates")
	public RealtimeSignalResp iceCandidates(@PathVariable Long id, @RequestBody RealtimeSignalReq request) {
		return realtimeVoiceSessionService.iceCandidates(id, request);
	}

	@Operation(summary = "修改实时语音会话", description = "修改实时语音会话，用于实时语音会话相关管理和运行场景。")
	@PostMapping("/{id}/interrupt")
	public RealtimeVoiceSessionVO interrupt(@PathVariable Long id) {
		return realtimeVoiceSessionService.interrupt(id);
	}

	@Operation(summary = "删除实时语音会话", description = "删除实时语音会话，用于实时语音会话相关管理和运行场景。")
	@AccessLog(module = "实时语音会话", description = "删除实时语音会话")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		realtimeVoiceSessionService.delete(id);
	}

}
