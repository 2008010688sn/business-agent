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

/**
 * AgentId 过滤请求。
 *
 * <p>
 * 与 {@link AgentIdReq} 报文结构一致，区别在于 agentId 可缺省：缺省时由服务端回退到默认 Agent 或不按 Agent 过滤。
 */
@Schema(description = "AgentId过滤请求")
public record AgentIdFilterReq(@Schema(description = "Agent ID，可缺省") Long agentId) {
}
