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
import java.time.Instant;
import lombok.Data;

/**
 * 策略版本列表行（PAP，PR-3b）：不含 JSON 正文，仅元数据（hash 可比对内容一致性）。
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Schema(description = "Agent 授权策略版本")
public class AuthorizationPolicyVersionResp {

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "版本ID")
	private Long id;

	@JsonSerialize(using = ToStringSerializer.class)
	@Schema(description = "策略主档ID")
	private Long policyId;

	@Schema(description = "版本号，从 1 递增")
	private Integer versionNo;

	@Schema(description = "策略 hash（规范化 JSON SHA-256）")
	private String policyHash;

	@Schema(description = "是否已发布")
	private Boolean published;

	@Schema(description = "创建时间")
	private Instant createTime;

}
