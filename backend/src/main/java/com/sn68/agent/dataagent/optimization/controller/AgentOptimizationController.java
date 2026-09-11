/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateCompareDTO;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateEvalRequest;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateGenerateRequest;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateRequest;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentDiagnoseRequest;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentDetailDTO;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentQueryRequest;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentRequest;
import com.sn68.agent.dataagent.optimization.dto.OptLoopStatusDTO;
import com.sn68.agent.dataagent.optimization.dto.OptOfflineEvalRequest;
import com.sn68.agent.dataagent.optimization.dto.OptReleaseApprovalRequest;
import com.sn68.agent.dataagent.optimization.dto.OptReleasePromoteRequest;
import com.sn68.agent.dataagent.optimization.dto.OptRollbackRequest;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidate;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptCandidateEval;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptExperiment;
import com.sn68.agent.dataagent.optimization.entity.DataAgentOptRelease;
import com.sn68.agent.dataagent.optimization.service.AgentOptimizationPermissionService;
import com.sn68.agent.dataagent.optimization.service.AgentOptimizationService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 自优化接口。
 */
@RestController
@RequestMapping("/data-agent/optimizations")
@RequiredArgsConstructor
@Tag(name = "Agent 优化", description = "维护 Agent 优化实验、候选方案、评估、发布和回滚记录")
public class AgentOptimizationController {

	private final AgentOptimizationService optimizationService;

	private final AgentOptimizationPermissionService permissionService;

	@Operation(summary = "创建Agent 优化实验", description = "创建Agent 优化实验，用于Agent 优化相关管理和运行场景。")
	@AccessLog(module = "Agent 优化", description = "创建优化实验")
	@PostMapping("/experiments/create")
	public DataAgentOptExperiment createExperiment(@RequestBody OptExperimentRequest request) {
		permissionService.requireManage();
		return optimizationService.createExperiment(request);
	}

	@Operation(summary = "分页查询Agent 优化实验", description = "分页查询Agent 优化实验，用于Agent 优化相关管理和运行场景。")
	@PostMapping("/experiments/page")
	public IPage<DataAgentOptExperiment> queryExperimentsPage(
			@RequestBody(required = false) OptExperimentQueryRequest request) {
		permissionService.requireView();
		return optimizationService.queryExperimentsPage(request);
	}

	@Operation(summary = "查询Agent 优化实验", description = "查询Agent 优化实验，用于Agent 优化相关管理和运行场景。")
	@GetMapping("/experiments/{id}/detail")
	public OptExperimentDetailDTO getExperimentDetail(@PathVariable Long id) {
		permissionService.requireView();
		return optimizationService.getExperimentDetail(id);
	}

	@Operation(summary = "诊断Agent 优化实验", description = "诊断Agent 优化实验，用于Agent 优化相关管理和运行场景。")
	@PostMapping("/experiments/diagnoses/create")
	public DataAgentOptExperiment diagnoseExperiment(@RequestBody OptExperimentDiagnoseRequest request) {
		permissionService.requireManage();
		return optimizationService.diagnoseExperiment(request == null ? null : request.experimentId());
	}

	@Operation(summary = "创建Agent 优化候选方案", description = "创建Agent 优化候选方案，用于Agent 优化相关管理和运行场景。")
	@AccessLog(module = "Agent 优化", description = "创建优化候选方案")
	@PostMapping("/candidates/create")
	public DataAgentOptCandidate createCandidate(@RequestBody OptCandidateRequest request) {
		permissionService.requireManage();
		return optimizationService.createCandidate(request == null ? null : request.getExperimentId(), request);
	}

	@Operation(summary = "LLM 生成优化候选", description = "默认关闭；基线无失败则拒绝；生成失败不写入候选。")
	@AccessLog(module = "Agent 优化", description = "LLM 生成优化候选")
	@PostMapping("/candidates/generate")
	public DataAgentOptCandidate generateCandidate(@RequestBody OptCandidateGenerateRequest request) {
		permissionService.requireManage();
		return optimizationService.generateCandidate(request == null ? null : request.getExperimentId(), request);
	}

	@Operation(summary = "评估Agent 优化候选评估", description = "评估Agent 优化候选评估，用于Agent 优化相关管理和运行场景。")
	@PostMapping("/candidate-evaluations/create")
	public DataAgentOptCandidateEval evaluateCandidate(@RequestBody OptCandidateEvalRequest request) {
		permissionService.requireManage();
		return optimizationService.evaluateCandidate(request == null ? null : request.getCandidateId(), request);
	}

	@Operation(summary = "查询Agent 优化候选方案", description = "查询Agent 优化候选方案，用于Agent 优化相关管理和运行场景。")
	@GetMapping("/candidates/{id}/compare")
	public OptCandidateCompareDTO getCandidateCompare(@PathVariable Long id) {
		permissionService.requireView();
		return optimizationService.getCandidateCompare(id);
	}

	@Operation(summary = "创建Agent 优化发布审批", description = "创建Agent 优化发布审批，用于Agent 优化相关管理和运行场景。")
	@AccessLog(module = "Agent 优化", description = "创建发布审批")
	@PostMapping("/release-approvals/create")
	public DataAgentOptRelease createReleaseApproval(@RequestBody OptReleaseApprovalRequest request) {
		permissionService.requireRelease();
		return optimizationService.createReleaseApproval(request == null ? null : request.getCandidateId(), request);
	}

	@Operation(summary = "数字员工进化提升生产", description = "SANDBOX 激活后二次确认，才 CAS 激活 PRODUCTION。")
	@AccessLog(module = "Agent 优化", description = "数字员工进化提升生产")
	@PostMapping("/release-promotions/create")
	public DataAgentOptRelease promoteEmployeeRelease(@RequestBody OptReleasePromoteRequest request) {
		permissionService.requireRelease();
		return optimizationService.promoteEmployeeRelease(request == null ? null : request.getReleaseId(), request);
	}

	@Operation(summary = "记录Agent 优化回滚记录", description = "记录Agent 优化回滚记录，用于Agent 优化相关管理和运行场景。")
	@AccessLog(module = "Agent 优化", description = "记录发布回滚")
	@PostMapping("/rollback-records/create")
	public DataAgentOptRelease recordRollback(@RequestBody(required = false) OptRollbackRequest request) {
		permissionService.requireRollback();
		return optimizationService.recordRollback(request == null ? null : request.getReleaseId(), request);
	}

	@Operation(summary = "发起候选离线评估（强制 DRY_RUN）",
			description = "自进化 LOOP 的 OfflineEval 阶段：为候选发起离线沙箱评估运行，执行意图由服务端强制为 DRY_RUN（禁写、禁外部副作用，违规记入发布门禁）。")
	@AccessLog(module = "Agent 优化", description = "发起候选离线评估")
	@PostMapping("/loop/offline-evaluations/create")
	public DataAgentEvalRun startCandidateOfflineEvaluation(@RequestBody OptOfflineEvalRequest request) {
		return optimizationService.startCandidateOfflineEvaluation(request);
	}

	@Operation(summary = "查询自进化 LOOP 编排状态",
			description = "按实验查询自进化 LOOP 各阶段进度（Observe→Diagnose→Generate→OfflineEval→HumanApprove→ReleaseDraft→Monitor/Rollback；Shadow/Canary 为扩展点）。")
	@GetMapping("/loop/{experimentId}/status")
	public OptLoopStatusDTO getLoopStatus(@PathVariable Long experimentId) {
		permissionService.requireView();
		return optimizationService.getLoopStatus(experimentId);
	}

}
