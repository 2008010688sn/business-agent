/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.skill;

import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves the tenant managed by the Skill center.
 *
 * <p>Always the current login tenant. Platform admins and {@code ai-agent:skill:platform}
 * must not rewrite writes onto tenant {@code 1}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillManagementTenantService {

	private final AuthenticationContext authenticationContext;

	public String effectiveTenantId() {
		try {
			String tenantId = authenticationContext.tenantId();
			return StringUtils.hasText(tenantId) ? tenantId.trim() : null;
		}
		catch (Exception ex) {
			log.warn("解析技能中心租户上下文失败, 按无租户处理", ex);
			return null;
		}
	}

}
