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
package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 钉钉凭据校验响应。
 */
@Schema(description = "钉钉CredentialValidate响应")
public record DingTalkCredentialValidateResponse(
		@Schema(description = "是否成功") boolean success,
		@Schema(description = "消息内容") String message,
		@Schema(description = "脱敏客户端ID") String clientIdMasked
) {
}
