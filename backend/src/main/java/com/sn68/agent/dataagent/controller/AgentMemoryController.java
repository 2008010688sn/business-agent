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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryConfigResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryDeleteReq;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryExportItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemsQueryReq;
import com.sn68.agent.dataagent.dto.memory.UpdateAgentMemoryConfigReq;
import com.sn68.agent.dataagent.dto.memory.UpdateAgentMemoryStatusReq;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.service.memory.AgentMemoryService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 Agent 用户记忆配置、记忆条目和清理操作。
 */
@RestController
@RequestMapping("/data-agent/memory")
@AllArgsConstructor
@Tag(name = "Agent 记忆", description = "维护 Agent 用户记忆配置、记忆条目和清理操作")
public class AgentMemoryController {

	/**
	 * 记忆导出权限码（敏感记忆治理：导出属数据外带动作，需显式授权）。
	 */
	public static final String PERMISSION_MEMORY_EXPORT = "agent:memory:export";

	/**
	 * 程序性记忆人工审核权限码。
	 */
	public static final String PERMISSION_MEMORY_REVIEW = "agent:memory:review";

	private final AgentMemoryService agentMemoryService;

	private final AuthenticationContext authenticationContext;

	@Operation(summary = "查询Agent 记忆配置", description = "查询Agent 记忆配置，用于Agent 记忆相关管理和运行场景。")
	@PostMapping("/config/query")
	public AgentMemoryConfigResp getConfig(@Valid @RequestBody AgentIdReq request) {
		return agentMemoryService.getConfig(request.agentId(), currentUserId());
	}

	@Operation(summary = "修改Agent 记忆配置", description = "修改Agent 记忆配置，用于Agent 记忆相关管理和运行场景。")
	@AccessLog(module = "Agent 记忆", description = "修改Agent 记忆配置")
	@PutMapping("/config")
	public AgentMemoryConfigResp saveConfig(@Valid @RequestBody UpdateAgentMemoryConfigReq request) {
		return agentMemoryService.saveConfig(request.agentId(), currentUserId(), request);
	}

	@Operation(summary = "查询Agent 记忆清单",
			description = "查询Agent 记忆清单（含范围/敏感级/同意状态等治理字段），支持按治理维度筛选。")
	@PostMapping("/items/query")
	public List<AgentMemoryItemResp> listMemories(@Valid @RequestBody AgentMemoryItemsQueryReq request) {
		return agentMemoryService.listMemories(request, currentUserId());
	}

	@Operation(summary = "修改Agent 记忆状态", description = "修改Agent 记忆状态，用于Agent 记忆相关管理和运行场景。")
	@AccessLog(module = "Agent 记忆", description = "修改Agent 记忆状态")
	@PutMapping("/items/status")
	public void updateStatus(@Valid @RequestBody UpdateAgentMemoryStatusReq request) {
		agentMemoryService.updateStatus(request.agentId(), currentUserId(), request.memoryId(), request.status());
	}

	@Operation(summary = "删除Agent 记忆", description = "删除Agent 记忆，用于Agent 记忆相关管理和运行场景。")
	@AccessLog(module = "Agent 记忆", description = "删除Agent 记忆条目")
	@DeleteMapping("/items")
	public void deleteMemory(@Valid @RequestBody AgentMemoryDeleteReq request) {
		agentMemoryService.deleteMemory(request.agentId(), currentUserId(), request.memoryId());
	}

	@Operation(summary = "删除Agent 记忆", description = "删除Agent 记忆，用于Agent 记忆相关管理和运行场景。")
	@AccessLog(module = "Agent 记忆", description = "清空当前用户Agent 记忆")
	@DeleteMapping("/items/clear")
	public void clearMyMemories(@Valid @RequestBody AgentIdReq request) {
		agentMemoryService.clearMyMemories(request.agentId(), currentUserId());
	}

	@Operation(summary = "导出Agent 记忆",
			description = "按记忆范围与主体全量导出记忆（含治理字段）。用户侧范围只能导出当前登录用户；响应体不进访问日志。")
	@AccessLog(module = "Agent 记忆", description = "按主体导出Agent 记忆", response = false)
	@GetMapping("/export")
	public List<AgentMemoryExportItemResp> exportMemories(@RequestParam Long agentId, @RequestParam MemoryScope scope,
			@RequestParam String subjectId) {
		return agentMemoryService.exportMemoriesBySubject(agentId, scope, subjectId);
	}

	@Operation(summary = "审核通过Agent 程序性记忆", description = "程序性记忆默认待人工审核，审核通过后转为启用并参与召回。")
	@AccessLog(module = "Agent 记忆", description = "审核通过程序性记忆")
	@PutMapping("/items/{memoryId}/approve")
	public void approveMemory(@PathVariable Long memoryId, @Valid @RequestBody AgentIdReq request) {
		agentMemoryService.approveProceduralMemory(request.agentId(), memoryId);
	}

	private String currentUserId() {
		return authenticationContext.userId();
	}

}
