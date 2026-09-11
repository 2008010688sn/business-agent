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
package com.sn68.agent.dataagent.dto.memory;

import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.enums.MemoryConsentStatus;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.enums.MemorySensitivity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import lombok.Builder;

/**
 * 长期记忆候选写入请求（服务层治理写入口）。
 *
 * <p>内容红线：summary/content 必须是提炼后的结论文本，
 * <b>禁止传入模型隐藏思维链、原始敏感业务 payload（如整段查询结果 JSON）</b>，
 * 写入口会做校验并拒绝可疑内容。
 */
@Builder
@Schema(description = "长期记忆候选写入请求")
public record AgentMemoryCandidateSaveReq(
		@Schema(description = "智能体ID") @NotNull(message = "agentId不能为空") Long agentId,
		@Schema(description = "数字员工共享记忆归属ID（WORKSPACE 共享记忆必填，与 subjectId 一致；PR-7 归属键切换）") Long digitalEmployeeId,
		@Schema(description = "记忆范围（主体类型），必填且单一，禁止跨范围写入") @NotNull(message = "记忆范围不能为空") MemoryScope scope,
		@Schema(description = "记忆主体ID（会话ID/用户ID/工作空间ID）") @NotBlank(message = "记忆主体ID不能为空") String subjectId,
		@Schema(description = "记忆命名空间") String namespaceId,
		@Schema(description = "记忆内容类型，缺省按事实记忆处理") AgentMemoryType memoryType,
		@Schema(description = "短摘要（提炼后的结论文本）") @NotBlank(message = "记忆摘要不能为空") String summary,
		@Schema(description = "完整记忆内容，缺省取摘要") String content,
		@Schema(description = "事实键，同键写入按 revision 递增修订") String factKey,
		@Schema(description = "来源说明（JSON 文本）") String provenance,
		@Schema(description = "来源根 Run ID") @NotNull(message = "sourceRunId不能为空") Long sourceRunId,
		@Schema(description = "来源根 Run 是否成功（只有成功的根 Run 才能生成长期记忆候选）") Boolean rootRunSucceeded,
		@Schema(description = "敏感级别，缺省 LOW") MemorySensitivity sensitivity,
		@Schema(description = "用户同意状态，敏感记忆必须为 GRANTED") MemoryConsentStatus consentStatus,
		@Schema(description = "生效时间，缺省当前时间") Instant validFrom,
		@Schema(description = "失效时间（TTL），可空") Instant validTo,
		@Schema(description = "重要度 0-1") Double importance,
		@Schema(description = "置信度 0-1") Double confidence,
		@Schema(description = "来源会话ID") String sourceSessionId,
		@Schema(description = "来源消息ID") String sourceMessageId,
		@Schema(description = "存储TTL过期时间，可空；到期后清理任务可作废，召回链路过期不注入") Instant expiresAt
) {
}
