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

import com.sn68.agent.dataagent.im.entity.AgentImUserBindSession;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * IM 用户扫码绑定会话 Mapper。
 */
@Repository
public interface AgentImUserBindSessionMapper extends SuperMapper<AgentImUserBindSession> {

	default AgentImUserBindSession findByIdInTenant(String tenantId, Long id) {
		if (!StringUtils.hasText(tenantId) || id == null) {
			return null;
		}
		return selectOne(Wraps.<AgentImUserBindSession>lbQ()
			.eq(AgentImUserBindSession::getTenantId, tenantId.trim())
			.eq(AgentImUserBindSession::getId, id));
	}

	default AgentImUserBindSession findPendingByCode(String tenantId, String bindCode) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(bindCode)) {
			return null;
		}
		return selectOne(Wraps.<AgentImUserBindSession>lbQ()
			.eq(AgentImUserBindSession::getTenantId, tenantId.trim())
			.eq(AgentImUserBindSession::getBindCode, bindCode.trim())
			.eq(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_PENDING));
	}

	default List<AgentImUserBindSession> findPendingByUser(String tenantId, String userId, String connectorCode) {
		if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)) {
			return List.of();
		}
		return selectList(Wraps.<AgentImUserBindSession>lbQ()
			.eq(AgentImUserBindSession::getTenantId, tenantId.trim())
			.eq(AgentImUserBindSession::getUserId, userId.trim())
			.eq(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_PENDING)
			.eq(AgentImUserBindSession::getConnectorCode,
					StringUtils.hasText(connectorCode) ? connectorCode.trim() : null));
	}

	default int markConsumed(Long id, String externalUserId, LocalDateTime consumedAt) {
		if (id == null) {
			return 0;
		}
		return update(null, Wraps.<AgentImUserBindSession>lbU()
			.eq(AgentImUserBindSession::getId, id)
			.eq(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_PENDING)
			.set(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_CONSUMED)
			.set(AgentImUserBindSession::getExternalUserId, externalUserId)
			.set(AgentImUserBindSession::getConsumedAt, consumedAt));
	}

	default int expirePending(Long id) {
		if (id == null) {
			return 0;
		}
		return update(null, Wraps.<AgentImUserBindSession>lbU()
			.eq(AgentImUserBindSession::getId, id)
			.eq(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_PENDING)
			.set(AgentImUserBindSession::getStatus, ImConstants.BIND_SESSION_EXPIRED));
	}
}
