/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalSuiteQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估集 Mapper。
 */
@Repository
public interface DataAgentEvalSuiteMapper extends SuperMapper<DataAgentEvalSuite> {

	/**
	 * 按 ID 查询启用且未删除的评估集；禁用或已删除返回 null。
	 */
	default DataAgentEvalSuite findEnabledById(Long id) {
		return selectOne(Wraps.<DataAgentEvalSuite>lbQ()
			.eq(DataAgentEvalSuite::getDeleted, false)
			.eq(DataAgentEvalSuite::getStatus, "enabled")
			.eq(DataAgentEvalSuite::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 评估对象 + 套件名查询（同对象下套件名唯一性校验用）。
	 */
	default DataAgentEvalSuite findBySubjectAndName(String tenantId, Long subjectId, String suiteName) {
		return selectOne(Wraps.<DataAgentEvalSuite>lbQ()
			.eq(DataAgentEvalSuite::getDeleted, false)
			.eq(DataAgentEvalSuite::getTenantId, tenantId)
			.eq(DataAgentEvalSuite::getSubjectId, subjectId)
			.eq(DataAgentEvalSuite::getSuiteName, trim(suiteName))
			.last(" limit 1"));
	}

	/**
	 * 分页查询租户内评估集：按名称模糊/评估对象/策略/状态可选过滤，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalSuite> selectSuitePage(IPage<DataAgentEvalSuite> page, EvalSuiteQueryRequest request,
			String tenantId) {
		EvalSuiteQueryRequest query = request == null ? new EvalSuiteQueryRequest() : request;
		return selectPage(page, Wraps.<DataAgentEvalSuite>lbQ()
			.eq(DataAgentEvalSuite::getDeleted, false)
			.eq(DataAgentEvalSuite::getTenantId, tenantId)
			.like(DataAgentEvalSuite::getSuiteName, trim(query.getSuiteName()))
			.eq(DataAgentEvalSuite::getSubjectId, query.getSubjectId())
			.eq(DataAgentEvalSuite::getPolicyId, query.getPolicyId())
			.eq(DataAgentEvalSuite::getStatus, trim(query.getStatus()))
			.orderByDesc(DataAgentEvalSuite::getId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
