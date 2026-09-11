/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.visibility;

import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationAuditReq;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityApplication;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import org.springframework.stereotype.Component;

/**
 * LocalAgent可见性ApprovalAdapter组件，封装 DataAgent 对应业务入口。
 */
@Component
public class LocalAgentVisibilityApprovalAdapter implements AgentVisibilityApprovalAdapter {

	/**
	 * 处理LocalAgent可见性ApprovalAdapter。
	 */
	@Override
	public String mode() {
		return AgentVisibilityConstant.APPROVAL_MODE_LOCAL;
	}

	/**
	 * 处理LocalAgent可见性ApprovalAdapter。
	 */
	@Override
	public void submit(DataAgentVisibilityApplication application, DataAgentVisibilityPolicy policy) {
		// 本地轻量审批只需要保留 PENDING 申请单。
	}

	/**
	 * 处理LocalAgent可见性ApprovalAdapter。
	 */
	@Override
	public void approve(DataAgentVisibilityApplication application, AgentVisibilityApplicationAuditReq request) {
		// 状态流转和授权创建由 DataAgentVisibilityService 统一处理。
	}

	/**
	 * 处理LocalAgent可见性ApprovalAdapter。
	 */
	@Override
	public void reject(DataAgentVisibilityApplication application, AgentVisibilityApplicationAuditReq request) {
		// 状态流转由 DataAgentVisibilityService 统一处理。
	}

}
