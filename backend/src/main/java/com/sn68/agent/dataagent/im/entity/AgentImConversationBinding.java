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

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
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
 * IM 会话与 Agent 绑定。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_im_conversation_binding")
@Schema(description = "AgentIM会话绑定实体")
public class AgentImConversationBinding extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "所属租户ID")
	private String tenantId;

	@TableField(updateStrategy = FieldStrategy.ALWAYS)
	@Schema(description = "绑定数字员工ID（可空，PR-1 起由数字员工域消费，员工表 PR-5 建表）")
	private Long digitalEmployeeId;

	@Schema(description = "平台类型")
	private String provider;

	@Schema(description = "连接器编码")
	private String connectorCode;

	@Schema(description = "会话类型")
	private String conversationType;

	@Schema(description = "externalConversationId字段")
	private String externalConversationId;

	@Schema(description = "名称")
	private String conversationName;

	@Schema(description = "Agent ID")
	private Long agentId;

	@Schema(description = "策略字段")
	private String triggerPolicy;

	@Schema(description = "wakeWords字段")
	private String wakeWords;

	@Schema(description = "会话字段")
	private String sessionScope;

	@Schema(description = "状态")
	private String status;

	@Schema(description = "displayOrder字段")
	private Integer displayOrder;

}
