/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.iam;

import com.sn68.agent.dataagent.config.OpenAccessFilter;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalAuthSnapshotResp;
import com.sn68.agent.dataagent.iam.dto.ServicePrincipalRoleResp;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * In-memory service-principal store for standalone mode (no IAM).
 *
 * @author sn68
 */
@Component
public class LocalPrincipalStore {

	private static final String PRINCIPAL_ID_PREFIX = "sp_";

	private final ConcurrentHashMap<String, Record> byPrincipalId = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, Record> byToken = new ConcurrentHashMap<>();

	public Record provision(String tenantId, String externalSubjectId, String displayName) {
		String principalId = PRINCIPAL_ID_PREFIX + externalSubjectId;
		Record existing = byPrincipalId.get(principalId);
		if (existing != null) {
			return existing;
		}
		Record created = new Record(principalId, tenantId, displayName, "ENABLED", 1L, new ArrayList<>(), null, null);
		byPrincipalId.put(principalId, created);
		return created;
	}

	public Record require(String principalId) {
		return principalId == null ? null : byPrincipalId.get(principalId);
	}

	public List<ServicePrincipalRoleResp> listRoles(String principalId) {
		Record record = require(principalId);
		return record == null ? List.of() : List.copyOf(record.roles);
	}

	public void replaceRoles(String principalId, List<String> roleIds) {
		Record record = require(principalId);
		if (record == null) {
			return;
		}
		List<ServicePrincipalRoleResp> roles = new ArrayList<>();
		if (roleIds != null) {
			for (String roleId : roleIds) {
				if (!StringUtils.hasText(roleId)) {
					continue;
				}
				roles.add(ServicePrincipalRoleResp.builder()
					.id(roleId)
					.tenantId(record.tenantId)
					.code(roleId)
					.name(roleId)
					.status("enabled")
					.serviceAssignable(true)
					.build());
			}
		}
		Record updated = record.withRoles(roles).bumpRevision();
		byPrincipalId.put(principalId, updated);
		if (StringUtils.hasText(updated.tokenValue)) {
			byToken.put(updated.tokenValue, updated);
		}
	}

	public void updateStatus(String principalId, String status) {
		Record record = require(principalId);
		if (record == null) {
			return;
		}
		Record updated = record.withStatus(status).bumpRevision();
		byPrincipalId.put(principalId, updated);
		if (StringUtils.hasText(updated.tokenValue)) {
			byToken.put(updated.tokenValue, updated);
		}
	}

	public ServicePrincipalAuthSnapshotResp snapshot(String principalId) {
		Record record = require(principalId);
		if (record == null) {
			return null;
		}
		return ServicePrincipalAuthSnapshotResp.builder()
			.principalId(record.principalId)
			.tenantId(record.tenantId)
			.funcPermissions(List.of())
			.authRevision(record.authRevision)
			.build();
	}

	public IssuedToken issueToken(String tenantId, String principalId, String displayName) {
		Record record = require(principalId);
		if (record == null) {
			record = provision(tenantId, principalId.startsWith(PRINCIPAL_ID_PREFIX)
					? principalId.substring(PRINCIPAL_ID_PREFIX.length()) : principalId, displayName);
		}
		String token = UUID.randomUUID().toString().replace("-", "");
		UserInfoDetails details = UserInfoDetails.builder()
			.userId(principalId)
			.username(principalId)
			.nickName(StringUtils.hasText(displayName) ? displayName : record.displayName)
			.tenantId(StringUtils.hasText(tenantId) ? tenantId : record.tenantId)
			.tenantCode(StringUtils.hasText(tenantId) ? tenantId : record.tenantId)
			.type(OpenAccessFilter.demoUser().getType())
			.enabled(Boolean.TRUE)
			.build();
		Record updated = record.withToken(token, details);
		byPrincipalId.put(principalId, updated);
		byToken.put(token, updated);
		return new IssuedToken(token, "Bearer", 3600L, updated.authRevision);
	}

	public Record findByToken(String tokenValue) {
		return StringUtils.hasText(tokenValue) ? byToken.get(tokenValue) : null;
	}

	public record IssuedToken(String tokenValue, String tokenType, Long expiresIn, Long authRevision) {
	}

	public static final class Record {

		private final String principalId;

		private final String tenantId;

		private final String displayName;

		private final String status;

		private final Long authRevision;

		private final List<ServicePrincipalRoleResp> roles;

		private final String tokenValue;

		private final UserInfoDetails userInfo;

		private Record(String principalId, String tenantId, String displayName, String status, Long authRevision,
				List<ServicePrincipalRoleResp> roles, String tokenValue, UserInfoDetails userInfo) {
			this.principalId = principalId;
			this.tenantId = tenantId;
			this.displayName = displayName;
			this.status = status;
			this.authRevision = authRevision;
			this.roles = roles;
			this.tokenValue = tokenValue;
			this.userInfo = userInfo;
		}

		public String principalId() {
			return principalId;
		}

		public String tenantId() {
			return tenantId;
		}

		public Long authRevision() {
			return authRevision;
		}

		public UserInfoDetails userInfo() {
			return userInfo;
		}

		private Record withRoles(List<ServicePrincipalRoleResp> nextRoles) {
			return new Record(principalId, tenantId, displayName, status, authRevision, nextRoles, tokenValue, userInfo);
		}

		private Record withStatus(String nextStatus) {
			return new Record(principalId, tenantId, displayName, nextStatus, authRevision, roles, tokenValue, userInfo);
		}

		private Record bumpRevision() {
			long next = authRevision == null ? 1L : authRevision + 1L;
			return new Record(principalId, tenantId, displayName, status, next, roles, tokenValue, userInfo);
		}

		private Record withToken(String nextToken, UserInfoDetails details) {
			return new Record(principalId, tenantId, displayName, status, authRevision, roles, nextToken, details);
		}

	}

}
