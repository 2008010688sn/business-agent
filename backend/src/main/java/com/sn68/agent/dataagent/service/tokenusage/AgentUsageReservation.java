/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import java.util.List;

/**
 * 用量预扣结果：记录本次调用预留的用量条目，用于结算或回退。
 */
public record AgentUsageReservation(List<Entry> entries) {

	public static AgentUsageReservation empty() {
		return new AgentUsageReservation(List.of());
	}

	public boolean isEmpty() {
		return entries == null || entries.isEmpty();
	}

	public record Entry(Long policyId, String key, long reservedAmount, boolean tokenPolicy) {
	}

}
