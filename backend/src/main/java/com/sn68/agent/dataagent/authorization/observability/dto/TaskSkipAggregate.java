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
package com.sn68.agent.dataagent.authorization.observability.dto;

import lombok.Data;

/**
 * SKIPPED 台账行聚合（租户 × 跳过原因 → 行数，PR-10 槽位冲突观测输入）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Data
public class TaskSkipAggregate {

	/**
	 * 租户ID（任务台账 String 口径）。
	 */
	private String tenantId;

	/**
	 * 跳过原因归类：SLOT_CONFLICT（CONCURRENT_SLOT_LOCKED）/ OTHER。
	 */
	private String skipReason;

	/**
	 * 窗口内 SKIPPED 行数。
	 */
	private Long total;

}
