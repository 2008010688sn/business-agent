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
 * 影子比对聚合行（租户 × 比对状态 → 事件数，PR-10 差异报告透视输入）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Data
public class ShadowComparisonAggregate {

	/**
	 * 租户ID（事件表数字口径，0 表示未解析租户）。
	 */
	private Long tenantId;

	/**
	 * 比对状态码（MATCHED/MISMATCHED/ORIGINAL_ONLY；明细缺失按 ORIGINAL_ONLY 口径归并）。
	 */
	private String comparisonStatus;

	/**
	 * 窗口内事件数。
	 */
	private Long total;

}
