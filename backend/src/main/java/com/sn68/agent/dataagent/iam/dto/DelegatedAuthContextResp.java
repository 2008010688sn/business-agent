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

import com.sn68.agent.framework.commons.security.DataPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Delegated auth response (local standalone copy of the former IAM DTO).
 *
 * @author sn68
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "委托授权上下文")
public class DelegatedAuthContextResp {

	@Schema(description = "访问Token")
	private String accessToken;

	@Schema(description = "Token类型")
	private String tokenType;

	@Schema(description = "访问Token有效期秒数")
	private Long expiresIn;

	@Schema(description = "授权上下文ID")
	private String authContextId;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "用户名")
	private String username;

	@Schema(description = "昵称")
	private String nickName;

	@Schema(description = "手机号")
	private String mobile;

	@Schema(description = "邮箱")
	private String email;

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "租户编码")
	private String tenantCode;

	@Schema(description = "租户名称")
	private String tenantName;

	@Schema(description = "权限客户端ID")
	private String clientId;

	@Schema(description = "委托Token设备标识")
	private String device;

	@Builder.Default
	@Schema(description = "团队ID列表")
	private List<String> teamIds = new ArrayList<>();

	@Builder.Default
	@Schema(description = "角色编码")
	private Collection<String> roles = new ArrayList<>();

	@Builder.Default
	@Schema(description = "功能权限")
	private Collection<String> funcPermissions = new ArrayList<>();

	@Schema(description = "最终数据权限")
	private DataPermission dataPermission;

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

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

}
