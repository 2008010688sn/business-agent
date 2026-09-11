/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.routing.RouteProfileBuildStatusResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCreateReq;
import com.sn68.agent.dataagent.dto.routing.RouteProfileCurrentResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileResp;
import com.sn68.agent.dataagent.dto.routing.RouteProfileModifyReq;
import com.sn68.agent.dataagent.service.routing.RouteProfileService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 智能路由 Profile 管理接口：维护平台级路由模型、阈值配置与物料构建状态。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/data-agent/routing-profiles")
@Tag(name = "智能路由 Profile", description = "维护平台级路由模型、阈值和 Artifact 构建状态")
public class RouteProfileController {

	private final RouteProfileService profileService;

	@GetMapping("/current")
	@Operation(summary = "查询当前管理路由 Profile")
	public RouteProfileCurrentResp current() {
		return profileService.current();
	}

	@PostMapping("/create")
	@AccessLog(module = "智能路由 Profile", description = "创建路由 Profile 草稿")
	@Operation(summary = "创建路由 Profile 草稿")
	public RouteProfileResp create(@RequestBody RouteProfileCreateReq request) {
		return profileService.create(request);
	}

	@PutMapping("/{id}/modify")
	@AccessLog(module = "智能路由 Profile", description = "修改路由 Profile 草稿")
	@Operation(summary = "修改路由 Profile 草稿")
	public RouteProfileResp modify(@PathVariable Long id, @RequestBody RouteProfileModifyReq request) {
		return profileService.modify(id, request);
	}

	@PostMapping("/{id}/probe")
	@Operation(summary = "探测路由模型和 embedding 模型")
	public RouteProfileResp probe(@PathVariable Long id) {
		return profileService.probe(id);
	}

	@PostMapping("/{id}/reprobe")
	@Operation(summary = "重新探测路由 Profile 能力")
	public RouteProfileResp reprobe(@PathVariable Long id) {
		return profileService.reprobe(id);
	}

	@PostMapping("/{id}/rebuild")
	@AccessLog(module = "智能路由 Profile", description = "重建路由 Artifact")
	@Operation(summary = "重建路由 Artifact")
	public RouteProfileBuildStatusResp rebuild(@PathVariable Long id) {
		return profileService.rebuild(id);
	}

	@GetMapping("/{id}/build-status")
	@Operation(summary = "查询路由 Artifact 构建状态")
	public RouteProfileBuildStatusResp buildStatus(@PathVariable Long id) {
		return profileService.buildStatus(id);
	}

	@PutMapping("/{id}/activate")
	@AccessLog(module = "智能路由 Profile", description = "激活路由 Profile")
	@Operation(summary = "激活路由 Profile")
	public RouteProfileResp activate(@PathVariable Long id) {
		return profileService.activate(id);
	}
}
