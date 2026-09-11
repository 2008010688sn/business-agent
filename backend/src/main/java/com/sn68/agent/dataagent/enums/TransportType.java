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

/**
 * 实时语音传输通道类型（code 与常量名一致）。
 */
@Getter
public enum TransportType implements DictEnum<String> {

	HTTP("HTTP"),

	SSE("SSE"),

	WEBSOCKET("WEBSOCKET"),

	WEBRTC("WEBRTC");

	private final String code;

	TransportType(String code) {
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
	 * 根据编码获取枚举（忽略大小写），未匹配抛出异常。
	 */
	public static TransportType fromCode(String code) {
		String normalizedCode = code == null ? null : code.trim();
		for (TransportType type : values()) {
			if (type.getCode().equalsIgnoreCase(normalizedCode)) {
				return type;
			}
		}
		throw new IllegalArgumentException("未知的传输类型: " + code);
	}

}
