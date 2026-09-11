package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IM连接器Code请求。
 */
@Schema(description = "IM连接器Code请求")
public record ImConnectorCodeRequest(@Schema(description = "连接器编码") String connectorCode) {
}
