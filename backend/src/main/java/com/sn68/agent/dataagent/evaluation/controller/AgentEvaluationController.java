/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseImportRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapResult;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalPolicyQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalPolicyRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultDetailDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultTraceDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalFailureClusterDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunEfficiencySummaryDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunFailureSummaryDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunResultQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunStatusModifyRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSubjectQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSubjectRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSuiteQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSuiteRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalPolicy;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
import com.sn68.agent.dataagent.evaluation.enums.EvalStatusDict;
import com.sn68.agent.dataagent.evaluation.service.AgentEvaluationPermissionService;
import com.sn68.agent.dataagent.evaluation.service.AgentEvaluationService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 评估中心接口。
 */
@RestController
@RequestMapping("/data-agent/evaluations")
@RequiredArgsConstructor
@Tag(name = "Agent 评测", description = "维护评测策略、对象、套件、用例、运行和结果")
public class AgentEvaluationController {

	private final AgentEvaluationService evaluationService;

	private final AgentEvaluationPermissionService permissionService;

	@Operation(summary = "分页查询Agent 评测策略", description = "分页查询Agent 评测策略，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/policies/page")
	public IPage<DataAgentEvalPolicy> queryPoliciesPage(@RequestBody(required = false) EvalPolicyQueryRequest request) {
		permissionService.requireView();
		return evaluationService.queryPoliciesPage(request);
	}

