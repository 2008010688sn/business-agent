/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Agent 评估失败原因。
 */
public enum EvalFailureReasonDict implements DictEnum<String> {

	AGENT_INVOKE_FAILED("AGENT_INVOKE_FAILED", "Agent 执行失败"),

	AGENT_INVOKE_TIMEOUT("AGENT_INVOKE_TIMEOUT", "Agent 执行超时"),

	EXPECTED_OUTPUT_NOT_MATCHED("EXPECTED_OUTPUT_NOT_MATCHED", "输出未命中期望结果"),

	TOOL_TRACE_FAILED("TOOL_TRACE_FAILED", "工具调用存在失败"),

	SQL_SAFETY_RISK("SQL_SAFETY_RISK", "SQL 安全风险"),

	RUN_CANCELLED("RUN_CANCELLED", "评估运行已取消"),

	DRY_RUN_WRITE_BLOCKED("DRY_RUN_WRITE_BLOCKED", "DRY_RUN 拦截写副作用或外部副作用"),

	DRY_RUN_ISOLATION_VIOLATION("DRY_RUN_ISOLATION_VIOLATION", "DRY_RUN 发现权限或租户隔离违规"),

	CODE_ORACLE_MISMATCH("CODE_ORACLE_MISMATCH", "代码 oracle 输出与期望不一致"),

	CODE_ORACLE_UNSAFE_EXECUTOR("CODE_ORACLE_UNSAFE_EXECUTOR", "代码 oracle 拒绝非 docker/非隔离网络执行器"),

	CODE_ORACLE_UNAVAILABLE("CODE_ORACLE_UNAVAILABLE", "代码执行池不可用，代码类用例不能计为通过");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EvalFailureReasonDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

}
