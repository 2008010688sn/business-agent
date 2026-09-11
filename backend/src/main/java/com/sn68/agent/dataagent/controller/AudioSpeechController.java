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

import com.sn68.agent.dataagent.dto.tts.AudioSpeechReq;
import com.sn68.agent.dataagent.dto.tts.AudioSpeechResult;
import com.sn68.agent.dataagent.dto.tts.TtsVoiceSampleResp;
import com.sn68.agent.dataagent.service.tts.TextToSpeechService;
import com.sn68.agent.dataagent.service.tts.VoiceSampleService;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供文本转语音、语音样本上传和样本管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/audio")
@Tag(name = "语音合成", description = "提供文本转语音、语音样本上传和样本管理接口")
public class AudioSpeechController {

	private final TextToSpeechService textToSpeechService;

	private final VoiceSampleService voiceSampleService;

	@Operation(summary = "合成语音（文本转语音）",
			description = "调用 TTS 模型把文本合成为音频，一次性返回完整二进制音频流。非只读接口，每次调用都会产生模型调用成本。")
	@IgnoreGlobalResponse(description = "语音合成二进制流")
	@PostMapping(value = "/speech", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<byte[]> speech(@Valid @RequestBody AudioSpeechReq request) {
		return toAudioResponse(textToSpeechService.synthesize(request));
	}

	@Operation(summary = "流式推送语音合成流式语音", description = "流式推送语音合成流式语音，用于语音合成相关管理和运行场景。")
	@IgnoreGlobalResponse(description = "语音合成二进制流")
	@PostMapping(value = "/speech/stream", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<byte[]> speechStream(@Valid @RequestBody AudioSpeechReq request) {
		return toAudioResponse(textToSpeechService.synthesize(request));
	}

	@Operation(summary = "创建语音样本", description = "创建语音样本，用于语音合成相关管理和运行场景。")
	@AccessLog(module = "语音合成", description = "上传语音样本", request = false)
	@PostMapping(value = "/voice-samples", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public TtsVoiceSampleResp uploadVoiceSample(@RequestPart("file") MultipartFile file,
			@RequestParam(value = "transcript", required = false) String transcript,
			@RequestParam(value = "consentConfirmed", defaultValue = "false") Boolean consentConfirmed) {
		return voiceSampleService.upload(file, transcript, consentConfirmed);
	}

	@Operation(summary = "查询语音样本", description = "查询语音样本，用于语音合成相关管理和运行场景。")
	@GetMapping("/voice-samples")
	public List<TtsVoiceSampleResp> listVoiceSamples() {
		return voiceSampleService.list();
	}

	@Operation(summary = "删除语音样本", description = "删除语音样本，用于语音合成相关管理和运行场景。")
	@AccessLog(module = "语音合成", description = "删除语音样本")
	@DeleteMapping("/voice-samples/{id}")
	public void deleteVoiceSample(@PathVariable Long id) {
		voiceSampleService.delete(id);
	}

	private ResponseEntity<byte[]> toAudioResponse(AudioSpeechResult result) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_TYPE, result.contentType())
			.header("X-Audio-Format", result.format())
			.body(result.audio());
	}

}
