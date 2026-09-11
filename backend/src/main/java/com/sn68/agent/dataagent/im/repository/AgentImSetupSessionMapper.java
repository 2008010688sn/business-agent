/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.im.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.im.entity.AgentImSetupSession;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * AgentIMSetup会话Mapper服务契约。
 */
@Repository
public interface AgentImSetupSessionMapper extends SuperMapper<AgentImSetupSession> {

	/**
	 * 按租户 + setupId 查询接入安装会话。租户ID为空失败关闭，禁止跨租户读取安装会话。
	 */
	default AgentImSetupSession findBySetupId(String tenantId, String setupId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("缺少租户上下文，无法访问接入会话");
		}
		if (!StringUtils.hasText(setupId)) {
			return null;
		}
		return selectOne(new LambdaQueryWrapper<AgentImSetupSession>()
			.eq(AgentImSetupSession::getTenantId, tenantId.trim())
			.eq(AgentImSetupSession::getSetupId, setupId.trim())
			.last(" limit 1"));
	}

}
