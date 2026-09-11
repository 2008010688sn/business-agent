/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.entity;

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
 * 持久运行时步骤产物实体。步骤之间只传递结构化 Artifact，最终自然语言回答仅用于展示。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName(value = "agent_runtime_artifact", autoResultMap = true)
@Schema(description = "持久运行时步骤产物")
public class AgentRuntimeArtifact extends SuperEntity<Long> {

	@Serial
	private static final long serialVersionUID = 1L;

	@Override
	@JsonSerialize(using = ToStringSerializer.class)
	public Long getId() {
		return super.getId();
	}

	@Schema(description = "租户ID")
	private String tenantId;

	@Schema(description = "所属运行ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long runId;

	@Schema(description = "产出步骤键")
	private String stepKey;

	@Schema(description = "产物数据结构版本")
	private String schemaVersion;

	@Schema(description = "结构化产物数据 JSON")
	@TableField(typeHandler = JsonbStringTypeHandler.class)
	private String data;

	@Schema(description = "来源调用ID")
	@JsonSerialize(using = ToStringSerializer.class)
	private Long sourceInvocationId;

	@Schema(description = "敏感级别：PUBLIC、INTERNAL、SENSITIVE")
	private String sensitivity;

	@Schema(description = "产物内容哈希")
	private String contentHash;

}
