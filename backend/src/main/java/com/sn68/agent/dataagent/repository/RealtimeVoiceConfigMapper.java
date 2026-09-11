/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.RealtimeVoiceConfig;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * 实时语音配置Mapper服务契约。
 */
@Repository
public interface RealtimeVoiceConfigMapper extends SuperMapper<RealtimeVoiceConfig> {

	/**
	 * 按主键查询实时语音配置；逻辑删除自动过滤。
	 */
	default RealtimeVoiceConfig findById(Long id) {
		return selectOne(new LambdaQueryWrapper<RealtimeVoiceConfig>().eq(RealtimeVoiceConfig::getId, id));
	}

	/**
	 * 查询指定租户下的实时语音配置：agentId 非空查该 Agent 专属，为空查该租户未绑定 Agent 的默认配置。
	 */
	default List<RealtimeVoiceConfig> findByAgentId(String tenantId, Long agentId) {
		if (!StringUtils.hasText(tenantId)) {
			return List.of();
		}
		return selectList(Wraps.<RealtimeVoiceConfig>lbQ()
			.eq(RealtimeVoiceConfig::getTenantId, tenantId.trim())
			.eq(agentId != null, RealtimeVoiceConfig::getAgentId, agentId)
			.isNull(agentId == null, RealtimeVoiceConfig::getAgentId)
			.orderByDesc(RealtimeVoiceConfig::getIsDefault)
			.orderByDesc(RealtimeVoiceConfig::getCreateTime));
	}

	/**
	 * 查询本租户生效的默认实时语音配置：优先 Agent 专属默认，缺省回退到本租户未绑定 Agent 的默认。
	 */
	default RealtimeVoiceConfig findDefault(String tenantId, Long agentId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		String tenant = tenantId.trim();
		RealtimeVoiceConfig agentDefault = agentId == null ? null
				: selectOne(Wraps.<RealtimeVoiceConfig>lbQ()
					.eq(RealtimeVoiceConfig::getTenantId, tenant)
					.eq(RealtimeVoiceConfig::getAgentId, agentId)
					.eq(RealtimeVoiceConfig::getIsDefault, true)
					.eq(RealtimeVoiceConfig::getEnabled, true)
					.last(" limit 1"));
		if (agentDefault != null) {
			return agentDefault;
		}
		return selectOne(Wraps.<RealtimeVoiceConfig>lbQ()
			.eq(RealtimeVoiceConfig::getTenantId, tenant)
			.isNull(RealtimeVoiceConfig::getAgentId)
			.eq(RealtimeVoiceConfig::getIsDefault, true)
			.eq(RealtimeVoiceConfig::getEnabled, true)
			.last(" limit 1"));
	}

	/**
	 * 清除同租户、同 Agent 作用域内的其它默认标记。agentId 为空只清本租户未绑定 Agent 的默认。
	 */
	default void clearDefault(String tenantId, Long agentId, Long keepId) {
		if (!StringUtils.hasText(tenantId)) {
			return;
		}
		update(null, Wraps.<RealtimeVoiceConfig>lbU()
			.eq(RealtimeVoiceConfig::getTenantId, tenantId.trim())
			.eq(agentId != null, RealtimeVoiceConfig::getAgentId, agentId)
			.isNull(agentId == null, RealtimeVoiceConfig::getAgentId)
			.ne(keepId != null, RealtimeVoiceConfig::getId, keepId)
			.set(RealtimeVoiceConfig::getIsDefault, false)
			.set(RealtimeVoiceConfig::getLastModifyTime, Instant.now()));
	}

}
