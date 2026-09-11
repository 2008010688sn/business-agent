/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent 自优化权限服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOptimizationPermissionService {

	private static final String PERMISSION_VIEW = "agent:optimization:view";

	private static final String PERMISSION_MANAGE = "agent:optimization:manage";

	private static final String PERMISSION_RELEASE = "agent:optimization:release";

	private static final String PERMISSION_ROLLBACK = "agent:optimization:rollback";

	private final AuthenticationContext authenticationContext;

	/**
	 * 校验Agent优化权限。
	 */
	public void requireView() {
		require(PERMISSION_VIEW);
	}

	/**
	 * 校验Agent优化权限。
	 */
	public void requireManage() {
		require(PERMISSION_MANAGE);
	}

	/**
	 * 校验Agent优化权限。
	 */
	public void requireRelease() {
		require(PERMISSION_RELEASE);
	}

	/**
	 * 校验Agent优化权限。
	 */
	public void requireRollback() {
		require(PERMISSION_ROLLBACK);
	}

	private void require(String permissionCode) {
		if (!canView(permissionCode)) {
			throw CheckedException.forbidden();
		}
	}

	private boolean canView(String permissionCode) {
		try {
			if (!StringUtils.hasText(permissionCode) || authenticationContext.anonymous()
					|| authenticationContext.getContext() == null) {
				return false;
			}
			if (UserType.isAdmin(authenticationContext.userType())) {
				return true;
			}
			List<String> permissions = authenticationContext.funcPermissionList();
			return permissions != null && permissions.contains(permissionCode);
		}
		catch (Exception ex) {
			log.warn("解析 Agent 自优化权限失败, 按无权限处理, permissionCode={}", permissionCode, ex);
			return false;
		}
	}

}
