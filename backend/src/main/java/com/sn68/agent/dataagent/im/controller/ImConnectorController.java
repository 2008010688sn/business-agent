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
package com.sn68.agent.dataagent.im.controller;

import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.im.dto.ImConnectorCodeRequest;
import com.sn68.agent.dataagent.im.dto.ImConnectorDTO;
import com.sn68.agent.dataagent.im.service.ImConnectorService;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
 * IM 对话连接器管理接口。
 */
@RestController
@RequestMapping("/im-connectors")
@RequiredArgsConstructor
@Tag(name = "IM 连接器", description = "维护 IM 连接器配置和连通性测试")
public class ImConnectorController {

	private final ImConnectorService connectorService;

	@Operation(summary = "查询IM 连接器清单", description = "查询IM 连接器清单，用于IM 连接器相关管理和运行场景。")
	@GetMapping
	public List<ImConnectorDTO> list() {
		return connectorService.list();
	}

	@Operation(summary = "查询IM 连接器详情", description = "查询IM 连接器详情，用于IM 连接器相关管理和运行场景。")
	@PostMapping("/detail/query")
	public ImConnectorDTO get(@RequestBody ImConnectorCodeRequest request) {
		return connectorService.get(request == null ? null : request.connectorCode());
	}

	@Operation(summary = "创建IM 连接器", description = "创建IM 连接器，用于IM 连接器相关管理和运行场景。")
	@AccessLog(module = "IM 连接器", description = "创建IM 连接器")
	@PostMapping("/create")
	public ImConnectorDTO create(@RequestBody ImConnectorDTO request) {
		return connectorService.create(request);
	}

	@Operation(summary = "修改IM 连接器", description = "修改IM 连接器，用于IM 连接器相关管理和运行场景。")
	@AccessLog(module = "IM 连接器", description = "修改IM 连接器")
	@PutMapping("/{id}/modify")
	public ImConnectorDTO update(@PathVariable Long id, @RequestBody ImConnectorDTO request) {
		return connectorService.update(id, request);
	}

	@Operation(summary = "删除IM 连接器", description = "删除IM 连接器，用于IM 连接器相关管理和运行场景。")
	@AccessLog(module = "IM 连接器", description = "删除IM 连接器")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		connectorService.delete(id);
	}

	@Operation(summary = "测试IM 连接器", description = "测试IM 连接器，用于IM 连接器相关管理和运行场景。")
	@PostMapping("/test")
	public NotificationAdapterResult test(@RequestBody ImConnectorCodeRequest request) {
		return connectorService.test(request == null ? null : request.connectorCode());
	}

}
