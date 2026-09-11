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
 * SQL 守护单条规则的执行结论：规则码、通过/失败状态与证据说明，逐条回显给模型形成校验轨迹。
 */
@Data
@Builder
class SqlGuardRuleCheck {

	private String code;

	private String title;

	private String status;

	private String detail;

	private String evidence;

}
