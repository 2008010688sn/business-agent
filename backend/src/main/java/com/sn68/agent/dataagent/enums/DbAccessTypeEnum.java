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
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.Optional;

/**
 * 数据源访问方式（code 为对外小写编码，历史契约保持不动）。
 */
public enum DbAccessTypeEnum implements DictEnum<String> {

	JDBC("jdbc"),

	SDK("sdk"),

	DATA_API("data-api"),

	FC_HTTP("fc-http"),

	MEMORY("in-memory");

	private String code;

	DbAccessTypeEnum(String code) {
		this.code = code;
	}

	public static DbAccessTypeEnum of(String code) {
		if (StringUtils.isBlank(code)) {
			return null;
		}

		Optional<DbAccessTypeEnum> any = Arrays.stream(values())
			.filter(typeEnum -> code.equals(typeEnum.getCode()))
			.findAny();

		return any.orElse(null);
	}

	public String getCode() {
		return code;
	}

	@Override
	public String getValue() {
		return code;
	}

	@Override
	public String getLabel() {
		return code;
	}

}
