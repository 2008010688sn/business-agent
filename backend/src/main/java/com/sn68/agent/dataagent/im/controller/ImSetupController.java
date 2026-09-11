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
import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateRequest;
import com.sn68.agent.dataagent.im.dto.DingTalkCredentialValidateResponse;
import com.sn68.agent.dataagent.im.dto.ImConnectorDTO;
import com.sn68.agent.dataagent.im.dto.ImSetupCompleteRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitRequest;
import com.sn68.agent.dataagent.im.dto.ImSetupInitResponse;
import com.sn68.agent.dataagent.im.dto.ImSetupStatusDTO;
import com.sn68.agent.dataagent.im.dto.ImSetupStatusRequest;
import com.sn68.agent.dataagent.im.service.ImSetupService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 接入向导接口。
 */
@RestController
@RequestMapping("/im-setup")
@RequiredArgsConstructor
@Tag(name = "IM 接入初始化", description = "提供钉钉接入初始化、校验和完成配置接口")
public class ImSetupController {

	private final ImSetupService setupService;

	@Operation(summary = "创建IM 接入初始化钉钉接入", description = "创建IM 接入初始化钉钉接入，用于IM 接入初始化相关管理和运行场景。")
	@AccessLog(module = "IM 接入初始化", description = "创建钉钉接入配置会话")
	@PostMapping("/dingtalk/init")
	public ImSetupInitResponse initDingTalk(@RequestBody(required = false) ImSetupInitRequest request) {
		return setupService.initDingTalk(request);
	}

	@Operation(summary = "查询IM 接入初始化状态", description = "查询IM 接入初始化状态，用于IM 接入初始化相关管理和运行场景。")
	@PostMapping("/status/query")
	public ImSetupStatusDTO status(@RequestBody ImSetupStatusRequest request) {
		return setupService.status(request == null ? null : request.setupId());
	}

	@Operation(summary = "测试IM 接入初始化钉钉接入", description = "测试IM 接入初始化钉钉接入，用于IM 接入初始化相关管理和运行场景。")
	@PostMapping("/dingtalk/validate")
	public DingTalkCredentialValidateResponse validateDingTalk(@RequestBody DingTalkCredentialValidateRequest request) {
		return setupService.validateDingTalk(request);
	}

	@Operation(summary = "创建IM 接入初始化", description = "创建IM 接入初始化，用于IM 接入初始化相关管理和运行场景。")
	// 请求体携带 clientSecret，关闭出入参记录，只留操作痕迹
	@AccessLog(module = "IM 接入初始化", description = "完成IM 接入配置", request = false, response = false)
	@PostMapping("/complete")
	public ImConnectorDTO complete(@RequestBody ImSetupCompleteRequest request) {
		return setupService.complete(request == null ? null : request.setupId(), request);
	}

}
