/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.routing.RoutePreviewReq;
import com.sn68.agent.dataagent.dto.routing.RoutePreviewResp;
import com.sn68.agent.dataagent.service.routing.RoutePreviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 混合路由预览接口：只试算路由决策，不实际执行路由目标。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/data-agent/{agentId}/routing")
@Tag(name = "混合路由预览", description = "试算Agent混合路由决策，不执行路由目标")
public class RoutePreviewController {

	private final RoutePreviewService previewService;

	@PostMapping("/preview")
	@Operation(summary = "预览混合路由决策")
	public RoutePreviewResp preview(@PathVariable Long agentId, @RequestBody RoutePreviewReq request) {
		return previewService.preview(agentId, request);
	}
}
