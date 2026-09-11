/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyQueryReq;
import com.sn68.agent.dataagent.entity.AgentUsageLimitPolicy;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Agent用量Limit策略Mapper服务契约。
 */
@Repository
public interface AgentUsageLimitPolicyMapper extends SuperMapper<AgentUsageLimitPolicy> {

	/**
	 * 查询全部已启用的用量限额策略，按 ID 倒序（供限流判断加载全量策略）。
	 */
	default List<AgentUsageLimitPolicy> listEnabled() {
		return selectList(Wraps.<AgentUsageLimitPolicy>lbQ()
			.eq(AgentUsageLimitPolicy::getEnabled, true)
			.orderByDesc(AgentUsageLimitPolicy::getId));
	}

	/**
	 * 分页查询限额策略，支持作用域类型/作用域 ID/Agent/模型/策略类型/启用状态过滤，按 ID 倒序。
	 */
	default IPage<AgentUsageLimitPolicy> selectPolicyPage(IPage<AgentUsageLimitPolicy> page,
			AgentUsageLimitPolicyQueryReq request) {
		return selectPage(page, buildQuery(request).orderByDesc(AgentUsageLimitPolicy::getId));
	}

	/**
	 * 组装限额策略查询条件，空条件自动忽略。
	 */
	private static LbqWrapper<AgentUsageLimitPolicy> buildQuery(AgentUsageLimitPolicyQueryReq request) {
		AgentUsageLimitPolicyQueryReq query = request == null ? new AgentUsageLimitPolicyQueryReq() : request;
		LbqWrapper<AgentUsageLimitPolicy> wrapper = Wraps.lbQ();
		if (StringUtils.hasText(query.getScopeType())) {
			wrapper.eq(AgentUsageLimitPolicy::getScopeType, query.getScopeType().trim());
		}
		if (StringUtils.hasText(query.getScopeId())) {
			wrapper.eq(AgentUsageLimitPolicy::getScopeId, query.getScopeId().trim());
		}
		if (query.getAgentId() != null) {
			wrapper.eq(AgentUsageLimitPolicy::getAgentId, query.getAgentId());
		}
		if (query.getModelConfigId() != null) {
			wrapper.eq(AgentUsageLimitPolicy::getModelConfigId, query.getModelConfigId());
		}
		if (StringUtils.hasText(query.getPolicyType())) {
			wrapper.eq(AgentUsageLimitPolicy::getPolicyType, query.getPolicyType().trim());
		}
		if (query.getEnabled() != null) {
			wrapper.eq(AgentUsageLimitPolicy::getEnabled, query.getEnabled());
		}
		return wrapper;
	}

}
