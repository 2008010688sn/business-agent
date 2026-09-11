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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.controller.DataAgentController;
import com.sn68.agent.dataagent.im.dto.ImMessageDTO;
import com.sn68.agent.dataagent.im.dto.ImMessagePageQuery;
import com.sn68.agent.dataagent.im.service.ImMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 消息日志接口。
 */
@RestController
@RequestMapping("/im-messages")
@RequiredArgsConstructor
@Tag(name = "IM 消息", description = "分页查询 IM 消息流水")
public class ImMessageController {

	private final ImMessageService messageService;

	@Operation(summary = "分页查询IM 消息", description = "分页查询IM 消息，用于IM 消息相关管理和运行场景。")
	@PostMapping("/page")
	public IPage<ImMessageDTO> page(@RequestBody(required = false) ImMessagePageQuery request) {
		return messageService.page(request);
	}

}
