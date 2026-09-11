/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeEventResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCancelReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 持久运行时 Run API：幂等创建、分页、详情、afterSeq 事件回放、取消与恢复（cancel/resume 走状态机）。
 */
@RestController
@RequestMapping("/runtime-runs")
@RequiredArgsConstructor
@Tag(name = "持久运行时", description = "权威运行记录的创建、查询、事件回放、取消与恢复")
public class RuntimeRunController {

	private final RuntimeRunService runtimeRunService;

	private final AuthenticationContext authenticationContext;

	@PostMapping("/create")
	@AccessLog(module = "持久运行时", description = "创建运行")
	@Operation(summary = "幂等创建运行",
			description = "(tenantId, workspaceId, clientRequestId) 幂等，重复请求返回既有运行，不重复创建；幂等域限本租户内。")
	public RuntimeRunResp create(@Valid @RequestBody RuntimeRunCreateReq request) {
		return runtimeRunService.create(currentTenantId(), currentUserId(), request);
	}

	@PostMapping("/page")
	@Operation(summary = "分页查询运行")
	public IPage<RuntimeRunResp> page(@RequestBody RuntimeRunPageQueryReq request) {
		return runtimeRunService.page(currentTenantId(), request);
	}

	@GetMapping("/{id}/detail")
	@Operation(summary = "查询运行详情", description = "返回运行、生效计划、步骤清单与当前最大事件 seq。")
	public RuntimeRunDetailResp detail(@PathVariable Long id) {
		return runtimeRunService.detail(currentTenantId(), id);
	}

	@GetMapping("/{id}/events")
	@Operation(summary = "按 afterSeq 回放运行事件",
			description = "返回 seq 大于 afterSeq 的事件（升序），SSE 断线后携最后收到的 seq 重放，无遗漏、无乱序、无重复。")
	public List<RuntimeEventResp> events(@PathVariable Long id,
			@RequestParam(name = "afterSeq", required = false, defaultValue = "0") Long afterSeq,
			@RequestParam(name = "limit", required = false) Integer limit) {
		return runtimeRunService.events(currentTenantId(), id, afterSeq, limit);
	}

	@PostMapping("/{id}/cancel")
	@AccessLog(module = "持久运行时", description = "取消运行")
	@Operation(summary = "取消运行", description = "取消纪元递增 + 中断记录 + 状态机推进（空闲态直接 CANCELLED，在途态 CANCELLING）。")
	public void cancel(@PathVariable Long id, @RequestBody(required = false) RuntimeRunCancelReq request) {
		runtimeRunService.cancel(currentTenantId(), currentUserId(), id, request == null ? null : request.reason());
	}

	@PostMapping("/{id}/resume")
	@AccessLog(module = "持久运行时", description = "恢复运行")
	@Operation(summary = "恢复运行", description = "仅等待审批/等待输入状态可恢复为 RUNNING，走状态机 CAS。")
	public void resume(@PathVariable Long id) {
		runtimeRunService.resume(currentTenantId(), currentUserId(), id);
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
