/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 持久运行时 Run 详情响应。
 */
@Schema(description = "持久运行时 Run 详情响应")
public record RuntimeRunDetailResp(

		@Schema(description = "运行信息")
		RuntimeRunResp run,

		@Schema(description = "最终回答（仅展示用途）")
		String finalAnswer,

		@Schema(description = "生效计划版本，无计划为空")
		Integer planVersion,

		@Schema(description = "生效计划哈希，无计划为空")
		String planHash,

		@Schema(description = "步骤清单")
		List<RuntimeStepResp> steps,

		@Schema(description = "当前最大事件 seq，作为事件流起始游标")
		Long latestSeq) {
}
