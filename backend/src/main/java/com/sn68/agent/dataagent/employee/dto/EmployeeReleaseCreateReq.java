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
package com.sn68.agent.dataagent.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 数字员工发布草稿创建请求（从员工当前草稿配置创建 DRAFT Release）。
 */
@Data
@Schema(description = "数字员工发布草稿创建请求")
public class EmployeeReleaseCreateReq {

	@Schema(description = "基于哪个历史 Release 创建（可空；空则从员工当前草稿装配）")
	private Long baseReleaseId;

}
