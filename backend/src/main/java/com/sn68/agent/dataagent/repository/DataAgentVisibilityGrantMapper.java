/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityGrantPageQueryReq;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityGrant;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * DataAgent可见性GrantMapper服务契约。
 */
@Repository
public interface DataAgentVisibilityGrantMapper extends SuperMapper<DataAgentVisibilityGrant> {

	/**
	 * 分页查询 Agent 的可见性授权（不区分是否过期），支持主体类型/主体 ID/主体名/状态过滤，按创建时间倒序。
	 */
	default IPage<DataAgentVisibilityGrant> selectGrantPage(Long agentId, IPage<DataAgentVisibilityGrant> page,
			AgentVisibilityGrantPageQueryReq request) {
		AgentVisibilityGrantPageQueryReq query = request == null ? new AgentVisibilityGrantPageQueryReq() : request;
		LbqWrapper<DataAgentVisibilityGrant> wrapper = Wraps.<DataAgentVisibilityGrant>lbQ()
			.eq(DataAgentVisibilityGrant::getAgentId, agentId);
		if (StringUtils.hasText(query.getSubjectType())) {
			wrapper.eq(DataAgentVisibilityGrant::getSubjectType, query.getSubjectType().trim().toUpperCase());
		}
		if (StringUtils.hasText(query.getSubjectId())) {
			wrapper.eq(DataAgentVisibilityGrant::getSubjectId, query.getSubjectId().trim());
		}
		if (StringUtils.hasText(query.getSubjectName())) {
			wrapper.like(DataAgentVisibilityGrant::getSubjectName, query.getSubjectName().trim());
		}
		if (StringUtils.hasText(query.getStatus())) {
			wrapper.eq(DataAgentVisibilityGrant::getStatus, query.getStatus().trim().toUpperCase());
		}
		return selectPage(page, wrapper.orderByDesc(DataAgentVisibilityGrant::getCreateTime));
	}

	/**
	 * 查询 Agent 当前生效的授权（ACTIVE 且未过期）。
	 */
	default List<DataAgentVisibilityGrant> listActiveByAgentId(Long agentId) {
		return selectList(activeWrapper().eq(DataAgentVisibilityGrant::getAgentId, agentId));
	}

	/**
	 * 查询主体在 Agent 上当前生效（ACTIVE 且未过期）的授权；参数缺失时返回 null。
	 */
	default DataAgentVisibilityGrant findActive(Long agentId, String subjectType, String subjectId) {
		if (agentId == null || !StringUtils.hasText(subjectType) || !StringUtils.hasText(subjectId)) {
			return null;
		}
		return selectOne(activeWrapper()
			.eq(DataAgentVisibilityGrant::getAgentId, agentId)
			.eq(DataAgentVisibilityGrant::getSubjectType, subjectType.trim().toUpperCase())
			.eq(DataAgentVisibilityGrant::getSubjectId, subjectId.trim())
			.last("limit 1"));
	}

	/**
	 * 查询主体在 Agent 上状态为 ACTIVE 的授权（不校验过期时间，供续期/撤销时定位记录）。
	 */
	default DataAgentVisibilityGrant findActiveStatus(Long agentId, String subjectType, String subjectId) {
		if (agentId == null || !StringUtils.hasText(subjectType) || !StringUtils.hasText(subjectId)) {
			return null;
		}
		return selectOne(Wraps.<DataAgentVisibilityGrant>lbQ()
			.eq(DataAgentVisibilityGrant::getStatus, AgentVisibilityConstant.GRANT_STATUS_ACTIVE)
			.eq(DataAgentVisibilityGrant::getAgentId, agentId)
			.eq(DataAgentVisibilityGrant::getSubjectType, subjectType.trim().toUpperCase())
			.eq(DataAgentVisibilityGrant::getSubjectId, subjectId.trim())
			.last("limit 1"));
	}

	/**
	 * 生效授权公共条件：状态 ACTIVE 且（无过期时间或过期时间晚于当前时刻）。
	 */
	private static LbqWrapper<DataAgentVisibilityGrant> activeWrapper() {
		Instant now = Instant.now();
		return Wraps.<DataAgentVisibilityGrant>lbQ()
			.eq(DataAgentVisibilityGrant::getStatus, AgentVisibilityConstant.GRANT_STATUS_ACTIVE)
			.and(wrapper -> wrapper.isNull(DataAgentVisibilityGrant::getExpireTime)
				.or()
				.gt(DataAgentVisibilityGrant::getExpireTime, now));
	}

}
