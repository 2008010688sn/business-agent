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
package com.sn68.agent.dataagent.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 实时语音错误码。
 */
@Getter
@AllArgsConstructor
public enum RealtimeVoiceErrorDict implements DictEnum<Integer> {

	CONFIG_NOT_READY(490001, "实时语音配置未就绪"),

	SESSION_NOT_FOUND(490002, "实时语音会话不存在或已关闭"),

	SESSION_TOKEN_INVALID(490003, "实时语音会话鉴权失败"),

	AUDIO_EMPTY(490004, "未收到有效语音内容"),

	AUDIO_FORMAT_INVALID(490005, "实时语音音频格式不支持"),

	ASR_FAILED(490006, "实时语音识别失败"),

	AGENT_FAILED(490007, "实时语音智能体执行失败"),

	TTS_FAILED(490008, "实时语音合成失败"),

	INTERRUPT_FAILED(490009, "实时语音打断失败"),

	SESSION_CLOSED(490010, "实时语音会话已关闭"),

	REALTIME_PROVIDER_NOT_READY(490011, "实时语音一体化模型配置未就绪");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
