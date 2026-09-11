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
package com.sn68.agent.dataagent.authorization.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicyStatus;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 授权策略主档实体（PAP，PR-3b）。
 *
 * <p>对应表 agent_authorization_policy：策略编码/名称/模板来源与当前发布版本指针。
 * 租户插件白名单默认空，tenant_id 过滤由 Mapper 显式谓词承担。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_authorization_policy")
@Schema(description = "Agent 授权策略主档")
public class AgentAuthorizationPolicy extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "策略编码，租户内唯一")
	private String code;

	@Schema(description = "策略名称")
	private String name;

	@Schema(description = "来源模板编码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER")
	private String templateCode;

	@Schema(description = "状态：DRAFT/PUBLISHED/RETIRED")
	private AuthorizationPolicyStatus status;

	@Schema(description = "当前发布版本ID，指向 agent_authorization_policy_version.id；DRAFT 期为空")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long currentVersionId;

}
