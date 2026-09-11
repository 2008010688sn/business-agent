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
import com.sn68.agent.dataagent.notification.dto.NotificationConnectorCodeRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationTargetDTO;
import com.sn68.agent.dataagent.notification.service.NotificationTargetService;
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
 * 维护通知目标配置。
 */
@RestController
@RequestMapping("/notification-targets")
@RequiredArgsConstructor
@Tag(name = "通知目标", description = "维护通知目标配置")
public class NotificationTargetController {

	private final NotificationTargetService targetService;

	@Operation(summary = "查询通知目标清单", description = "查询通知目标清单，用于通知目标相关管理和运行场景。")
	@PostMapping("/query")
	public List<NotificationTargetDTO> list(@RequestBody(required = false) NotificationConnectorCodeRequest request) {
		return targetService.list(request == null ? null : request.connectorCode());
	}

	@Operation(summary = "创建通知目标", description = "创建通知目标，用于通知目标相关管理和运行场景。")
	@AccessLog(module = "通知目标", description = "创建通知目标")
	@PostMapping("/create")
	public NotificationTargetDTO create(@RequestBody NotificationTargetDTO request) {
		return targetService.create(request);
	}

	@Operation(summary = "修改通知目标", description = "修改通知目标，用于通知目标相关管理和运行场景。")
	@AccessLog(module = "通知目标", description = "修改通知目标")
	@PutMapping("/{id}/modify")
	public NotificationTargetDTO update(@PathVariable Long id, @RequestBody NotificationTargetDTO request) {
		return targetService.update(id, request);
	}

	@Operation(summary = "删除通知目标", description = "删除通知目标，用于通知目标相关管理和运行场景。")
	@AccessLog(module = "通知目标", description = "删除通知目标")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		targetService.delete(id);
	}

}
