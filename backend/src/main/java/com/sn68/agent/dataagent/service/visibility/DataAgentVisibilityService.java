/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.visibility;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.visibility.AgentUserCatalogResp;
import com.sn68.agent.dataagent.dto.visibility.AgentUserCatalogPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityAgentOptionResp;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityAgentOptionPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationAuditReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationCreateReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityApplicationPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityGrantCreateReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityGrantPageQueryReq;
import com.sn68.agent.dataagent.dto.visibility.AgentVisibilityPolicyReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityApplication;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityGrant;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import java.util.List;

/**
 * DataAgent可见性服务契约。
 */
public interface DataAgentVisibilityService {

	/**
	 * 查询DataAgent可见性。
	 */
	List<DataAgent> listUserWorkbenchAgents();

	/**
	 * 查询DataAgent可见性。
	 */
	IPage<AgentUserCatalogResp> queryUserCatalogPage(AgentUserCatalogPageQueryReq request);

	/**
	 * 创建DataAgent可见性。
	 */
	DataAgentVisibilityApplication createApplication(Long agentId, AgentVisibilityApplicationCreateReq request);

	/**
	 * 查询DataAgent可见性。
	 */
	IPage<DataAgentVisibilityApplication> queryMyApplicationsPage(AgentVisibilityApplicationPageQueryReq request);

	/**
	 * 查询DataAgent可见性。
	 */
	DataAgentVisibilityPolicy getPolicy(Long agentId);

	/**
	 * 保存DataAgent可见性。
	 */
	DataAgentVisibilityPolicy modifyPolicy(Long agentId, AgentVisibilityPolicyReq request);

	/**
	 * 查询DataAgent可见性。
	 */
	IPage<DataAgentVisibilityGrant> queryGrantsPage(Long agentId, AgentVisibilityGrantPageQueryReq request);

	/**
	 * 创建DataAgent可见性。
	 */
	DataAgentVisibilityGrant createGrant(Long agentId, AgentVisibilityGrantCreateReq request);

	/**
	 * 删除DataAgent可见性。
	 */
	void deleteGrant(Long id);

	/**
	 * 查询DataAgent可见性。
	 */
	IPage<DataAgentVisibilityApplication> queryApplicationsPage(AgentVisibilityApplicationPageQueryReq request);

	/**
	 * 分页查询可发起可见性申请的 Agent 选项列表。
	 */
	IPage<AgentVisibilityAgentOptionResp> queryApplicationAgentOptionsPage(
			AgentVisibilityAgentOptionPageQueryReq request);

	/**
	 * 处理DataAgent可见性。
	 */
	DataAgentVisibilityApplication approveApplication(Long id, AgentVisibilityApplicationAuditReq request);

	/**
	 * 处理DataAgent可见性。
	 */
	DataAgentVisibilityApplication rejectApplication(Long id, AgentVisibilityApplicationAuditReq request);

	/**
	 * 创建DataAgent可见性。
	 */
	DataAgentVisibilityGrant createGrantByApprovedApplication(DataAgentVisibilityApplication application);

	/**
	 * 处理DataAgent可见性。
	 */
	boolean canVisibleInUserWorkbench(Long agentId);

}
