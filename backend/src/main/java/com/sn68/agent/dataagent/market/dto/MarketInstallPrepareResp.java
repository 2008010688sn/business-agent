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
package com.sn68.agent.dataagent.market.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 市场安装结果（PR-1 失败关闭过渡期不再返回；PR-5 以 DigitalEmployee Capability 绑定重建安装后复用）。
 */
@Builder
@Schema(description = "市场安装结果")
public record MarketInstallPrepareResp(
		@Schema(description = "安装记录ID") @JsonSerialize(using = ToStringSerializer.class) Long installationId,
		@Schema(description = "市场条目ID") @JsonSerialize(using = ToStringSerializer.class) Long listingId,
		@Schema(description = "来源条目版本ID（安装追溯源）") @JsonSerialize(using = ToStringSerializer.class) Long listingVersionId,
		@Schema(description = "条目内版本号") Integer versionNo,
		@Schema(description = "来源 Skill 版本ID") @JsonSerialize(using = ToStringSerializer.class) Long skillVersionId,
		@Schema(description = "内容哈希") String contentHash,
		@Schema(description = "使用配额默认值（次/日）") Integer defaultUsageQuota
) {
}
