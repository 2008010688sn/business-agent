package com.sn68.agent.dataagent.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 通知连接器Code请求。
 */
@Schema(description = "通知连接器Code请求")
public record NotificationConnectorCodeRequest(@Schema(description = "连接器编码") String connectorCode) {
}
