/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.flow;

/**
 * Output contracts accepted by write-capable FLOW extraction.
 *
 * <p>无论使用哪种协议，响应体都必须是 {@code {"set":{...}}}；JSON_OBJECT 仅在
 * provider 明确以 400/422 拒绝 schema 协议时作为一次 fallback 传输方式，
 * 解码仍走同一严格 set-only 校验。
 */
public enum FlowStructuredOutputProtocol {

	FUNCTION_CALL,

	STRICT_JSON_SCHEMA,

	JSON_OBJECT,

	NONE;

	/** set-only 响应协议中唯一合法的顶层包装字段名。 */
	public static final String SET_FIELD = "set";

}
