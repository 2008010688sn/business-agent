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
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * IM 用户扫码绑定会话。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_user_bind_session")
@Schema(description = "AgentIM用户绑定会话实体")
public class AgentImUserBindSession extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "一次性绑定短码")
	private String bindCode;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "系统用户ID")
	private String userId;

	@Schema(description = "系统账号快照")
	private String username;

	@Schema(description = "系统昵称快照")
	private String nickName;

	@Schema(description = "会话状态")
	private String status;

	@Schema(description = "过期时间")
	private LocalDateTime expireTime;

	@Schema(description = "成功消费时间")
	private LocalDateTime consumedAt;

	@Schema(description = "消费成功后的外部用户ID")
	private String externalUserId;

}
