/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.iam.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Delegated auth request (local standalone copy of the former IAM DTO).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "委托授权上下文请求")
public class DelegatedAuthContextReq {

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "可信联系方式")
	private String contact;

	@Schema(description = "权限客户端ID")
	private String clientId;

	@Schema(description = "委托Token设备标识")
	private String device;

	@Schema(description = "调用来源")
	private String source;

	@Schema(description = "IM供应商")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "授权受众")
	private String audience;

	@Schema(description = "权限范围")
	private String scopes;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@Schema(description = "Token有效期秒数")
	private Long timeoutSeconds;

}
