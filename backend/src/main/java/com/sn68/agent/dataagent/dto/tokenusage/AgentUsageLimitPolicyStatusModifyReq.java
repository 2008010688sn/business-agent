/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.dto.tokenusage;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Agent用量Limit策略StatusModify请求。
 */
@Schema(description = "Agent用量Limit策略StatusModify请求")
public record AgentUsageLimitPolicyStatusModifyReq(@Schema(description = "是否启用") Boolean enabled) {
}
