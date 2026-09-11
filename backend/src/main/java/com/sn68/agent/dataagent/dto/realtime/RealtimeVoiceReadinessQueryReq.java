package com.sn68.agent.dataagent.dto.realtime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 实时语音Readiness查询请求。
 */
@Schema(description = "实时语音Readiness查询请求")
public record RealtimeVoiceReadinessQueryReq(
		@Schema(description = "Agent ID") Long agentId,
		@Schema(description = "配置时间") Long realtimeConfigId
) {
}
