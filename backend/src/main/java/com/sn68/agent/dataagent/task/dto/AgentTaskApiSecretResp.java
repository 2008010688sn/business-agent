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
package com.sn68.agent.dataagent.task.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * API 触发签名密钥轮换响应。明文密钥仅本次返回，落库为密文且读接口脱敏。
 */
@Schema(description = "API 触发签名密钥轮换响应")
public record AgentTaskApiSecretResp(
		@Schema(description = "签名密钥明文（仅本次返回，请妥善保存；旧密钥已即刻失效）") String secret,
		@Schema(description = "签名算法") String algorithm) {
}
