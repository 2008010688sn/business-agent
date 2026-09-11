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
package com.sn68.agent.dataagent.agentscope.tool;

/**
 * 工具执行错误码：区分输入非法、动作不支持、数据源不可用、表/列不可见与执行失败，
 * 供模型据此决定重试、改写还是澄清。枚举名即对外 JSON 值，不可改动。
 */
public enum ToolErrorCode {

	INVALID_INPUT,

	UNSUPPORTED_ACTION,

	DATASOURCE_UNAVAILABLE,

	TABLE_NOT_VISIBLE,

	COLUMN_NOT_VISIBLE,

	EXECUTION_FAILED

}
