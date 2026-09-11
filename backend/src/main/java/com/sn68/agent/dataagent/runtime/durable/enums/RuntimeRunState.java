/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import java.util.List;

/**
 * 持久运行时 Run 状态。
 *
 * <p>终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT）一旦写入不允许被任何旧 fence 覆盖，
 * 状态推进必须经由 {@code RuntimeStateService} 的 CAS 更新。</p>
 */
public enum RuntimeRunState implements DictEnum<String> {

	PENDING("PENDING", "待启动"),

	RUNNING("RUNNING", "运行中"),

	WAITING_APPROVAL("WAITING_APPROVAL", "等待审批"),

	WAITING_INPUT("WAITING_INPUT", "等待用户输入"),

	CANCELLING("CANCELLING", "取消中"),

	SUCCEEDED("SUCCEEDED", "成功"),

	FAILED("FAILED", "失败"),

	CANCELLED("CANCELLED", "已取消"),

	TIMED_OUT("TIMED_OUT", "超时");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeRunState(String value, String label) {
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

	public boolean terminal() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
	}

	/**
	 * 终态状态值集合，供 CAS SQL 的「终态不可覆盖」谓词使用。
	 */
	public static List<String> terminalValues() {
		return List.of(SUCCEEDED.value, FAILED.value, CANCELLED.value, TIMED_OUT.value);
	}

	public static RuntimeRunState of(String value) {
		for (RuntimeRunState state : values()) {
			if (state.value.equals(value)) {
				return state;
			}
		}
		return null;
	}

}
