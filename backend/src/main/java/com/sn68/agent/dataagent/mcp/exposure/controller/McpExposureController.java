/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.mcp.exposure.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureDTO;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposurePageQueryRequest;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureRuntimeSyncResult;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureService;
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
 * 维护 Agent 能力暴露为 MCP 的配置。
 */
@RestController
@RequestMapping("/mcp-exposures")
@RequiredArgsConstructor
@Tag(name = "MCP 暴露", description = "维护 Agent 能力暴露为 MCP 的配置")
public class McpExposureController {

	private final McpExposureService exposureService;

	@Operation(summary = "查询MCP 暴露清单", description = "查询MCP 暴露清单，用于MCP 暴露相关管理和运行场景。")
	@PostMapping("/query")
	public List<McpExposureDTO> list(@RequestBody(required = false) McpExposurePageQueryRequest request) {
		return exposureService.list(request == null ? null : request.getToolKey(),
				request == null ? null : request.getStatus());
	}

	@Operation(summary = "分页查询MCP 暴露", description = "分页查询MCP 暴露，用于MCP 暴露相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<McpExposureDTO> page(@RequestBody(required = false) McpExposurePageQueryRequest request) {
		return exposureService.page(request);
	}

	@Operation(summary = "创建MCP 暴露", description = "创建MCP 暴露，用于MCP 暴露相关管理和运行场景。")
	@AccessLog(module = "MCP 暴露", description = "创建MCP 暴露")
	@PostMapping("/create")
	public McpExposureDTO create(@RequestBody McpExposureDTO request) {
		return exposureService.create(request);
	}

	@Operation(summary = "修改MCP 暴露", description = "修改MCP 暴露，用于MCP 暴露相关管理和运行场景。")
	@AccessLog(module = "MCP 暴露", description = "修改MCP 暴露")
	@PutMapping("/{id}/modify")
	public McpExposureDTO update(@PathVariable Long id, @RequestBody McpExposureDTO request) {
		return exposureService.update(id, request);
	}

	@Operation(summary = "删除MCP 暴露", description = "删除MCP 暴露，用于MCP 暴露相关管理和运行场景。")
	@AccessLog(module = "MCP 暴露", description = "删除MCP 暴露")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		exposureService.delete(id);
	}

	@Operation(summary = "同步 MCP 暴露运行时", description = "同步当前实例并通知其他在线 AI 实例重新加载 MCP 暴露。")
	@PostMapping("/runtime/sync")
	public McpExposureRuntimeSyncResult syncRuntime() {
		return exposureService.syncRuntime();
	}

}
