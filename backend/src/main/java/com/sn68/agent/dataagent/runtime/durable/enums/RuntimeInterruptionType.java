/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 持久运行时中断类型。
 */
public enum RuntimeInterruptionType implements DictEnum<String> {

	CANCEL("CANCEL", "用户取消"),

	TIMEOUT("TIMEOUT", "超时中断"),

	MANUAL_TAKEOVER("MANUAL_TAKEOVER", "人工接管");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeInterruptionType(String value, String label) {
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
