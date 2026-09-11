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
package com.sn68.agent.dataagent.notification.controller;

import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.dto.NotificationConnectorCodeRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationConnectorDTO;
import com.sn68.agent.dataagent.notification.service.NotificationConnectorService;
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
 * 维护通知连接器配置和测试接口。
 */
@RestController
@RequestMapping("/notification-connectors")
@RequiredArgsConstructor
@Tag(name = "通知连接器", description = "维护通知连接器配置和测试接口")
public class NotificationConnectorController {

	private final NotificationConnectorService connectorService;

	@Operation(summary = "查询通知连接器清单", description = "查询通知连接器清单，用于通知连接器相关管理和运行场景。")
	@GetMapping
	public List<NotificationConnectorDTO> list() {
		return connectorService.list();
	}

	@Operation(summary = "查询通知连接器详情", description = "查询通知连接器详情，用于通知连接器相关管理和运行场景。")
	@PostMapping("/detail/query")
	public NotificationConnectorDTO get(@RequestBody NotificationConnectorCodeRequest request) {
		return connectorService.get(request == null ? null : request.connectorCode());
	}

	@Operation(summary = "创建通知连接器", description = "创建通知连接器，用于通知连接器相关管理和运行场景。")
	@AccessLog(module = "通知连接器", description = "创建通知连接器")
	@PostMapping("/create")
	public NotificationConnectorDTO create(@RequestBody NotificationConnectorDTO request) {
		return connectorService.create(request);
	}

	@Operation(summary = "修改通知连接器", description = "修改通知连接器，用于通知连接器相关管理和运行场景。")
	@AccessLog(module = "通知连接器", description = "修改通知连接器")
	@PutMapping("/{id}/modify")
	public NotificationConnectorDTO update(@PathVariable Long id, @RequestBody NotificationConnectorDTO request) {
		return connectorService.update(id, request);
	}

	@Operation(summary = "删除通知连接器", description = "删除通知连接器，用于通知连接器相关管理和运行场景。")
	@AccessLog(module = "通知连接器", description = "删除通知连接器")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		connectorService.delete(id);
	}

	@Operation(summary = "测试通知连接器", description = "测试通知连接器，用于通知连接器相关管理和运行场景。")
	@PostMapping("/test")
	public NotificationAdapterResult test(@RequestBody NotificationConnectorCodeRequest request) {
		return connectorService.test(request == null ? null : request.connectorCode());
	}

}
