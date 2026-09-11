/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 运行产物摘要。敏感产物只回 schema/step，不回 data。
 */
@Schema(description = "运行产物摘要")
public record RuntimeArtifactResp(

		@Schema(description = "产物ID")
		@JsonSerialize(using = ToStringSerializer.class)
		Long id,

		@Schema(description = "产出步骤键")
		String stepKey,

		@Schema(description = "产物数据结构版本")
		String schemaVersion,

		@Schema(description = "结构化产物 JSON；敏感级为空")
		String data,

		@Schema(description = "敏感级别")
		String sensitivity) {
}
