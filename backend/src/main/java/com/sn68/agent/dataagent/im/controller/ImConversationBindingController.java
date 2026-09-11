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
import com.sn68.agent.dataagent.im.dto.ImConversationBindingQueryRequest;
import com.sn68.agent.dataagent.im.dto.ImConversationBindingDTO;
import com.sn68.agent.dataagent.im.service.ImConversationBindingService;
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
 * IM 会话绑定管理接口。
 */
@RestController
@RequestMapping("/im-conversation-bindings")
@RequiredArgsConstructor
@Tag(name = "IM 会话绑定", description = "维护 IM 外部会话与 Agent 会话的绑定关系")
public class ImConversationBindingController {

	private final ImConversationBindingService bindingService;

	@Operation(summary = "查询IM 会话绑定清单", description = "查询IM 会话绑定清单，用于IM 会话绑定相关管理和运行场景。")
	@PostMapping("/query")
	public List<ImConversationBindingDTO> list(@RequestBody(required = false) ImConversationBindingQueryRequest request) {
		return bindingService.list(request == null ? null : request.provider(),
				request == null ? null : request.connectorCode());
	}

	@Operation(summary = "创建IM 会话绑定", description = "创建IM 会话绑定，用于IM 会话绑定相关管理和运行场景。")
	@AccessLog(module = "IM 会话绑定", description = "创建IM 会话绑定")
	@PostMapping("/create")
	public ImConversationBindingDTO create(@RequestBody ImConversationBindingDTO request) {
		return bindingService.create(request);
	}

	@Operation(summary = "修改IM 会话绑定", description = "修改IM 会话绑定，用于IM 会话绑定相关管理和运行场景。")
	@AccessLog(module = "IM 会话绑定", description = "修改IM 会话绑定")
	@PutMapping("/{id}/modify")
	public ImConversationBindingDTO update(@PathVariable Long id, @RequestBody ImConversationBindingDTO request) {
		return bindingService.update(id, request);
	}

	@Operation(summary = "删除IM 会话绑定", description = "删除IM 会话绑定，用于IM 会话绑定相关管理和运行场景。")
	@AccessLog(module = "IM 会话绑定", description = "删除IM 会话绑定")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long id) {
		bindingService.delete(id);
	}

}
