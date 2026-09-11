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
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 授权记录实体（PAP，PR-3b）。
 *
 * <p>对应表 agent_authorization_grant：以主体（用户/团队/权限/租户）为维度的 DISCOVER/USE 授权，
 * 供员工域灰度期与 Legacy 可见性授权并行的粗粒度放行判定。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("agent_authorization_grant")
@Schema(description = "Agent 授权记录")
public class AgentAuthorizationGrant extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "授权对象类型：DATA_AGENT/DIGITAL_EMPLOYEE")
	private AuthorizationOwnerType ownerType;

	@Schema(description = "授权对象ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long ownerId;

	@Schema(description = "授权主体类型：USER/TEAM/PERMISSION/TENANT")
	private AuthorizationGrantSubjectType subjectType;

	@Schema(description = "授权主体ID")
	private String subjectId;

	@Schema(description = "授权主体名称（冗余展示用）")
	private String subjectName;

	@Schema(description = "资源类型，默认 OWNER（预留扩展）")
	private String resourceType;

	@Schema(description = "资源ID，默认空（预留扩展）")
	private String resourceId;

	@Schema(description = "授权权限：DISCOVER-目录可见；USE-可使用")
	private AuthorizationGrantPermission permission;

	@Schema(description = "来源：MANUAL-手工；LEGACY-存量迁移；SYSTEM-系统初始化")
	private String sourceType;

	@Schema(description = "过期时间，空表示长期有效")
	private Instant expireTime;

	@Schema(description = "状态：ACTIVE/REVOKED")
	private String status;

}
