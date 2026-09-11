/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.controller;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeInvocationReconcileReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeInvocationResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeInvocationService;
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
 * 外部副作用调用对账 API。
 *
 * <p>本地超时/连接中断只能证明本地停止，无法证明外部副作用是否已发生，这类调用落
 * OUTCOME_UNKNOWN 且禁止自动重试——否则重试等于放任重复写。被封锁的幂等键必须由运维核对
 * 下游系统后经本 API 显式收敛为 SUCCESS 或 FAILED，收敛后该幂等键才恢复可用；
 * 没有这个入口，OUTCOME_UNKNOWN 的幂等键将永久毒化、只能改库解除。</p>
 */
@RestController
@RequestMapping("/runtime-invocations")
@RequiredArgsConstructor
@Tag(name = "运行时调用对账", description = "结果未知的外部副作用调用的人工对账收敛")
public class RuntimeInvocationController {

	private final RuntimeInvocationService runtimeInvocationService;

	private final AuthenticationContext authenticationContext;

	@GetMapping("/pending-reconcile")
	@Operation(summary = "查询待对账调用",
			description = "返回本租户处于 OUTCOME_UNKNOWN / RECONCILING 的调用记录，这些幂等键在收敛前禁止重试。")
	public List<RuntimeInvocationResp> pendingReconcile(
			@RequestParam(name = "limit", required = false) Integer limit) {
		return runtimeInvocationService.listPendingReconcile(currentTenantId(), limit).stream()
			.map(RuntimeInvocationResp::from)
			.toList();
	}

	@PostMapping("/{id}/reconcile")
	@AccessLog(module = "运行时调用对账", description = "人工对账收敛")
	@Operation(summary = "人工对账收敛",
			description = "按核对到的外部实际结果把 OUTCOME_UNKNOWN / RECONCILING 的调用收敛为 SUCCESS 或 FAILED，"
					+ "解除该幂等键的禁止重试封锁。系统不做自动对账：外部副作用是否生效只能人工核对。")
	public void reconcile(@PathVariable Long id, @Valid @RequestBody RuntimeInvocationReconcileReq request) {
		runtimeInvocationService.reconcile(currentTenantId(), currentUserId(), id, request.success(),
				request.comment());
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
