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
package com.sn68.agent.dataagent.dto.tts;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 音频语音合成结果，承载合成后的音频数据与格式信息。
 */
@Builder
@Schema(description = "音频语音合成结果")
public record AudioSpeechResult(
		@Schema(description = "合成的音频二进制数据") byte[] audio,
		@Schema(description = "音频MIME类型") String contentType,
		@Schema(description = "音频格式") String format
) {
}
