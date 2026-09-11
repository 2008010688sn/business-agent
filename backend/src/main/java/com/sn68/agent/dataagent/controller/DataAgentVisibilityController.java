/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.dto.agent.AgentIdFilterReq;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
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
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.visibility.DataAgentVisibilityService;
import com.sn68.agent.dataagent.vo.AgentRunWorkbenchMetaVO;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 DataAgent 用户目录、可见性策略、授权和申请审批。
 */
@RestController
@RequestMapping("/data-agent")
@RequiredArgsConstructor
@Tag(name = "DataAgent 可见性", description = "维护 DataAgent 用户目录、可见性策略、授权和申请审批")
public class DataAgentVisibilityController {

	private final DataAgentVisibilityService visibilityService;

	private final AgentModelConfigService agentModelConfigService;

	@Operation(summary = "查询DataAgent 可见性清单", description = "查询DataAgent 可见性清单，用于DataAgent 可见性相关管理和运行场景。")
	@PostMapping("/user-workbench-meta/query")
	public AgentRunWorkbenchMetaVO userWorkbenchMeta(
			@RequestBody(required = false) AgentIdFilterReq request) {
		Long agentId = request == null ? null : request.agentId();
		List<DataAgent> availableAgents = visibilityService.listUserWorkbenchAgents();
		DataAgent currentAgent = resolveCurrentAgent(availableAgents, agentId);
		return AgentRunWorkbenchMetaVO.builder()
			.availableAgents(availableAgents)
			.currentAgent(currentAgent)
			.chatModels(currentAgent == null ? Collections.emptyList()
					: agentModelConfigService.listRuntimeChatModels(currentAgent))
			.build();
	}

	@Operation(summary = "分页查询DataAgent 可见性目录", description = "分页查询DataAgent 可见性目录，用于DataAgent 可见性相关管理和运行场景。")
	@PostMapping("/user-catalog/page")
	public IPage<AgentUserCatalogResp> userCatalogPage(
			@RequestBody(required = false) AgentUserCatalogPageQueryReq request) {
		return visibilityService.queryUserCatalogPage(request);
	}

