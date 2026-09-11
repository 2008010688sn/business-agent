/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 草稿 Skill 路由规则确定性测试的入参。
 */
@Schema(description = "Skill草稿路由测试请求")
public record SkillRouteTestReq(
		@Schema(description = "待测试的用户问题文本") String query) {
}
