/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.tool.ToolPageQueryReq;
import com.sn68.agent.dataagent.dto.tool.ToolResourceDTO;
import com.sn68.agent.dataagent.dto.tool.ToolDetailResp;
import com.sn68.agent.dataagent.dto.tool.ToolReferenceResp;
import com.sn68.agent.dataagent.dto.tool.ToolVersionPublishReq;
import com.sn68.agent.dataagent.dto.tool.ToolTestReq;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.AgentMcpServer;
import com.sn68.agent.dataagent.entity.AgentMcpTool;
import com.sn68.agent.dataagent.tool.ToolResourceService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具中心 API，管理 MCP/API 执行资源及不可变版本。
 */
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
@Tag(name = "工具中心", description = "管理工具资源、版本、引用和测试")
public class ToolController {

	private final ToolResourceService toolResourceService;

	@PostMapping("/page")
	@Operation(summary = "分页查询工具")
	public IPage<ToolResourceDTO> page(@RequestBody ToolPageQueryReq request) {
		return toolResourceService.page(request);
	}

	@GetMapping("/{resourceKey}/detail")
	@Operation(summary = "查询工具详情和版本")
	public ToolDetailResp detail(@PathVariable String resourceKey) {
		return toolResourceService.detail(resourceKey);
	}

	@PostMapping("/create")
	// headerTemplate 是自由 Map，可能被填入鉴权头明文，关闭出入参记录
	@AccessLog(module = "工具中心", description = "创建工具资源", request = false, response = false)
	@Operation(summary = "创建工具资源")
	public ToolResourceDTO create(@RequestBody ToolResourceDTO request) {
		return toolResourceService.save(request);
	}

	@PutMapping("/{resourceKey}/modify")
	// headerTemplate 是自由 Map，可能被填入鉴权头明文，关闭出入参记录
	@AccessLog(module = "工具中心", description = "修改工具资源", request = false, response = false)
	@Operation(summary = "修改工具资源")
	public ToolResourceDTO modify(@PathVariable String resourceKey,
			@RequestBody ToolResourceDTO request) {
		return toolResourceService.save(new ToolResourceDTO(request.id(), request.resourceType(), resourceKey,
				request.resourceName(), request.serverCode(),
				request.serviceName(), request.baseUrl(), request.toolName(), request.endpointUrl(), request.httpMethod(),
				request.headerTemplate(), request.authType(), request.credentialRef(), request.paramMapping(),
				request.requestTemplate(), request.responseMapping(), request.enabled(), request.status(),
				request.displayOrder(), request.extConfig()));
	}

	@PutMapping("/{resourceKey}/publish")
	@AccessLog(module = "工具中心", description = "发布工具版本", response = false)
	@Operation(summary = "发布不可变工具版本")
	public AgentExecutionResourceVersion publish(@PathVariable String resourceKey,
			@RequestBody ToolVersionPublishReq request) {
		return toolResourceService.publish(resourceKey, request);
	}

	@PostMapping("/{resourceKey}/test")
	@Operation(summary = "测试已发布的只读工具版本")
	public Map<String, Object> test(@PathVariable String resourceKey, @RequestBody ToolTestReq request) {
		return toolResourceService.test(resourceKey, request);
	}

	@DeleteMapping("/{resourceKey}")
	@AccessLog(module = "工具中心", description = "删除工具资源")
	@Operation(summary = "删除未被引用的工具")
	public void delete(@PathVariable String resourceKey) {
		toolResourceService.delete(resourceKey);
	}

	@GetMapping("/{resourceKey}/references")
	@Operation(summary = "查询已发布 Skill 的引用")
	public List<ToolReferenceResp> listReferences(@PathVariable String resourceKey) {
		return toolResourceService.listReferences(resourceKey);
	}

	@GetMapping("/mcp/servers")
	@Operation(summary = "查询 MCP 服务列表")
	public List<AgentMcpServer> listMcpServers() {
		return toolResourceService.listMcpServers();
	}

	@GetMapping("/mcp/servers/{serverCode}/tools")
	@Operation(summary = "查询 MCP 服务工具列表")
	public List<AgentMcpTool> listMcpTools(@PathVariable String serverCode) {
		return toolResourceService.listMcpTools(serverCode);
	}

	@PostMapping("/mcp/servers/{serverCode}/tools/{toolName}/create")
	@AccessLog(module = "工具中心", description = "将 MCP 工具加入工具目录", response = false)
	@Operation(summary = "将 MCP 工具加入工具目录")
	public ToolResourceDTO addMcpTool(@PathVariable String serverCode, @PathVariable String toolName) {
		return toolResourceService.addMcpTool(serverCode, toolName);
	}

	@PostMapping("/mcp/sync")
	@Operation(summary = "同步 MCP 工具")
	public void syncMcpTools() {
		toolResourceService.syncMcpTools();
	}

}
