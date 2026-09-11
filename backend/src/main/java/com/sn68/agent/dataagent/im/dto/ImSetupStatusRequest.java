package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IMSetupStatus请求。
 */
@Schema(description = "IMSetupStatus请求")
public record ImSetupStatusRequest(@Schema(description = "接入配置会话ID") String setupId) {
}
