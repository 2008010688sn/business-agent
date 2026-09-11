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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.vo.ThinkingPermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询 DataAgent 当前用户功能权限。
 */
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
@Tag(name = "DataAgent 权限", description = "查询 DataAgent 当前用户功能权限")
public class DataAgentPermissionController {

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	@Operation(summary = "查询DataAgent 权限", description = "查询DataAgent 权限，用于DataAgent 权限相关管理和运行场景。")
	@GetMapping("/thinking")
	public ThinkingPermissionVO thinking() {
		return ThinkingPermissionVO.builder()
			.canViewThinking(thinkingPermissionService.canViewThinking())
			.canViewAnswerSource(thinkingPermissionService.canViewAnswerSource())
			.canViewCallChain(thinkingPermissionService.canViewCallChain())
			.canViewAnswerExplain(thinkingPermissionService.canViewAnswerExplain())
			.canViewDiagnostics(thinkingPermissionService.canViewAnyDiagnostics())
			.build();
	}

}
