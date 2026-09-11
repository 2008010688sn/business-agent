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

/**
 * 会话上下文压缩状态。
 */
public enum SessionContextCompressionStatus implements DictEnum<String> {

	COMPRESSED("COMPRESSED", "已压缩上下文"),

	NO_COMPRESSIBLE_CONTENT("NO_COMPRESSIBLE_CONTENT", "当前无需压缩"),

	DISABLED("DISABLED", "上下文压缩未启用"),

	BUSY("BUSY", "当前会话正在运行，请稍后再压缩"),

	FAILED("FAILED", "上下文压缩失败");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	SessionContextCompressionStatus(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

}
