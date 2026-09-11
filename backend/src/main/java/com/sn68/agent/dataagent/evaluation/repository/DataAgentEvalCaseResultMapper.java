/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估用例结果 Mapper。
 */
@Repository
public interface DataAgentEvalCaseResultMapper extends SuperMapper<DataAgentEvalCaseResult> {

	/**
	 * 按 ID 查询未删除的用例结果；已删除返回 null。
	 */
	default DataAgentEvalCaseResult findActiveById(Long id) {
		return selectOne(Wraps.<DataAgentEvalCaseResult>lbQ()
			.eq(DataAgentEvalCaseResult::getDeleted, false)
			.eq(DataAgentEvalCaseResult::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 查询一次评估运行的全部用例结果（汇总统计用），按 ID 升序。
	 */
	default List<DataAgentEvalCaseResult> findByRunId(Long runId) {
		return selectList(Wraps.<DataAgentEvalCaseResult>lbQ()
			.eq(DataAgentEvalCaseResult::getDeleted, false)
			.eq(DataAgentEvalCaseResult::getRunId, runId)
			.orderByAsc(DataAgentEvalCaseResult::getId));
	}

	/**
	 * 查询某运行中指定用例的最大重试序号（无记录返回 0），用于重跑时分配下一次 attemptNo。
	 */
	default Integer findMaxAttemptNo(Long runId, Long caseId) {
		return selectList(Wraps.<DataAgentEvalCaseResult>lbQ()
			.eq(DataAgentEvalCaseResult::getDeleted, false)
			.eq(DataAgentEvalCaseResult::getRunId, runId)
			.eq(DataAgentEvalCaseResult::getCaseId, caseId)).stream()
			.map(DataAgentEvalCaseResult::getAttemptNo)
			.filter(java.util.Objects::nonNull)
			.max(Integer::compareTo)
			.orElse(0);
	}

	/**
	 * 分页查询租户内用例结果：按运行/套件/用例/主体/状态/硬失败过滤，
	 * 关键词命中输入、实际输出、期望输出或运行时请求号，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalCaseResult> selectResultPage(IPage<DataAgentEvalCaseResult> page,
			EvalResultQueryRequest request, String tenantId) {
		EvalResultQueryRequest query = request == null ? new EvalResultQueryRequest() : request;
		var wrapper = Wraps.<DataAgentEvalCaseResult>lbQ()
			.eq(DataAgentEvalCaseResult::getDeleted, false)
			.eq(DataAgentEvalCaseResult::getTenantId, tenantId)
			.eq(DataAgentEvalCaseResult::getRunId, query.getRunId())
			.eq(DataAgentEvalCaseResult::getSuiteId, query.getSuiteId())
			.eq(DataAgentEvalCaseResult::getCaseId, query.getCaseId())
			.eq(DataAgentEvalCaseResult::getSubjectId, query.getSubjectId())
			.eq(DataAgentEvalCaseResult::getStatus, trim(query.getStatus()))
			.eq(DataAgentEvalCaseResult::getHardFail, query.getHardFail());
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(nested -> nested.like(DataAgentEvalCaseResult::getUserInput, keyword)
				.or()
				.like(DataAgentEvalCaseResult::getAgentOutput, keyword)
				.or()
				.like(DataAgentEvalCaseResult::getExpectedOutput, keyword)
				.or()
				.like(DataAgentEvalCaseResult::getRuntimeRequestId, keyword));
		}
		return selectPage(page, wrapper.orderByDesc(DataAgentEvalCaseResult::getId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
