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
import com.sn68.agent.dataagent.im.dto.ImProviderConfigDTO;
import com.sn68.agent.dataagent.im.dto.ImProviderRequest;
import com.sn68.agent.dataagent.im.service.ImProviderConfigService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 平台配置管理接口。
 */
@RestController
@RequestMapping("/im-provider-configs")
@RequiredArgsConstructor
@Tag(name = "IM 平台配置", description = "维护 IM 平台级配置")
public class ImProviderConfigController {

	private final ImProviderConfigService providerConfigService;

	@Operation(summary = "查询IM 平台配置清单", description = "查询IM 平台配置清单，用于IM 平台配置相关管理和运行场景。")
	@GetMapping
	public List<ImProviderConfigDTO> list() {
		return providerConfigService.list();
	}

	@Operation(summary = "查询IM 平台配置详情", description = "查询IM 平台配置详情，用于IM 平台配置相关管理和运行场景。")
	@PostMapping("/detail/query")
	public ImProviderConfigDTO get(@RequestBody ImProviderRequest request) {
		return providerConfigService.get(request == null ? null : request.provider());
	}

	@Operation(summary = "修改IM 平台配置", description = "修改IM 平台配置，用于IM 平台配置相关管理和运行场景。")
	@AccessLog(module = "IM 平台配置", description = "修改IM 平台配置")
	@PutMapping("/modify")
	public ImProviderConfigDTO save(@RequestBody ImProviderConfigDTO request) {
		return providerConfigService.save(request == null ? null : request.provider(), request);
	}

}
