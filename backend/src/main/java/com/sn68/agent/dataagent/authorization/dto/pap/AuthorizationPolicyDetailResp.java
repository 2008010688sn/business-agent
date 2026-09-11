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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略详情响应（PAP，PR-3b）：主档基础字段 + 当前发布版本/最新草稿版本的内容。
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权策略详情")
public class AuthorizationPolicyDetailResp {

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "策略ID")
	private Long id;

	@Schema(description = "策略编码")
	private String code;

	@Schema(description = "策略名称")
	private String name;

	@Schema(description = "来源模板编码")
	private String templateCode;

	@Schema(description = "状态：DRAFT/PUBLISHED/RETIRED")
	private String status;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "当前发布版本ID；未发布为空")
	private Long currentVersionId;

	@Schema(description = "当前发布版本号；未发布为空")
	private Integer currentVersionNo;

	@Schema(description = "当前发布版本策略 JSON；未发布为空")
	private String currentPolicyJson;

	@Schema(description = "当前发布版本策略 hash（规范化 JSON SHA-256）")
	private String currentPolicyHash;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "最新草稿版本ID；无草稿为空")
	private Long draftVersionId;

	@Schema(description = "最新草稿版本号；无草稿为空")
	private Integer draftVersionNo;

	@Schema(description = "最新草稿策略 JSON；无草稿为空")
	private String draftPolicyJson;

}
