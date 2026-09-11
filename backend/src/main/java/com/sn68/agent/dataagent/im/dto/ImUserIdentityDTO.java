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
 * IM 外部用户与 IAM 用户映射。
 */
@Schema(description = "IM用户身份数据传输对象")
public record ImUserIdentityDTO(
		@Schema(description = "主键ID") Long id,
		@Schema(description = "平台类型") String provider,
		@Schema(description = "连接器编码") String connectorCode,
		@Schema(description = "外部用户ID") String externalUserId,
		@Schema(description = "unionId字段") String unionId,
		@Schema(description = "contact字段") String contact,
		@Schema(description = "用户ID") String userId,
		@Schema(description = "名称") String username,
		@Schema(description = "用户昵称") String nickName,
		@Schema(description = "状态") String bindStatus,
		@Schema(description = "来源字段") String bindSource
) {
}