	@Operation(summary = "创建Agent 评测策略", description = "创建Agent 评测策略，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "创建评测策略")
	@PostMapping("/policies/create")
	public DataAgentEvalPolicy createPolicy(@RequestBody EvalPolicyRequest request) {
		permissionService.requireManage();
		return evaluationService.createPolicy(request);
	}

	@Operation(summary = "修改Agent 评测策略", description = "修改Agent 评测策略，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "修改评测策略")
	@PutMapping("/policies/{id}/modify")
	public DataAgentEvalPolicy updatePolicy(@PathVariable Long id, @RequestBody EvalPolicyRequest request) {
		permissionService.requireManage();
		return evaluationService.updatePolicy(id, request);
	}

	@Operation(summary = "分页查询Agent 评测评测对象", description = "分页查询Agent 评测评测对象，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/subjects/page")
	public IPage<DataAgentEvalSubject> querySubjectsPage(@RequestBody(required = false) EvalSubjectQueryRequest request) {
		permissionService.requireView();
		return evaluationService.querySubjectsPage(request);
	}

	@Operation(summary = "创建Agent 评测评测对象", description = "创建Agent 评测评测对象，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "创建评测对象")
	@PostMapping("/subjects/create")
	public DataAgentEvalSubject createSubject(@RequestBody EvalSubjectRequest request) {
		permissionService.requireManage();
		return evaluationService.createSubject(request);
	}

	@Operation(summary = "修改Agent 评测评测对象", description = "修改Agent 评测评测对象，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "修改评测对象")
	@PutMapping("/subjects/{id}/modify")
	public DataAgentEvalSubject updateSubject(@PathVariable Long id, @RequestBody EvalSubjectRequest request) {
		permissionService.requireManage();
		return evaluationService.updateSubject(id, request);
	}

	@Operation(summary = "分页查询Agent 评测评测套件", description = "分页查询Agent 评测评测套件，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/suites/page")
	public IPage<DataAgentEvalSuite> querySuitesPage(@RequestBody(required = false) EvalSuiteQueryRequest request) {
		permissionService.requireView();
		return evaluationService.querySuitesPage(request);
	}

	@Operation(summary = "创建Agent 评测评测套件", description = "创建Agent 评测评测套件，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "创建评测套件")
	@PostMapping("/suites/create")
	public DataAgentEvalSuite createSuite(@RequestBody EvalSuiteRequest request) {
		permissionService.requireManage();
		return evaluationService.createSuite(request);
	}

	@Operation(summary = "修改Agent 评测评测套件", description = "修改Agent 评测评测套件，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "修改评测套件")
	@PutMapping("/suites/{id}/modify")
	public DataAgentEvalSuite updateSuite(@PathVariable Long id, @RequestBody EvalSuiteRequest request) {
		permissionService.requireManage();
		return evaluationService.updateSuite(id, request);
	}

	@Operation(summary = "分页查询Agent 评测评测用例", description = "分页查询Agent 评测评测用例，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/cases/page")
	public IPage<DataAgentEvalCase> queryCasesPage(@RequestBody(required = false) EvalCaseQueryRequest request) {
		permissionService.requireView();
		return evaluationService.queryCasesPage(request);
	}

	@Operation(summary = "创建Agent 评测评测用例", description = "创建Agent 评测评测用例，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "创建评测用例")
	@PostMapping("/cases/create")
	public DataAgentEvalCase createCase(@RequestBody EvalCaseRequest request) {
		permissionService.requireManage();
		return evaluationService.createCase(request);
	}

	@Operation(summary = "修改Agent 评测评测用例", description = "修改Agent 评测评测用例，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "修改评测用例")
	@PutMapping("/cases/{id}/modify")
	public DataAgentEvalCase updateCase(@PathVariable Long id, @RequestBody EvalCaseRequest request) {
		permissionService.requireManage();
		return evaluationService.updateCase(id, request);
	}

	@Operation(summary = "导入Agent 评测评测用例", description = "导入Agent 评测评测用例，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "导入评测用例", request = false)
	@PostMapping("/cases/import")
	public DataAgentEvalCase importCase(@RequestBody EvalCaseImportRequest request) {
		permissionService.requireManage();
		return evaluationService.importCase(request);
	}

	@Operation(summary = "创建Agent 评测运行", description = "创建Agent 评测运行，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "创建评测运行")
	@PostMapping("/runs/create")
	public DataAgentEvalRun createRun(@RequestBody EvalRunCreateRequest request) {
		permissionService.requireRun();
		return evaluationService.createRun(request);
	}

	@Operation(summary = "初始化评估默认配置",
			description = "agentId 与 employeeId 二选一。DataAgent 沿用原默认策略；数字员工写入 DIGITAL_EMPLOYEE_CANDIDATE 对象并在 SANDBOX 无指针时用生产 Release 补齐。")
	@PostMapping("/bootstrap/defaults")
	public EvalBootstrapResult bootstrapDefaults(@RequestBody EvalBootstrapRequest request) {
		permissionService.requireManage();
		if (request != null && Boolean.TRUE.equals(request.getRunNow())) {
			permissionService.requireRun();
		}
		return evaluationService.bootstrapDefaults(request);
	}

	@Operation(summary = "修改Agent 评测运行", description = "修改Agent 评测运行，用于Agent 评测相关管理和运行场景。")
	@AccessLog(module = "Agent 评测", description = "修改评测运行状态")
	@PutMapping("/runs/{id}/status")
	public void updateRunStatus(@PathVariable Long id, @RequestBody EvalRunStatusModifyRequest request) {
		permissionService.requireRun();
		if (request == null || !EvalStatusDict.CANCELLED.getValue().equals(request.status())) {
			throw CheckedException.badRequest("status只支持cancelled");
		}
		evaluationService.cancelRun(id);
	}

	@Operation(summary = "分页查询Agent 评测运行", description = "分页查询Agent 评测运行，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/runs/page")
	public IPage<DataAgentEvalRun> queryRunsPage(@RequestBody(required = false) EvalRunQueryRequest request) {
		permissionService.requireView();
		return evaluationService.queryRunsPage(request);
	}

	@Operation(summary = "查询Agent 评测运行", description = "查询Agent 评测运行，用于Agent 评测相关管理和运行场景。")
	@GetMapping("/runs/{id}/detail")
	public DataAgentEvalRun getRun(@PathVariable Long id) {
		permissionService.requireView();
		return evaluationService.getRun(id);
	}

	@Operation(summary = "查询Agent 评测运行结果", description = "查询Agent 评测运行结果，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/runs/results/query")
	public List<DataAgentEvalCaseResult> listRunResults(@RequestBody EvalRunResultQueryRequest request) {
		permissionService.requireView();
		return evaluationService.listRunResults(requireRunId(request));
	}

	@Operation(summary = "查询Agent 评测效率汇总", description = "查询Agent 评测效率汇总，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/runs/efficiency-summary/query")
	public EvalRunEfficiencySummaryDTO getRunEfficiencySummary(@RequestBody EvalRunResultQueryRequest request) {
		permissionService.requireTroubleshootingView();
		return evaluationService.getRunEfficiencySummary(requireRunId(request));
	}

	@Operation(summary = "查询Agent 评测失败汇总", description = "查询Agent 评测失败汇总，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/runs/failure-summary/query")
	public EvalRunFailureSummaryDTO getRunFailureSummary(@RequestBody EvalRunResultQueryRequest request) {
		permissionService.requireTroubleshootingView();
		return evaluationService.getRunFailureSummary(requireRunId(request));
	}

	@Operation(summary = "查询评估失败聚类", description = "评估排障与自进化 LOOP 诊断共用的失败原因/慢用例聚类。")
	@GetMapping("/runs/{id}/failure-cluster")
	public EvalFailureClusterDTO clusterFailures(@PathVariable Long id) {
		permissionService.requireTroubleshootingView();
		return evaluationService.clusterFailures(id);
	}

	@Operation(summary = "分页查询Agent 评测结果", description = "分页查询Agent 评测结果，用于Agent 评测相关管理和运行场景。")
	@PostMapping("/results/page")
	public IPage<DataAgentEvalCaseResult> queryResultsPage(@RequestBody(required = false) EvalResultQueryRequest request) {
		permissionService.requireView();
		return evaluationService.queryResultsPage(request);
	}

	@Operation(summary = "查询Agent 评测结果", description = "查询Agent 评测结果，用于Agent 评测相关管理和运行场景。")
	@GetMapping("/results/{id}/detail")
	public EvalResultDetailDTO getResultDetail(@PathVariable Long id) {
		permissionService.requireView();
		return evaluationService.getResultDetail(id);
	}

	@Operation(summary = "查询Agent 评测结果", description = "查询Agent 评测结果，用于Agent 评测相关管理和运行场景。")
	@GetMapping("/results/{id}/trace")
	public EvalResultTraceDTO getResultTrace(@PathVariable Long id) {
		permissionService.requireTroubleshootingView();
		return evaluationService.getResultTrace(id, permissionService.canViewSourceTrace());
	}

	private Long requireRunId(EvalRunResultQueryRequest request) {
		if (request == null || request.runId() == null) {
			throw CheckedException.badRequest("runId不能为空");
		}
		return request.runId();
	}

}
