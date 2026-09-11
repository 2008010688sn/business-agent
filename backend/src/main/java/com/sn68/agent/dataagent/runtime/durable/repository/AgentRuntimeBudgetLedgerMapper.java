/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.repository;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetAggregateRow;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeBudgetLedger;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

/**
 * 持久运行时预算流水 Mapper。查询发生在已校验租户归属的 run 语境内。
 */
@Repository
public interface AgentRuntimeBudgetLedgerMapper extends SuperMapper<AgentRuntimeBudgetLedger> {

	default List<AgentRuntimeBudgetLedger> listByRunId(Long runId) {
		return selectList(Wraps.<AgentRuntimeBudgetLedger>lbQ()
			.eq(AgentRuntimeBudgetLedger::getRunId, runId)
			.orderByAsc(AgentRuntimeBudgetLedger::getId));
	}

	/**
	 * 聚合统计属 Wraps 不适用场景（SUM 聚合），使用注解 SQL。
	 */
	@Select("""
			SELECT COALESCE(SUM(amount), 0)
			FROM agent_runtime_budget_ledger
			WHERE run_id = #{runId}
			  AND budget_type = #{budgetType}
			  AND deleted = false
			""")
	BigDecimal sumAmount(@Param("runId") Long runId, @Param("budgetType") String budgetType);

	/**
	 * 租户时间窗内按预算类型合计。JOIN 运行表才能按数字员工过滤；聚合场景 Wraps 不适用。
	 */
	@Select("""
			SELECT l.budget_type AS budgetType,
			       COALESCE(SUM(l.amount), 0) AS amount,
			       COUNT(DISTINCT l.run_id) AS runCount
			FROM agent_runtime_budget_ledger l
			INNER JOIN agent_runtime_run r ON r.id = l.run_id AND r.deleted = false
			WHERE l.tenant_id = #{tenantId}
			  AND l.deleted = false
			  AND l.occurred_at >= #{fromTime}
			  AND l.occurred_at < #{toTime}
			  AND (#{digitalEmployeeId} IS NULL
			       OR r.digital_employee_id = #{digitalEmployeeId}
			       OR (r.owner_type = 'DIGITAL_EMPLOYEE' AND r.owner_id = #{digitalEmployeeId}))
			GROUP BY l.budget_type
			ORDER BY l.budget_type
			""")
	List<RuntimeBudgetAggregateRow> aggregateByType(@Param("tenantId") String tenantId,
			@Param("fromTime") Instant fromTime, @Param("toTime") Instant toTime,
			@Param("digitalEmployeeId") Long digitalEmployeeId);

	/**
	 * 租户时间窗内按运行主体 + 预算类型合计。
	 */
	@Select("""
			SELECT r.digital_employee_id AS digitalEmployeeId,
			       r.owner_type AS ownerType,
			       r.owner_id AS ownerId,
			       l.budget_type AS budgetType,
			       COALESCE(SUM(l.amount), 0) AS amount,
			       COUNT(DISTINCT l.run_id) AS runCount
			FROM agent_runtime_budget_ledger l
			INNER JOIN agent_runtime_run r ON r.id = l.run_id AND r.deleted = false
			WHERE l.tenant_id = #{tenantId}
			  AND l.deleted = false
			  AND l.occurred_at >= #{fromTime}
			  AND l.occurred_at < #{toTime}
			  AND (#{digitalEmployeeId} IS NULL
			       OR r.digital_employee_id = #{digitalEmployeeId}
			       OR (r.owner_type = 'DIGITAL_EMPLOYEE' AND r.owner_id = #{digitalEmployeeId}))
			GROUP BY r.digital_employee_id, r.owner_type, r.owner_id, l.budget_type
			ORDER BY r.digital_employee_id NULLS LAST, r.owner_type, r.owner_id, l.budget_type
			""")
	List<RuntimeBudgetAggregateRow> aggregateByOwner(@Param("tenantId") String tenantId,
			@Param("fromTime") Instant fromTime, @Param("toTime") Instant toTime,
			@Param("digitalEmployeeId") Long digitalEmployeeId);

}
