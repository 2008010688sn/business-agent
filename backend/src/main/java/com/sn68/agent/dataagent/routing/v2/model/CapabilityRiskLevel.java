/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 服务端候选能力的风险分级,严重度单调递增;UNKNOWN 无法归类时按最保守处理。
 *
 * <p>编译产物的整体风险只取候选集声明的最高风险,禁止采信提案中的任何风险字段。
 */
public enum CapabilityRiskLevel {

	READ_ONLY(0, false),

	DELEGATED(1, false),

	FLOW(2, true),

	WRITE(3, true),

	UNKNOWN(4, true);

	private final int severity;

	private final boolean approvalRequired;

	CapabilityRiskLevel(int severity, boolean approvalRequired) {
		this.severity = severity;
		this.approvalRequired = approvalRequired;
	}

	public int severity() {
		return severity;
	}

	public boolean requiresApproval() {
		return approvalRequired;
	}

	/** 返回两个风险等级中更严重的一个。 */
	public static CapabilityRiskLevel highest(CapabilityRiskLevel left, CapabilityRiskLevel right) {
		if (left == null) {
			return right == null ? UNKNOWN : right;
		}
		if (right == null) {
			return left;
		}
		return left.severity >= right.severity ? left : right;
	}

}
