/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent 评估权限服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEvaluationPermissionService {

	private static final String PERMISSION_VIEW = "agent:evaluation:view";

	private static final String PERMISSION_MANAGE = "agent:evaluation:manage";

	private static final String PERMISSION_RUN = "agent:evaluation:run";

	private static final String PERMISSION_TROUBLESHOOTING_VIEW = "agent:troubleshooting:view";

	private static final String PERMISSION_TROUBLESHOOTING_SOURCE_VIEW = "agent:troubleshooting:source-view";

	private final AuthenticationContext authenticationContext;

	/**
	 * 校验AgentEvaluation权限。
	 */
	public void requireView() {
		require(PERMISSION_VIEW);
	}

	/**
	 * 校验AgentEvaluation权限。
	 */
	public void requireManage() {
		require(PERMISSION_MANAGE);
	}

	/**
	 * 校验AgentEvaluation权限。
	 */
	public void requireRun() {
		require(PERMISSION_RUN);
	}

	/**
	 * 校验AgentEvaluation权限。
	 */
	public void requireTroubleshootingView() {
		require(PERMISSION_TROUBLESHOOTING_VIEW);
	}

	/**
	 * 校验AgentEvaluation权限。
	 */
	public boolean canViewSourceTrace() {
		return canView(PERMISSION_TROUBLESHOOTING_SOURCE_VIEW);
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
			log.warn("解析 Agent 评估权限失败, 按无权限处理, permissionCode={}", permissionCode, ex);
			return false;
		}
	}

}
