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
package com.sn68.agent.dataagent.authorization.legacy;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.service.OwnerAuthorizationQueryService;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * DATA_AGENT 域的 owner 授权查询（PR-3b 首期：Legacy 判定路径）。
 *
 * <p>数据员工（DataAgent）在策略表迁移完成前继续由存量可见性两表驱动：
 * 判定委托 {@link LegacyVisibilityPolicyProvider}，Agent 状态经 DataAgentService 读取。
 * PR-3d/PR-4 灰度切至策略表后再替换实现。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Component
@RequiredArgsConstructor
public class DataAgentLegacyAuthorizationQueryService implements OwnerAuthorizationQueryService {

	private final DataAgentService dataAgentService;

	private final LegacyVisibilityPolicyProvider legacyProvider;

	@Override
	public boolean supports(AuthorizationOwnerType ownerType) {
		return AuthorizationOwnerType.DATA_AGENT == ownerType;
	}

	@Override
	public boolean canDiscover(Long ownerId, AuthorizationSubjectSnapshot subject) {
		DataAgent agent = ownerId == null ? null : dataAgentService.findById(ownerId);
		return legacyProvider.canDiscover(ownerId, agent == null ? null : agent.getStatus(), subject);
	}

	@Override
	public boolean canUse(Long ownerId, AuthorizationSubjectSnapshot subject) {
		DataAgent agent = ownerId == null ? null : dataAgentService.findById(ownerId);
		return legacyProvider.canUse(ownerId, agent == null ? null : agent.getStatus(), subject);
	}

}