	@Operation(summary = "创建DataAgent 可见性申请单", description = "提交 Agent 可见性申请单，进入审批流。")
	@AccessLog(module = "DataAgent 可见性", description = "提交可见性申请单")
	@PostMapping("/visibility-applications/create")
	public DataAgentVisibilityApplication createApplication(
			@Valid @RequestBody(required = false) AgentVisibilityApplicationCreateReq request) {
		// 请求体可缺省，缺省时无从取得 agentId，与 @NotNull 校验保持同一拒绝语义
		if (request == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		return visibilityService.createApplication(request.getAgentId(), request);
	}

	@Operation(summary = "分页查询我提交的DataAgent 可见性申请单", description = "按当前登录用户分页查询其提交的 Agent 可见性申请单。")
	@PostMapping("/visibility-applications/my-page")
	public IPage<DataAgentVisibilityApplication> myApplicationsPage(
			@RequestBody(required = false) AgentVisibilityApplicationPageQueryReq request) {
		return visibilityService.queryMyApplicationsPage(request);
	}

	@Operation(summary = "查询DataAgent 可见性策略", description = "查询DataAgent 可见性策略，用于DataAgent 可见性相关管理和运行场景。")
	@PostMapping("/visibility-policy/query")
	public DataAgentVisibilityPolicy getPolicy(@Valid @RequestBody AgentIdReq request) {
		return visibilityService.getPolicy(request.agentId());
	}

	@Operation(summary = "修改DataAgent 可见性策略", description = "修改DataAgent 可见性策略，用于DataAgent 可见性相关管理和运行场景。")
	@AccessLog(module = "DataAgent 可见性", description = "修改可见性策略")
	@PutMapping("/visibility-policy/modify")
	public DataAgentVisibilityPolicy modifyPolicy(@Valid @RequestBody AgentVisibilityPolicyReq request) {
		return visibilityService.modifyPolicy(request.getAgentId(), request);
	}

	@Operation(summary = "分页查询DataAgent 可见性授权记录", description = "按 Agent 分页查询已生效的可见性授权记录。")
	@PostMapping("/visibility-grants/page")
	public IPage<DataAgentVisibilityGrant> grantsPage(
			@Valid @RequestBody(required = false) AgentVisibilityGrantPageQueryReq request) {
		// 请求体可缺省，缺省时无从取得 agentId，与 @NotNull 校验保持同一拒绝语义
		if (request == null) {
			throw CheckedException.badRequest("agentId不能为空");
		}
		return visibilityService.queryGrantsPage(request.getAgentId(), request);
	}

	@Operation(summary = "创建DataAgent 可见性授权记录", description = "不经审批流直接为指定对象授予 Agent 可见性，需审批权限。")
	@AccessLog(module = "DataAgent 可见性", description = "直接授予可见性")
	@PostMapping("/visibility-grants/create")
	public DataAgentVisibilityGrant createGrant(@Valid @RequestBody AgentVisibilityGrantCreateReq request) {
		return visibilityService.createGrant(request.getAgentId(), request);
	}

	@Operation(summary = "删除DataAgent 可见性授权记录", description = "按授权记录 ID 回收已授予的 Agent 可见性。")
	@AccessLog(module = "DataAgent 可见性", description = "回收可见性授权")
	@DeleteMapping("/visibility-grants/{id}")
	public void deleteGrant(@PathVariable Long id) {
		visibilityService.deleteGrant(id);
	}

	@Operation(summary = "分页查询待审批的DataAgent 可见性申请单", description = "审批视角分页查询全部 Agent 可见性申请单。")
	@PostMapping("/visibility-applications/page")
	public IPage<DataAgentVisibilityApplication> applicationsPage(
			@RequestBody(required = false) AgentVisibilityApplicationPageQueryReq request) {
		return visibilityService.queryApplicationsPage(request);
	}

	@Operation(summary = "分页查询可申请的DataAgent 选项", description = "分页查询提交可见性申请时可选的 Agent 列表。")
	@PostMapping("/visibility-applications/agent-options/page")
	public IPage<AgentVisibilityAgentOptionResp> applicationAgentOptionsPage(
			@RequestBody(required = false) AgentVisibilityAgentOptionPageQueryReq request) {
		return visibilityService.queryApplicationAgentOptionsPage(request);
	}

	@Operation(summary = "修改DataAgent 可见性状态", description = "修改DataAgent 可见性状态，用于DataAgent 可见性相关管理和运行场景。")
	@AccessLog(module = "DataAgent 可见性", description = "审批可见性申请单")
	@PutMapping("/visibility-applications/{id}/status")
	public DataAgentVisibilityApplication updateApplicationStatus(@PathVariable Long id,
			@RequestBody(required = false) AgentVisibilityApplicationAuditReq request) {
		String status = request == null ? null : request.getStatus();
		if (AgentVisibilityConstant.APPLICATION_STATUS_APPROVED.equalsIgnoreCase(status)) {
			return visibilityService.approveApplication(id, request);
		}
		if (AgentVisibilityConstant.APPLICATION_STATUS_REJECTED.equalsIgnoreCase(status)) {
			return visibilityService.rejectApplication(id, request);
		}
		throw CheckedException.badRequest("status只支持APPROVED或REJECTED");
	}

	private DataAgent resolveCurrentAgent(List<DataAgent> availableAgents, Long agentId) {
		if (availableAgents == null || availableAgents.isEmpty()) {
			return null;
		}
		if (agentId != null) {
			return availableAgents.stream()
				.filter(agent -> agentId.equals(agent.getId()))
				.findFirst()
				.orElse(availableAgents.get(0));
		}
		return availableAgents.get(0);
	}

}
