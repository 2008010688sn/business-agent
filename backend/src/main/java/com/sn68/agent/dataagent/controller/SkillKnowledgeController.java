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
import com.sn68.agent.dataagent.dto.knowledge.KnowledgeRecallStatusReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeCreateReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeUpdateReq;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeService;
import com.sn68.agent.dataagent.vo.SkillKnowledgeVO;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 维护 Skill 绑定的问答知识库资源。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/skills/{skillId}/resources/knowledge-base")
@Tag(name = "Skill 知识库资源", description = "维护 Skill 绑定的问答知识库条目、召回状态与向量化重试")
public class SkillKnowledgeController {

	private final SkillKnowledgeService service;

	@Operation(summary = "查询 Skill 知识库条目详情", description = "按 Skill 和条目 ID 查询问答知识库条目详情。")
	@GetMapping("/{id}/detail")
	public SkillKnowledgeVO get(@PathVariable Long skillId, @PathVariable Long id) {
		return service.get(skillId, id);
	}

	@Operation(summary = "创建 Skill 知识库条目", description = "在指定 Skill 下新增问答知识库条目并触发向量化。")
	@AccessLog(module = "Skill 知识库资源", description = "创建 Skill 知识库条目")
	@PostMapping
	public SkillKnowledgeVO create(@PathVariable Long skillId,
			@Valid @RequestBody SkillKnowledgeCreateReq request) {
		return service.create(skillId, request);
	}

	@Operation(summary = "修改 Skill 知识库条目", description = "修改指定 Skill 下的问答知识库条目内容。")
	@AccessLog(module = "Skill 知识库资源", description = "修改 Skill 知识库条目")
	@PutMapping("/{id}")
	public SkillKnowledgeVO update(@PathVariable Long skillId, @PathVariable Long id,
			@RequestBody SkillKnowledgeUpdateReq request) {
		return service.update(skillId, id, request);
	}

	@Operation(summary = "修改 Skill 知识库条目召回状态", description = "开启或关闭指定问答知识库条目参与召回。")
	@AccessLog(module = "Skill 知识库资源", description = "修改 Skill 知识库条目召回状态")
	@PutMapping("/{id}/status")
	public SkillKnowledgeVO updateStatus(@PathVariable Long skillId, @PathVariable Long id,
			@RequestBody KnowledgeRecallStatusReq request) {
		return service.updateRecallStatus(skillId, id, request == null ? null : request.isRecall());
	}

	@Operation(summary = "删除 Skill 知识库条目", description = "删除指定 Skill 下的问答知识库条目及其向量数据。")
	@AccessLog(module = "Skill 知识库资源", description = "删除 Skill 知识库条目")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long skillId, @PathVariable Long id) {
		service.delete(skillId, id);
	}

	@Operation(summary = "分页查询 Skill 知识库条目", description = "分页查询指定 Skill 下的问答知识库条目。")
	@PostMapping("/query/page")
	public IPage<SkillKnowledgeVO> page(@PathVariable Long skillId,
			@Valid @RequestBody SkillKnowledgeQueryReq request) {
		return service.page(skillId, request);
	}

	@Operation(summary = "重试 Skill 知识库条目向量化", description = "对向量化失败的问答知识库条目重新发起 embedding。")
	@AccessLog(module = "Skill 知识库资源", description = "重试 Skill 知识库条目向量化")
	@PostMapping("/{id}/embedding/retry")
	public void retry(@PathVariable Long skillId, @PathVariable Long id) {
		service.retryEmbedding(skillId, id);
	}

}
