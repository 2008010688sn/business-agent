/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.evaluation.adapter.EvalRunHarness;
import com.sn68.agent.dataagent.evaluation.dto.EvalFailureClusterDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.evaluation.enums.ExecutionIntentDict;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseResultMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalRunMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSubjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.evaluation.service.AgentEvaluationService;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateCompareDTO;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateEvalRequest;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateGenerateRequest;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateRequest;
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
import com.sn68.agent.dataagent.optimization.enums.AgentOptimizationErrorDict;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptCandidateEvalMapper;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptCandidateMapper;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptExperimentMapper;
import com.sn68.agent.dataagent.optimization.repository.DataAgentOptReleaseMapper;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent 自优化服务：承载方案第十四章自进化 LOOP 的编排
 * （Observe → Diagnose → Generate → OfflineEval(DRY_RUN) → HumanApprove →
 * 写入活体并 {@code DataAgentService#publish} → Monitor → Rollback）。
 *
 * <p>安全约束（第一等公民，不为跑通流程而弱化）：
 * <ul>
 * <li>候选产物类型 guardrail：只允许方案许可的 7 类，命中禁止项即拒绝并记录；</li>
 * <li>离线评估强制 DRY_RUN：非 DRY_RUN 沙箱运行一律不过门禁；</li>
 * <li>硬门禁：权限/租户隔离违规数=0、写副作用数=0，另保留分数/通过率/p90/token 门槛；</li>
 * <li>分片评估：按租户/岗位/业务类型标签分片对比，维度缺失时如实降级并记录；</li>
 * <li>发布载体：审批通过时先把活体可进化字段写入 {@code beforeSnapshotJson}，再覆盖并
 * {@code DataAgentService#publish}；回滚必须按该快照恢复活体。{@code DataAgentOptRelease}
 * 仍只作实验内部审计（不再创建 data_agent_release）。</li>
 * <li>REPLAY 评估（数字员工 Release 回放）不得作为门禁沙箱。</li>
 * </ul>
 *
 * <p>Shadow：DataAgent 生产问答可按租户白名单异步 DRY_RUN 镜像候选 prompt，不切线上流量。
 * 数字员工写任务明确跳过。Canary 流量切分仍为 NOT_IMPLEMENTED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOptimizationService {

	/**
	 * 方案第十四章允许自动生成的候选产物类型：Prompt、路由示例、Skill 描述、澄清问题、
	 * 记忆召回策略、模型路由策略、Flow 抽取样例。
	 */
	private static final Set<String> ALLOWED_TARGET_TYPES = Set.of("PROMPT", "ROUTE_EXAMPLE", "SKILL_DESCRIPTION",
			"CLARIFY_QUESTION", "MEMORY_RECALL_POLICY", "MODEL_ROUTE", "FLOW_EXTRACTION_SAMPLE");

	/**
	 * 方案第十四章禁止自动修改的产物类型：权限、凭据、工具代码、Flow 写入逻辑、
	 * 财务/库存/资产规则、数据库结构、补偿逻辑、审批策略。命中即显式拒绝并记录。
	 */
	private static final Set<String> FORBIDDEN_TARGET_TYPES = Set.of("PERMISSION", "CREDENTIAL", "TOOL_CODE",
			"FLOW_WRITE_LOGIC", "FINANCE_RULE", "INVENTORY_RULE", "ASSET_RULE", "DATABASE_SCHEMA",
			"COMPENSATION_LOGIC", "APPROVAL_POLICY");

	/** 候选补丁 JSON 中禁止出现的键（小写比对）：命中即按“补丁内容触碰禁止项”拒绝。 */
	private static final Set<String> FORBIDDEN_PATCH_KEYS = Set.of("permission", "permissions", "permissionrefs",
			"permissioncode", "credential", "credentials", "credentialref", "apikey", "secret", "token",
			"toolcode", "flowwritelogic", "approvalpolicy", "compensationlogic", "databaseschema", "ddl");

	/** 候选补丁中快照覆盖节点的键名。 */
	private static final String PATCH_KEY_SNAPSHOT_OVERLAY = "snapshotOverlay";

	/** 各产物类型允许映射到活体的快照覆盖键；空集表示该类型不写活体字段。 */
	private static final Map<String, Set<String>> TARGET_TYPE_OVERLAY_KEYS = Map.of(
			"PROMPT", Set.of("promptSnapshot"),
			"ROUTE_EXAMPLE", Set.of("routingProfileSnapshot"),
			"CLARIFY_QUESTION", Set.of("promptSnapshot"),
			"MEMORY_RECALL_POLICY", Set.of("memoryPolicySnapshot"),
			"MODEL_ROUTE", Set.of("modelConfigSnapshot", "routingProfileSnapshot"),
			"SKILL_DESCRIPTION", Set.of(),
			"FLOW_EXTRACTION_SAMPLE", Set.of());

	/** 评估对象类型：只有 DATA_AGENT 才能把候选写入活体并发布。 */
	private static final String SUBJECT_TYPE_DATA_AGENT = "DATA_AGENT";

	/**
	 * 数字员工 Release 评估适配器当前只有 REPLAY。REPLAY 回放旧答案，不能当进化门禁沙箱。
	 */
	private static final String SUBJECT_TYPE_DIGITAL_EMPLOYEE_RELEASE = "DIGITAL_EMPLOYEE_RELEASE";

	private static final String SUBJECT_TYPE_DIGITAL_EMPLOYEE_CANDIDATE = "DIGITAL_EMPLOYEE_CANDIDATE";

	private static final String OWNER_TYPE_DATA_AGENT = "DATA_AGENT";

	private static final String OWNER_TYPE_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private static final String STATUS_SUCCESS = "success";

	private static final String STATUS_FAILED = "failed";

	private static final String STATUS_TIMEOUT = "timeout";

	private static final String EXPERIMENT_STATUS_DRAFT = "draft";

	private static final String EXPERIMENT_STATUS_DIAGNOSED = "diagnosed";

	private static final String EXPERIMENT_STATUS_CANDIDATE_CREATED = "candidate_created";

	private static final String EXPERIMENT_STATUS_EVALUATED = "evaluated";

	private static final String CANDIDATE_STATUS_DRAFT = "draft";

	private static final String CANDIDATE_STATUS_OFFLINE_EVALUATING = "offline_evaluating";

	private static final String CANDIDATE_STATUS_EVALUATED = "evaluated";

	private static final String CANDIDATE_STATUS_GATE_BLOCKED = "gate_blocked";

	private static final String CANDIDATE_STATUS_SANDBOX_ACTIVATED = "sandbox_activated";

	private static final String CANDIDATE_STATUS_RELEASE_APPROVED = "release_approved";

	private static final String EVAL_STATUS_PASSED = "passed";

	private static final String EVAL_STATUS_BLOCKED = "blocked";

	private static final String RELEASE_ACTION_RELEASE = "release";

	private static final String RELEASE_ACTION_ROLLBACK = "rollback";

	private static final String RELEASE_STATUS_RECORDED = "recorded";

	private static final String RELEASE_STATUS_SANDBOX_ACTIVATED = "sandbox_activated";

	private static final String RELEASE_STATUS_PRODUCTION_ACTIVATED = "production_activated";

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private static final BigDecimal MAX_RESOURCE_DELTA_PCT = BigDecimal.valueOf(20);

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentOptExperimentMapper experimentMapper;

	private final DataAgentOptCandidateMapper candidateMapper;

	private final DataAgentOptCandidateEvalMapper candidateEvalMapper;

	private final DataAgentOptReleaseMapper releaseMapper;

	private final DataAgentEvalRunMapper runMapper;

	private final DataAgentEvalCaseResultMapper resultMapper;

	private final DataAgentEvalCaseMapper caseMapper;

	private final DataAgentEvalSubjectMapper subjectMapper;

	private final AgentEvaluationService evaluationService;

	private final DataAgentService dataAgentService;

	private final EmployeeReleaseLifecycleService employeeReleaseLifecycleService;

	private final EmployeeEvolutionApplyAdapter employeeEvolutionApplyAdapter;

	private final OptCandidateGenerator optCandidateGenerator;

	private final DataAgentProperties dataAgentProperties;

	private final ObjectMapper objectMapper;

	private final AuthenticationContext authenticationContext;

	/**
	 * 查询Agent优化。
	 */
	public IPage<DataAgentOptExperiment> queryExperimentsPage(OptExperimentQueryRequest request) {
		OptExperimentQueryRequest query = request == null ? new OptExperimentQueryRequest() : request;
		return experimentMapper.selectExperimentPage(query.buildPage(), query, currentTenantId());
	}

	/**
	 * 创建Agent优化。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptExperiment createExperiment(OptExperimentRequest request) {
		if (request == null || !StringUtils.hasText(request.getExperimentName()) || request.getBaselineRunId() == null) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		DataAgentEvalRun baselineRun = requireTenantRun(request.getBaselineRunId(),
				AgentOptimizationErrorDict.BASELINE_RUN_NOT_FOUND);
		if (request.getSubjectId() != null && baselineRun.getSubjectId() != null
				&& !Objects.equals(request.getSubjectId(), baselineRun.getSubjectId())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		OwnerRef owner = resolveExperimentOwner(request, baselineRun);
		Instant now = Instant.now();
		DataAgentOptExperiment experiment = DataAgentOptExperiment.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentName(request.getExperimentName().trim())
			.subjectId(firstNonNull(request.getSubjectId(), baselineRun.getSubjectId()))
			.ownerType(owner.type())
			.ownerId(owner.id())
			.baselineRunId(baselineRun.getId())
			.status(EXPERIMENT_STATUS_DRAFT)
			.baselineMetricsJson(writeJson(collectRunMetrics(baselineRun).toMap()))
			.description(trim(request.getDescription()))
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		experimentMapper.insert(experiment);
		return experimentMapper.selectById(experiment.getId());
	}

	public OptExperimentDetailDTO getExperimentDetail(Long id) {
		DataAgentOptExperiment experiment = requireTenantExperiment(id);
		List<DataAgentOptCandidate> candidates = candidateMapper.findByExperimentId(experiment.getId())
			.stream()
			.filter(this::sameTenant)
			.toList();
		List<DataAgentOptCandidateEval> latestEvals = candidates.stream()
			.map(candidate -> candidateEvalMapper.findLatestByCandidateId(candidate.getId()))
			.filter(Objects::nonNull)
			.filter(this::sameTenant)
			.toList();
		return OptExperimentDetailDTO.builder()
			.experiment(experiment)
			.candidates(candidates)
			.latestCandidateEvaluations(latestEvals)
			.build();
	}

	/**
	 * 执行Agent优化。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptExperiment diagnoseExperiment(Long id) {
		DataAgentOptExperiment experiment = requireTenantExperiment(id);
		DataAgentEvalRun baselineRun = requireTenantRun(experiment.getBaselineRunId(),
				AgentOptimizationErrorDict.BASELINE_RUN_NOT_FOUND);
		EvalFailureClusterDTO cluster = evaluationService.clusterFailures(baselineRun.getId());
		Map<String, Object> diagnosis = new LinkedHashMap<>();
		diagnosis.put("baselineRunId", baselineRun.getId());
		diagnosis.put("metrics", collectRunMetrics(baselineRun).toMap());
		diagnosis.put("failureReasons", cluster.failureReasons());
		diagnosis.put("slowCases", cluster.slowCases());
		diagnosis.put("failedCount", cluster.failedCount());
		diagnosis.put("hardFailCount", cluster.hardFailCount());
		diagnosis.put("generatedAt", Instant.now().toString());
		experiment.setDiagnosisJson(writeJson(diagnosis));
		experiment.setStatus(EXPERIMENT_STATUS_DIAGNOSED);
		experiment.setLastModifyTime(Instant.now());
		experimentMapper.updateById(experiment);
		return experimentMapper.selectById(id);
	}

	/**
	 * 创建Agent优化。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptCandidate createCandidate(Long experimentId, OptCandidateRequest request) {
		DataAgentOptExperiment experiment = requireTenantExperiment(experimentId);
		if (request == null || !StringUtils.hasText(request.getCandidateName())
				|| !StringUtils.hasText(request.getTargetType()) || !StringUtils.hasText(request.getTargetId())
				|| !StringUtils.hasText(request.getBeforeHash()) || !StringUtils.hasText(request.getAfterHash())
				|| !StringUtils.hasText(request.getPatchJson())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		String targetType = normalizeTargetType(request.getTargetType());
		validatePatchContent(targetType, request.getPatchJson());
		Instant now = Instant.now();
		DataAgentOptCandidate candidate = DataAgentOptCandidate.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(experiment.getId())
			.candidateName(request.getCandidateName().trim())
			.targetType(targetType)
			.targetId(request.getTargetId().trim())
			.patchSchemaVersion(defaultText(request.getPatchSchemaVersion(), "v1"))
			.targetVersion(trim(request.getTargetVersion()))
			.beforeHash(request.getBeforeHash().trim())
			.afterHash(request.getAfterHash().trim())
			.changeReason(trim(request.getChangeReason()))
			.expectedImpact(trim(request.getExpectedImpact()))
			.patchJson(request.getPatchJson())
			.beforeSnapshotJson(request.getBeforeSnapshotJson())
			.rollbackPatchJson(request.getRollbackPatchJson())
			.riskLevel(defaultText(request.getRiskLevel(), "medium"))
			.generatedBy(defaultText(request.getGeneratedBy(), "manual"))
			.status(CANDIDATE_STATUS_DRAFT)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		candidateMapper.insert(candidate);
		updateExperimentStatus(experiment, EXPERIMENT_STATUS_CANDIDATE_CREATED);
		return candidateMapper.selectById(candidate.getId());
	}

	/**
	 * LLM 生成候选：默认关闭；基线已无失败则拒绝；生成失败不插候选。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptCandidate generateCandidate(Long experimentId, OptCandidateGenerateRequest request) {
		if (!optCandidateGenerator.autoGenerateEnabled()) {
			throw badRequest(AgentOptimizationErrorDict.AUTO_GENERATE_DISABLED);
		}
		DataAgentOptExperiment experiment = requireTenantExperiment(experimentId);
		DataAgentEvalRun baselineRun = requireTenantRun(experiment.getBaselineRunId(),
				AgentOptimizationErrorDict.BASELINE_RUN_NOT_FOUND);
		EvalFailureClusterDTO cluster = evaluationService.clusterFailures(baselineRun.getId());
		if (cluster == null || cluster.failedCount() == null || cluster.failedCount() <= 0) {
			throw badRequest(AgentOptimizationErrorDict.GENERATE_SATURATED);
		}
		String traces = buildGenerateTraces(baselineRun.getId(), cluster, optCandidateGenerator.maxTraceChars());
		String targetId = StringUtils.hasText(experiment.getOwnerId()) ? experiment.getOwnerId()
				: (experiment.getSubjectId() == null ? null : String.valueOf(experiment.getSubjectId()));
		OptCandidateRequest generated = optCandidateGenerator.generate(request == null ? null : request.getTargetType(),
				targetId, request == null ? null : request.getModelConfigId(), traces);
		generated.setExperimentId(experiment.getId());
		return createCandidate(experiment.getId(), generated);
	}

	/**
	 * 执行Agent优化。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptCandidateEval evaluateCandidate(Long candidateId, OptCandidateEvalRequest request) {
		DataAgentOptCandidate candidate = requireTenantCandidate(candidateId);
		if (request == null || request.getSandboxRunId() == null) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		DataAgentOptExperiment experiment = requireTenantExperiment(candidate.getExperimentId());
		DataAgentEvalRun sandboxRun = requireTenantRun(request.getSandboxRunId(),
				AgentOptimizationErrorDict.SANDBOX_RUN_NOT_FOUND);
		if (experiment.getSubjectId() != null && sandboxRun.getSubjectId() != null
				&& !Objects.equals(experiment.getSubjectId(), sandboxRun.getSubjectId())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		rejectReplaySandbox(sandboxRun, experiment.getSubjectId());
		// 未终态的运行违规计数不完整，据此判门禁会漏掉后续违规，失败关闭。
		requireFinishedRun(sandboxRun);
		OptCandidateCompareDTO compare = buildCompare(candidate, sandboxRun.getId());
		Instant now = Instant.now();
		DataAgentOptCandidateEval candidateEval = DataAgentOptCandidateEval.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(experiment.getId())
			.candidateId(candidate.getId())
			.baselineRunId(experiment.getBaselineRunId())
			.sandboxRunId(sandboxRun.getId())
			.scoreDelta(compare.scoreDelta())
			.passRateDelta(compare.passRateDelta())
			.durationDeltaPct(compare.durationDeltaPct())
			.tokenDeltaPct(compare.tokenDeltaPct())
			.writeViolationCount(compare.writeViolationCount())
			.isolationViolationCount(compare.isolationViolationCount())
			.shardResultJson(writeJson(compare.shardResults()))
			.passed(compare.passed())
			.compareJson(writeJson(compare))
			.status(Boolean.TRUE.equals(compare.passed()) ? EVAL_STATUS_PASSED : EVAL_STATUS_BLOCKED)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		candidateEvalMapper.insert(candidateEval);
		candidate.setSandboxRunId(sandboxRun.getId());
		candidate.setGateResultJson(writeJson(compare));
		candidate.setStatus(Boolean.TRUE.equals(compare.passed()) ? CANDIDATE_STATUS_EVALUATED
				: CANDIDATE_STATUS_GATE_BLOCKED);
		candidate.setLastModifyTime(now);
		candidateMapper.updateById(candidate);
		updateExperimentStatus(experiment, EXPERIMENT_STATUS_EVALUATED);
		return candidateEvalMapper.selectById(candidateEval.getId());
	}

	public OptCandidateCompareDTO getCandidateCompare(Long candidateId) {
		DataAgentOptCandidate candidate = requireTenantCandidate(candidateId);
		Long sandboxRunId = candidate.getSandboxRunId();
		if (sandboxRunId == null) {
			DataAgentOptCandidateEval latestEval = candidateEvalMapper.findLatestByCandidateId(candidate.getId());
			if (latestEval != null && sameTenant(latestEval)) {
				sandboxRunId = latestEval.getSandboxRunId();
			}
		}
		if (sandboxRunId == null) {
			throw badRequest(AgentOptimizationErrorDict.SANDBOX_RUN_NOT_FOUND);
		}
		return buildCompare(candidate, sandboxRunId);
	}

	/**
	 * 人工审批门：候选经 DRY_RUN 离线评估过门禁后，由审批人调用本方法。
	 * 通过后把允许的覆盖写入 DataAgent 活体并调用 {@code DataAgentService#publish}，
	 * {@code DataAgentOptRelease} 只作实验内部审计，不再指向已删除的 {@code data_agent_release}。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptRelease createReleaseApproval(Long candidateId, OptReleaseApprovalRequest request) {
		DataAgentOptCandidate candidate = requireTenantCandidate(candidateId);
		if (request == null || !StringUtils.hasText(request.getCurrentHash())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		if (!Objects.equals(trim(request.getCurrentHash()), trim(candidate.getBeforeHash()))) {
			throw badRequest(AgentOptimizationErrorDict.HASH_MISMATCH);
		}
		if (!gatePassed(candidate)) {
			throw badRequest(AgentOptimizationErrorDict.GATE_NOT_PASSED);
		}
		DataAgentOptExperiment experiment = requireTenantExperiment(candidate.getExperimentId());
		if (OWNER_TYPE_DIGITAL_EMPLOYEE.equalsIgnoreCase(experiment.getOwnerType())) {
			return applyEmployeeSandbox(candidate, experiment, request);
		}
		Long agentId = resolveDataAgentId(experiment);
		Map<String, String> snapshotOverlays = resolveSnapshotOverlays(candidate);
		DataAgent agent = dataAgentService.requireAgent(agentId);
		String liveSnapshot = writeJson(liveSnapshotNode(agent));
		if (!StringUtils.hasText(liveSnapshot) || "{}".equals(liveSnapshot.trim())) {
			throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
		}
		candidate.setBeforeSnapshotJson(liveSnapshot);
		applyOverlaysToLiveAgent(agent, snapshotOverlays);
		dataAgentService.update(agentId, agent);
		dataAgentService.publish(agentId);
		Instant now = Instant.now();
		DataAgentOptRelease release = DataAgentOptRelease.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(experiment.getId())
			.candidateId(candidate.getId())
			.productionReleaseId(null)
			.actionType(RELEASE_ACTION_RELEASE)
			.status(RELEASE_STATUS_RECORDED)
			.beforeHash(candidate.getBeforeHash())
			.afterHash(candidate.getAfterHash())
			.operatorId(currentUserId())
			.reason(trim(request.getReason()))
			.approvalJson(request.getApprovalJson())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		releaseMapper.insert(release);
		candidate.setStatus(CANDIDATE_STATUS_RELEASE_APPROVED);
		candidate.setLastModifyTime(now);
		candidateMapper.updateById(candidate);
		log.info("自进化候选审批通过, 已写入活体并发布. candidateId={}, agentId={}, optReleaseId={}",
				candidate.getId(), agentId, release.getId());
		return releaseMapper.selectById(release.getId());
	}

	/**
	 * 数字员工二次确认：SANDBOX 指针已指向新 Release 后，才允许激活 PRODUCTION。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptRelease promoteEmployeeRelease(Long optReleaseId, OptReleasePromoteRequest request) {
		DataAgentOptRelease source = requireTenantRelease(optReleaseId);
		if (!RELEASE_ACTION_RELEASE.equals(source.getActionType())
				|| !RELEASE_STATUS_SANDBOX_ACTIVATED.equals(source.getStatus())) {
			throw badRequest(AgentOptimizationErrorDict.EMPLOYEE_STAGING_REQUIRED);
		}
		DataAgentOptCandidate candidate = requireTenantCandidate(source.getCandidateId());
		DataAgentOptExperiment experiment = requireTenantExperiment(source.getExperimentId());
		if (!OWNER_TYPE_DIGITAL_EMPLOYEE.equalsIgnoreCase(experiment.getOwnerType())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		Long employeeId = parseEmployeeOwnerId(experiment);
		employeeEvolutionApplyAdapter.promoteToProduction(employeeId, source.getProductionReleaseId());
		Instant now = Instant.now();
		source.setStatus(RELEASE_STATUS_PRODUCTION_ACTIVATED);
		source.setLastModifyTime(now);
		if (request != null && StringUtils.hasText(request.getReason())) {
			source.setReason(trim(request.getReason()));
		}
		releaseMapper.updateById(source);
		candidate.setStatus(CANDIDATE_STATUS_RELEASE_APPROVED);
		candidate.setLastModifyTime(now);
		candidateMapper.updateById(candidate);
		log.info("数字员工进化已提升 PRODUCTION. candidateId={}, employeeId={}, employeeReleaseId={}",
				candidate.getId(), employeeId, source.getProductionReleaseId());
		return releaseMapper.selectById(source.getId());
	}

	private DataAgentOptRelease applyEmployeeSandbox(DataAgentOptCandidate candidate,
			DataAgentOptExperiment experiment, OptReleaseApprovalRequest request) {
		Long employeeId = parseEmployeeOwnerId(experiment);
		if (!StringUtils.hasText(candidate.getBeforeSnapshotJson())) {
			candidate.setBeforeSnapshotJson(employeeEvolutionApplyAdapter.captureBeforeSnapshot(employeeId));
		}
		if (!StringUtils.hasText(candidate.getBeforeSnapshotJson())) {
			throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
		}
		EmployeeEvolutionApplyAdapter.ApplyResult applied = employeeEvolutionApplyAdapter.applyToSandbox(employeeId,
				resolveSnapshotOverlays(candidate));
		Instant now = Instant.now();
		DataAgentOptRelease release = DataAgentOptRelease.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(experiment.getId())
			.candidateId(candidate.getId())
			.productionReleaseId(applied.employeeReleaseId())
			.actionType(RELEASE_ACTION_RELEASE)
			.status(RELEASE_STATUS_SANDBOX_ACTIVATED)
			.beforeHash(candidate.getBeforeHash())
			.afterHash(candidate.getAfterHash())
			.operatorId(currentUserId())
			.reason(trim(request.getReason()))
			.approvalJson(request.getApprovalJson())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		releaseMapper.insert(release);
		candidate.setStatus(CANDIDATE_STATUS_SANDBOX_ACTIVATED);
		candidate.setLastModifyTime(now);
		candidateMapper.updateById(candidate);
		log.info("数字员工进化已激活 SANDBOX. candidateId={}, employeeId={}, employeeReleaseId={}, productionActive={}",
				candidate.getId(), employeeId, applied.employeeReleaseId(), applied.productionActiveReleaseId());
		return releaseMapper.selectById(release.getId());
	}

	private DataAgentOptRelease rollbackEmployee(DataAgentOptRelease sourceRelease, DataAgentOptExperiment experiment,
			OptRollbackRequest request) {
		Long employeeId = parseEmployeeOwnerId(experiment);
		if (RELEASE_STATUS_PRODUCTION_ACTIVATED.equals(sourceRelease.getStatus())) {
			employeeEvolutionApplyAdapter.rollbackEnvironment(employeeId,
					DeploymentEnvironmentDict.PRODUCTION.getValue());
		}
		employeeEvolutionApplyAdapter.rollbackEnvironment(employeeId, DeploymentEnvironmentDict.SANDBOX.getValue());
		DataAgentOptCandidate candidate = requireTenantCandidate(sourceRelease.getCandidateId());
		if (StringUtils.hasText(candidate.getBeforeSnapshotJson())) {
			employeeEvolutionApplyAdapter.restoreDraft(employeeId, candidate.getBeforeSnapshotJson());
		}
		Instant now = Instant.now();
		DataAgentOptRelease rollback = DataAgentOptRelease.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(sourceRelease.getExperimentId())
			.candidateId(sourceRelease.getCandidateId())
			.sourceReleaseId(sourceRelease.getId())
			.productionReleaseId(sourceRelease.getProductionReleaseId())
			.actionType(RELEASE_ACTION_ROLLBACK)
			.status(RELEASE_STATUS_RECORDED)
			.beforeHash(sourceRelease.getAfterHash())
			.afterHash(sourceRelease.getBeforeHash())
			.operatorId(currentUserId())
			.reason(request == null ? null : trim(request.getReason()))
			.approvalJson(request == null ? null : request.getApprovalJson())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		releaseMapper.insert(rollback);
		return releaseMapper.selectById(rollback.getId());
	}

	private Long parseEmployeeOwnerId(DataAgentOptExperiment experiment) {
		try {
			return Long.valueOf(trim(experiment.getOwnerId()));
		}
		catch (RuntimeException ex) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
	}

	/**
	 * 把候选允许的覆盖写进活体：至少处理 promptSnapshot.prompt/description 与
	 * modelConfigSnapshot.chatModelConfigId；路由/记忆快照没有对应活体列，校验后跳过。
	 */
	private void applyOverlaysToLiveAgent(DataAgent agent, Map<String, String> overlays) {
		if (overlays == null || overlays.isEmpty()) {
			return;
		}
		try {
			String promptSnapshot = overlays.get("promptSnapshot");
			if (StringUtils.hasText(promptSnapshot)) {
				JsonNode node = objectMapper.readTree(promptSnapshot);
				if (node.hasNonNull("prompt")) {
					agent.setPrompt(node.get("prompt").asText());
				}
				if (node.hasNonNull("description")) {
					agent.setDescription(node.get("description").asText());
				}
			}
			String modelSnapshot = overlays.get("modelConfigSnapshot");
			if (StringUtils.hasText(modelSnapshot)) {
				JsonNode node = objectMapper.readTree(modelSnapshot);
				if (node.has("chatModelConfigId")) {
					JsonNode modelId = node.get("chatModelConfigId");
					agent.setChatModelConfigId(modelId == null || modelId.isNull() ? null : modelId.asLong());
				}
			}
		}
		catch (Exception ex) {
			throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
		}
	}

	/**
	 * Apply 前记录活体可进化字段，回滚只认这份快照，不信任调用方自带的 beforeSnapshotJson。
	 */
	private Map<String, Object> liveSnapshotNode(DataAgent agent) {
		Map<String, Object> promptSnapshot = new LinkedHashMap<>();
		promptSnapshot.put("prompt", agent.getPrompt());
		promptSnapshot.put("description", agent.getDescription());
		Map<String, Object> modelSnapshot = new LinkedHashMap<>();
		modelSnapshot.put("chatModelConfigId", agent.getChatModelConfigId());
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("promptSnapshot", promptSnapshot);
		snapshot.put("modelConfigSnapshot", modelSnapshot);
		return snapshot;
	}

	private Map<String, String> parseSnapshotAsOverlays(String snapshotJson) {
		try {
			JsonNode root = objectMapper.readTree(snapshotJson == null ? "{}" : snapshotJson);
			if (root == null || !root.isObject() || root.isEmpty()) {
				throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
			}
			Map<String, String> overlays = new LinkedHashMap<>();
			Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				overlays.put(field.getKey(), field.getValue().toString());
			}
			if (overlays.isEmpty()) {
				throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
			}
			return overlays;
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
		}
	}

	/**
	 * 数字员工 Release 的 REPLAY 回放不能当进化门禁；INVOKE 真跑（SANDBOX）才放行。
	 */
	private void rejectReplaySandbox(DataAgentEvalRun sandboxRun, Long fallbackSubjectId) {
		Long subjectId = firstNonNull(sandboxRun == null ? null : sandboxRun.getSubjectId(), fallbackSubjectId);
		if (subjectId == null) {
			return;
		}
		DataAgentEvalSubject subject = subjectMapper.findEnabledById(subjectId);
		if (subject == null || !sameTenant(subject.getTenantId())) {
			return;
		}
		if (SUBJECT_TYPE_DIGITAL_EMPLOYEE_RELEASE.equalsIgnoreCase(subject.getSubjectType())
				|| SUBJECT_TYPE_DIGITAL_EMPLOYEE_RELEASE.equalsIgnoreCase(subject.getAdapterCode())) {
			if (!EvalRunHarness.isInvoke(sandboxRun, objectMapper)) {
				throw badRequest(AgentOptimizationErrorDict.SANDBOX_RUN_REPLAY_NOT_ALLOWED);
			}
		}
	}

	private String extractOverlayJson(DataAgentOptCandidate candidate) {
		if (candidate == null || !StringUtils.hasText(candidate.getPatchJson())) {
			return null;
		}
		try {
			JsonNode patch = objectMapper.readTree(candidate.getPatchJson());
			JsonNode overlay = patch == null ? null : patch.get(PATCH_KEY_SNAPSHOT_OVERLAY);
			JsonNode promptSnapshot = overlay == null ? null : overlay.get("promptSnapshot");
			if (promptSnapshot == null || promptSnapshot.isNull()) {
				return null;
			}
			String instruction = null;
			if (promptSnapshot.hasNonNull("systemInstruction")) {
				instruction = promptSnapshot.get("systemInstruction").asText();
			}
			else if (promptSnapshot.hasNonNull("prompt")) {
				instruction = promptSnapshot.get("prompt").asText();
			}
			if (!StringUtils.hasText(instruction)) {
				return null;
			}
			return writeJson(Map.of("systemInstruction", instruction));
		}
		catch (Exception ex) {
			return null;
		}
	}

	private OwnerRef resolveExperimentOwner(OptExperimentRequest request, DataAgentEvalRun baselineRun) {
		OwnerRef requested = requestedOwner(request);
		DataAgentEvalSubject subject = loadExperimentSubject(
				firstNonNull(request.getSubjectId(), baselineRun == null ? null : baselineRun.getSubjectId()));
		OwnerRef derived = deriveOwnerFromSubject(subject);
		if (requested != null && derived != null && !requested.equals(derived)) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		OwnerRef owner = requested != null ? requested : derived;
		if (owner == null) {
			return OwnerRef.empty();
		}
		if (!OWNER_TYPE_DATA_AGENT.equals(owner.type()) && !OWNER_TYPE_DIGITAL_EMPLOYEE.equals(owner.type())) {
			throw badRequest(AgentOptimizationErrorDict.OWNER_TYPE_NOT_ALLOWED);
		}
		if (!StringUtils.hasText(owner.id())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		return owner;
	}

	private OwnerRef requestedOwner(OptExperimentRequest request) {
		String type = request == null ? null : trim(request.getOwnerType());
		String id = request == null ? null : trim(request.getOwnerId());
		if (!StringUtils.hasText(type) && !StringUtils.hasText(id)) {
			return null;
		}
		if (!StringUtils.hasText(type) || !StringUtils.hasText(id)) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		return new OwnerRef(type.toUpperCase(Locale.ROOT), id);
	}

	private DataAgentEvalSubject loadExperimentSubject(Long subjectId) {
		if (subjectId == null) {
			return null;
		}
		DataAgentEvalSubject subject = subjectMapper.findEnabledById(subjectId);
		if (subject == null || !sameTenant(subject.getTenantId())) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		return subject;
	}

	private OwnerRef deriveOwnerFromSubject(DataAgentEvalSubject subject) {
		if (subject == null || !StringUtils.hasText(subject.getSubjectType())) {
			return null;
		}
		String subjectType = subject.getSubjectType().trim();
		if (SUBJECT_TYPE_DATA_AGENT.equalsIgnoreCase(subjectType)) {
			return new OwnerRef(OWNER_TYPE_DATA_AGENT, trim(subject.getSubjectId()));
		}
		if (SUBJECT_TYPE_DIGITAL_EMPLOYEE_CANDIDATE.equalsIgnoreCase(subjectType)) {
			return new OwnerRef(OWNER_TYPE_DIGITAL_EMPLOYEE, trim(subject.getSubjectId()));
		}
		if (SUBJECT_TYPE_DIGITAL_EMPLOYEE_RELEASE.equalsIgnoreCase(subjectType)) {
			return new OwnerRef(OWNER_TYPE_DIGITAL_EMPLOYEE, resolveEmployeeIdFromReleaseSubject(subject.getSubjectId()));
		}
		return null;
	}

	private String resolveEmployeeIdFromReleaseSubject(String releaseIdText) {
		Long releaseId;
		try {
			releaseId = Long.valueOf(trim(releaseIdText));
		}
		catch (RuntimeException ex) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		DigitalEmployeeRelease release = employeeReleaseLifecycleService.getDetail(releaseId);
		if (release == null || release.getEmployeeId() == null) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		return String.valueOf(release.getEmployeeId());
	}

	/** 从实验的评估对象解析 DataAgent ID：只有 DATA_AGENT 类型对象才能写入活体并发布。 */
	private Long resolveDataAgentId(DataAgentOptExperiment experiment) {
		DataAgentEvalSubject subject = experiment.getSubjectId() == null ? null
				: subjectMapper.findEnabledById(experiment.getSubjectId());
		if (subject == null || !sameTenant(subject.getTenantId())
				|| !SUBJECT_TYPE_DATA_AGENT.equalsIgnoreCase(subject.getSubjectType())) {
			throw badRequest(AgentOptimizationErrorDict.SUBJECT_NOT_DATA_AGENT);
		}
		try {
			return Long.valueOf(subject.getSubjectId().trim());
		}
		catch (RuntimeException ex) {
			throw badRequest(AgentOptimizationErrorDict.SUBJECT_NOT_DATA_AGENT);
		}
	}

	/**
	 * 从候选补丁提取快照覆盖（{@code patchJson.snapshotOverlay}），并按产物类型允许的覆盖键
	 * 二次校验（创建候选时已校验过，发布前防御性重查，防止入库后被直改）。
	 */
	private Map<String, String> resolveSnapshotOverlays(DataAgentOptCandidate candidate) {
		JsonNode patch;
		try {
			patch = objectMapper.readTree(candidate.getPatchJson() == null ? "{}" : candidate.getPatchJson());
		}
		catch (Exception ex) {
			throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
		}
		JsonNode overlay = patch.get(PATCH_KEY_SNAPSHOT_OVERLAY);
		if (overlay == null || overlay.isNull()) {
			return Map.of();
		}
		if (!overlay.isObject()) {
			throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
		}
		Set<String> allowedOverlayKeys = TARGET_TYPE_OVERLAY_KEYS
			.getOrDefault(candidate.getTargetType() == null ? "" : candidate.getTargetType().trim().toUpperCase(),
					Set.of());
		Map<String, String> overlays = new LinkedHashMap<>();
		Iterator<Map.Entry<String, JsonNode>> fields = overlay.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			if (!allowedOverlayKeys.contains(field.getKey())) {
				log.warn("候选快照覆盖键越界, 发布审批拒绝. candidateId={}, overlayKey={}, allowed={}",
						candidate.getId(), field.getKey(), allowedOverlayKeys);
				throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
			}
			overlays.put(field.getKey(), field.getValue().toString());
		}
		return overlays;
	}

	/**
	 * 保存Agent优化。
	 */
	@Transactional(rollbackFor = Exception.class)
	public DataAgentOptRelease recordRollback(Long releaseId, OptRollbackRequest request) {
		DataAgentOptRelease sourceRelease = requireTenantRelease(releaseId);
		if (!RELEASE_ACTION_RELEASE.equals(sourceRelease.getActionType())) {
			throw badRequest(AgentOptimizationErrorDict.RELEASE_NOT_ROLLBACKABLE);
		}
		DataAgentOptCandidate candidate = requireTenantCandidate(sourceRelease.getCandidateId());
		if (!StringUtils.hasText(candidate.getBeforeSnapshotJson())) {
			throw badRequest(AgentOptimizationErrorDict.SNAPSHOT_MISSING);
		}
		DataAgentOptExperiment experiment = requireTenantExperiment(sourceRelease.getExperimentId());
		if (OWNER_TYPE_DIGITAL_EMPLOYEE.equalsIgnoreCase(experiment.getOwnerType())) {
			return rollbackEmployee(sourceRelease, experiment, request);
		}
		Long agentId = resolveDataAgentId(experiment);
		DataAgent agent = dataAgentService.requireAgent(agentId);
		applyOverlaysToLiveAgent(agent, parseSnapshotAsOverlays(candidate.getBeforeSnapshotJson()));
		dataAgentService.update(agentId, agent);
		dataAgentService.publish(agentId);
		Instant now = Instant.now();
		DataAgentOptRelease rollback = DataAgentOptRelease.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.experimentId(sourceRelease.getExperimentId())
			.candidateId(sourceRelease.getCandidateId())
			.sourceReleaseId(sourceRelease.getId())
			.actionType(RELEASE_ACTION_ROLLBACK)
			.status(RELEASE_STATUS_RECORDED)
			.beforeHash(sourceRelease.getAfterHash())
			.afterHash(sourceRelease.getBeforeHash())
			.operatorId(currentUserId())
			.reason(request == null ? null : trim(request.getReason()))
			.approvalJson(request == null ? null : request.getApprovalJson())
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		releaseMapper.insert(rollback);
		return releaseMapper.selectById(rollback.getId());
	}

	/**
	 * 自进化 LOOP OfflineEval 阶段：为候选发起离线沙箱评估运行。
	 * <b>执行意图由服务端强制为 DRY_RUN</b>（调用方不可指定），评估执行链路据此禁写、
	 * 禁外部副作用并记违规。运行异步执行，结束后调用既有候选评估接口做门禁判定。
	 *
	 * <p>本方法不加事务：评估运行创建后立即投递异步执行器（复用评估中心既有语义，
	 * 运行行必须先于异步线程可见）；候选绑定失败时运行独立存在，可重新绑定，不构成脏数据。
	 */
	public DataAgentEvalRun startCandidateOfflineEvaluation(OptOfflineEvalRequest request) {
		if (request == null || request.getCandidateId() == null || request.getSuiteId() == null) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		DataAgentOptCandidate candidate = requireTenantCandidate(request.getCandidateId());
		DataAgentOptExperiment experiment = requireTenantExperiment(candidate.getExperimentId());
		EvalRunCreateRequest runRequest = new EvalRunCreateRequest();
		runRequest.setSuiteId(request.getSuiteId());
		runRequest.setSubjectId(experiment.getSubjectId());
		runRequest.setPolicyId(request.getPolicyId());
		runRequest.setExecutionIntent(ExecutionIntentDict.DRY_RUN.getValue());
		runRequest.setEvalMode(EvalRunHarness.MODE_INVOKE);
		runRequest.setCandidateOverlayJson(extractOverlayJson(candidate));
		DataAgentEvalRun run = evaluationService.createRun(runRequest);
		candidate.setSandboxRunId(run.getId());
		candidate.setStatus(CANDIDATE_STATUS_OFFLINE_EVALUATING);
		candidate.setLastModifyTime(Instant.now());
		candidateMapper.updateById(candidate);
		log.info("自进化候选离线评估已发起（强制 DRY_RUN）. candidateId={}, sandboxRunId={}, suiteId={}",
				candidate.getId(), run.getId(), request.getSuiteId());
		return run;
	}

	/**
	 * 自进化 LOOP 编排状态：Observe → Diagnose → Generate → OfflineEval → HumanApprove →
	 * Apply → Shadow（DataAgent 只读镜像）→ Canary（切流仍未实现）→ Monitor/Rollback。
	 */
	public OptLoopStatusDTO getLoopStatus(Long experimentId) {
		DataAgentOptExperiment experiment = requireTenantExperiment(experimentId);
		List<DataAgentOptCandidate> candidates = candidateMapper.findByExperimentId(experiment.getId())
			.stream()
			.filter(this::sameTenant)
			.toList();
		List<DataAgentOptRelease> optReleases = releaseMapper.findByExperimentId(experiment.getId())
			.stream()
			.filter(this::sameTenant)
			.toList();
		List<DataAgentOptRelease> approvals = optReleases.stream()
			.filter(release -> RELEASE_ACTION_RELEASE.equals(release.getActionType()))
			.toList();
		List<DataAgentOptRelease> rollbacks = optReleases.stream()
			.filter(release -> RELEASE_ACTION_ROLLBACK.equals(release.getActionType()))
			.toList();
		boolean diagnosed = StringUtils.hasText(experiment.getDiagnosisJson());
		boolean gatePassed = candidates.stream()
			.anyMatch(candidate -> CANDIDATE_STATUS_EVALUATED.equals(candidate.getStatus())
					|| CANDIDATE_STATUS_SANDBOX_ACTIVATED.equals(candidate.getStatus())
					|| CANDIDATE_STATUS_RELEASE_APPROVED.equals(candidate.getStatus()));
		boolean evaluating = candidates.stream()
			.anyMatch(candidate -> candidate.getSandboxRunId() != null
					&& CANDIDATE_STATUS_OFFLINE_EVALUATING.equals(candidate.getStatus()));
		boolean published = !approvals.isEmpty();
		boolean employeeOwner = OWNER_TYPE_DIGITAL_EMPLOYEE.equalsIgnoreCase(experiment.getOwnerType());
		boolean stagingDone = optReleases.stream()
			.anyMatch(release -> RELEASE_ACTION_RELEASE.equals(release.getActionType())
					&& (RELEASE_STATUS_SANDBOX_ACTIVATED.equals(release.getStatus())
							|| RELEASE_STATUS_PRODUCTION_ACTIVATED.equals(release.getStatus())));
		boolean productionDone = optReleases.stream()
			.anyMatch(release -> RELEASE_ACTION_RELEASE.equals(release.getActionType())
					&& RELEASE_STATUS_PRODUCTION_ACTIVATED.equals(release.getStatus()));
		List<Map<String, Object>> stages = new ArrayList<>();
		stages.add(stage("OBSERVE", "DONE", Map.of("baselineRunId", experiment.getBaselineRunId())));
		stages.add(stage("DIAGNOSE", diagnosed ? "DONE" : "PENDING", Map.of()));
		stages.add(stage("GENERATE_CANDIDATE", candidates.isEmpty() ? "PENDING" : "DONE",
				Map.of("candidateCount", candidates.size())));
		stages.add(stage("OFFLINE_EVAL_DRY_RUN", gatePassed ? "DONE" : evaluating ? "IN_PROGRESS" : "PENDING",
				Map.of("gatePassed", gatePassed)));
		stages.add(stage("HUMAN_APPROVE", approvals.isEmpty() ? "PENDING" : "DONE",
				Map.of("approvalCount", approvals.size())));
		if (employeeOwner) {
			stages.add(stage("APPLY_STAGING", stagingDone ? "DONE" : "PENDING",
					Map.of("note", "Seal 新 Release 并激活 SANDBOX，PRODUCTION 指针保持不动")));
			stages.add(stage("APPLY_PROD", productionDone ? "DONE" : "PENDING",
					Map.of("note", "二次确认后 CAS 激活 PRODUCTION")));
		}
		else {
			stages.add(stage("APPLY_AND_PUBLISH", published ? "DONE" : "PENDING",
					Map.of("note", "Apply 适配器写入可进化字段快照并 publish；回滚必须恢复 beforeSnapshot")));
		}
		stages.add(shadowStage(experiment, employeeOwner));
		stages.add(stage("CANARY", "NOT_IMPLEMENTED", Map.of("note", "运行时流量切分扩展点，本期未实现")));
		stages.add(stage("MONITOR_ROLLBACK", rollbacks.isEmpty() ? "STANDBY" : "ROLLED_BACK",
				Map.of("rollbackCount", rollbacks.size())));
		return OptLoopStatusDTO.builder()
			.experimentId(experiment.getId())
			.ownerType(experiment.getOwnerType())
			.ownerId(experiment.getOwnerId())
			.experimentStatus(experiment.getStatus())
			.stages(stages)
			.nextAction(resolveNextAction(diagnosed, candidates, gatePassed, evaluating, approvals, employeeOwner,
					stagingDone, productionDone))
			.build();
	}

	private Map<String, Object> shadowStage(DataAgentOptExperiment experiment, boolean employeeOwner) {
		if (employeeOwner) {
			return stage("SHADOW", "SKIPPED", Map.of("note", "数字员工写任务不做 Shadow / Canary"));
		}
		DataAgentProperties.Optimization optimization = dataAgentProperties == null ? null
				: dataAgentProperties.getOptimization();
		boolean enabled = optimization != null && optimization.isShadowEnabled();
		List<String> allow = optimization == null ? List.of() : optimization.getShadowTenantIds();
		boolean tenantAllowed = enabled && allow != null && StringUtils.hasText(experiment.getTenantId())
				&& allow.stream().anyMatch(id -> experiment.getTenantId().equals(id == null ? null : id.trim()));
		if (tenantAllowed) {
			return stage("SHADOW", "ENABLED",
					Map.of("note", "只读异步 DRY_RUN 镜像候选 prompt，不是线上切流"));
		}
		return stage("SHADOW", "STANDBY",
				Map.of("note", "只读异步镜像默认关闭：需 shadow-enabled 且租户在白名单；Canary 切流仍未实现"));
	}

	private Map<String, Object> stage(String stageName, String status, Map<String, Object> detail) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("stage", stageName);
		item.put("status", status);
		item.put("detail", detail);
		return item;
	}

	private String resolveNextAction(boolean diagnosed, List<DataAgentOptCandidate> candidates, boolean gatePassed,
			boolean evaluating, List<DataAgentOptRelease> approvals, boolean employeeOwner, boolean stagingDone,
			boolean productionDone) {
		if (!diagnosed) {
			return "执行实验诊断（POST /data-agent/optimizations/experiments/diagnoses/create）";
		}
		if (candidates.isEmpty()) {
			return "生成优化候选（POST /data-agent/optimizations/candidates/create，仅允许方案许可的产物类型）";
		}
		if (evaluating) {
			return "等待离线 DRY_RUN 评估运行结束后执行门禁判定（POST /data-agent/optimizations/candidate-evaluations/create）";
		}
		if (!gatePassed) {
			return "发起候选离线评估（POST /data-agent/optimizations/loop/offline-evaluations/create，服务端强制 DRY_RUN）";
		}
		if (employeeOwner && !stagingDone) {
			return "人工审批激活 SANDBOX（POST /data-agent/optimizations/release-approvals/create，生产指针不动）";
		}
		if (employeeOwner && !productionDone) {
			return "二次确认提升 PRODUCTION（POST /data-agent/optimizations/release-promotions/create）";
		}
		if (approvals.isEmpty()) {
			return "人工审批发布（POST /data-agent/optimizations/release-approvals/create，Apply 适配器落快照后 publish）";
		}
		return "关注运行指标，必要时回滚（POST /data-agent/optimizations/rollback-records/create，将恢复 beforeSnapshot）";
	}

	private OptCandidateCompareDTO buildCompare(DataAgentOptCandidate candidate, Long sandboxRunId) {
		DataAgentOptExperiment experiment = requireTenantExperiment(candidate.getExperimentId());
		DataAgentEvalRun baselineRun = requireTenantRun(experiment.getBaselineRunId(),
				AgentOptimizationErrorDict.BASELINE_RUN_NOT_FOUND);
		DataAgentEvalRun sandboxRun = requireTenantRun(sandboxRunId, AgentOptimizationErrorDict.SANDBOX_RUN_NOT_FOUND);
		RunMetrics baseline = collectRunMetrics(baselineRun);
		RunMetrics sandbox = collectRunMetrics(sandboxRun);
		BigDecimal scoreDelta = sandbox.averageScore().subtract(baseline.averageScore()).setScale(2, RoundingMode.HALF_UP);
		BigDecimal passRateDelta = sandbox.passRate().subtract(baseline.passRate()).setScale(2, RoundingMode.HALF_UP);
		BigDecimal durationDeltaPct = percentChange(baseline.p90DurationMs(), sandbox.p90DurationMs());
		BigDecimal tokenDeltaPct = percentChange(baseline.totalTokens(), sandbox.totalTokens());
		List<String> gateReasons = buildGateReasons(baseline, sandbox, scoreDelta, passRateDelta, durationDeltaPct,
				tokenDeltaPct);
		appendHardGateReasons(sandboxRun, sandbox, gateReasons);
		List<Map<String, Object>> shardResults = buildShardResults(baselineRun, sandboxRun, gateReasons);
		return OptCandidateCompareDTO.builder()
			.candidateId(candidate.getId())
			.baselineRunId(baselineRun.getId())
			.sandboxRunId(sandboxRun.getId())
			.baselineScore(baseline.averageScore())
			.candidateScore(sandbox.averageScore())
			.scoreDelta(scoreDelta)
			.baselinePassRate(baseline.passRate())
			.candidatePassRate(sandbox.passRate())
			.passRateDelta(passRateDelta)
			.durationDeltaPct(durationDeltaPct)
			.tokenDeltaPct(tokenDeltaPct)
			.sandboxExecutionIntent(sandboxRun.getExecutionIntent())
			.writeViolationCount(sandbox.writeViolationCount())
			.isolationViolationCount(sandbox.isolationViolationCount())
			.shardResults(shardResults)
			.passed(gateReasons.isEmpty())
			.gateReasons(gateReasons)
			.build();
	}

	/** 既有软门槛：分数/通过率/硬失败/p90 耗时/token 涨幅（保留，不弱化）。 */
	private List<String> buildGateReasons(RunMetrics baseline, RunMetrics sandbox, BigDecimal scoreDelta,
			BigDecimal passRateDelta, BigDecimal durationDeltaPct, BigDecimal tokenDeltaPct) {
		List<String> reasons = new ArrayList<>();
		if (sandbox.hardFailCount() > 0) {
			reasons.add("候选评估存在硬失败用例");
		}
		if (sandbox.hardFailCount() > baseline.hardFailCount()) {
			reasons.add("候选硬失败数量高于基线");
		}
		if (scoreDelta.compareTo(BigDecimal.ZERO) <= 0 && passRateDelta.compareTo(BigDecimal.ZERO) <= 0) {
			reasons.add("候选平均分和通过率均未提升");
		}
		if (durationDeltaPct.compareTo(MAX_RESOURCE_DELTA_PCT) > 0) {
			reasons.add("候选 p90 耗时涨幅超过 20%");
		}
		if (tokenDeltaPct.compareTo(MAX_RESOURCE_DELTA_PCT) > 0) {
			reasons.add("候选 Token 用量涨幅超过 20%");
		}
		return reasons;
	}

	/**
	 * 方案第十四章硬门禁：候选评估必须 DRY_RUN 执行、权限或租户隔离违规为 0、写副作用为 0。
	 * 任一命中即不过门禁，禁止进入发布审批。
	 */
	private void appendHardGateReasons(DataAgentEvalRun sandboxRun, RunMetrics sandbox, List<String> gateReasons) {
		if (!ExecutionIntentDict.DRY_RUN.getValue().equals(sandboxRun.getExecutionIntent())) {
			gateReasons.add("候选评估运行未按 DRY_RUN 执行（executionIntent="
					+ (StringUtils.hasText(sandboxRun.getExecutionIntent()) ? sandboxRun.getExecutionIntent() : "缺失")
					+ "），离线评估必须强制 DRY_RUN");
		}
		if (sandbox.isolationViolationCount() > 0) {
			gateReasons.add("候选评估存在权限或租户隔离违规（" + sandbox.isolationViolationCount() + " 次），硬门禁要求为 0");
		}
		if (sandbox.writeViolationCount() > 0) {
			gateReasons.add("候选评估存在写副作用违规（" + sandbox.writeViolationCount() + " 次），硬门禁要求为 0");
		}
		if (tenantResults(sandboxRun).stream().anyMatch(this::hasCodeOracleFailure)) {
			gateReasons.add("候选评估代码 oracle 未通过（CODE_ORACLE_MISMATCH/UNSAFE/UNAVAILABLE），硬门禁直接拦截");
		}
	}

	private boolean hasCodeOracleFailure(DataAgentEvalCaseResult result) {
		String reasons = result == null ? null : result.getFailureReasonsJson();
		if (!StringUtils.hasText(reasons)) {
			return false;
		}
		return reasons.contains("CODE_ORACLE_MISMATCH") || reasons.contains("CODE_ORACLE_UNSAFE_EXECUTOR")
				|| reasons.contains("CODE_ORACLE_UNAVAILABLE");
	}

	/**
	 * 分片评估（方案第十四章）：按用例标签中的租户（tenant:）/岗位（position:）/业务类型（bizType:）
	 * 维度分片对比基线与候选通过率，防止大租户样本掩盖小租户回归。用例未标注任何分片维度时
	 * <b>如实降级</b>：记录降级原因，不假装完成分片，也不因此拦发布（降级本身对审批人可见）。
	 * 分片回归（候选分片通过率低于基线同分片）计入门禁原因。
	 */
	private List<Map<String, Object>> buildShardResults(DataAgentEvalRun baselineRun, DataAgentEvalRun sandboxRun,
			List<String> gateReasons) {
		Map<Long, List<String>> baselineCaseShards = loadCaseShardTags(baselineRun.getSuiteId());
		Map<Long, List<String>> sandboxCaseShards = Objects.equals(baselineRun.getSuiteId(), sandboxRun.getSuiteId())
				? baselineCaseShards : loadCaseShardTags(sandboxRun.getSuiteId());
		List<DataAgentEvalCaseResult> baselineResults = tenantResults(baselineRun);
		List<DataAgentEvalCaseResult> sandboxResults = tenantResults(sandboxRun);
		Set<String> shardKeys = new TreeSet<>();
		Map<String, ShardCounter> baselineShards = countByShard(baselineResults, baselineCaseShards, shardKeys);
		Map<String, ShardCounter> sandboxShards = countByShard(sandboxResults, sandboxCaseShards, shardKeys);
		List<Map<String, Object>> shardResults = new ArrayList<>();
		if (shardKeys.isEmpty()) {
			Map<String, Object> degraded = new LinkedHashMap<>();
			degraded.put("degraded", true);
			degraded.put("reason", "评估用例未标注分片维度标签（tenant:/position:/bizType:），本次按整体指标评估，未执行分片对比");
			shardResults.add(degraded);
			log.info("自进化分片评估降级：用例无分片标签. baselineRunId={}, sandboxRunId={}", baselineRun.getId(),
					sandboxRun.getId());
			return shardResults;
		}
		for (String shardKey : shardKeys) {
			ShardCounter baseline = baselineShards.get(shardKey);
			ShardCounter sandbox = sandboxShards.get(shardKey);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("shardKey", shardKey);
			item.put("baselineTotal", baseline == null ? 0 : baseline.total);
			item.put("baselinePassRate", baseline == null ? null : baseline.passRate());
			item.put("candidateTotal", sandbox == null ? 0 : sandbox.total);
			item.put("candidatePassRate", sandbox == null ? null : sandbox.passRate());
			boolean regressed = baseline != null && sandbox != null && baseline.total > 0 && sandbox.total > 0
					&& sandbox.passRate().compareTo(baseline.passRate()) < 0;
			item.put("regressed", regressed);
			if (regressed) {
				gateReasons.add("分片[" + shardKey + "]通过率低于基线（基线 " + baseline.passRate() + "% → 候选 "
						+ sandbox.passRate() + "%），分片评估不通过");
			}
			shardResults.add(item);
		}
		return shardResults;
	}

	/** 加载评估集内启用用例的分片标签（tenant:/position:/bizType: 前缀）。 */
	private Map<Long, List<String>> loadCaseShardTags(Long suiteId) {
		if (suiteId == null) {
			return Map.of();
		}
		Map<Long, List<String>> shardTags = new LinkedHashMap<>();
		for (DataAgentEvalCase evalCase : caseMapper.findEnabledBySuiteId(suiteId)) {
			List<String> tags = readStringList(evalCase.getTagsJson()).stream()
				.filter(tag -> tag.startsWith("tenant:") || tag.startsWith("position:") || tag.startsWith("bizType:"))
				.toList();
			if (!tags.isEmpty()) {
				shardTags.put(evalCase.getId(), tags);
			}
		}
		return shardTags;
	}

	private Map<String, ShardCounter> countByShard(List<DataAgentEvalCaseResult> results,
			Map<Long, List<String>> caseShards, Set<String> shardKeys) {
		Map<String, ShardCounter> counters = new LinkedHashMap<>();
		for (DataAgentEvalCaseResult result : results) {
			List<String> shards = caseShards.get(result.getCaseId());
			if (shards == null || shards.isEmpty()) {
				continue;
			}
			for (String shardKey : shards) {
				shardKeys.add(shardKey);
				ShardCounter counter = counters.computeIfAbsent(shardKey, key -> new ShardCounter());
				counter.total++;
				if (STATUS_SUCCESS.equals(result.getStatus())) {
					counter.success++;
				}
			}
		}
		return counters;
	}

	private String buildGenerateTraces(Long runId, EvalFailureClusterDTO cluster, int maxChars) {
		StringBuilder traces = new StringBuilder();
		traces.append("failureReasons=").append(cluster.failureReasons()).append('\n');
		resultMapper.findByRunId(runId).stream().filter(this::sameTenant)
			.filter(result -> STATUS_FAILED.equals(result.getStatus()) || STATUS_TIMEOUT.equals(result.getStatus()))
			.limit(5)
			.forEach(result -> traces.append("input=")
				.append(OptCandidateGenerator.sanitize(result.getUserInput(), 400))
				.append("\nexpected=")
				.append(OptCandidateGenerator.sanitize(result.getExpectedOutput(), 200))
				.append("\nactual=")
				.append(OptCandidateGenerator.sanitize(result.getAgentOutput(), 400))
				.append("\nreasons=")
				.append(OptCandidateGenerator.sanitize(result.getFailureReasonsJson(), 300))
				.append('\n'));
		return OptCandidateGenerator.sanitize(traces.toString(), maxChars);
	}

	private List<String> readStringList(String json) {
		if (!StringUtils.hasText(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, new TypeReference<List<String>>() {
			});
		}
		catch (Exception ex) {
			log.warn("解析评估用例标签失败, 按无标签处理. length={}", json.length(), ex);
			return List.of();
		}
	}

	private static final class ShardCounter {

		private int total;

		private int success;

		private BigDecimal passRate() {
			return total <= 0 ? BigDecimal.ZERO
					: BigDecimal.valueOf(success).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2,
							RoundingMode.HALF_UP);
		}

	}

	private RunMetrics collectRunMetrics(DataAgentEvalRun run) {
		List<DataAgentEvalCaseResult> results = tenantResults(run);
		int total = results.isEmpty() ? safeInt(run.getTotalCount()) : results.size();
		int success = results.isEmpty() ? safeInt(run.getSuccessCount())
				: (int) results.stream().filter(result -> STATUS_SUCCESS.equals(result.getStatus())).count();
		int failed = results.isEmpty() ? safeInt(run.getFailedCount())
				: (int) results.stream()
					.filter(result -> STATUS_FAILED.equals(result.getStatus()) || STATUS_TIMEOUT.equals(result.getStatus()))
					.count();
		int hardFail = results.isEmpty() ? safeInt(run.getHardFailCount())
				: (int) results.stream().filter(result -> Boolean.TRUE.equals(result.getHardFail())).count();
		// 违规计数取两处最大值：run 聚合列与结果明细求和互为校验，避免任一侧漏记导致门禁放水。
		int writeViolations = Math.max(safeInt(run.getWriteViolationCount()),
				results.stream().mapToInt(result -> safeInt(result.getWriteViolationCount())).sum());
		int isolationViolations = Math.max(safeInt(run.getIsolationViolationCount()),
				results.stream().mapToInt(result -> safeInt(result.getIsolationViolationCount())).sum());
		BigDecimal averageScore = run.getAverageScore() != null ? run.getAverageScore() : averageScore(results);
		BigDecimal passRate = total <= 0 ? BigDecimal.ZERO
				: BigDecimal.valueOf(success).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
		List<Long> durations = results.stream()
			.map(DataAgentEvalCaseResult::getDurationMs)
			.filter(Objects::nonNull)
			.sorted()
			.toList();
		long totalTokens = results.stream()
			.map(result -> mapLong(readMap(result.getEfficiencyMetricsJson()), "totalTokens"))
			.filter(Objects::nonNull)
			.mapToLong(Long::longValue)
			.sum();
		return new RunMetrics(run.getId(), total, success, failed, hardFail, writeViolations, isolationViolations,
				normalize(averageScore), passRate, percentile(durations, 0.9D), totalTokens);
	}

	/** 门禁判定只接受终态运行：queued/running 计数不完整，cancelled 结果残缺，一律失败关闭。 */
	private void requireFinishedRun(DataAgentEvalRun run) {
		String status = run.getStatus();
		if (!STATUS_SUCCESS.equals(status) && !STATUS_FAILED.equals(status) && !STATUS_TIMEOUT.equals(status)) {
			throw badRequest(AgentOptimizationErrorDict.SANDBOX_RUN_NOT_FINISHED);
		}
	}

	private List<DataAgentEvalCaseResult> tenantResults(DataAgentEvalRun run) {
		return resultMapper.findByRunId(run.getId()).stream().filter(this::sameTenant).toList();
	}

	private BigDecimal averageScore(List<DataAgentEvalCaseResult> results) {
		List<BigDecimal> scores = results.stream()
			.map(DataAgentEvalCaseResult::getScore)
			.filter(Objects::nonNull)
			.toList();
		if (scores.isEmpty()) {
			return BigDecimal.ZERO;
		}
		return scores.stream()
			.reduce(BigDecimal.ZERO, BigDecimal::add)
			.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP);
	}

	private boolean gatePassed(DataAgentOptCandidate candidate) {
		Map<String, Object> gateResult = readMap(candidate.getGateResultJson());
		Object passed = gateResult.get("passed");
		if (passed instanceof Boolean booleanValue) {
			return booleanValue;
		}
		DataAgentOptCandidateEval latestEval = candidateEvalMapper.findLatestByCandidateId(candidate.getId());
		return latestEval != null && sameTenant(latestEval) && Boolean.TRUE.equals(latestEval.getPassed());
	}

	private DataAgentOptExperiment requireTenantExperiment(Long id) {
		DataAgentOptExperiment experiment = id == null ? null : experimentMapper.findActiveById(id);
		if (experiment == null || !sameTenant(experiment)) {
			throw badRequest(AgentOptimizationErrorDict.EXPERIMENT_NOT_FOUND);
		}
		return experiment;
	}

	private DataAgentOptCandidate requireTenantCandidate(Long id) {
		DataAgentOptCandidate candidate = id == null ? null : candidateMapper.findActiveById(id);
		if (candidate == null || !sameTenant(candidate)) {
			throw badRequest(AgentOptimizationErrorDict.CANDIDATE_NOT_FOUND);
		}
		return candidate;
	}

	private DataAgentOptRelease requireTenantRelease(Long id) {
		DataAgentOptRelease release = id == null ? null : releaseMapper.findActiveById(id);
		if (release == null || !sameTenant(release)) {
			throw badRequest(AgentOptimizationErrorDict.RELEASE_NOT_FOUND);
		}
		return release;
	}

	private DataAgentEvalRun requireTenantRun(Long id, AgentOptimizationErrorDict error) {
		DataAgentEvalRun run = id == null ? null : runMapper.findActiveById(id);
		if (run == null || !sameTenant(run.getTenantId())) {
			throw badRequest(error);
		}
		return run;
	}

	private void updateExperimentStatus(DataAgentOptExperiment experiment, String status) {
		experiment.setStatus(status);
		experiment.setLastModifyTime(Instant.now());
		experimentMapper.updateById(experiment);
	}

	/**
	 * 候选产物类型 guardrail（方案第十四章）：禁止项显式拒绝并记录，其余仅放行允许清单内的类型。
	 */
	private String normalizeTargetType(String targetType) {
		String normalized = targetType.trim().toUpperCase();
		if (FORBIDDEN_TARGET_TYPES.contains(normalized)) {
			// 记录命中禁止项的候选生成企图：这是自进化安全边界事件，必须可审计。
			log.warn("候选生成命中禁止产物类型, 已拒绝. targetType={}, tenantId={}, operator={}", normalized,
					currentTenantId(), currentUserId());
			throw badRequest(AgentOptimizationErrorDict.TARGET_TYPE_FORBIDDEN);
		}
		if (!ALLOWED_TARGET_TYPES.contains(normalized)) {
			throw badRequest(AgentOptimizationErrorDict.TARGET_TYPE_NOT_ALLOWED);
		}
		return normalized;
	}

	/**
	 * 候选补丁内容校验：即便产物类型合法，补丁内容也不允许触碰权限/凭据/审批策略等禁止项；
	 * 快照覆盖键必须落在该产物类型允许的 Release 快照列内。补丁不是合法 JSON 一律拒绝
	 * （无法校验的内容按不安全处理，失败关闭）。
	 */
	private void validatePatchContent(String targetType, String patchJson) {
		JsonNode patch;
		try {
			patch = objectMapper.readTree(patchJson);
		}
		catch (Exception ex) {
			throw badRequest(AgentOptimizationErrorDict.REQUEST_INVALID);
		}
		String forbiddenKey = findForbiddenKey(patch);
		if (forbiddenKey != null) {
			log.warn("候选补丁内容命中禁止键, 已拒绝. targetType={}, forbiddenKey={}, tenantId={}, operator={}",
					targetType, forbiddenKey, currentTenantId(), currentUserId());
			throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
		}
		JsonNode overlay = patch.get(PATCH_KEY_SNAPSHOT_OVERLAY);
		if (overlay == null || overlay.isNull()) {
			return;
		}
		if (!overlay.isObject()) {
			throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
		}
		Set<String> allowedOverlayKeys = TARGET_TYPE_OVERLAY_KEYS.getOrDefault(targetType, Set.of());
		overlay.fieldNames().forEachRemaining(fieldName -> {
			if (!allowedOverlayKeys.contains(fieldName)) {
				log.warn("候选补丁快照覆盖键越界, 已拒绝. targetType={}, overlayKey={}, allowed={}", targetType,
						fieldName, allowedOverlayKeys);
				throw badRequest(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN);
			}
		});
	}

	/** 深度遍历补丁 JSON 的所有键名，返回第一个命中的禁止键；未命中返回 null。 */
	private String findForbiddenKey(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (FORBIDDEN_PATCH_KEYS.contains(field.getKey().toLowerCase(Locale.ROOT))) {
					return field.getKey();
				}
				String nested = findForbiddenKey(field.getValue());
				if (nested != null) {
					return nested;
				}
			}
		}
		if (node.isArray()) {
			for (JsonNode item : node) {
				String nested = findForbiddenKey(item);
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}

	private BigDecimal percentChange(long baselineValue, long candidateValue) {
		if (baselineValue <= 0L) {
			return candidateValue <= 0L ? BigDecimal.ZERO : HUNDRED;
		}
		return BigDecimal.valueOf(candidateValue - baselineValue)
			.multiply(HUNDRED)
			.divide(BigDecimal.valueOf(baselineValue), 2, RoundingMode.HALF_UP);
	}

	private long percentile(List<Long> sortedValues, double percentile) {
		if (sortedValues == null || sortedValues.isEmpty()) {
			return 0L;
		}
		int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
		return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
	}

	private Map<String, Object> readMap(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception ex) {
			// The payload is not logged: optimization snapshots carry prompt and tenant business data.
			log.warn("Failed to parse optimization JSON object, falling back to an empty map. length={}", json.length(),
					ex);
			return Map.of();
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			// "{}" is persisted as if it were a real snapshot, so the loss has to be traceable.
			log.warn("Failed to serialize optimization snapshot, persisting an empty object instead. valueType={}",
					value == null ? null : value.getClass().getName(), ex);
			return "{}";
		}
	}

	private Long mapLong(Map<String, Object> values, String key) {
		Object value = values.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				return Long.valueOf(text.trim());
			}
			catch (NumberFormatException ex) {
				log.warn("Optimization metric value is not a valid long, treating it as absent. key={}", key, ex);
				return null;
			}
		}
		return null;
	}

	private boolean sameTenant(DataAgentOptExperiment experiment) {
		return experiment == null || sameTenant(experiment.getTenantId());
	}

	private boolean sameTenant(DataAgentOptCandidate candidate) {
		return candidate == null || sameTenant(candidate.getTenantId());
	}

	private boolean sameTenant(DataAgentOptCandidateEval candidateEval) {
		return candidateEval == null || sameTenant(candidateEval.getTenantId());
	}

	private boolean sameTenant(DataAgentOptRelease release) {
		return release == null || sameTenant(release.getTenantId());
	}

	private boolean sameTenant(DataAgentEvalCaseResult result) {
		return result == null || sameTenant(result.getTenantId());
	}

	/**
	 * 比对行归属租户与当前登录租户。
	 * <p>
	 * 行上没有租户视为平台级数据，对所有租户放行；当前租户拿不到则一律拒绝——这两种情形都表现为空值，
	 * 但语义完全不同，不能共用一个放行分支（见 PRD P0-13）。
	 */
	private boolean sameTenant(String rowTenantId) {
		if (!StringUtils.hasText(rowTenantId)) {
			return true;
		}
		String currentTenantId = currentTenantId();
		if (!StringUtils.hasText(currentTenantId)) {
			log.warn("当前租户上下文不可用, 拒绝访问租户级优化实验数据. rowTenantId={}", rowTenantId);
			return false;
		}
		return currentTenantId.equals(rowTenantId);
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			// sameTenant() 会因此拒绝全部租户级数据，必须留痕以便定位鉴权上下文故障。
			log.warn("解析优化实验租户上下文失败, 租户级优化实验数据将失败关闭", ex);
			return null;
		}
	}

	private String currentTenantCode() {
		try {
			return authenticationContext.tenantCode();
		}
		catch (Exception ex) {
			log.warn("解析优化实验租户编码失败, 按无租户编码处理", ex);
			return null;
		}
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析优化实验当前用户失败, 按匿名处理", ex);
			return null;
		}
	}

	private CheckedException badRequest(AgentOptimizationErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

	private String trim(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String defaultText(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value.trim() : defaultValue;
	}

	private int safeInt(Integer value) {
		return value == null ? 0 : value;
	}

	private long safeLong(Long value) {
		return value == null ? 0L : value;
	}

	private BigDecimal normalize(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
	}

	private Long firstNonNull(Long first, Long second) {
		return first == null ? second : first;
	}

	private record OwnerRef(String type, String id) {

		static OwnerRef empty() {
			return new OwnerRef(null, null);
		}
	}

	private record RunMetrics(Long runId, int totalCount, int successCount, int failedCount, int hardFailCount,
			int writeViolationCount, int isolationViolationCount, BigDecimal averageScore, BigDecimal passRate,
			long p90DurationMs, long totalTokens) {

		private Map<String, Object> toMap() {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("runId", runId);
			values.put("totalCount", totalCount);
			values.put("successCount", successCount);
			values.put("failedCount", failedCount);
			values.put("hardFailCount", hardFailCount);
			values.put("writeViolationCount", writeViolationCount);
			values.put("isolationViolationCount", isolationViolationCount);
			values.put("averageScore", averageScore);
			values.put("passRate", passRate);
			values.put("p90DurationMs", p90DurationMs);
			values.put("totalTokens", totalTokens);
			return values;
		}
	}

}
