/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.dto.skill.AgentSkillToolRefDTO;
import com.sn68.agent.dataagent.dto.skill.SkillDetailResp;
import com.sn68.agent.dataagent.dto.skill.SkillCloneReq;
import com.sn68.agent.dataagent.dto.skill.SkillFlowTestResult;
import com.sn68.agent.dataagent.dto.skill.SkillImportReq;
import com.sn68.agent.dataagent.dto.skill.SkillPageQueryReq;
import com.sn68.agent.dataagent.dto.skill.SkillPreviewResp;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestReq;
import com.sn68.agent.dataagent.dto.skill.SkillRouteTestResult;
import com.sn68.agent.dataagent.dto.skill.SkillSaveReq;
import com.sn68.agent.dataagent.dto.skill.SkillToolEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.SkillValidationResult;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.service.skill.DataAgentSkillToolRefService;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.dataagent.service.skill.SkillImportExportService;
import com.sn68.agent.dataagent.service.skill.SkillPublishService;
import com.sn68.agent.dataagent.service.skill.SkillTestService;
import com.sn68.agent.dataagent.service.skill.SkillValidationService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
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
 * 基于数据库的 Skill 目录及不可变版本管理。
 */
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
@Tag(name = "Skill 中心", description = "管理 Skill 草稿、版本、校验和发布")
public class SkillController {

	private final SkillCatalogService skillCatalogService;

	private final SkillPublishService skillPublishService;

	private final SkillValidationService skillValidationService;

	private final SkillImportExportService skillImportExportService;

	private final DataAgentSkillToolRefService skillToolRefService;

	private final SkillTestService skillTestService;

	@PostMapping("/page")
	@Operation(summary = "分页查询 Skill")
	public IPage<DataAgentSkill> page(@RequestBody SkillPageQueryReq request) {
		return skillCatalogService.page(request);
	}

	@GetMapping("/{skillCode}/detail")
	@Operation(summary = "查询 Skill 详情")
	public SkillDetailResp detail(@PathVariable String skillCode) {
		return skillCatalogService.detail(skillCode);
	}

	@GetMapping("/{skillCode}/preview")
	@Operation(summary = "预览 Skill 最终内容和运行时有效配置")
	public SkillPreviewResp preview(@PathVariable String skillCode,
			@RequestParam(defaultValue = "draft") String version) {
		return skillCatalogService.preview(skillCode, version);
	}

	@GetMapping("/tool-editor-context")
	@Operation(summary = "查询 Skill 工具编辑上下文")
	public SkillToolEditorContextResp toolEditorContext(
			@RequestParam(required = false) String skillCode, @RequestParam(required = false) String scope,
			@RequestParam String executionMode) {
		return skillCatalogService.toolEditorContext(skillCode, scope, executionMode);
	}

	@PostMapping("/create")
	@AccessLog(module = "Skill 中心", description = "创建 Skill 草稿")
	@Operation(summary = "创建 Skill 草稿")
	public SkillDetailResp create(@RequestBody SkillSaveReq request) {
		return skillCatalogService.create(request);
	}

	@PutMapping("/{skillCode}/modify")
	@AccessLog(module = "Skill 中心", description = "修改 Skill 草稿")
	@Operation(summary = "修改 Skill 草稿")
	public SkillDetailResp modify(@PathVariable String skillCode, @RequestBody SkillSaveReq request) {
		return skillCatalogService.modify(skillCode, request);
	}

	@PostMapping("/{skillCode}/clone")
	@AccessLog(module = "Skill 中心", description = "克隆已发布 Skill 为新草稿")
	@Operation(summary = "克隆已发布 Skill 为新草稿")
	public SkillDetailResp clonePublished(@PathVariable String skillCode, @RequestBody SkillCloneReq request) {
		return skillCatalogService.clonePublished(skillCode, request);
	}

	@PutMapping("/{skillCode}/publish")
	@AccessLog(module = "Skill 中心", description = "发布 Skill 版本")
	@Operation(summary = "发布不可变 Skill 版本")
	public SkillDetailResp publish(@PathVariable String skillCode) {
		return skillPublishService.publish(skillCode);
	}

	@PostMapping("/{skillCode}/validate")
	@Operation(summary = "校验 Skill 草稿")
	public SkillValidationResult validate(@PathVariable String skillCode) {
		return skillValidationService.validate(skillCode);
	}

	@PostMapping("/{skillCode}/route-test")
	@Operation(summary = "测试 Skill 草稿路由规则")
	public SkillRouteTestResult testRoute(@PathVariable String skillCode,
			@RequestBody SkillRouteTestReq request) {
		return skillTestService.testRoute(skillCode, request);
	}

	@PostMapping("/{skillCode}/flow-test")
	@Operation(summary = "试运行 Skill 草稿流程（不执行工具）")
	public SkillFlowTestResult testFlow(@PathVariable String skillCode) {
		return skillTestService.testFlow(skillCode);
	}

	@GetMapping("/{skillCode}/tool-refs")
	@Operation(summary = "查询 Skill 工具版本引用")
	public List<AgentSkillToolRefDTO> listToolRefs(@PathVariable String skillCode) {
		return skillToolRefService.listRefs(skillCode);
	}

	@PutMapping("/{skillCode}/tool-refs")
	@AccessLog(module = "Skill 中心", description = "替换 Skill 草稿工具版本引用")
	@Operation(summary = "替换 Skill 草稿工具版本引用")
	public List<AgentSkillToolRefDTO> replaceToolRefs(@PathVariable String skillCode,
			@RequestBody List<AgentSkillToolRefDTO> refs) {
		return skillToolRefService.saveRefs(skillCode, refs);
	}

	@PostMapping("/import")
	@AccessLog(module = "Skill 中心", description = "导入 Skill 包")
	@Operation(summary = "导入 Skill 包")
	public SkillDetailResp importSkill(@RequestBody SkillImportReq request) {
		return skillImportExportService.importBundle(request);
	}

	@GetMapping("/{skillCode}/export")
	@Operation(summary = "导出 Skill 包")
	public Map<String, Object> exportSkill(@PathVariable String skillCode) {
		return skillImportExportService.exportBundle(skillCode);
	}

	@DeleteMapping("/{skillCode}")
	@AccessLog(module = "Skill 中心", description = "删除未绑定的 Skill")
	@Operation(summary = "删除未绑定的 Skill")
	public void delete(@PathVariable String skillCode) {
		skillCatalogService.delete(skillCode);
	}

}
