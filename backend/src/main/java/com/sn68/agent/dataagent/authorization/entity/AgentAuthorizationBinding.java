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
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 授权策略绑定实体（PAP，PR-3b）。
 *
 * <p>对应表 agent_authorization_binding：owner（数据员工/数字员工）在指定环境下消费的策略版本指针。
 * bind_revision 支撑乐观锁 CAS 更新，防止并发改绑丢失。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_authorization_binding")
@Schema(description = "Agent 授权策略绑定")
public class AgentAuthorizationBinding extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE")
	private AuthorizationOwnerType ownerType;

	@Schema(description = "绑定主体ID（data_agent.id 或员工表主键）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long ownerId;

	@Schema(description = "生效环境：SANDBOX/PRODUCTION")
	private AuthorizationEnvironment environment;

	@Schema(description = "绑定的策略版本ID，指向 agent_authorization_policy_version.id")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long policyVersionId;

	@Schema(description = "乐观锁版本号，每次 CAS 更新成功后自增")
	private Long bindRevision;

}
