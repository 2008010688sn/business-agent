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

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数字员工分页查询请求。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "数字员工分页查询请求")
public class DigitalEmployeePageQueryReq extends PageRequest {

	@Schema(description = "状态：DRAFT/ENABLED/DISABLED/ARCHIVED")
	private String status;

	@Schema(description = "名称关键字")
	private String keyword;

}
