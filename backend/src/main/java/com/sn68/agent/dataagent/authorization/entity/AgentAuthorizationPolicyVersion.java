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

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serial;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Agent 授权策略版本实体（PAP，PR-3b）。
 *
 * <p>对应表 agent_authorization_policy_version：策略 JSON 的不可变版本载体。
 * published=false 为草稿（允许覆盖 JSON），published=true 为已发布版本（JSON 与 hash 永不 UPDATE）。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_authorization_policy_version", autoResultMap = true)
@Schema(description = "Agent 授权策略版本")
public class AgentAuthorizationPolicyVersion extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "所属租户ID")
	private String tenantId;

	@Schema(description = "策略主档ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long policyId;

	@Schema(description = "版本号，从 1 递增")
	private Integer versionNo;

	@Schema(description = "策略 JSON 原文（落库前经 PolicyValidator 校验，读取时重新校验重建）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String policyJson;

	@Schema(description = "策略规范化 JSON 的 SHA-256（AuthorizationPolicy.computeHash 冻结算法）")
	private String policyHash;

	@Schema(description = "是否已发布：true-不可变；false-草稿可修改")
	private Boolean published;

}
