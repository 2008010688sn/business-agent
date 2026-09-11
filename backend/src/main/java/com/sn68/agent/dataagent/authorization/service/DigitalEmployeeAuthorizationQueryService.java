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
package com.sn68.agent.dataagent.authorization.service;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantPermission;
import com.sn68.agent.dataagent.authorization.model.AuthorizationGrantSubjectType;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.repository.AgentAuthorizationGrantMapper;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * DIGITAL_EMPLOYEE 域的 owner 授权查询（PR-3b：新授权记录路径）。
 *
 * <p>数字员工由 agent_authorization_grant 表驱动粗粒度 DISCOVER/USE 判定：
 * 主体匹配（USER/TENANT 等值、TEAM/PERMISSION 包含）+ 权限级别（USE 蕴含 DISCOVER）。
 * 管理员恒放行；无匹配授权默认拒（fail-closed）。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Component
@RequiredArgsConstructor
public class DigitalEmployeeAuthorizationQueryService implements OwnerAuthorizationQueryService {

	private final AgentAuthorizationGrantMapper grantMapper;

	@Override
	public boolean supports(AuthorizationOwnerType ownerType) {
		return AuthorizationOwnerType.DIGITAL_EMPLOYEE == ownerType;
	}

	@Override
	public boolean canDiscover(Long ownerId, AuthorizationSubjectSnapshot subject) {
		if (ownerId == null || subject == null) {
			return false;
		}
		if (subject.isAdmin()) {
			return true;
		}
		return hasMatchedGrant(ownerId, subject, false);
	}

	@Override
	public boolean canUse(Long ownerId, AuthorizationSubjectSnapshot subject) {
		if (ownerId == null || subject == null) {
			return false;
		}
		if (subject.isAdmin()) {
			return true;
		}
		return hasMatchedGrant(ownerId, subject, true);
	}

	/**
	 * 主体匹配判定：requireUse=true 时仅 USE 授权生效；false 时 DISCOVER/USE 均可。
	 */
	private boolean hasMatchedGrant(Long ownerId, AuthorizationSubjectSnapshot subject, boolean requireUse) {
		List<AgentAuthorizationGrant> grants = grantMapper.listActiveByOwner(AuthorizationOwnerType.DIGITAL_EMPLOYEE,
				ownerId, subject.getTenantId());
		return grants.stream()
			.filter(grant -> !requireUse || AuthorizationGrantPermission.USE == grant.getPermission())
			.anyMatch(grant -> matchesSnapshot(grant, subject));
	}

	/**
	 * 授权主体匹配：USER/TENANT 精确等值，TEAM/PERMISSION 列表包含。
	 */
	private boolean matchesSnapshot(AgentAuthorizationGrant grant, AuthorizationSubjectSnapshot subject) {
		if (grant == null || !Objects.equals(AgentAuthorizationGrantMapper.GRANT_STATUS_ACTIVE, grant.getStatus())) {
			return false;
		}
		List<String> teamIds = subject.getTeamIds() == null ? List.of() : subject.getTeamIds();
		List<String> permissions = subject.getFuncPermissions() == null ? List.of() : subject.getFuncPermissions();
		if (AuthorizationGrantSubjectType.USER == grant.getSubjectType()) {
			return Objects.equals(grant.getSubjectId(), subject.getUserId());
		}
		if (AuthorizationGrantSubjectType.TENANT == grant.getSubjectType()) {
			return Objects.equals(grant.getSubjectId(), subject.getTenantId());
		}
		if (AuthorizationGrantSubjectType.TEAM == grant.getSubjectType()) {
			return teamIds.contains(grant.getSubjectId());
		}
		if (AuthorizationGrantSubjectType.PERMISSION == grant.getSubjectType()) {
			return permissions.contains(grant.getSubjectId());
		}
		return false;
	}

}
