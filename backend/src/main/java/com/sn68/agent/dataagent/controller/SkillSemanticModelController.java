/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.schema.SemanticModelAddDTO;
import com.sn68.agent.dataagent.dto.schema.SemanticModelBatchImportDTO;
import com.sn68.agent.dataagent.dto.schema.SemanticModelExcelImportReq;
import com.sn68.agent.dataagent.dto.schema.SemanticModelStatusModifyReq;
import com.sn68.agent.dataagent.dto.schema.SemanticModelUpdateReq;
import com.sn68.agent.dataagent.dto.skill.SkillSemanticModelBatchImportReq;
import com.sn68.agent.dataagent.dto.skill.SkillSemanticModelCreateReq;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.service.semantic.SemanticModelService;
import com.sn68.agent.dataagent.service.skill.SkillResourceAccessService;
import com.sn68.agent.dataagent.vo.BatchImportResult;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Skill 语义模型管理接口：维护 Skill 数据源字段的业务语义映射，支持单条维护与批量导入导出。
 */
@RestController
@RequestMapping("/skills/{skillId}/resources/semantic-models")
@Tag(name = "Skill语义模型", description = "维护Skill数据源语义模型")
@RequiredArgsConstructor
public class SkillSemanticModelController {

	private final SemanticModelService semanticModelService;

	private final SkillResourceAccessService skillResourceAccessService;

	@Operation(summary = "查询Skill语义模型")
	@GetMapping
	public List<SemanticModel> list(@PathVariable Long skillId,
			@RequestParam(required = false) String keyword) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return semanticModelService.listSemanticModels(keyword, skillId);
	}

	@Operation(summary = "查询Skill语义模型详情")
	@GetMapping("/{id}")
	public SemanticModel get(@PathVariable Long skillId, @PathVariable Long id) {
		return requireSkillSemanticModel(skillId, id);
	}

	@Operation(summary = "创建Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "创建Skill语义模型")
	@PostMapping
	public void create(@PathVariable Long skillId, @RequestBody @Valid SkillSemanticModelCreateReq request) {
		requireSkillDatasource(skillId, request.datasourceId());
		semanticModelService.createSemanticModel(SemanticModelAddDTO.builder()
			.skillId(skillId)
			.datasourceId(request.datasourceId())
			.tableName(request.tableName())
			.columnName(request.columnName())
			.businessName(request.businessName())
			.synonyms(request.synonyms())
			.businessDescription(request.businessDescription())
			.columnComment(request.columnComment())
			.dataType(request.dataType())
			.build());
	}

	@Operation(summary = "修改Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "修改Skill语义模型")
	@PutMapping("/{id}")
	public SemanticModel update(@PathVariable Long skillId, @PathVariable Long id,
			@RequestBody @Valid SemanticModelUpdateReq request) {
		requireSkillSemanticModel(skillId, id);
		return semanticModelService.updateSemanticModelAndReturn(id, request);
	}

	@Operation(summary = "删除Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "删除Skill语义模型")
	@DeleteMapping("/{id}")
	public void delete(@PathVariable Long skillId, @PathVariable Long id) {
		requireSkillSemanticModel(skillId, id);
		semanticModelService.deleteSemanticModel(id);
	}

	@Operation(summary = "批量删除Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "批量删除Skill语义模型")
	@DeleteMapping("/batch")
	public void batchDelete(@PathVariable Long skillId, @RequestBody @NotEmpty List<Long> ids) {
		for (Long id : ids) {
			requireSkillSemanticModel(skillId, id);
		}
		semanticModelService.deleteSemanticModels(ids);
	}

	@Operation(summary = "启停Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "启停Skill语义模型")
	@PutMapping("/status")
	public void updateStatus(@PathVariable Long skillId, @RequestBody SemanticModelStatusModifyReq request) {
		if (request == null || request.ids() == null || request.ids().isEmpty() || request.enabled() == null) {
			throw CheckedException.badRequest("ids和enabled不能为空");
		}
		for (Long id : request.ids()) {
			requireSkillSemanticModel(skillId, id);
		}
		if (request.enabled()) {
			semanticModelService.enableSemanticModels(request.ids());
			return;
		}
		semanticModelService.disableSemanticModels(request.ids());
	}

	@Operation(summary = "批量导入Skill语义模型")
	@AccessLog(module = "Skill 语义模型", description = "批量导入Skill语义模型", request = false)
	@PostMapping("/batch-import")
	public BatchImportResult batchImport(@PathVariable Long skillId,
			@RequestBody @Valid SkillSemanticModelBatchImportReq request) {
		requireSkillDatasource(skillId, request.datasourceId());
		return semanticModelService.batchImport(SemanticModelBatchImportDTO.builder()
			.skillId(skillId)
			.datasourceId(request.datasourceId())
			.items(request.items())
			.build());
	}

	@Operation(summary = "下载Skill语义模型导入模板")
	@IgnoreGlobalResponse(description = "download semantic model template")
	@GetMapping("/template/download")
	public byte[] downloadTemplate(@PathVariable Long skillId, HttpServletResponse response) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return semanticModelService.downloadTemplate(response);
	}

	@Operation(summary = "Excel导入Skill语义模型")
	@PostMapping(value = "/import/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Mono<BatchImportResult> importExcel(@PathVariable Long skillId,
			@ModelAttribute SemanticModelExcelImportReq request) {
		if (request == null || request.getDatasourceId() == null) {
			throw CheckedException.badRequest("datasourceId不能为空");
		}
		requireSkillDatasource(skillId, request.getDatasourceId());
		request.setSkillId(skillId);
		return semanticModelService.importFromExcel(request);
	}

	private SemanticModel requireSkillSemanticModel(Long skillId, Long id) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
		return semanticModelService.requireBySkillId(skillId, id);
	}

	private void requireSkillDatasource(Long skillId, Long datasourceId) {
		skillResourceAccessService.requireQueryResourceSkill(skillId);
	}

}
