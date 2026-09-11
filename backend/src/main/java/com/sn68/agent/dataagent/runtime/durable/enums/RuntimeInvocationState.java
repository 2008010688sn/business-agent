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
 * 持久运行时外部副作用调用状态。
 *
 * <p>生命周期：DISPATCH_INTENT → INVOCATION_SENT → SUCCESS / FAILED / OUTCOME_UNKNOWN → RECONCILING。
 * {@code Future.cancel(true)} 与线程中断只代表本地停止请求，不能证明外部副作用未发生，
 * 因此超时/中断后的调用必须落 OUTCOME_UNKNOWN；OUTCOME_UNKNOWN 禁止自动重试，只能进入对账。</p>
 */
public enum RuntimeInvocationState implements DictEnum<String> {

	DISPATCH_INTENT("DISPATCH_INTENT", "准备下发"),

	INVOCATION_SENT("INVOCATION_SENT", "已下发"),

	SUCCESS("SUCCESS", "成功"),

	FAILED("FAILED", "失败"),

	OUTCOME_UNKNOWN("OUTCOME_UNKNOWN", "结果未知"),

	RECONCILING("RECONCILING", "对账中");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeInvocationState(String value, String label) {
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
		return this == SUCCESS || this == FAILED;
	}

	public static RuntimeInvocationState of(String value) {
		for (RuntimeInvocationState state : values()) {
			if (state.value.equals(value)) {
				return state;
			}
		}
		return null;
	}

}
