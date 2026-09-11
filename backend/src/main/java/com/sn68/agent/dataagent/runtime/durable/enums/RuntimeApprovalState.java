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
 * 持久运行时审批状态。过期、参数变化、权限变化后必须重新发起审批。
 *
 * <p>状态机：PENDING → APPROVED / REJECTED / EXPIRED / CANCELLED；APPROVED → CONSUMED（一次性
 * 消费，用后失效）/ EXPIRED（批复后未在有效期内使用）。其余流转一律非法。</p>
 */
public enum RuntimeApprovalState implements DictEnum<String> {

	PENDING("PENDING", "待审批"),

	APPROVED("APPROVED", "已通过"),

	REJECTED("REJECTED", "已拒绝"),

	EXPIRED("EXPIRED", "已过期"),

	CANCELLED("CANCELLED", "已取消"),

	/** 已通过的审批被一次性消费后进入本终态，同一审批不允许二次放行。 */
	CONSUMED("CONSUMED", "已消费");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeApprovalState(String value, String label) {
		this.value = value;
		this.label = label;
	}

	/**
	 * 解析审批状态，未识别返回 null（由调用方决定失败语义）。
	 */
	public static RuntimeApprovalState of(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		for (RuntimeApprovalState state : values()) {
			if (state.value.equalsIgnoreCase(value.trim())) {
				return state;
			}
		}
		return null;
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
