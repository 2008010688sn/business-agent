package com.sn68.agent.dataagent.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Channel会话Takeover请求。
 */
@Schema(description = "Channel会话Takeover请求")
public record ChannelSessionTakeoverReq(@Schema(description = "会话ID") Long sessionId) {
}
