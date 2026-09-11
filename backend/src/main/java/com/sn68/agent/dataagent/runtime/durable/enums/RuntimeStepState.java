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
 * 持久运行时 Step 状态。
 *
 * <p>PENDING 表示依赖未满足；READY 表示依赖已满足等待认领；一个步骤进入终态后，
 * 事件驱动调度器立即计算并释放其就绪下游，不等待整批步骤结束。</p>
 */
public enum RuntimeStepState implements DictEnum<String> {

	PENDING("PENDING", "等待依赖"),

	READY("READY", "就绪待执行"),

	RUNNING("RUNNING", "执行中"),

	WAITING("WAITING", "等待交互"),

	SUCCEEDED("SUCCEEDED", "成功"),

	FAILED("FAILED", "失败"),

	CANCELLED("CANCELLED", "已取消"),

	TIMED_OUT("TIMED_OUT", "超时"),

	SKIPPED("SKIPPED", "已跳过");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeStepState(String value, String label) {
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
		return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT || this == SKIPPED;
	}

	/**
	 * 终态状态值集合，供 CAS SQL 的「终态不可覆盖」谓词使用。
	 */
	public static List<String> terminalValues() {
		return List.of(SUCCEEDED.value, FAILED.value, CANCELLED.value, TIMED_OUT.value, SKIPPED.value);
	}

	public static RuntimeStepState of(String value) {
		for (RuntimeStepState state : values()) {
			if (state.value.equals(value)) {
				return state;
			}
		}
		return null;
	}

}
