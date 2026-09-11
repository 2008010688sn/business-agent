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
import com.sn68.agent.dataagent.notification.dto.NotificationDeliveryDTO;
import com.sn68.agent.dataagent.notification.dto.NotificationDeliveryQueryRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationSendResponse;
import com.sn68.agent.dataagent.notification.service.NotificationDeliveryService;
import com.sn68.agent.dataagent.notification.service.NotificationFacadeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供通知预览、发送和投递记录查询。
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "通知运行", description = "提供通知预览、发送和投递记录查询")
public class NotificationRuntimeController {

	private final NotificationFacadeService facadeService;

	private final NotificationDeliveryService deliveryService;

	@Operation(summary = "预览通知运行", description = "预览通知运行，用于通知运行相关管理和运行场景。")
	@PostMapping("/preview")
	public NotificationSendResponse preview(@RequestBody NotificationSendRequest request) {
		return facadeService.preview(request);
	}

	@Operation(summary = "发送通知运行", description = "发送通知运行，用于通知运行相关管理和运行场景。")
	@PostMapping("/send")
	public NotificationSendResponse send(@RequestBody NotificationSendRequest request) {
		return facadeService.send(request);
	}

	@Operation(summary = "查询通知运行清单", description = "查询通知运行清单，用于通知运行相关管理和运行场景。")
	@PostMapping("/deliveries/query")
	public List<NotificationDeliveryDTO> deliveries(@RequestBody(required = false) NotificationDeliveryQueryRequest request) {
		return deliveryService.list(request == null ? null : request.agentId(),
				request == null ? null : request.skillCode(), request == null ? null : request.skillVersionId(),
				request == null ? null : request.resourceKey(),
				request == null ? null : request.targetAlias(), request == null ? null : request.templateCode(),
				request == null ? null : request.deliveryId());
	}

}
