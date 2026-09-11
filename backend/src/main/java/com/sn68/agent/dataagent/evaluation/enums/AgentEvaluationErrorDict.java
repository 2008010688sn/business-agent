/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 评估错误码。
 */
@Getter
@AllArgsConstructor
public enum AgentEvaluationErrorDict implements DictEnum<Integer> {

	POLICY_NOT_FOUND(490001, "评估策略不存在或已停用"),

	POLICY_CODE_EXISTS(490002, "评估策略编码已存在"),

	SUBJECT_NOT_FOUND(490003, "评估对象不存在或已停用"),

	SUITE_NOT_FOUND(490004, "评估集不存在或已停用"),

	CASE_NOT_FOUND(490005, "评估用例不存在或已停用"),

	RUN_NOT_FOUND(490006, "评估运行不存在"),

	ADAPTER_NOT_FOUND(490007, "评估适配器不存在或不支持当前对象类型"),

	REQUEST_INVALID(490008, "评估请求参数无效"),

	RUN_CANCELLED(490009, "评估运行已取消"),

	EVALUATION_QUEUE_FULL(490010, "评估执行队列已满"),

	ADAPTER_DISABLED(490011, "评估适配器未启用"),

	ADAPTER_CODE_DUPLICATED(490012, "评估适配器编码重复"),

	AGENT_NOT_FOUND_OR_UNPUBLISHED(490013, "目标 DataAgent 不存在、未发布或当前用户不可见"),

	EMPLOYEE_NOT_FOUND(490014, "数字员工不存在或无权访问"),

	EMPLOYEE_PRODUCTION_RELEASE_REQUIRED(490015, "请先在发布 Tab 发布当前配置并激活生产，再初始化评估");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
