/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetAggregateRow;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetOwnerRowResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetTypeAmountResp;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeBudgetLedgerMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeBudgetReportService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 成本报表实现。流水已按租户记账，这里只做时间窗聚合，不换算金额、不跨租户。
 */
@Service
@RequiredArgsConstructor
public class RuntimeBudgetReportServiceImpl implements RuntimeBudgetReportService {

	static final Duration MAX_RANGE = Duration.ofDays(93);

	static final Duration DEFAULT_RANGE = Duration.ofDays(7);

	private final AgentRuntimeBudgetLedgerMapper budgetLedgerMapper;

	@Override
	public RuntimeBudgetReportResp report(String tenantId, Instant fromTime, Instant toTime, Long digitalEmployeeId) {
		if (tenantId == null) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		Instant to = toTime == null ? Instant.now() : toTime;
		Instant from = fromTime == null ? to.minus(DEFAULT_RANGE) : fromTime;
		if (!from.isBefore(to)) {
			throw CheckedException.badRequest("统计开始时间必须早于结束时间");
		}
		if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
			throw CheckedException.badRequest("统计时间窗不能超过 93 天");
		}
		List<RuntimeBudgetTypeAmountResp> totals = toTypeAmounts(
				budgetLedgerMapper.aggregateByType(tenantId, from, to, digitalEmployeeId));
		List<RuntimeBudgetOwnerRowResp> owners = groupOwners(
				budgetLedgerMapper.aggregateByOwner(tenantId, from, to, digitalEmployeeId));
		return new RuntimeBudgetReportResp(from, to, totals, owners);
	}

	private List<RuntimeBudgetTypeAmountResp> toTypeAmounts(List<RuntimeBudgetAggregateRow> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		List<RuntimeBudgetTypeAmountResp> amounts = new ArrayList<>(rows.size());
		for (RuntimeBudgetAggregateRow row : rows) {
			if (row == null || !StringUtils.hasText(row.getBudgetType())) {
				continue;
			}
			amounts.add(new RuntimeBudgetTypeAmountResp(row.getBudgetType(), amountOrZero(row.getAmount()),
					row.getRunCount() == null ? 0L : row.getRunCount()));
		}
		return List.copyOf(amounts);
	}

	private List<RuntimeBudgetOwnerRowResp> groupOwners(List<RuntimeBudgetAggregateRow> rows) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		Map<String, OwnerAccumulator> grouped = new LinkedHashMap<>();
		for (RuntimeBudgetAggregateRow row : rows) {
			if (row == null || !StringUtils.hasText(row.getBudgetType())) {
				continue;
			}
			String key = (row.getDigitalEmployeeId() == null ? "-" : row.getDigitalEmployeeId()) + "|"
					+ (row.getOwnerType() == null ? "" : row.getOwnerType()) + "|"
					+ (row.getOwnerId() == null ? "-" : row.getOwnerId());
			OwnerAccumulator acc = grouped.computeIfAbsent(key,
					ignored -> new OwnerAccumulator(row.getDigitalEmployeeId(), row.getOwnerType(), row.getOwnerId()));
			acc.amounts.add(new RuntimeBudgetTypeAmountResp(row.getBudgetType(), amountOrZero(row.getAmount()),
					row.getRunCount() == null ? 0L : row.getRunCount()));
			long runCount = row.getRunCount() == null ? 0L : row.getRunCount();
			if (runCount > acc.runCount) {
				acc.runCount = runCount;
			}
		}
		List<RuntimeBudgetOwnerRowResp> owners = new ArrayList<>(grouped.size());
		for (OwnerAccumulator acc : grouped.values()) {
			owners.add(new RuntimeBudgetOwnerRowResp(acc.digitalEmployeeId, acc.ownerType, acc.ownerId, acc.runCount,
					List.copyOf(acc.amounts)));
		}
		return List.copyOf(owners);
	}

	private BigDecimal amountOrZero(BigDecimal amount) {
		return amount == null ? BigDecimal.ZERO : amount;
	}

	private static final class OwnerAccumulator {

		private final Long digitalEmployeeId;

		private final String ownerType;

		private final Long ownerId;

		private long runCount;

		private final List<RuntimeBudgetTypeAmountResp> amounts = new ArrayList<>();

		private OwnerAccumulator(Long digitalEmployeeId, String ownerType, Long ownerId) {
			this.digitalEmployeeId = digitalEmployeeId;
			this.ownerType = ownerType;
			this.ownerId = ownerId;
		}

	}

}
