/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import java.util.Map;
import lombok.Builder;

/**
 * 统一能力结果信封。所有经 {@link CapabilityGateway} 的能力调用统一返回本结构，下游只依赖
 * 结构化字段传递业务数据，不从自然语言回答中恢复业务实体。
 *
 * @param status 调用状态，见 {@link #STATUS_SUCCESS} / {@link #STATUS_FAILED}
 * @param schemaVersion 信封结构版本，见 {@link #SCHEMA_VERSION_V1}
 * @param data 能力原始返回数据（目录能力为响应 Map，进程内工具为原始字符串结果）
 * @param evidence 调用证据：能力编码、来源、耗时、参数指纹等，不含参数值与凭据
 * @param sensitivity 敏感级别，见 {@link #SENSITIVITY_INTERNAL} / {@link #SENSITIVITY_SENSITIVE}
 * @param source 调用来源，与请求一致
 * @param retryability 可重试性，见 {@link #RETRYABILITY_RETRYABLE} 等常量
 * @param sideEffectReceipt 副作用回执（写能力回显幂等键与能力编码），只读能力为空
 * @param idempotencyKey 本次调用生效的幂等键
 */
@Builder(toBuilder = true)
public record ResultEnvelope(
		String status,
		String schemaVersion,
		Object data,
		Map<String, Object> evidence,
		String sensitivity,
		String source,
		String retryability,
		Map<String, Object> sideEffectReceipt,
		String idempotencyKey) {

	public static final String SCHEMA_VERSION_V1 = "result-envelope/v1";

	public static final String STATUS_SUCCESS = "SUCCESS";

	public static final String STATUS_FAILED = "FAILED";

	public static final String SENSITIVITY_INTERNAL = "INTERNAL";

	public static final String SENSITIVITY_SENSITIVE = "SENSITIVE";

	public static final String RETRYABILITY_RETRYABLE = "RETRYABLE";

	public static final String RETRYABILITY_NON_RETRYABLE = "NON_RETRYABLE";

	public static final String RETRYABILITY_UNKNOWN = "UNKNOWN";

}
