/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentQueryRequest;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptExperiment;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent 自优化实验 Mapper。
 */
@Repository
public interface DataAgentOptExperimentMapper extends SuperMapper<DataAgentOptExperiment> {

	/**
	 * 按 ID 查询未删除的优化实验；已删除返回 null。
	 */
	default DataAgentOptExperiment findActiveById(Long id) {
		return selectOne(Wraps.<DataAgentOptExperiment>lbQ()
			.eq(DataAgentOptExperiment::getDeleted, false)
			.eq(DataAgentOptExperiment::getId, id)
			.last(" limit 1"));
	}

	/**
	 * 分页查询租户内优化实验：按评估对象/状态/实验名模糊可选过滤，未删除，按 ID 倒序。
	 */
	default IPage<DataAgentOptExperiment> selectExperimentPage(IPage<DataAgentOptExperiment> page,
			OptExperimentQueryRequest request, String tenantId) {
		OptExperimentQueryRequest query = request == null ? new OptExperimentQueryRequest() : request;
		var wrapper = Wraps.<DataAgentOptExperiment>lbQ()
			.eq(DataAgentOptExperiment::getDeleted, false)
			.eq(DataAgentOptExperiment::getTenantId, tenantId)
			.eq(DataAgentOptExperiment::getSubjectId, query.getSubjectId())
			.eq(DataAgentOptExperiment::getOwnerType, trim(query.getOwnerType()))
			.eq(DataAgentOptExperiment::getOwnerId, trim(query.getOwnerId()))
			.eq(DataAgentOptExperiment::getStatus, trim(query.getStatus()));
		if (StringUtils.hasText(query.getExperimentName())) {
			wrapper.like(DataAgentOptExperiment::getExperimentName, query.getExperimentName().trim());
		}
		return selectPage(page, wrapper.orderByDesc(DataAgentOptExperiment::getId));
	}

	/**
	 * 按归属查询未删除实验，最新在前，最多 5 条。owner 任一为空则返回空列表。
	 */
	default java.util.List<DataAgentOptExperiment> findActiveByOwner(String tenantId, String ownerType, String ownerId) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(ownerType) || !StringUtils.hasText(ownerId)) {
			return java.util.List.of();
		}
		return selectList(Wraps.<DataAgentOptExperiment>lbQ()
			.eq(DataAgentOptExperiment::getDeleted, false)
			.eq(DataAgentOptExperiment::getTenantId, tenantId.trim())
			.eq(DataAgentOptExperiment::getOwnerType, ownerType.trim())
			.eq(DataAgentOptExperiment::getOwnerId, ownerId.trim())
			.orderByDesc(DataAgentOptExperiment::getId)
			.last(" limit 5"));
	}

	private static String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
