/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.CreateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.UpdateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.dto.skill.SkillBusinessKnowledgeCreateReq;
import com.sn68.agent.dataagent.dto.skill.SkillBusinessKnowledgeUpdateReq;
import com.sn68.agent.dataagent.dto.skill.SkillRecallReq;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.service.business.BusinessKnowledgeService;
import com.sn68.agent.dataagent.service.skill.SkillResourceAccessService;
import com.sn68.agent.dataagent.vo.BusinessKnowledgeVO;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Skill 业务知识（业务术语）管理接口：维护业务知识、召回状态与向量同步。
 */
@RestController
@RequestMapping("/skills/{skillId}/resources/business-knowledge")
@Tag(name = "Skill业务知识", description = "维护Skill业务知识、召回状态和向量同步")
@RequiredArgsConstructor
public class SkillBusinessKnowledgeController {

	private final BusinessKnowledgeService businessKnowledgeService;

	private final SkillResourceAccessService skillResourceAccessService;

	@Operation(summary = "查询Skill业务知识")
	@GetMapping
	public List<BusinessKnowledgeVO> list(@PathVariable Long skillId,
			@RequestParam(required = false) String keyword) {
		skillResourceAccessService.requireBusinessKnowledgeResourceSkill(skillId);
		return businessKnowledgeService.listKnowledge(skillId, keyword);
	}

	@Operation(summary = "查询Skill业务知识详情")
	@GetMapping("/{id}")
	public BusinessKnowledgeVO get(@PathVariable Long skillId, @PathVariable Long id) {
		return requireSkillKnowledge(skillId, id);
	}

	@Operation(summary = "创建Skill业务知识")
	@AccessLog(module = "Skill 业务知识", description = "创建Skill业务知识")
	@PostMapping
	public BusinessKnowledgeVO create(@PathVariable Long skillId,
			@RequestBody @Valid SkillBusinessKnowledgeCreateReq request) {
		skillResourceAccessService.requireBusinessKnowledgeResourceSkill(skillId);
		return businessKnowledgeService.addKnowledge(CreateBusinessKnowledgeDTO.builder()
			.skillId(skillId)
			.businessTerm(request.businessTerm())
			.description(request.description())
			.synonyms(request.synonyms())
			.isRecall(request.isRecall() == null || request.isRecall())
			.build());
	}

	@Operation(summary = "修改Skill业务知识")
	@AccessLog(module = "Skill 业务知识", description = "修改Skill业务知识")
	@PutMapping("/{id}")
	public BusinessKnowledgeVO update(@PathVariable Long skillId, @PathVariable Long id,
			@RequestBody @Valid SkillBusinessKnowledgeUpdateReq request) {
		requireSkillKnowledge(skillId, id);
		return businessKnowledgeService.updateKnowledge(id, UpdateBusinessKnowledgeDTO.builder()
			.skillId(skillId)
			.businessTerm(request.businessTerm())
			.description(request.description())
			.synonyms(request.synonyms())
			.build());
	}

	@Operation(summary = "删除Skill业务知识")
	@AccessLog(module = "Skill 业务知识", description = "删除Skill业务知识")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long skillId, @PathVariable Long id) {
		requireSkillKnowledge(skillId, id);
		businessKnowledgeService.deleteKnowledge(id);
	}

	@Operation(summary = "修改Skill业务知识召回状态")
	@AccessLog(module = "Skill 业务知识", description = "修改Skill业务知识召回状态")
	@PutMapping("/{id}/recall")
	public void updateRecall(@PathVariable Long skillId, @PathVariable Long id,
			@RequestBody @Valid SkillRecallReq request) {
		requireSkillKnowledge(skillId, id);
		businessKnowledgeService.recallKnowledge(id, request.isRecall());
	}

	@Operation(summary = "重试Skill业务知识向量化")
	@PostMapping("/{id}/embedding/retry")
	public void retryEmbedding(@PathVariable Long skillId, @PathVariable Long id) {
		requireSkillKnowledge(skillId, id);
		businessKnowledgeService.retryEmbedding(id);
	}

	@Operation(summary = "刷新Skill业务知识向量")
	@PostMapping("/embedding/refresh")
	public void refreshEmbedding(@PathVariable Long skillId) {
		skillResourceAccessService.requireBusinessKnowledgeResourceSkill(skillId);
		businessKnowledgeService.refreshAllKnowledgeToVectorStore(skillId);
	}

	private BusinessKnowledgeVO requireSkillKnowledge(Long skillId, Long id) {
		skillResourceAccessService.requireBusinessKnowledgeResourceSkill(skillId);
		BusinessKnowledge knowledge = businessKnowledgeService.requireBySkillId(skillId, id);
		return businessKnowledgeService.requireKnowledgeById(knowledge.getId());
	}

}
