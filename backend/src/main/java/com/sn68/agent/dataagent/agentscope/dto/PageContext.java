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
package com.sn68.agent.dataagent.agentscope.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前页可选上下文。客户端可伪造，只当查库 hint，雪花 id / 单号必须是字符串。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "当前页可选上下文，仅 hint")
public class PageContext {

	@Schema(description = "页面已渲染的业务键，值必须是字符串")
	private Map<String, String> keys;

	@Schema(description = "页面声明的对象类型，可选")
	private String objectType;

}
