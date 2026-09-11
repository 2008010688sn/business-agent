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
package com.sn68.agent.dataagent.authorization.dto.pap;

import com.sn68.agent.framework.db.mybatisplus.page.PageRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策略分页查询请求（PAP，PR-3b）。
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "Agent 授权策略分页查询")
public class AuthorizationPolicyPageQueryReq extends PageRequest {

	@Schema(description = "策略编码（模糊）")
	private String code;

	@Schema(description = "策略名称（模糊）")
	private String name;

	@Schema(description = "状态：DRAFT/PUBLISHED/RETIRED")
	private String status;

	@Schema(description = "来源模板编码")
	private String templateCode;

}
