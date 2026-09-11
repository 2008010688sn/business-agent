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
 * 知识文档切分器类型（value 为对外小写编码，历史契约保持不动）。
 */
@Getter
public enum SplitterType implements DictEnum<String> {

	TOKEN("token"), RECURSIVE("recursive"), SENTENCE("sentence"), PARAGRAPH("paragraph"), SEMANTIC("semantic");

	private final String value;

	SplitterType(String value) {
		this.value = value;
	}

	@Override
	public String getLabel() {
		return value;
	}

}
