/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估运行 Mapper。
 */
@Repository
public interface DataAgentEvalRunMapper extends SuperMapper<DataAgentEvalRun> {

	/**
	 * 按 ID 查询未删除的评估运行记录；已删除返回 null。
	 */
	default DataAgentEvalRun findActiveById(Long id) {
		return selectOne(Wraps.<DataAgentEvalRun>lbQ()
			.eq(DataAgentEvalRun::getDeleted, false)
			.eq(DataAgentEvalRun::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 分页查询租户内评估运行：按套件/评估对象/状态/开始时间区间可选过滤，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalRun> selectRunPage(IPage<DataAgentEvalRun> page, EvalRunQueryRequest request,
			String tenantId) {
		EvalRunQueryRequest query = request == null ? new EvalRunQueryRequest() : request;
		return selectPage(page, Wraps.<DataAgentEvalRun>lbQ()
			.eq(DataAgentEvalRun::getDeleted, false)
			.eq(DataAgentEvalRun::getTenantId, tenantId)
			.eq(DataAgentEvalRun::getSuiteId, query.getSuiteId())
			.eq(DataAgentEvalRun::getSubjectId, query.getSubjectId())
			.eq(DataAgentEvalRun::getStatus, trim(query.getStatus()))
			.ge(DataAgentEvalRun::getStartedAt, query.getStartTime())
			.le(DataAgentEvalRun::getStartedAt, query.getEndTime())
			.orderByDesc(DataAgentEvalRun::getId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
