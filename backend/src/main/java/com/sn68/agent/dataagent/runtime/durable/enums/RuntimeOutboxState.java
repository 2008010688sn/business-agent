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
 * 持久运行时 Outbox 消息派发状态。
 */
public enum RuntimeOutboxState implements DictEnum<String> {

	PENDING("PENDING", "待派发"),

	DISPATCHED("DISPATCHED", "已派发"),

	FAILED("FAILED", "派发失败待重试"),

	DEAD("DEAD", "超过重试上限");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeOutboxState(String value, String label) {
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
