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

import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.dto.agent.PresetQuestionDeleteReq;
import com.sn68.agent.dataagent.dto.agent.PresetQuestionsSaveReq;
import com.sn68.agent.dataagent.dto.agent.SavePresetQuestionReq;
import com.sn68.agent.dataagent.entity.AgentPresetQuestion;
import com.sn68.agent.dataagent.service.agent.AgentPresetQuestionService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护 Agent 运行页预设问题。
 */
@RestController
@RequestMapping("/data-agent/preset-questions")
@AllArgsConstructor
@Tag(name = "Agent 预设问题", description = "维护 Agent 运行页预设问题")
public class AgentPresetQuestionController {

	private final AgentPresetQuestionService presetQuestionService;

	@Operation(summary = "查询Agent 预设问题清单", description = "查询Agent 预设问题清单，用于Agent 预设问题相关管理和运行场景。")
	@PostMapping("/query")
	public List<AgentPresetQuestion> getPresetQuestions(@Valid @RequestBody AgentIdReq request) {
		return presetQuestionService.findAllByAgentId(request.agentId());
	}

	@Operation(summary = "修改Agent 预设问题", description = "修改Agent 预设问题，用于Agent 预设问题相关管理和运行场景。")
	@AccessLog(module = "Agent 预设问题", description = "保存Agent 预设问题")
	@PutMapping
	public void savePresetQuestions(@Valid @RequestBody PresetQuestionsSaveReq request) {
		presetQuestionService.batchSaveRequests(request.agentId(), request.questions());
	}

	@Operation(summary = "删除Agent 预设问题", description = "删除Agent 预设问题，用于Agent 预设问题相关管理和运行场景。")
	@AccessLog(module = "Agent 预设问题", description = "删除Agent 预设问题")
	@DeleteMapping
	public void deletePresetQuestion(@Valid @RequestBody PresetQuestionDeleteReq request) {
		presetQuestionService.deleteById(request.questionId());
	}

}
