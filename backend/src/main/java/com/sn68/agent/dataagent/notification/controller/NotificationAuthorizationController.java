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
import com.sn68.agent.dataagent.notification.dto.NotificationAuthorizationDTO;
import com.sn68.agent.dataagent.notification.service.NotificationAuthorizationService;
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
 * 维护通知连接器授权配置。
 */
@RestController
@RequestMapping("/notification-authorizations")
@RequiredArgsConstructor
@Tag(name = "通知授权", description = "维护通知连接器授权配置")
public class NotificationAuthorizationController {

	private final NotificationAuthorizationService authorizationService;

	@Operation(summary = "查询通知授权清单", description = "查询通知授权清单，用于通知授权相关管理和运行场景。")
	@GetMapping
	public List<NotificationAuthorizationDTO> list() {
		return authorizationService.list();
	}

	@Operation(summary = "创建通知授权", description = "创建通知授权，用于通知授权相关管理和运行场景。")
	@AccessLog(module = "通知授权", description = "创建通知授权")
	@PostMapping("/create")
	public NotificationAuthorizationDTO create(@RequestBody NotificationAuthorizationDTO request) {
		return authorizationService.create(request);
	}

	@Operation(summary = "修改通知授权", description = "修改通知授权，用于通知授权相关管理和运行场景。")
	@AccessLog(module = "通知授权", description = "修改通知授权")
	@PutMapping("/{id}/modify")
	public NotificationAuthorizationDTO update(@PathVariable Long id,
			@RequestBody NotificationAuthorizationDTO request) {
		return authorizationService.update(id, request);
	}

	@Operation(summary = "删除通知授权", description = "删除通知授权，用于通知授权相关管理和运行场景。")
	@AccessLog(module = "通知授权", description = "删除通知授权")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		authorizationService.delete(id);
	}

}
