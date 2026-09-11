/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.skill;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 切换 Skill 业务知识是否参与召回的请求。
 */
@Schema(description = "Skill业务知识召回状态请求")
public record SkillRecallReq(@Schema(description = "是否参与召回") @NotNull Boolean isRecall) {
}
