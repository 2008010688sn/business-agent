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
package com.sn68.agent.dataagent.dto.agent;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * AgentId请求。
 *
 * <p>
 * agentId 必填。agentId 可缺省的场景（工作台默认 Agent、按 Agent 过滤清单）用
 * {@link AgentIdFilterReq}。
 */
@Schema(description = "AgentId请求")
public record AgentIdReq(@Schema(description = "Agent ID") @NotNull(message = "agentId不能为空") Long agentId) {
}
