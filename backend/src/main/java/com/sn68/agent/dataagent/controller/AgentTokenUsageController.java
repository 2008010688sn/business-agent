/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageBreakdownResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageOverviewResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageQueryReq;
import com.sn68.agent.dataagent.dto.tokenusage.AgentTokenUsageSummaryResp;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyQueryReq;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyReq;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyStatusModifyReq;
import com.sn68.agent.dataagent.entity.AgentTokenUsage;
import com.sn68.agent.dataagent.entity.AgentUsageLimitPolicy;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageLimitService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 查询 Agent Token 用量并维护用量限制策略。
 */
@RestController
@RequestMapping("/data-agent/token-usage")
@RequiredArgsConstructor
@Tag(name = "Agent Token 用量", description = "查询 Agent Token 用量并维护用量限制策略")
public class AgentTokenUsageController {

	private final AgentTokenUsageService tokenUsageService;

	private final AgentUsageLimitService limitService;

	private final DataAgentThinkingPermissionService permissionService;

	@Operation(summary = "查询Agent Token 用量概览", description = "查询Agent Token 用量概览，用于Agent Token 用量相关管理和运行场景。")
	@PostMapping("/overview")
	public AgentTokenUsageOverviewResp queryOverview(@RequestBody(required = false) AgentTokenUsageQueryReq request) {
		permissionService.requireCanViewUsage();
		return tokenUsageService.queryOverview(request);
	}

	@Deprecated(since = "2026-07-13", forRemoval = false)
	@Operation(deprecated = true, summary = "查询 Token 用量汇总（已废弃，请使用 /overview）")
	@PostMapping("/summary")
	public AgentTokenUsageSummaryResp querySummary(@RequestBody(required = false) AgentTokenUsageQueryReq request) {
		permissionService.requireCanViewUsage();
		return tokenUsageService.querySummary(request);
	}

	@Deprecated(since = "2026-07-13", forRemoval = false)
	@Operation(deprecated = true, summary = "查询 Token 用量分组（已废弃，请使用 /overview）")
	@PostMapping("/breakdown")
	public List<AgentTokenUsageBreakdownResp> queryBreakdown(
			@RequestBody(required = false) AgentTokenUsageQueryReq request) {
		permissionService.requireCanViewUsage();
		return tokenUsageService.queryBreakdown(request);
	}

	@Operation(summary = "分页查询Agent Token 用量明细", description = "分页查询Agent Token 用量明细，用于Agent Token 用量相关管理和运行场景。")
	@PostMapping("/details/page")
	public IPage<AgentTokenUsage> queryDetailsPage(@RequestBody(required = false) AgentTokenUsageQueryReq request) {
		permissionService.requireCanViewUsage();
		return tokenUsageService.queryDetailsPage(request);
	}

	@Operation(summary = "分页查询Agent Token 用量策略", description = "分页查询Agent Token 用量策略，用于Agent Token 用量相关管理和运行场景。")
	@PostMapping("/policies/page")
	public IPage<AgentUsageLimitPolicy> queryPoliciesPage(
			@RequestBody(required = false) AgentUsageLimitPolicyQueryReq request) {
		permissionService.requireCanManageUsage();
		return limitService.queryPoliciesPage(request);
	}

	@Operation(summary = "创建Agent Token 用量策略", description = "创建Agent Token 用量策略，用于Agent Token 用量相关管理和运行场景。")
	@AccessLog(module = "Agent Token 用量", description = "创建用量限制策略")
	@PostMapping("/policies/create")
	public AgentUsageLimitPolicy createPolicy(@RequestBody AgentUsageLimitPolicyReq request) {
		permissionService.requireCanManageUsage();
		return limitService.createPolicy(request);
	}

	@Operation(summary = "修改Agent Token 用量策略", description = "修改Agent Token 用量策略，用于Agent Token 用量相关管理和运行场景。")
	@AccessLog(module = "Agent Token 用量", description = "修改用量限制策略")
	@PutMapping("/policies/{id}/modify")
	public AgentUsageLimitPolicy updatePolicy(@PathVariable Long id, @RequestBody AgentUsageLimitPolicyReq request) {
		permissionService.requireCanManageUsage();
		return limitService.updatePolicy(id, request);
	}

	@Operation(summary = "修改Agent Token 用量策略", description = "修改Agent Token 用量策略，用于Agent Token 用量相关管理和运行场景。")
	@AccessLog(module = "Agent Token 用量", description = "启停用量限制策略")
	@PutMapping("/policies/{id}/status")
	public void updatePolicyStatus(@PathVariable Long id,
			@RequestBody(required = false) AgentUsageLimitPolicyStatusModifyReq request) {
		permissionService.requireCanManageUsage();
		limitService.updatePolicyStatus(id, request != null && Boolean.TRUE.equals(request.enabled()));
	}

	@Operation(summary = "删除Agent Token 用量策略", description = "删除Agent Token 用量策略，用于Agent Token 用量相关管理和运行场景。")
	@AccessLog(module = "Agent Token 用量", description = "删除用量限制策略")
	@DeleteMapping("/policies/{id}")
	public void deletePolicy(@PathVariable Long id) {
		permissionService.requireCanManageUsage();
		limitService.deletePolicy(id);
	}

}
