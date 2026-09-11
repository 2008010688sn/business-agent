/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 预算流水聚合查询行（Mapper 内部载体，不对外暴露）。
 */
@Data
public class RuntimeBudgetAggregateRow {

	private Long digitalEmployeeId;

	private String ownerType;

	private Long ownerId;

	private String budgetType;

	private BigDecimal amount;

	private Long runCount;

}
