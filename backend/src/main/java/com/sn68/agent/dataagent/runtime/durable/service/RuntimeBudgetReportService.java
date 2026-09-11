/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import java.time.Instant;

/**
 * 运行成本报表：按租户时间窗汇总预算流水，可选按数字员工过滤。
 */
public interface RuntimeBudgetReportService {

	RuntimeBudgetReportResp report(String tenantId, Instant fromTime, Instant toTime, Long digitalEmployeeId);

}
