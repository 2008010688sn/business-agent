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
package com.sn68.agent.dataagent.dto.datasource;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * SQL 重试原因描述：区分语义校验失败与 SQL 执行失败两类重试场景。
 */
@Schema(description = "SQL重试原因")
public record SqlRetryDTO(
		@Schema(description = "重试原因说明") String reason,
		@Schema(description = "是否语义校验失败") boolean semanticFail,
		@Schema(description = "是否SQL执行失败") boolean sqlExecuteFail
) {

	public static SqlRetryDTO semantic(String reason) {
		return new SqlRetryDTO(reason, true, false);
	}

	public static SqlRetryDTO sqlExecute(String reason) {
		return new SqlRetryDTO(reason, false, true);
	}

	public static SqlRetryDTO empty() {
		return new SqlRetryDTO("", false, false);
	}

}
