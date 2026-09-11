/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.visibility;

import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationAuditReq;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityApplication;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;

/**
 * Agent可见性Approval服务契约。
 */
public interface AgentVisibilityApprovalAdapter {

	/**
	 * 处理Agent可见性Approval。
	 */
	String mode();

	/**
	 * 处理Agent可见性Approval。
	 */
	void submit(DataAgentVisibilityApplication application, DataAgentVisibilityPolicy policy);

	/**
	 * 处理Agent可见性Approval。
	 */
	void approve(DataAgentVisibilityApplication application, AgentVisibilityApplicationAuditReq request);

	/**
	 * 处理Agent可见性Approval。
	 */
	void reject(DataAgentVisibilityApplication application, AgentVisibilityApplicationAuditReq request);

}
