/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Skill 编辑器可选的已发布工具版本轻量选项。
 */
@Schema(description = "已发布工具版本选项")
public record ToolVersionOptionDTO(
		@Schema(description = "工具版本ID") Long resourceVersionId,
		@Schema(description = "工具资源Key") String resourceKey,
		@Schema(description = "工具资源名称") String resourceName,
		@Schema(description = "版本号") Integer versionNo,
		@Schema(description = "访问模式") String accessMode,
		@Schema(description = "暴露模式") String exposureMode,
		@Schema(description = "是否可选") boolean selectable,
		@Schema(description = "不可选原因") String unavailableReason) {
}
