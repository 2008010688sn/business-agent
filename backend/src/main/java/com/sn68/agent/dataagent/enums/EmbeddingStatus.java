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
 * 知识向量化状态（value 与常量名一致，getValue 返回 value 不改变既有 DB/JSON 取值）。
 */
@Getter
public enum EmbeddingStatus implements DictEnum<String> {

	PENDING("PENDING"), PROCESSING("PROCESSING"), COMPLETED("COMPLETED"), FAILED("FAILED");

	private final String value;

	EmbeddingStatus(String value) {
		this.value = value;
	}

	@Override
	public String getLabel() {
		return value;
	}

	public static EmbeddingStatus fromValue(String value) {
		for (EmbeddingStatus status : EmbeddingStatus.values()) {
			// 严格比对
			if (status.value.equals(value)) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown embedding status: " + value);
	}

}
