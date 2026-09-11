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

import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.Getter;
import org.springframework.util.StringUtils;

/**
 * 实时语音运行模式（code 与常量名一致）。
 */
@Getter
public enum RealtimeVoiceRuntimeMode implements DictEnum<String> {

	/**
	 * ASR -> Agent -> TTS 编排模式。
	 */
	PIPELINE("PIPELINE"),

	/**
	 * 一体化 speech-to-speech 实时模型模式。
	 */
	REALTIME("REALTIME");

	private final String code;

	RealtimeVoiceRuntimeMode(String code) {
		this.code = code;
	}

	@Override
	public String getValue() {
		return code;
	}

	@Override
	public String getLabel() {
		return code;
	}

	/**
	 * 根据编码获取枚举（忽略大小写），空白回退 PIPELINE，未匹配抛出异常。
	 */
	public static RealtimeVoiceRuntimeMode fromCode(String code) {
		if (!StringUtils.hasText(code)) {
			return PIPELINE;
		}
		String normalizedCode = code.trim();
		for (RealtimeVoiceRuntimeMode mode : values()) {
			if (mode.getCode().equalsIgnoreCase(normalizedCode)) {
				return mode;
			}
		}
		throw new IllegalArgumentException("未知实时语音运行模式: " + code);
	}

}
