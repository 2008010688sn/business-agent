/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalSubjectQueryRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent 评估对象 Mapper。
 */
@Repository
public interface DataAgentEvalSubjectMapper extends SuperMapper<DataAgentEvalSubject> {

	/**
	 * 按 ID 查询启用且未删除的评估对象；禁用或已删除返回 null。
	 */
	default DataAgentEvalSubject findEnabledById(Long id) {
		return selectOne(Wraps.<DataAgentEvalSubject>lbQ()
			.eq(DataAgentEvalSubject::getDeleted, false)
			.eq(DataAgentEvalSubject::getStatus, "enabled")
			.eq(DataAgentEvalSubject::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 按租户 + 主体类型 + 业务主体 ID + 适配器编码查询（评估对象唯一性定位用）。
	 */
	default DataAgentEvalSubject findByIdentity(String tenantId, String subjectType, String subjectId, String adapterCode) {
		return selectOne(Wraps.<DataAgentEvalSubject>lbQ()
			.eq(DataAgentEvalSubject::getDeleted, false)
			.eq(DataAgentEvalSubject::getTenantId, tenantId)
			.eq(DataAgentEvalSubject::getSubjectType, trim(subjectType))
			.eq(DataAgentEvalSubject::getSubjectId, trim(subjectId))
			.eq(DataAgentEvalSubject::getAdapterCode, trim(adapterCode))
			.last(" limit 1"));
	}

	/**
	 * 分页查询租户内评估对象：按名称模糊/类型/业务主体 ID/适配器编码/状态可选过滤，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentEvalSubject> selectSubjectPage(IPage<DataAgentEvalSubject> page,
			EvalSubjectQueryRequest request, String tenantId) {
		EvalSubjectQueryRequest query = request == null ? new EvalSubjectQueryRequest() : request;
		return selectPage(page, Wraps.<DataAgentEvalSubject>lbQ()
			.eq(DataAgentEvalSubject::getDeleted, false)
			.eq(DataAgentEvalSubject::getTenantId, tenantId)
			.like(DataAgentEvalSubject::getSubjectName, trim(query.getSubjectName()))
			.eq(DataAgentEvalSubject::getSubjectType, trim(query.getSubjectType()))
			.eq(DataAgentEvalSubject::getSubjectId, trim(query.getSubjectId()))
			.eq(DataAgentEvalSubject::getAdapterCode, trim(query.getAdapterCode()))
			.eq(DataAgentEvalSubject::getStatus, trim(query.getStatus()))
			.orderByDesc(DataAgentEvalSubject::getId));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
