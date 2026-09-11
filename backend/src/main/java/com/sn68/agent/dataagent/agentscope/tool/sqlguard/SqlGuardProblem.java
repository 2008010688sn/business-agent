/*
 * Copyright 2026 the original author or authors.
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
package com.sn68.agent.dataagent.agentscope.tool.sqlguard;

import lombok.Builder;
import lombok.Data;

/**
 * SQL 守护校验发现的单个问题：错误码、严重级别、期望与实际差异说明及修复提示，供模型自查修订。
 */
@Data
@Builder
class SqlGuardProblem {

	private String code;

	private String title;

	private String severity;

	private String message;

	private String why;

	private String expected;

	private String actual;

	private String evidence;

	private String repairHint;

}
