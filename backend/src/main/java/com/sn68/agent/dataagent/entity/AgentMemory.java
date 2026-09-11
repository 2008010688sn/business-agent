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
package com.sn68.agent.dataagent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sn68.agent.dataagent.enums.AgentMemoryStatus;
import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import com.sn68.agent.dataagent.repository.typehandler.JsonbStringTypeHandler;
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
 * Agent记忆实体。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_agent_memory", autoResultMap = true)
@Schema(description = "DataAgent 长期记忆")
public class AgentMemory extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "记忆命名空间")
	private String namespaceId;

	@Schema(description = "智能体ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long agentId;

	@Schema(description = "数字员工共享记忆归属ID（WORKSPACE 共享记忆冗余列，subjectId 同值为数字员工ID），空表示非数字员工归属（PR-7 归属键切换）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long digitalEmployeeId;

	@Schema(description = "用户ID（EMPLOYEE_USER 记忆的主体用户，其余范围为写入人）")
	private String userId;

	@Schema(description = "记忆主体类型（记忆范围）")
	private MemoryScope subjectType;

	@Schema(description = "记忆主体ID（会话ID/用户ID/工作空间ID，见 MemoryScope 约定）")
	private String subjectId;

	@Schema(description = "事实键，同键新写入按 revision 递增修订")
	private String factKey;

	@Schema(description = "同 factKey 下的修订版本，从 1 递增")
	private Integer revision;

	@Schema(description = "记忆类型")
	private AgentMemoryType memoryType;

	@Schema(description = "短摘要")
	private String summary;

	@Schema(description = "完整记忆内容")
	private String content;

	@Schema(description = "语义去重哈希")
	private String semanticHash;

	@Schema(description = "重要度")
	private Double importance;

	@Schema(description = "置信度")
	private Double confidence;

	@Schema(description = "状态")
	private AgentMemoryStatus status;

	@Schema(description = "来源说明（抽取来源、依据等，JSONB）")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String provenance;

	@Schema(description = "来源运行ID（生成该记忆候选的根 Run）")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceRunId;

	@Schema(description = "敏感级别，HIGH 必须先取得用户同意")
	private MemorySensitivity sensitivity;

	@Schema(description = "用户同意状态")
	private MemoryConsentStatus consentStatus;

	@Schema(description = "记忆生效时间")
	private Instant validFrom;

	@Schema(description = "记忆失效时间（TTL，过期不再召回）")
	private Instant validTo;

	@Schema(description = "来源会话ID")
	private String sourceSessionId;

	@Schema(description = "来源消息ID")
	private String sourceMessageId;

	@Schema(description = "使用次数")
	private Integer useCount;

	@Schema(description = "最近使用时间")
	private Instant lastUsedTime;

	@Schema(description = "存储TTL过期时间（PR-7 新增，清理任务扫描列；与 validTo 双轨：validTo 表业务有效期，本列表存储生命周期）")
	private Instant expiresAt;

	@Schema(description = "过期时间")
	private Instant expireTime;

}
