/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalDecisionReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalResp;
import com.sn68.agent.dataagent.runtime.durable.dto.ToolConfirmDecisionReq;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持久运行时审批 API：高风险能力调用与 ASSISTED 任务的人工审批入口。
 * 审批通过后由能力网关一次性消费放行（CONSUMED），或经 outbox 事件续发任务运行。
 */
@RestController
@RequestMapping("/agent-approvals")
@RequiredArgsConstructor
@Tag(name = "运行时审批", description = "高风险能力与 ASSISTED 任务的审批查询、通过与驳回")
public class AgentApprovalController {

	private final AgentApprovalService approvalService;

	private final AuthenticationContext authenticationContext;

	@PostMapping("/page")
	@Operation(summary = "分页查询审批记录")
	public IPage<RuntimeApprovalResp> page(@RequestBody(required = false) RuntimeApprovalPageQueryReq request) {
		return approvalService.page(currentTenantId(), request);
	}

	@GetMapping("/{id}/detail")
	@Operation(summary = "查询审批详情", description = "读取时执行懒惰过期判定：已过期的待审批/已通过记录置为 EXPIRED。")
	public RuntimeApprovalResp detail(@PathVariable Long id) {
		return approvalService.detail(currentTenantId(), id);
	}

	@PostMapping("/{id}/approve")
	@AccessLog(module = "运行时审批", description = "审批通过")
	@Operation(summary = "审批通过", description = "PENDING → APPROVED；批复与参数指纹绑定且一次性消费，过期后必须重新发起审批。")
	public void approve(@PathVariable Long id, @RequestBody(required = false) RuntimeApprovalDecisionReq request) {
		approvalService.approve(currentTenantId(), currentUserId(), id, request == null ? null : request.comment());
	}

	@PostMapping("/{id}/reject")
	@AccessLog(module = "运行时审批", description = "审批驳回")
	@Operation(summary = "审批驳回", description = "PENDING → REJECTED，审批意见必填。")
	public void reject(@PathVariable Long id, @RequestBody RuntimeApprovalDecisionReq request) {
		approvalService.reject(currentTenantId(), currentUserId(), id, request == null ? null : request.comment());
	}

	@PostMapping("/tool-confirms/approve")
	@AccessLog(module = "运行时审批", description = "写工具 ASK 确认通过")
	@Operation(summary = "写工具 ASK 确认通过",
			description = "绑定 toolCallId + 参数指纹，TTL 5 分钟，审批表一次性消费；v2 同时写入 Redis 凭证供下一回合跳过 ASK。")
	public void approveToolConfirm(@RequestBody ToolConfirmDecisionReq request) {
		if (request == null) {
			throw CheckedException.badRequest("写工具确认请求不能为空");
		}
		approvalService.decideToolConfirm(currentTenantId(), currentUserId(), request.toolCallId(), request.toolName(),
				request.paramFingerprint(), true, request.comment());
	}

	@PostMapping("/tool-confirms/reject")
	@AccessLog(module = "运行时审批", description = "写工具 ASK 确认驳回")
	@Operation(summary = "写工具 ASK 确认驳回", description = "绑定 toolCallId + 参数指纹驳回，意见必填。")
	public void rejectToolConfirm(@RequestBody ToolConfirmDecisionReq request) {
		if (request == null) {
			throw CheckedException.badRequest("写工具确认请求不能为空");
		}
		approvalService.decideToolConfirm(currentTenantId(), currentUserId(), request.toolCallId(), request.toolName(),
				request.paramFingerprint(), false, request.comment());
	}

	private String currentTenantId() {
		String tenantId = authenticationContext.tenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		return tenantId.trim();
	}

	private String currentUserId() {
		return authenticationContext.userId();
	}

}
