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
package com.sn68.agent.dataagent.channel.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 渠道会话错误字典。
 */
public enum ChannelSessionErrorDict implements DictEnum<Integer> {

	REQUEST_INVALID(481001, "渠道会话参数不完整"),

	SCOPE_UNSUPPORTED(481002, "渠道会话范围不支持"),

	MAPPING_CREATE_FAILED(481003, "渠道会话映射创建失败"),

	WEB_SESSION_REQUIRED(481004, "普通 Web 试聊只能使用 Web 会话"),

	TAKEOVER_FORBIDDEN(481005, "当前账号无权接管渠道会话"),

	SESSION_NOT_FOUND(481006, "渠道会话不存在"),

	EMPLOYEE_FACADE_REQUIRED(481007, "数字员工对话请走员工接口，禁止通过 /stream/search 旁路"),

	SESSION_OWNER_FORBIDDEN(481008, "会话不属于当前用户，禁止操作该运行");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

	ChannelSessionErrorDict(Integer value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public Integer getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

}
