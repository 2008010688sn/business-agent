package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IM会话绑定查询请求。
 */
@Schema(description = "IM会话绑定查询请求")
public record ImConversationBindingQueryRequest(
		@Schema(description = "平台类型") String provider,
		@Schema(description = "连接器编码") String connectorCode
) {
}
