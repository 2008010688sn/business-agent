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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * IM 用户扫码绑定会话。
 */
@Schema(description = "IM用户绑定会话")
public record ImUserBindSessionDTO(
		@Schema(description = "主键ID") @JsonSerialize(using = ToStringSerializer.class) Long id,
		@Schema(description = "一次性绑定短码") String code,
		@Schema(description = "二维码内容，等于短码") String qrContent,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "系统用户ID") String userId,
		@Schema(description = "系统昵称") String nickName,
		@Schema(description = "PENDING/CONSUMED/EXPIRED") String status,
		@Schema(description = "过期时间") LocalDateTime expiresAt,
		@Schema(description = "消费成功后的外部用户ID") String externalUserId
) {
}
