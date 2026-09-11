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
package com.sn68.agent.dataagent.im.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * IM 外部用户身份映射。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_user_identity")
@Schema(description = "AgentIM用户身份实体")
public class AgentImUserIdentity extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "外部用户ID")
	private String externalUserId;

	@Schema(description = "unionId字段")
	private String unionId;

	@Schema(description = "contact字段")
	private String contact;

	@Schema(description = "用户ID")
	private String userId;

	@Schema(description = "名称")
	private String username;

	@Schema(description = "用户昵称")
	private String nickName;

	@Schema(description = "状态")
	private String bindStatus;

	@Schema(description = "来源字段")
	private String bindSource;

}
