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

import com.sn68.agent.dataagent.service.audio.AudioTranscriptionService;
import com.sn68.agent.dataagent.vo.AudioTranscriptionResp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 提供音频转写文本接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/audio")
@Tag(name = "语音转写", description = "提供音频转写文本接口")
public class AudioTranscriptionController {

	private final AudioTranscriptionService audioTranscriptionService;

	@Operation(summary = "上传音频并转写为文本", description = "上传音频文件并调用转写模型返回文本。非只读接口，每次调用都会产生模型调用成本。")
	@PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public AudioTranscriptionResp transcribe(@RequestPart("file") MultipartFile file) {
		return AudioTranscriptionResp.builder().text(audioTranscriptionService.transcribe(file)).build();
	}

}
