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
 * 持久运行时事件类型。
 */
public enum RuntimeEventType implements DictEnum<String> {

	RUN_CREATED("RUN_CREATED", "运行已创建"),

	RUN_STARTED("RUN_STARTED", "运行已开始"),

	RUN_RESUMED("RUN_RESUMED", "运行已恢复"),

	RUN_CANCEL_REQUESTED("RUN_CANCEL_REQUESTED", "已请求取消"),

	RUN_SUCCEEDED("RUN_SUCCEEDED", "运行成功"),

	RUN_FAILED("RUN_FAILED", "运行失败"),

	RUN_CANCELLED("RUN_CANCELLED", "运行已取消"),

	RUN_TIMED_OUT("RUN_TIMED_OUT", "运行超时"),

	RUN_WAITING("RUN_WAITING", "运行等待交互"),

	STEP_READY("STEP_READY", "步骤就绪"),

	STEP_STARTED("STEP_STARTED", "步骤开始"),

	STEP_WAITING("STEP_WAITING", "步骤等待交互"),

	STEP_SUCCEEDED("STEP_SUCCEEDED", "步骤成功"),

	STEP_FAILED("STEP_FAILED", "步骤失败"),

	STEP_CANCELLED("STEP_CANCELLED", "步骤已取消"),

	STEP_TIMED_OUT("STEP_TIMED_OUT", "步骤超时"),

	STEP_SKIPPED("STEP_SKIPPED", "步骤已跳过"),

	INVOCATION_STATE_CHANGED("INVOCATION_STATE_CHANGED", "调用状态变化"),

	ASSISTANT_DELTA("ASSISTANT_DELTA", "助手正文增量"),

	ASSISTANT_SNAPSHOT("ASSISTANT_SNAPSHOT", "助手正文快照"),

	USER_MESSAGE("USER_MESSAGE", "用户消息已受理"),

	RUNTIME_PROGRESS("RUNTIME_PROGRESS", "运行进度"),

	APPROVAL_REQUESTED("APPROVAL_REQUESTED", "审批已发起"),

	APPROVAL_DECIDED("APPROVAL_DECIDED", "审批已决定"),

	AUTHORIZATION_DECISION("AUTHORIZATION_DECISION", "授权决策影子记录");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	RuntimeEventType(String value, String label) {
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
