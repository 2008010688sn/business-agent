/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估用例 Mapper。
 */
@Repository
public interface DataAgentEvalCaseMapper extends SuperMapper<DataAgentEvalCase> {

	/**
	 * 按 ID 查询启用且未删除的评估用例；禁用或已删除返回 null。
	 */
	default DataAgentEvalCase findEnabledById(Long id) {
		return selectOne(Wraps.<DataAgentEvalCase>lbQ()
			.eq(DataAgentEvalCase::getDeleted, false)
			.eq(DataAgentEvalCase::getStatus, "enabled")
			.eq(DataAgentEvalCase::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 套件 + 用例名查询（同套件内用例名唯一性校验用）。
	 */
	default DataAgentEvalCase findBySuiteAndName(String tenantId, Long suiteId, String caseName) {
		return selectOne(Wraps.<DataAgentEvalCase>lbQ()
			.eq(DataAgentEvalCase::getDeleted, false)
			.eq(DataAgentEvalCase::getTenantId, tenantId)
			.eq(DataAgentEvalCase::getSuiteId, suiteId)
			.eq(DataAgentEvalCase::getCaseName, trim(caseName))
			.last(" limit 1"));
	}

	/**
	 * 查询套件下全部启用用例（评估运行取数用），按 ID 升序保证执行顺序稳定。
	 */
	default List<DataAgentEvalCase> findEnabledBySuiteId(Long suiteId) {
		return selectList(Wraps.<DataAgentEvalCase>lbQ()
			.eq(DataAgentEvalCase::getDeleted, false)
			.eq(DataAgentEvalCase::getStatus, "enabled")
			.eq(DataAgentEvalCase::getSuiteId, suiteId)
			.orderByAsc(DataAgentEvalCase::getId));
	}

	/**
	 * 分页查询租户内评估用例：按套件/用例名模糊/状态过滤，关键词命中输入或期望输出，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalCase> selectCasePage(IPage<DataAgentEvalCase> page, EvalCaseQueryRequest request,
			String tenantId) {
		EvalCaseQueryRequest query = request == null ? new EvalCaseQueryRequest() : request;
		var wrapper = Wraps.<DataAgentEvalCase>lbQ()
			.eq(DataAgentEvalCase::getDeleted, false)
			.eq(DataAgentEvalCase::getTenantId, tenantId)
			.eq(DataAgentEvalCase::getSuiteId, query.getSuiteId())
			.like(DataAgentEvalCase::getCaseName, trim(query.getCaseName()))
			.eq(DataAgentEvalCase::getStatus, trim(query.getStatus()));
		if (StringUtils.hasText(query.getKeyword())) {
			String keyword = query.getKeyword().trim();
			wrapper.and(nested -> nested.like(DataAgentEvalCase::getUserInput, keyword)
				.or()
				.like(DataAgentEvalCase::getExpectedOutput, keyword));
		}
		return selectPage(page, wrapper.orderByDesc(DataAgentEvalCase::getId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
