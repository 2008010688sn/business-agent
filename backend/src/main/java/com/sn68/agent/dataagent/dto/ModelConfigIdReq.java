package com.sn68.agent.dataagent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 模型配置Id请求。
 */
@Schema(description = "模型配置Id请求")
public record ModelConfigIdReq(@Schema(description = "模型配置ID") Long modelConfigId) {
}
