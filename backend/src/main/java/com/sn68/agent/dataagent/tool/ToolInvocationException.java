/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

/**
 * 工具调用契约异常。错误码用于运行日志和通道侧稳定分类。
 */
public class ToolInvocationException extends RuntimeException {

	public enum Code {
		FLOW_REFERENCE_INVALID,
		FLOW_STATE_STALE,
		FLOW_VERSION_UNAVAILABLE,
		TOOL_PERMISSION_DENIED,
		TOOL_TOKEN_INVALID
	}

	private final Code code;

	public ToolInvocationException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public ToolInvocationException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code getCode() {
		return code;
	}

}
