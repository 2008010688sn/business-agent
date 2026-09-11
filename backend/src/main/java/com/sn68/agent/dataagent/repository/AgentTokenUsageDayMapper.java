/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

import com.sn68.agent.dataagent.entity.AgentTokenUsageDay;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

/**
 * AgentToken用量DayMapper服务契约。
 */
@Repository
public interface AgentTokenUsageDayMapper extends SuperMapper<AgentTokenUsageDay> {

	/**
	 * Token 日用量增量合并写入（PostgreSQL {@code ON CONFLICT} upsert）。
	 *
	 * <p>复杂 SQL（方言 upsert，MyBatis-Plus API 无法表达），XML 维护：
	 * {@code mapper/dataagent/AgentTokenUsageDayMapper.xml}。
	 * 以统计日 + 租户 + 用户 + Agent + 模型 + 来源为冲突键，命中时计数与 token 列累加，
	 * 昵称/Agent 名称仅在新值非空时覆盖，并复活 deleted=false。
	 */
	void upsertDelta(AgentTokenUsageDay usageDay);

}
