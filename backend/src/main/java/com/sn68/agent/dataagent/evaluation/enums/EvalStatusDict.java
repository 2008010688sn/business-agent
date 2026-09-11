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
 * Agent 评估状态。
 */
public enum EvalStatusDict implements DictEnum<String> {

	QUEUED("queued", "排队中"),

	RUNNING("running", "运行中"),

	SUCCESS("success", "成功"),

	FAILED("failed", "失败"),

	CANCELLED("cancelled", "已取消"),

	TIMEOUT("timeout", "已超时");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	EvalStatusDict(String value, String label) {
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
