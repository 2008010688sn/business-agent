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
package com.sn68.agent.dataagent.tool;

import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.dto.tool.ToolPermissionResult;
import com.sn68.agent.dataagent.properties.ToolCenterProperties;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
/**
 * 工具调用权限服务：基于当前认证上下文判断调用方是否具备目标工具/资源的执行权限。
 */
@Service
@RequiredArgsConstructor
public class ToolPermissionServiceImpl implements ToolPermissionService {

	private final AuthenticationContext authenticationContext;

	private final ToolCenterProperties properties;

	private final PepAuthorizationProperties pepAuthorizationProperties;

	@Override
	public ToolPermissionResult canAccess(List<String> permissionCodes, String denyMessage) {
		List<String> required = permissionCodes == null ? List.of()
				: permissionCodes.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
		if (required.isEmpty()) {
			return new ToolPermissionResult(true, null);
		}
		try {
			if (authenticationContext.anonymous() || authenticationContext.getContext() == null) {
				return denied("授权上下文未建立，请重新发起当前操作", ToolPermissionResult.AUTH_CONTEXT_MISSING);
			}
			if (allowAdminBypass()) {
				return new ToolPermissionResult(true, null);
			}
			List<String> permissions = authenticationContext.funcPermissionList();
			boolean allowed = required.stream()
				.allMatch(permission -> permissions != null && permissions.contains(permission));
			return allowed ? new ToolPermissionResult(true, null)
					: denied(denyMessage, ToolPermissionResult.FUNCTION_PERMISSION_DENIED);
		}
		catch (Exception ex) {
			log.warn("工具权限上下文读取失败. errorType={}", ex.getClass().getSimpleName());
			return denied("授权上下文读取失败，请稍后重试", ToolPermissionResult.AUTH_CONTEXT_ERROR);
		}
	}

	/**
	 * 管理员 bypass 只给人管后台、且仅 SHADOW 保持现网行为。
	 * ENFORCE 租户、Principal（无 UserType / {@code sp_}）一律走功能权限码。
	 */
	private boolean allowAdminBypass() {
		if (isServicePrincipal()) {
			return false;
		}
		if (pepAuthorizationProperties != null
				&& pepAuthorizationProperties.resolveMode(currentTenantId()).enforce()) {
			return false;
		}
		return UserType.isAdmin(authenticationContext.userType());
	}

	private boolean isServicePrincipal() {
		try {
			String userId = authenticationContext.userId();
			return userId != null && userId.trim().startsWith("sp_");
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private ToolPermissionResult denied(String denyMessage, String reasonCode) {
		return new ToolPermissionResult(false,
				StringUtils.hasText(denyMessage) ? denyMessage : properties.getPermission().getDenyMessage(), reasonCode);
	}

}
