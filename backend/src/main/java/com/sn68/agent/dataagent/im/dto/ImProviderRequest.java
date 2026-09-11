package com.sn68.agent.dataagent.im.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IM平台请求。
 */
@Schema(description = "IM平台请求")
public record ImProviderRequest(@Schema(description = "平台类型") String provider) {
}
