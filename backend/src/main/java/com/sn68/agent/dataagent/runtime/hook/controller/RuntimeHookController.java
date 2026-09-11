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
package com.sn68.agent.dataagent.runtime.hook.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookDTO;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookLogDTO;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookLogPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookPageQueryRequest;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookService;
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
 * 维护运行钩子配置和执行日志。
 */
@RestController
@RequestMapping("/runtime-hooks")
@RequiredArgsConstructor
@Tag(name = "运行钩子", description = "维护运行钩子配置和执行日志")
public class RuntimeHookController {

	private final RuntimeHookService hookService;

	@Operation(summary = "查询运行钩子清单", description = "查询运行钩子清单，用于运行钩子相关管理和运行场景。")
	@PostMapping("/query")
	public List<RuntimeHookDTO> list(@RequestBody(required = false) RuntimeHookPageQueryRequest request) {
		return hookService.list(request == null ? null : request.getEventType(),
				request == null ? null : request.getAgentId(), request == null ? null : request.getSkillCode(),
				request == null ? null : request.getSkillVersionId(),
				request == null ? null : request.getResourceKey());
	}

	@Operation(summary = "分页查询运行钩子", description = "分页查询运行钩子，用于运行钩子相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<RuntimeHookDTO> page(@RequestBody(required = false) RuntimeHookPageQueryRequest request) {
		return hookService.page(request);
	}

	@Operation(summary = "创建运行钩子", description = "创建运行钩子，用于运行钩子相关管理和运行场景。")
	@AccessLog(module = "运行钩子", description = "创建运行钩子")
	@PostMapping("/create")
	public RuntimeHookDTO create(@RequestBody RuntimeHookDTO request) {
		return hookService.create(request);
	}

	@Operation(summary = "修改运行钩子", description = "修改运行钩子，用于运行钩子相关管理和运行场景。")
	@AccessLog(module = "运行钩子", description = "修改运行钩子")
	@PutMapping("/{id}/modify")
	public RuntimeHookDTO update(@PathVariable Long id, @RequestBody RuntimeHookDTO request) {
		return hookService.update(id, request);
	}

	@Operation(summary = "删除运行钩子", description = "删除运行钩子，用于运行钩子相关管理和运行场景。")
	@AccessLog(module = "运行钩子", description = "删除运行钩子")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		hookService.delete(id);
	}

	@Operation(summary = "查询运行钩子清单", description = "查询运行钩子清单，用于运行钩子相关管理和运行场景。")
	@PostMapping("/logs/query")
	public List<RuntimeHookLogDTO> logs(@RequestBody(required = false) RuntimeHookLogPageQueryRequest request) {
		return hookService.listLogs(request == null ? null : request.getHookCode(),
				request == null ? null : request.getEventType(), request == null ? null : request.getStatus(),
				request == null ? null : request.getAgentId(), request == null ? null : request.getSkillCode(),
				request == null ? null : request.getSkillVersionId(),
				request == null ? null : request.getResourceKey(),
				request == null ? null : request.getRuntimeRequestId());
	}

	@Operation(summary = "分页查询运行钩子", description = "分页查询运行钩子，用于运行钩子相关管理和运行场景。")
	@PostMapping("/logs/page")
	public IPage<RuntimeHookLogDTO> pageLogs(@RequestBody(required = false) RuntimeHookLogPageQueryRequest request) {
		return hookService.pageLogs(request);
	}

}
