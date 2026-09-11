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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailQueryReq;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailResp;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnPageQueryReq;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryResp;
import com.sn68.agent.dataagent.dto.chat.DataChatUserSummaryQueryReq;
import com.sn68.agent.dataagent.dto.chat.SessionTurnsQueryReq;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询 DataAgent 会话轮次、明细和用户摘要。
 */
@RestController
@RequestMapping("/data-agent/diagnostics")
@RequiredArgsConstructor
@Tag(name = "DataAgent 诊断", description = "查询 DataAgent 会话轮次、明细和用户摘要")
public class DataAgentDiagnosticsController {

	private final DataChatTurnService turnService;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	@Operation(summary = "分页查询DataAgent 诊断", description = "分页查询DataAgent 诊断，用于DataAgent 诊断相关管理和运行场景。")
	@PostMapping("/turns/page")
	public IPage<DataChatTurn> queryTurns(@RequestBody(required = false) DataChatTurnPageQueryReq request) {
		requireDiagnosticsPermission();
		return turnService.queryTurns(request);
	}

	@Operation(summary = "查询DataAgent 诊断详情", description = "查询DataAgent 诊断详情，用于DataAgent 诊断相关管理和运行场景。")
	@PostMapping("/turns/detail/query")
	public DataChatTurnDetailResp getTurnDetail(@RequestBody DataChatTurnDetailQueryReq request) {
		requireDiagnosticsPermission();
		return turnService.getTurnDetail(requireSessionId(request == null ? null : request.sessionId()),
				requireRuntimeRequestId(request == null ? null : request.runtimeRequestId()));
	}

	@Operation(summary = "查询DataAgent 诊断清单", description = "查询DataAgent 诊断清单，用于DataAgent 诊断相关管理和运行场景。")
	@PostMapping("/sessions/turns/query")
	public List<DataChatTurn> listSessionTurns(@RequestBody SessionTurnsQueryReq request) {
		requireDiagnosticsPermission();
		return turnService.listSessionTurns(requireSessionId(request == null ? null : request.sessionId()));
	}

	@Operation(summary = "查询DataAgent 诊断汇总", description = "查询DataAgent 诊断汇总，用于DataAgent 诊断相关管理和运行场景。")
	@PostMapping("/users/summary")
	public List<DataChatUserSummaryResp> summarizeUsers(
			@RequestBody(required = false) DataChatUserSummaryQueryReq request) {
		requireDiagnosticsPermission();
		return turnService.summarizeUsers(request);
	}

	private void requireDiagnosticsPermission() {
		thinkingPermissionService.requireCanViewAnyDiagnostics();
	}

	private Long requireSessionId(Long sessionId) {
		if (sessionId == null) {
			throw CheckedException.badRequest("sessionId不能为空");
		}
		return sessionId;
	}

	private String requireRuntimeRequestId(String runtimeRequestId) {
		if (runtimeRequestId == null || runtimeRequestId.isBlank()) {
			throw CheckedException.badRequest("runtimeRequestId不能为空");
		}
		return runtimeRequestId;
	}

}
