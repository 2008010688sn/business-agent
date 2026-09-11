/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.dto.chat.DataChatTurnDetailResp;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.evaluation.adapter.AgentEvalAdapter;
import com.sn68.agent.dataagent.evaluation.adapter.DigitalEmployeeCandidateEvalAdapter;
import com.sn68.agent.dataagent.evaluation.adapter.DigitalEmployeeReleaseEvalAdapter;
import com.sn68.agent.dataagent.evaluation.adapter.EvalRunHarness;
import com.sn68.agent.dataagent.evaluation.adapter.EvalInvocationPrepared;
import com.sn68.agent.dataagent.evaluation.adapter.EvalInvocationResult;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapItemStatus;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapResult;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseImportRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalPolicyQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalPolicyRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultDetailDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultTraceDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalFailureClusterDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunEfficiencySummaryDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunFailureSummaryDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalRunQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSubjectQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSubjectRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSuiteQueryRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalSuiteRequest;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalPolicy;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
import com.sn68.agent.dataagent.evaluation.enums.AgentEvaluationErrorDict;
import com.sn68.agent.dataagent.evaluation.enums.EvalFailureReasonDict;
import com.sn68.agent.dataagent.evaluation.enums.EvalSlowReasonDict;
import com.sn68.agent.dataagent.evaluation.enums.EvalStatusDict;
import com.sn68.agent.dataagent.evaluation.enums.ExecutionIntentDict;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseResultMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalPolicyMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalRunMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSubjectMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSuiteMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeArtifact;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Agent 评估服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEvaluationService {

	private static final String STATUS_ENABLED = "enabled";

	private static final String STATUS_QUEUED = EvalStatusDict.QUEUED.getValue();

	private static final String STATUS_RUNNING = EvalStatusDict.RUNNING.getValue();

	private static final String STATUS_SUCCESS = EvalStatusDict.SUCCESS.getValue();

	private static final String STATUS_FAILED = EvalStatusDict.FAILED.getValue();

	private static final String STATUS_CANCELLED = EvalStatusDict.CANCELLED.getValue();

	private static final String STATUS_TIMEOUT = EvalStatusDict.TIMEOUT.getValue();

	private static final String DEFAULT_POLICY_CODE = "DATA_AGENT_DEFAULT";

	private static final String EMPLOYEE_DEFAULT_POLICY_CODE = "DIGITAL_EMPLOYEE_DEFAULT";

	private static final String DEFAULT_SUBJECT_TYPE = "DATA_AGENT";

	private static final String EMPLOYEE_SUBJECT_TYPE = DigitalEmployeeCandidateEvalAdapter.SUBJECT_TYPE;

	private static final String EMPLOYEE_ADAPTER_CODE = DigitalEmployeeCandidateEvalAdapter.ADAPTER_CODE;

	private static final int MAX_POSITION_CHARS = 64;

	private static final String DEFAULT_ADAPTER_CODE = "DATA_AGENT";

	private static final String DEFAULT_SMOKE_CASE_NAME = "默认冒烟用例";

	private static final String DEFAULT_SMOKE_CASE_INPUT = "请用一句话说明你能提供哪些能力";

	private static final long DEFAULT_MAX_DURATION_MS = 60000L;

	private static final long DEFAULT_TOKEN_BUDGET = 8000L;

	/** 分片评估标签前缀，与 AgentOptimizationService#loadCaseShardTags 的识别前缀一一对应 */
	private static final String SHARD_TAG_TENANT_PREFIX = "tenant:";

	/** 敏感级产物不作为评估用例的参考输出 */
	private static final String ARTIFACT_SENSITIVITY_SENSITIVE = "SENSITIVE";

	private static final String WEIGHT_KEY_QUALITY = "quality";

	private static final String WEIGHT_KEY_SAFETY = "safety";

	private static final String WEIGHT_KEY_EFFICIENCY = "efficiency";

	private static final String WEIGHT_KEY_STABILITY = "stability";

	private static final BigDecimal DEFAULT_QUALITY_WEIGHT = BigDecimal.valueOf(45);

	private static final BigDecimal DEFAULT_SAFETY_WEIGHT = BigDecimal.valueOf(25);

	private static final BigDecimal DEFAULT_EFFICIENCY_WEIGHT = BigDecimal.valueOf(20);

	private static final BigDecimal DEFAULT_STABILITY_WEIGHT = BigDecimal.valueOf(10);

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
	};

	private final DataAgentEvalPolicyMapper policyMapper;

	private final DataAgentEvalSubjectMapper subjectMapper;

	private final DataAgentEvalSuiteMapper suiteMapper;

	private final DataAgentEvalCaseMapper caseMapper;

	private final DataAgentEvalRunMapper runMapper;

	private final DataAgentEvalCaseResultMapper resultMapper;

	private final DataChatTurnService turnService;

	private final RuntimeRunService runtimeRunService;

	private final AgentRuntimeArtifactMapper artifactMapper;

	private final EvalRuntimeRunImportEnricher runtimeRunImportEnricher;

	private final DataAgentService dataAgentService;

	// PR-7：DIGITAL_EMPLOYEE_RELEASE 评测主体校验（只读消费 employee 域服务，不改其文件）。
	private final EmployeeReleaseLifecycleService employeeReleaseLifecycleService;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final EmployeeDeploymentService employeeDeploymentService;

	private final AuthenticationContext authenticationContext;

	private final ObjectMapper objectMapper;

	private final List<AgentEvalAdapter> adapters;

	private final DataAgentProperties dataAgentProperties;

	private final TransactionTemplate transactionTemplate;

	private final ObjectProvider<EvalCodeOracleService> evalCodeOracleProvider;

	private ExecutorService evaluationExecutor;

	private Map<String, AgentEvalAdapter> adapterRegistry = Map.of();

	/**
	 * 创建AgentEvaluation。
	 */
	@PostConstruct
	public void initEvaluationExecutor() {
		this.adapterRegistry = buildAdapterRegistry();
		DataAgentProperties.Executor executorProperties = dataAgentProperties.getEvaluation() == null
				|| dataAgentProperties.getEvaluation().getExecutor() == null ? new DataAgentProperties.Executor()
						: dataAgentProperties.getEvaluation().getExecutor();
		int maxConcurrency = Math.max(1, executorProperties.getMaxConcurrency());
		int queueCapacity = Math.max(1, executorProperties.getQueueCapacity());
		AtomicInteger threadIndex = new AtomicInteger(1);
		ThreadFactory threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "agent-evaluation-" + threadIndex.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		};
		this.evaluationExecutor = new ThreadPoolExecutor(maxConcurrency, maxConcurrency, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(queueCapacity), threadFactory, new ThreadPoolExecutor.AbortPolicy());
	}

	/**
	 * 处理AgentEvaluation。
	 */
	@PreDestroy
	public void shutdownEvaluationExecutor() {
		if (evaluationExecutor != null) {
			evaluationExecutor.shutdown();
		}
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalPolicy> queryPoliciesPage(EvalPolicyQueryRequest request) {
		EvalPolicyQueryRequest query = request == null ? new EvalPolicyQueryRequest() : request;
		return policyMapper.selectPolicyPage(query.buildPage(), query, currentTenantId());
	}

	/**
	 * 创建AgentEvaluation。
	 */
	public DataAgentEvalPolicy createPolicy(EvalPolicyRequest request) {
		if (request == null || !StringUtils.hasText(request.getPolicyCode()) || !StringUtils.hasText(request.getPolicyName())) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		if (policyMapper.findByCode(currentTenantId(), request.getPolicyCode()) != null) {
			throw badRequest(AgentEvaluationErrorDict.POLICY_CODE_EXISTS);
		}
		Instant now = Instant.now();
		DataAgentEvalPolicy policy = toPolicy(new DataAgentEvalPolicy(), request);
		policy.setTenantId(currentTenantId());
		policy.setTenantCode(currentTenantCode());
		policy.setVersionNo(1);
		policy.setCreateTime(now);
		policy.setLastModifyTime(now);
		policy.setDeleted(false);
		if (Boolean.TRUE.equals(policy.getDefaultFlag())) {
			policyMapper.clearDefault(policy.getTenantId(), policy.getSubjectType(), null, now);
		}
		policyMapper.insert(policy);
		return policyMapper.selectById(policy.getId());
	}

	/**
	 * 保存AgentEvaluation。
	 */
	public DataAgentEvalPolicy updatePolicy(Long id, EvalPolicyRequest request) {
		DataAgentEvalPolicy policy = requireTenantPolicy(id, false);
		toPolicy(policy, request);
		policy.setVersionNo(value(policy.getVersionNo()) + 1);
		policy.setLastModifyTime(Instant.now());
		if (Boolean.TRUE.equals(policy.getDefaultFlag())) {
			policyMapper.clearDefault(policy.getTenantId(), policy.getSubjectType(), id, policy.getLastModifyTime());
		}
		policyMapper.updateById(policy);
		return policyMapper.selectById(id);
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalSubject> querySubjectsPage(EvalSubjectQueryRequest request) {
		EvalSubjectQueryRequest query = request == null ? new EvalSubjectQueryRequest() : request;
		return subjectMapper.selectSubjectPage(query.buildPage(), query, currentTenantId());
	}

	/**
	 * 创建AgentEvaluation。
	 */
	public DataAgentEvalSubject createSubject(EvalSubjectRequest request) {
		if (request == null || !StringUtils.hasText(request.getSubjectName()) || !StringUtils.hasText(request.getSubjectType())
				|| !StringUtils.hasText(request.getSubjectId()) || !StringUtils.hasText(request.getAdapterCode())) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		// PR-7 主体适配：数字员工发布版本主体校验 Release 存在且当前租户可访问（getDetail 跨租户直接 404）。
		if (DigitalEmployeeReleaseEvalAdapter.SUBJECT_TYPE.equalsIgnoreCase(normalize(request.getSubjectType()))) {
			requireDigitalEmployeeRelease(request.getSubjectId());
		}
		if (DigitalEmployeeCandidateEvalAdapter.SUBJECT_TYPE.equalsIgnoreCase(normalize(request.getSubjectType()))) {
			requireDigitalEmployeeId(request.getSubjectId());
		}
		Instant now = Instant.now();
		DataAgentEvalSubject subject = toSubject(new DataAgentEvalSubject(), request);
		subject.setTenantId(currentTenantId());
		subject.setTenantCode(currentTenantCode());
		subject.setCreateTime(now);
		subject.setLastModifyTime(now);
		subject.setDeleted(false);
		subjectMapper.insert(subject);
		return subjectMapper.selectById(subject.getId());
	}

	/**
	 * 保存AgentEvaluation。
	 */
	public DataAgentEvalSubject updateSubject(Long id, EvalSubjectRequest request) {
		DataAgentEvalSubject subject = requireTenantSubject(id);
		toSubject(subject, request);
		subject.setLastModifyTime(Instant.now());
		subjectMapper.updateById(subject);
		return subjectMapper.selectById(id);
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalSuite> querySuitesPage(EvalSuiteQueryRequest request) {
		EvalSuiteQueryRequest query = request == null ? new EvalSuiteQueryRequest() : request;
		return suiteMapper.selectSuitePage(query.buildPage(), query, currentTenantId());
	}

	/**
	 * 创建AgentEvaluation。
	 */
	public DataAgentEvalSuite createSuite(EvalSuiteRequest request) {
		if (request == null || !StringUtils.hasText(request.getSuiteName()) || request.getSubjectId() == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		requireTenantSubject(request.getSubjectId());
		if (request.getPolicyId() != null) {
			requireTenantPolicy(request.getPolicyId(), false);
		}
		Instant now = Instant.now();
		DataAgentEvalSuite suite = toSuite(new DataAgentEvalSuite(), request);
		suite.setTenantId(currentTenantId());
		suite.setTenantCode(currentTenantCode());
		suite.setCreateTime(now);
		suite.setLastModifyTime(now);
		suite.setDeleted(false);
		suiteMapper.insert(suite);
		return suiteMapper.selectById(suite.getId());
	}

	/**
	 * 保存AgentEvaluation。
	 */
	public DataAgentEvalSuite updateSuite(Long id, EvalSuiteRequest request) {
		DataAgentEvalSuite suite = requireTenantSuite(id);
		toSuite(suite, request);
		if (suite.getSubjectId() != null) {
			requireTenantSubject(suite.getSubjectId());
		}
		if (suite.getPolicyId() != null) {
			requireTenantPolicy(suite.getPolicyId(), false);
		}
		suite.setLastModifyTime(Instant.now());
		suiteMapper.updateById(suite);
		return suiteMapper.selectById(id);
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalCase> queryCasesPage(EvalCaseQueryRequest request) {
		EvalCaseQueryRequest query = request == null ? new EvalCaseQueryRequest() : request;
		return caseMapper.selectCasePage(query.buildPage(), query, currentTenantId());
	}

	/**
	 * 创建AgentEvaluation。
	 */
	public DataAgentEvalCase createCase(EvalCaseRequest request) {
		if (request == null || request.getSuiteId() == null || !StringUtils.hasText(request.getCaseName())
				|| !StringUtils.hasText(request.getUserInput())) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		requireTenantSuite(request.getSuiteId());
		Instant now = Instant.now();
		DataAgentEvalCase evalCase = toCase(new DataAgentEvalCase(), request);
		evalCase.setTenantId(currentTenantId());
		evalCase.setTenantCode(currentTenantCode());
		evalCase.setCreateTime(now);
		evalCase.setLastModifyTime(now);
		evalCase.setDeleted(false);
		caseMapper.insert(evalCase);
		return caseMapper.selectById(evalCase.getId());
	}

	/**
	 * 保存AgentEvaluation。
	 */
	public DataAgentEvalCase updateCase(Long id, EvalCaseRequest request) {
		DataAgentEvalCase evalCase = requireTenantCase(id);
		toCase(evalCase, request);
		if (evalCase.getSuiteId() != null) {
			requireTenantSuite(evalCase.getSuiteId());
		}
		evalCase.setLastModifyTime(Instant.now());
		caseMapper.updateById(evalCase);
		return caseMapper.selectById(id);
	}

	/**
	 * 创建AgentEvaluation。
	 */
	public DataAgentEvalRun createRun(EvalRunCreateRequest request) {
		if (request == null || request.getSuiteId() == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		ExecutionIntentDict executionIntent = ExecutionIntentDict.normalize(request.getExecutionIntent());
		if (executionIntent == null) {
			// 意图非法时失败关闭：绝不把未知意图静默当 LIVE 跑，避免绕过 DRY_RUN 约束。
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		String evalMode = null;
		if (StringUtils.hasText(request.getEvalMode())) {
			evalMode = EvalRunHarness.normalizeMode(request.getEvalMode());
			if (evalMode == null) {
				throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
			}
		}
		DataAgentEvalSuite suite = requireTenantSuite(request.getSuiteId());
		DataAgentEvalSubject subject = requireTenantSubject(firstNonNull(request.getSubjectId(), suite.getSubjectId()));
		DataAgentEvalPolicy policy = resolvePolicy(request.getPolicyId(), suite.getPolicyId(), subject.getSubjectType());
		PolicySnapshot policySnapshot = buildPolicySnapshot(policy);
		List<DataAgentEvalCase> cases = caseMapper.findEnabledBySuiteId(suite.getId())
			.stream()
			.filter(this::sameTenant)
			.toList();
		if (cases.isEmpty()) {
			throw badRequest(AgentEvaluationErrorDict.CASE_NOT_FOUND);
		}
		Instant now = Instant.now();
		DataAgentEvalRun run = DataAgentEvalRun.builder()
			.tenantId(currentTenantId())
			.tenantCode(currentTenantCode())
			.suiteId(suite.getId())
			.subjectId(subject.getId())
			.policyId(policy == null ? null : policy.getId())
			.policyVersionNo(policy == null ? null : policy.getVersionNo())
			.policyHash(policySnapshot.hash())
			.policySnapshotJson(policySnapshot.json())
			.userIdSnapshot(currentUserId())
			.clientIdSnapshot(currentClientId())
			.teamIdsJson(writeJson(currentTeamIds()))
			.dataPermissionSnapshotJson(writeJson(currentDataPermission()))
			.modelConfigSnapshotJson(writeJson(Map.of()))
			.agentConfigSnapshotJson(writeJson(EvalRunHarness.mergeSnapshot(buildAgentConfigSnapshot(subject), evalMode,
					request.getCandidateOverlayJson(), objectMapper)))
			.executionIntent(executionIntent.getValue())
			.writeViolationCount(0)
			.isolationViolationCount(0)
			.status(STATUS_QUEUED)
			.totalCount(cases.size())
			.successCount(0)
			.failedCount(0)
			.hardFailCount(0)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		runMapper.insert(run);
		submitRun(run.getId());
		return runMapper.selectById(run.getId());
	}

	/**
	 * 处理AgentEvaluation。
	 */
	public EvalBootstrapResult bootstrapDefaults(EvalBootstrapRequest request) {
		if (request == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		boolean hasAgent = request.getAgentId() != null;
		boolean hasEmployee = request.getEmployeeId() != null;
		if (hasAgent == hasEmployee) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		EvalBootstrapResult result;
		boolean employeeBootstrap = hasEmployee;
		if (employeeBootstrap) {
			DigitalEmployee employee = requireEmployeeForBootstrap(request.getEmployeeId());
			ensureEmployeeSandbox(employee);
			result = transactionTemplate.execute(status -> bootstrapEmployeeDefaultsInTransaction(employee, request));
		}
		else {
			DataAgent agent = resolveBootstrapAgent(request.getAgentId());
			result = transactionTemplate.execute(status -> bootstrapDefaultsInTransaction(agent, request));
		}
		if (result == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		if (Boolean.TRUE.equals(request.getRunNow())) {
			EvalRunCreateRequest runRequest = new EvalRunCreateRequest();
			runRequest.setSuiteId(result.getSuiteId());
			runRequest.setSubjectId(result.getSubjectId());
			runRequest.setPolicyId(result.getPolicyId());
			if (employeeBootstrap) {
				runRequest.setExecutionIntent(ExecutionIntentDict.DRY_RUN.getValue());
				runRequest.setEvalMode(EvalRunHarness.MODE_INVOKE);
			}
			DataAgentEvalRun run = createRun(runRequest);
			result.setRunId(run == null ? null : run.getId());
		}
		return result;
	}

	/**
	 * 校验AgentEvaluation。
	 */
	public void cancelRun(Long id) {
		DataAgentEvalRun run = requireTenantRun(id);
		if (!STATUS_QUEUED.equals(run.getStatus()) && !STATUS_RUNNING.equals(run.getStatus())) {
			return;
		}
		run.setStatus(STATUS_CANCELLED);
		run.setFinishedAt(Instant.now());
		run.setLastModifyTime(run.getFinishedAt());
		runMapper.updateById(run);
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalRun> queryRunsPage(EvalRunQueryRequest request) {
		EvalRunQueryRequest query = request == null ? new EvalRunQueryRequest() : request;
		return runMapper.selectRunPage(query.buildPage(), query, currentTenantId());
	}

	public DataAgentEvalRun getRun(Long id) {
		return requireTenantRun(id);
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public List<DataAgentEvalCaseResult> listRunResults(Long runId) {
		requireTenantRun(runId);
		return resultMapper.findByRunId(runId).stream().filter(this::sameTenant).toList();
	}

	/**
	 * 查询AgentEvaluation。
	 */
	public IPage<DataAgentEvalCaseResult> queryResultsPage(EvalResultQueryRequest request) {
		EvalResultQueryRequest query = request == null ? new EvalResultQueryRequest() : request;
		return resultMapper.selectResultPage(query.buildPage(), query, currentTenantId());
	}

	public EvalResultDetailDTO getResultDetail(Long id) {
		DataAgentEvalCaseResult result = requireTenantResult(id);
		return EvalResultDetailDTO.builder().result(result).build();
	}

	/**
	 * 处理AgentEvaluation。
	 *
	 * <p>两条取材链路：给 {@code runtimeRunId} 走任务链路（agent_runtime_run），
	 * 否则走原有聊天链路（data_chat_turn）。
	 */
	public DataAgentEvalCase importCase(EvalCaseImportRequest request) {
		if (request == null || request.getSuiteId() == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		if (request.getRuntimeRunId() != null) {
			return importCaseFromRuntimeRun(request);
		}
		if (request.getSessionId() == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		requireTenantSuite(request.getSuiteId());
		DataChatTurnDetailResp detail = turnService.getTurnDetail(request.getSessionId(), request.getRuntimeRequestId());
		DataChatTurn turn = detail == null ? null : detail.getTurn();
		if (turn == null || !StringUtils.hasText(turn.getQuestion())) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		EvalCaseRequest caseRequest = new EvalCaseRequest();
		caseRequest.setSuiteId(request.getSuiteId());
		caseRequest.setCaseName(defaultText(request.getCaseName(), "历史会话-" + request.getRuntimeRequestId()));
		caseRequest.setUserInput(turn.getQuestion());
		caseRequest.setExpectedOutput(defaultText(request.getExpectedOutput(), turn.getAnswer()));
		caseRequest.setTagsJson(writeJson(request.getTags() == null ? List.of() : request.getTags()));
		caseRequest.setTraceSnapshotJson(writeJson(buildTraceSummary(turn)));
		caseRequest.setStatus(STATUS_ENABLED);
		return createCase(caseRequest);
	}

	/**
	 * 从数字员工任务运行取材：Run 的 query 作为用例输入，最终回答（缺失时退回最近一条非敏感产物）作为参考输出。
	 *
	 * <p>租户归属由 {@code runtimeRunService.detail} 保证（跨租户直接 404），本方法不再自行拼租户条件。
	 */
	private DataAgentEvalCase importCaseFromRuntimeRun(EvalCaseImportRequest request) {
		String tenantId = requireNumericTenantId();
		requireTenantSuite(request.getSuiteId());
		RuntimeRunDetailResp detail = runtimeRunService.detail(tenantId, request.getRuntimeRunId());
		RuntimeRunResp run = detail == null ? null : detail.run();
		if (run == null) {
			throw CheckedException.notFound("运行不存在或无权访问: " + request.getRuntimeRunId());
		}
		if (!StringUtils.hasText(run.query())) {
			throw CheckedException.badRequest("该运行没有任务描述，无法作为评估用例输入, runId=" + run.id());
		}
		String expectedOutput = defaultText(request.getExpectedOutput(), detail.finalAnswer());
		if (!StringUtils.hasText(expectedOutput)) {
			expectedOutput = latestReferenceArtifact(run.id());
		}
		expectedOutput = runtimeRunImportEnricher.enrichExpectedOutput(expectedOutput, tenantId, run.id());
		EvalCaseRequest caseRequest = new EvalCaseRequest();
		caseRequest.setSuiteId(request.getSuiteId());
		caseRequest.setCaseName(defaultText(request.getCaseName(), "运行回流-" + run.id()));
		caseRequest.setUserInput(run.query());
		caseRequest.setExpectedOutput(expectedOutput);
		caseRequest.setTagsJson(writeJson(runtimeRunImportEnricher.enrichTags(
				buildRunShardTags(request.getTags(), tenantId, run), tenantId, run)));
		caseRequest.setTraceSnapshotJson(writeJson(buildRunTraceSummary(detail, run)));
		caseRequest.setStatus(STATUS_ENABLED);
		return createCase(caseRequest);
	}

	/**
	 * 生成用例分片标签（方案第十四章分片评估的输入，见 AgentOptimizationService#loadCaseShardTags）。
	 *
	 * <p>能确定来源的才打：{@code tenant:} 取自 Run 的租户。{@code position:} 由
	 * {@link EvalRuntimeRunImportEnricher} 在员工 jobTitle 非空时追加；
	 * {@code bizType:} 在 Run 上没有任何可靠来源，一律不打——宁可少一个维度，也不编造分片。
	 */
	private List<String> buildRunShardTags(List<String> requestTags, String tenantId, RuntimeRunResp run) {
		Set<String> tags = new LinkedHashSet<>();
		if (requestTags != null) {
			requestTags.stream().filter(StringUtils::hasText).map(String::trim).forEach(tags::add);
		}
		if (StringUtils.hasText(tenantId) && !"0".equals(tenantId.trim())) {
			tags.add(SHARD_TAG_TENANT_PREFIX + tenantId.trim());
		}
		return List.copyOf(tags);
	}

	/**
	 * Run 没有最终回答时（DAG 任务链路常见，结论落在结构化产物里）退回最近一条产物作为参考输出。
	 * 敏感级产物不进评估集，宁可留空由人工补全。
	 */
	private String latestReferenceArtifact(Long runId) {
		List<AgentRuntimeArtifact> artifacts = artifactMapper.listByRunId(runId);
		for (int index = artifacts.size() - 1; index >= 0; index--) {
			AgentRuntimeArtifact artifact = artifacts.get(index);
			if (!ARTIFACT_SENSITIVITY_SENSITIVE.equals(artifact.getSensitivity())
					&& StringUtils.hasText(artifact.getData())) {
				return artifact.getData();
			}
		}
		return null;
	}

	private Map<String, Object> buildRunTraceSummary(RuntimeRunDetailResp detail, RuntimeRunResp run) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("source", "RUNTIME_RUN");
		summary.put("runtimeRunId", run.id());
		summary.put("runtimeRequestId", run.runtimeRequestId());
		summary.put("ownerType", run.ownerType());
		summary.put("ownerId", run.ownerId());
		summary.put("digitalEmployeeId", run.digitalEmployeeId());
		summary.put("agentId", run.agentId());
		summary.put("releaseId", run.releaseId());
		summary.put("threadId", run.threadId());
		summary.put("triggerSource", run.triggerSource());
		summary.put("runMode", run.runMode());
		summary.put("state", run.state());
		summary.put("errorCode", run.errorCode());
		summary.put("startedAt", Objects.toString(run.startedAt(), null));
		summary.put("finishedAt", Objects.toString(run.finishedAt(), null));
		summary.put("stepCount", detail.steps() == null ? 0 : detail.steps().size());
		return summary;
	}

	private String requireNumericTenantId() {
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.forbidden("当前登录租户为空，禁止从运行记录导入评估用例");
		}
		return tenantId.trim();
	}

	public EvalRunEfficiencySummaryDTO getRunEfficiencySummary(Long runId) {
		DataAgentEvalRun run = requireTenantRun(runId);
		List<DataAgentEvalCaseResult> results = tenantCaseResults(runId);
		EvalFailureClusterDTO cluster = toFailureCluster(run, results);
		List<Long> durations = results.stream()
			.map(DataAgentEvalCaseResult::getDurationMs)
			.filter(Objects::nonNull)
			.sorted()
			.toList();
		BigDecimal average = durations.isEmpty() ? BigDecimal.ZERO
				: BigDecimal.valueOf(durations.stream().mapToLong(Long::longValue).average().orElse(0D))
					.setScale(2, RoundingMode.HALF_UP);
		Long p90 = percentile(durations, 0.9D);
		Long max = durations.stream().max(Long::compareTo).orElse(0L);
		return EvalRunEfficiencySummaryDTO.builder()
			.runId(run.getId())
			.totalCount(results.size())
			.averageDurationMs(average)
			.p90DurationMs(p90)
			.maxDurationMs(max)
			.timeoutCount((int) results.stream().filter(result -> STATUS_TIMEOUT.equals(result.getStatus())).count())
			.slowCount(cluster.slowCases().size())
			.slowReasons(cluster.slowCases())
			.build();
	}

	/**
	 * 失败聚类唯一入口：评估排障与自进化 LOOP 诊断都必须走这里，禁止各算一遍。
	 */
	public EvalFailureClusterDTO clusterFailures(Long runId) {
		DataAgentEvalRun run = requireTenantRun(runId);
		return toFailureCluster(run, tenantCaseResults(runId));
	}

	public EvalRunFailureSummaryDTO getRunFailureSummary(Long runId) {
		EvalFailureClusterDTO cluster = clusterFailures(runId);
		return EvalRunFailureSummaryDTO.builder()
			.runId(cluster.runId())
			.failedCount(cluster.failedCount())
			.hardFailCount(cluster.hardFailCount())
			.failureReasons(cluster.failureReasons())
			.build();
	}

	public EvalResultTraceDTO getResultTrace(Long id, boolean includeSource) {
		DataAgentEvalCaseResult result = requireTenantResult(id);
		DataChatTurnDetailResp sourceTrace = null;
		String sourceMessage = null;
		if (includeSource && result.getSessionId() != null && StringUtils.hasText(result.getRuntimeRequestId())) {
			sourceTrace = turnService.getTurnDetail(result.getSessionId(), result.getRuntimeRequestId());
		}
		else if (!includeSource) {
			sourceMessage = "无源数据查看权限";
		}
		return EvalResultTraceDTO.builder()
			.result(result)
			.traceSnapshotJson(result.getTraceSnapshotJson())
			.sourceTrace(sourceTrace)
			.sourceVisible(includeSource)
			.sourceMessage(sourceMessage)
			.build();
	}

	private void submitRun(Long runId) {
		try {
			evaluationExecutor.execute(() -> runQueuedRun(runId));
		}
		catch (RejectedExecutionException ex) {
			log.warn("Agent evaluation run rejected by the executor, marking it failed. runId={}", runId, ex);
			markRunFailed(runId, AgentEvaluationErrorDict.EVALUATION_QUEUE_FULL.getLabel());
		}
	}

	private void runQueuedRun(Long runId) {
		try {
			DataAgentEvalRun run = runMapper.findActiveById(runId);
			if (run == null) {
				return;
			}
			if (STATUS_CANCELLED.equals(run.getStatus())) {
				insertCancelledResultsForRun(run);
				return;
			}
			if (!STATUS_QUEUED.equals(run.getStatus())) {
				return;
			}
			Instant now = Instant.now();
			run.setStatus(STATUS_RUNNING);
			run.setStartedAt(now);
			run.setLastModifyTime(now);
			runMapper.updateById(run);
			DataAgentEvalSubject subject = requireRunSubject(run);
			List<DataAgentEvalCase> cases = caseMapper.findEnabledBySuiteId(run.getSuiteId())
				.stream()
				.filter(evalCase -> sameTenant(evalCase.getTenantId(), run.getTenantId()))
				.toList();
			if (cases.isEmpty()) {
				markRunFailed(runId, AgentEvaluationErrorDict.CASE_NOT_FOUND.getLabel());
				return;
			}
			runCases(run, subject, cases, buildPolicySnapshot(run));
		}
		catch (RuntimeException ex) {
			log.warn("Agent evaluation run failed. runId={}", runId, ex);
			markRunFailedUnlessCancelled(runId, ex.getMessage());
		}
	}

	private void runCases(DataAgentEvalRun run, DataAgentEvalSubject subject, List<DataAgentEvalCase> cases,
			PolicySnapshot policySnapshot) {
		AgentEvalAdapter adapter = resolveAdapter(subject);
		for (DataAgentEvalCase evalCase : cases) {
			DataAgentEvalRun latest = runMapper.selectById(run.getId());
			if (latest == null) {
				return;
			}
			if (STATUS_CANCELLED.equals(latest.getStatus())) {
				insertCancelledResult(latest, evalCase);
				continue;
			}
			executeCase(latest, subject, evalCase, adapter, policySnapshot);
			refreshRunCounts(run.getId(), false);
		}
		finishRunFromResults(run.getId(), true);
	}

	private DataAgentEvalCaseResult executeCase(DataAgentEvalRun run, DataAgentEvalSubject subject,
			DataAgentEvalCase evalCase, AgentEvalAdapter adapter, PolicySnapshot policySnapshot) {
		Instant startedAt = Instant.now();
		Duration timeout = resolveTimeout(evalCase, policySnapshot);
		boolean dryRun = ExecutionIntentContext.INTENT_DRY_RUN.equals(run.getExecutionIntent());
		ExecutionIntentContext.ViolationCollector collector = dryRun
				? new ExecutionIntentContext.ViolationCollector() : null;
		String scopeKey = dryRun
				? "eval-dryrun-" + run.getId() + "-" + evalCase.getId() + "-" + UUID.randomUUID() : null;
		EvalInvocationPrepared prepared = new EvalInvocationPrepared(subject, evalCase, run, timeout, scopeKey);
		// DRY_RUN 用例在执行意图作用域内运行：写路径关口据此拦截写能力/外部副作用并把违规回投采集器。
		EvalInvocationResult invocation = dryRun
				? ExecutionIntentContext.supplyDryRun(scopeKey, collector, () -> adapter.invoke(prepared))
				: adapter.invoke(prepared);
		Instant finishedAt = Instant.now();
		DataAgentEvalRun latest = runMapper.selectById(run.getId());
		if (latest != null && STATUS_CANCELLED.equals(latest.getStatus())) {
			return insertCancelledResult(latest, evalCase, subject.getId(), startedAt, finishedAt, invocation);
		}
		ScoreResult score = score(evalCase, invocation, policySnapshot, timeout);
		score = applyCodeOracle(evalCase, invocation, score);
		if (collector != null && collector.hasViolations()) {
			appendViolationFailureReasons(score, collector);
		}
		DataAgentEvalCaseResult result = DataAgentEvalCaseResult.builder()
			.tenantId(run.getTenantId())
			.tenantCode(run.getTenantCode())
			.runId(run.getId())
			.suiteId(run.getSuiteId())
			.caseId(evalCase.getId())
			.subjectId(subject.getId())
			.attemptNo(nextAttemptNo(run.getId(), evalCase.getId()))
			.sessionId(invocation.sessionId())
			.threadId(invocation.threadId())
			.runtimeRequestId(invocation.runtimeRequestId())
			.status(score.timeout() ? STATUS_TIMEOUT : score.passed() ? STATUS_SUCCESS : STATUS_FAILED)
			.userInput(evalCase.getUserInput())
			.agentOutput(invocation.answer())
			.expectedOutput(evalCase.getExpectedOutput())
			.score(score.totalScore())
			.qualityScore(score.qualityScore())
			.safetyScore(score.safetyScore())
			.efficiencyScore(score.efficiencyScore())
			.stabilityScore(score.stabilityScore())
			.hardFail(score.hardFail())
			.writeViolationCount(collector == null ? 0 : collector.writeViolationCount())
			.isolationViolationCount(collector == null ? 0 : collector.isolationViolationCount())
			.violationDetailJson(collector == null || !collector.hasViolations() ? null
					: writeJson(collector.toDetailList()))
			.scoreDetailJson(writeJson(score.detail()))
			.failureReasonsJson(writeJson(score.failureReasons()))
			.efficiencyMetricsJson(writeJson(buildEfficiencyMetrics(invocation, timeout)))
			.traceSnapshotJson(writeJson(buildResultTraceSummary(invocation, score)))
			.startedAt(startedAt)
			.finishedAt(finishedAt)
			.durationMs(firstNonNull(invocation.durationMs(), Duration.between(startedAt, finishedAt).toMillis()))
			.errorMessage(invocation.errorMessage())
			.createTime(startedAt)
			.lastModifyTime(finishedAt)
			.deleted(false)
			.build();
		resultMapper.insert(result);
		return result;
	}

	private DataAgentEvalCaseResult insertCancelledResult(DataAgentEvalRun run, DataAgentEvalCase evalCase) {
		Instant now = Instant.now();
		return insertCancelledResult(run, evalCase, run.getSubjectId(), now, now, null);
	}

	private DataAgentEvalCaseResult insertCancelledResult(DataAgentEvalRun run, DataAgentEvalCase evalCase, Long subjectId,
			Instant startedAt, Instant finishedAt, EvalInvocationResult invocation) {
		DataAgentEvalCaseResult result = DataAgentEvalCaseResult.builder()
			.tenantId(run.getTenantId())
			.tenantCode(run.getTenantCode())
			.runId(run.getId())
			.suiteId(run.getSuiteId())
			.caseId(evalCase.getId())
			.subjectId(subjectId)
			.attemptNo(nextAttemptNo(run.getId(), evalCase.getId()))
			.sessionId(invocation == null ? null : invocation.sessionId())
			.threadId(invocation == null ? null : invocation.threadId())
			.runtimeRequestId(invocation == null ? null : invocation.runtimeRequestId())
			.status(STATUS_CANCELLED)
			.userInput(evalCase.getUserInput())
			.agentOutput(invocation == null ? null : invocation.answer())
			.expectedOutput(evalCase.getExpectedOutput())
			.hardFail(false)
			.failureReasonsJson(writeJson(List.of(reason(EvalFailureReasonDict.RUN_CANCELLED))))
			.startedAt(startedAt)
			.finishedAt(finishedAt)
			.durationMs(Duration.between(startedAt, finishedAt).toMillis())
			.errorMessage(invocation == null ? null : invocation.errorMessage())
			.createTime(startedAt)
			.lastModifyTime(finishedAt)
			.deleted(false)
			.build();
		resultMapper.insert(result);
		return result;
	}

	private ScoreResult applyCodeOracle(DataAgentEvalCase evalCase, EvalInvocationResult invocation, ScoreResult score) {
		String spec = resolveCodeOracleJson(evalCase);
		if (!StringUtils.hasText(spec)) {
			return score;
		}
		EvalCodeOracleService oracle = evalCodeOracleProvider == null ? null : evalCodeOracleProvider.getIfAvailable();
		EvalCodeOracleService.Verdict verdict = oracle == null
				? EvalCodeOracleService.Verdict.unavailable("代码执行池未注入，代码类用例不能计为通过")
				: oracle.evaluate(invocation == null ? null : invocation.answer(), spec);
		if (!verdict.applicable() || verdict.passed()) {
			if (verdict.applicable() && verdict.passed()) {
				Map<String, Object> detail = new LinkedHashMap<>(score.detail());
				detail.put("codeOracle", "PASSED");
				return new ScoreResult(score.passed(), score.hardFail(), score.timeout(), score.totalScore(),
						score.qualityScore(), score.safetyScore(), score.efficiencyScore(), score.stabilityScore(),
						detail, score.failureReasons());
			}
			return score;
		}
		List<Map<String, Object>> reasons = new ArrayList<>(score.failureReasons());
		reasons.add(reason(verdict.reason(), verdict.detail()));
		Map<String, Object> detail = new LinkedHashMap<>(score.detail());
		detail.put("codeOracle", verdict.reason() == null ? "FAILED" : verdict.reason().getValue());
		detail.put("codeOracleDetail", verdict.detail());
		return new ScoreResult(false, true, score.timeout(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
				score.efficiencyScore(), BigDecimal.ZERO, detail, reasons);
	}

	private String resolveCodeOracleJson(DataAgentEvalCase evalCase) {
		if (evalCase == null) {
			return null;
		}
		if (StringUtils.hasText(evalCase.getCodeOracleJson())) {
			return evalCase.getCodeOracleJson();
		}
		if (!StringUtils.hasText(evalCase.getTraceSnapshotJson())) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(evalCase.getTraceSnapshotJson());
			JsonNode oracle = node == null ? null : node.get("codeOracle");
			return oracle == null || oracle.isNull() ? null : oracle.toString();
		}
		catch (Exception ex) {
			return null;
		}
	}

	/** DRY_RUN 违规写入用例失败原因，保证违规在结果面板可见，而不是只藏在计数列里。 */
	private void appendViolationFailureReasons(ScoreResult score, ExecutionIntentContext.ViolationCollector collector) {
		if (collector.writeViolationCount() > 0) {
			score.failureReasons().add(reason(EvalFailureReasonDict.DRY_RUN_WRITE_BLOCKED));
		}
		if (collector.isolationViolationCount() > 0) {
			score.failureReasons().add(reason(EvalFailureReasonDict.DRY_RUN_ISOLATION_VIOLATION));
		}
	}

	private void refreshRunCounts(Long runId, boolean finished) {
		DataAgentEvalRun latest = runMapper.selectById(runId);
		if (latest == null) {
			return;
		}
		List<DataAgentEvalCaseResult> results = resultMapper.findByRunId(runId);
		long success = results.stream().filter(result -> STATUS_SUCCESS.equals(result.getStatus())).count();
		long failed = results.stream()
			.filter(result -> STATUS_FAILED.equals(result.getStatus()) || STATUS_TIMEOUT.equals(result.getStatus()))
			.count();
		long timeout = results.stream().filter(result -> STATUS_TIMEOUT.equals(result.getStatus())).count();
		long hardFail = results.stream().filter(result -> Boolean.TRUE.equals(result.getHardFail())).count();
		long writeViolations = results.stream().mapToLong(result -> safeInt(result.getWriteViolationCount())).sum();
		long isolationViolations = results.stream()
			.mapToLong(result -> safeInt(result.getIsolationViolationCount()))
			.sum();
		List<BigDecimal> scores = results.stream()
			.map(DataAgentEvalCaseResult::getScore)
			.filter(Objects::nonNull)
			.toList();
		Instant now = Instant.now();
		latest.setSuccessCount((int) success);
		latest.setFailedCount((int) failed);
		latest.setHardFailCount((int) hardFail);
		latest.setWriteViolationCount((int) writeViolations);
		latest.setIsolationViolationCount((int) isolationViolations);
		latest.setAverageScore(scores.isEmpty() ? BigDecimal.ZERO
				: scores.stream()
					.reduce(BigDecimal.ZERO, BigDecimal::add)
					.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
		if (finished) {
			if (STATUS_CANCELLED.equals(latest.getStatus())) {
				latest.setStatus(STATUS_CANCELLED);
			}
			else if (timeout > 0) {
				latest.setStatus(STATUS_TIMEOUT);
			}
			else if (failed > 0) {
				latest.setStatus(STATUS_FAILED);
			}
			else {
				latest.setStatus(STATUS_SUCCESS);
			}
			latest.setFinishedAt(now);
		}
		latest.setLastModifyTime(now);
		runMapper.updateById(latest);
	}

	private void finishRunFromResults(Long runId, boolean finished) {
		refreshRunCounts(runId, finished);
	}

	private ScoreResult score(DataAgentEvalCase evalCase, EvalInvocationResult invocation, PolicySnapshot policySnapshot,
			Duration timeout) {
		boolean invocationSuccess = invocation.success();
		boolean timeoutFailure = isTimeout(invocation, timeout);
		boolean hardFail = !invocationSuccess;
		BigDecimal quality = qualityScore(evalCase.getExpectedOutput(), invocation.answer(), invocationSuccess);
		BigDecimal safety = hardFail ? BigDecimal.ZERO : BigDecimal.valueOf(100);
		BigDecimal efficiency = efficiencyScore(invocation.durationMs(), timeout);
		BigDecimal stability = invocationSuccess ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
		BigDecimal total = weightedScore(quality, safety, efficiency, stability, policySnapshot.weights());
		boolean passed = !hardFail && quality.compareTo(BigDecimal.valueOf(60)) >= 0;
		List<Map<String, Object>> failureReasons = new ArrayList<>();
		if (timeoutFailure) {
			failureReasons.add(reason(EvalFailureReasonDict.AGENT_INVOKE_TIMEOUT));
		}
		else if (!invocationSuccess) {
			failureReasons.add(reason(EvalFailureReasonDict.AGENT_INVOKE_FAILED));
		}
		if (quality.compareTo(BigDecimal.valueOf(60)) < 0) {
			failureReasons.add(reason(EvalFailureReasonDict.EXPECTED_OUTPUT_NOT_MATCHED));
		}
		if (safeInt(invocation.toolFailCount()) > 0) {
			failureReasons.add(reason(EvalFailureReasonDict.TOOL_TRACE_FAILED));
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("qualityScore", quality);
		detail.put("safetyScore", safety);
		detail.put("efficiencyScore", efficiency);
		detail.put("stabilityScore", stability);
		detail.put("weights", policySnapshot.weights());
		detail.put("usageAvailable", invocation.totalTokens() != null);
		return new ScoreResult(passed, hardFail, timeoutFailure, total, quality, safety, efficiency, stability, detail,
				failureReasons);
	}

	private BigDecimal qualityScore(String expectedOutput, String answer, boolean invocationSuccess) {
		if (!invocationSuccess) {
			return BigDecimal.ZERO;
		}
		if (!StringUtils.hasText(expectedOutput)) {
			return BigDecimal.valueOf(100);
		}
		if (StringUtils.hasText(answer) && answer.contains(expectedOutput.trim())) {
			return BigDecimal.valueOf(100);
		}
		return BigDecimal.valueOf(50);
	}

	private BigDecimal efficiencyScore(Long durationMs, Duration timeout) {
		long actual = safeLong(durationMs);
		long budget = Math.max(1L, timeout.toMillis());
		if (actual <= budget) {
			return BigDecimal.valueOf(100);
		}
		double overRatio = (actual - budget) / (double) budget;
		return BigDecimal.valueOf(Math.max(0D, 100D - overRatio * 100D)).setScale(2, RoundingMode.HALF_UP);
	}

	private BigDecimal weightedScore(BigDecimal quality, BigDecimal safety, BigDecimal efficiency, BigDecimal stability,
			ScoreWeights weights) {
		BigDecimal totalWeight = weights.quality()
			.add(weights.safety())
			.add(weights.efficiency())
			.add(weights.stability());
		if (totalWeight.compareTo(BigDecimal.ZERO) <= 0) {
			totalWeight = BigDecimal.valueOf(100);
		}
		return quality.multiply(weights.quality())
			.add(safety.multiply(weights.safety()))
			.add(efficiency.multiply(weights.efficiency()))
			.add(stability.multiply(weights.stability()))
			.divide(totalWeight, 2, RoundingMode.HALF_UP);
	}

	private Duration resolveTimeout(DataAgentEvalCase evalCase, PolicySnapshot policySnapshot) {
		Long caseBudget = readLong(evalCase.getEfficiencyBudgetJson(), "maxDurationMs");
		Long policyBudget = readLong(policySnapshot.efficiencyBudgetJson(), "maxDurationMs");
		long timeoutMs = firstPositive(caseBudget, policyBudget, DEFAULT_MAX_DURATION_MS);
		return Duration.ofMillis(timeoutMs);
	}

	private Map<String, Object> buildEfficiencyMetrics(EvalInvocationResult invocation, Duration timeout) {
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("durationMs", safeLong(invocation.durationMs()));
		metrics.put("maxDurationMs", timeout.toMillis());
		metrics.put("usageAvailable", invocation.totalTokens() != null);
		metrics.put("promptTokens", invocation.promptTokens());
		metrics.put("completionTokens", invocation.completionTokens());
		metrics.put("totalTokens", invocation.totalTokens());
		metrics.put("toolCount", safeInt(invocation.toolCount()));
		metrics.put("toolFailCount", safeInt(invocation.toolFailCount()));
		return metrics;
	}

	private Map<String, Object> buildResultTraceSummary(EvalInvocationResult invocation, ScoreResult score) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("sessionId", invocation.sessionId());
		summary.put("threadId", invocation.threadId());
		summary.put("runtimeRequestId", invocation.runtimeRequestId());
		summary.put("durationMs", invocation.durationMs());
		summary.put("toolCount", invocation.toolCount());
		summary.put("toolFailCount", invocation.toolFailCount());
		summary.put("usageAvailable", invocation.totalTokens() != null);
		summary.put("totalTokens", invocation.totalTokens());
		summary.put("timeout", score.timeout());
		summary.put("hardFail", score.hardFail());
		return summary;
	}

	private Map<String, Object> buildTraceSummary(DataChatTurn turn) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("sessionId", turn.getSessionId());
		summary.put("threadId", turn.getThreadId());
		summary.put("runtimeRequestId", turn.getRuntimeRequestId());
		summary.put("agentId", turn.getAgentId());
		summary.put("status", turn.getStatus());
		summary.put("durationMs", turn.getDurationMs());
		summary.put("promptTokens", turn.getPromptTokens());
		summary.put("completionTokens", turn.getCompletionTokens());
		summary.put("totalTokens", turn.getTotalTokens());
		summary.put("toolCount", turn.getToolCount());
		summary.put("toolFailCount", turn.getToolFailCount());
		summary.put("hasDatasource", turn.getHasDatasource());
		summary.put("hasSql", turn.getHasSql());
		return summary;
	}

	private List<DataAgentEvalCaseResult> tenantCaseResults(Long runId) {
		return resultMapper.findByRunId(runId).stream().filter(this::sameTenant).toList();
	}

	private EvalFailureClusterDTO toFailureCluster(DataAgentEvalRun run, List<DataAgentEvalCaseResult> results) {
		List<Map<String, Object>> reasons = results.stream()
			.flatMap(result -> readList(result.getFailureReasonsJson()).stream())
			.collect(Collectors.groupingBy(reason -> defaultText(String.valueOf(reason.get("reasonCode")), "UNKNOWN"),
					LinkedHashMap::new, Collectors.counting()))
			.entrySet()
			.stream()
			.map(entry -> Map.<String, Object>of("reasonCode", entry.getKey(), "count", entry.getValue()))
			.toList();
		int failedCount = (int) results.stream()
			.filter(result -> STATUS_FAILED.equals(result.getStatus()) || STATUS_TIMEOUT.equals(result.getStatus()))
			.count();
		int hardFailCount = (int) results.stream().filter(result -> Boolean.TRUE.equals(result.getHardFail())).count();
		return EvalFailureClusterDTO.builder()
			.runId(run.getId())
			.failedCount(failedCount)
			.hardFailCount(hardFailCount)
			.failureReasons(reasons)
			.slowCases(buildSlowReasons(run, results))
			.build();
	}

	private List<Map<String, Object>> buildSlowReasons(DataAgentEvalRun run, List<DataAgentEvalCaseResult> results) {
		List<Map<String, Object>> reasons = new ArrayList<>();
		for (DataAgentEvalCaseResult result : results) {
			Map<String, Object> metrics = readMap(result.getEfficiencyMetricsJson());
			long durationMs = safeLong(result.getDurationMs());
			long maxDurationMs = firstPositive(mapLong(metrics, "maxDurationMs"), null, DEFAULT_MAX_DURATION_MS);
			if (STATUS_TIMEOUT.equals(result.getStatus()) || durationMs > maxDurationMs) {
				reasons.add(slowReason(result, EvalSlowReasonDict.MODEL_CALL_SLOW, durationMs, maxDurationMs));
			}
			Long totalTokens = mapLong(metrics, "totalTokens");
			if (totalTokens != null && totalTokens > DEFAULT_TOKEN_BUDGET) {
				reasons.add(slowReason(result, EvalSlowReasonDict.TOKEN_TOO_HIGH, totalTokens, DEFAULT_TOKEN_BUDGET));
			}
			int toolCount = safeInt(mapInt(metrics, "toolCount"));
			if (toolCount > 10) {
				reasons.add(slowReason(result, EvalSlowReasonDict.TOOL_TOO_MANY, toolCount, 10));
			}
		}
		if (reasons.isEmpty() && STATUS_TIMEOUT.equals(run.getStatus())) {
			reasons.add(Map.of("reasonCode", EvalSlowReasonDict.ORCHESTRATION_SLOW.getValue(), "reasonText",
					EvalSlowReasonDict.ORCHESTRATION_SLOW.getLabel()));
		}
		return reasons;
	}

	private Map<String, Object> slowReason(DataAgentEvalCaseResult result, EvalSlowReasonDict reason, long actual,
			long budget) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("resultId", result.getId());
		value.put("caseId", result.getCaseId());
		value.put("reasonCode", reason.getValue());
		value.put("reasonText", reason.getLabel());
		value.put("actual", actual);
		value.put("budget", budget);
		return value;
	}

	private Long percentile(List<Long> sortedValues, double percentile) {
		if (sortedValues == null || sortedValues.isEmpty()) {
			return 0L;
		}
		int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
		return sortedValues.get(Math.min(index, sortedValues.size() - 1));
	}

	private boolean isTimeout(EvalInvocationResult invocation, Duration timeout) {
		String errorMessage = invocation.errorMessage();
		if (!invocation.success() && StringUtils.hasText(errorMessage)) {
			String lower = errorMessage.toLowerCase();
			if (lower.contains("timeout") || errorMessage.contains("超时")) {
				return true;
			}
		}
		return !invocation.success() && invocation.durationMs() != null && invocation.durationMs() >= timeout.toMillis();
	}

	private Map<String, Object> reason(EvalFailureReasonDict reason) {
		return Map.of("reasonCode", reason.getValue(), "reasonText", reason.getLabel());
	}

	private Map<String, Object> reason(EvalFailureReasonDict reason, String detail) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("reasonCode", reason.getValue());
		item.put("reasonText", reason.getLabel());
		if (StringUtils.hasText(detail)) {
			item.put("detail", detail);
		}
		return item;
	}

	private PolicySnapshot buildPolicySnapshot(DataAgentEvalRun run) {
		Map<String, Object> snapshot = readMap(run.getPolicySnapshotJson());
		String weightsJson = stringValue(snapshot.get("scoreWeightsJson"));
		String efficiencyBudgetJson = stringValue(snapshot.get("efficiencyBudgetJson"));
		if (!StringUtils.hasText(efficiencyBudgetJson)) {
			efficiencyBudgetJson = writeJson(Map.of("maxDurationMs", DEFAULT_MAX_DURATION_MS));
		}
		return new PolicySnapshot(null, run.getPolicySnapshotJson(), run.getPolicyHash(), parseWeights(weightsJson),
				efficiencyBudgetJson);
	}

	private PolicySnapshot buildPolicySnapshot(DataAgentEvalPolicy policy) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		if (policy != null) {
			snapshot.put("policyId", policy.getId());
			snapshot.put("policyCode", policy.getPolicyCode());
			snapshot.put("policyName", policy.getPolicyName());
			snapshot.put("versionNo", policy.getVersionNo());
			snapshot.put("scoreWeightsJson", policy.getScoreWeightsJson());
			snapshot.put("graderConfigJson", policy.getGraderConfigJson());
			snapshot.put("efficiencyBudgetJson", policy.getEfficiencyBudgetJson());
			snapshot.put("gateRuleJson", policy.getGateRuleJson());
			snapshot.put("hardFailRuleJson", policy.getHardFailRuleJson());
		}
		else {
			snapshot.put("policyCode", "CODE_DEFAULT");
			snapshot.put("policyName", "代码默认评估策略");
			snapshot.put("versionNo", 1);
		}
		String weightsJson = policy == null ? null : policy.getScoreWeightsJson();
		String efficiencyBudgetJson = policy == null ? null : policy.getEfficiencyBudgetJson();
		ScoreWeights weights = parseWeights(weightsJson);
		if (!StringUtils.hasText(efficiencyBudgetJson)) {
			efficiencyBudgetJson = writeJson(Map.of("maxDurationMs", DEFAULT_MAX_DURATION_MS));
			snapshot.put("efficiencyBudgetJson", efficiencyBudgetJson);
		}
		if (!StringUtils.hasText(weightsJson)) {
			snapshot.put("scoreWeightsJson", writeJson(Map.of(WEIGHT_KEY_QUALITY, DEFAULT_QUALITY_WEIGHT,
					WEIGHT_KEY_SAFETY, DEFAULT_SAFETY_WEIGHT, WEIGHT_KEY_EFFICIENCY, DEFAULT_EFFICIENCY_WEIGHT,
					WEIGHT_KEY_STABILITY, DEFAULT_STABILITY_WEIGHT)));
		}
		String json = writeJson(snapshot);
		return new PolicySnapshot(policy, json, sha256(json), weights, efficiencyBudgetJson);
	}

	private ScoreWeights parseWeights(String json) {
		Map<String, Object> values = readMap(json);
		return new ScoreWeights(readDecimal(values, WEIGHT_KEY_QUALITY, DEFAULT_QUALITY_WEIGHT),
				readDecimal(values, WEIGHT_KEY_SAFETY, DEFAULT_SAFETY_WEIGHT),
				readDecimal(values, WEIGHT_KEY_EFFICIENCY, DEFAULT_EFFICIENCY_WEIGHT),
				readDecimal(values, WEIGHT_KEY_STABILITY, DEFAULT_STABILITY_WEIGHT));
	}

	private DataAgentEvalPolicy resolvePolicy(Long requestPolicyId, Long suitePolicyId, String subjectType) {
		Long policyId = firstNonNull(requestPolicyId, suitePolicyId);
		if (policyId != null) {
			return requireTenantPolicy(policyId, true);
		}
		DataAgentEvalPolicy tenantDefaultPolicy = policyMapper.findDefault(currentTenantId(), subjectType);
		if (tenantDefaultPolicy != null) {
			return tenantDefaultPolicy;
		}
		throw CheckedException.notFound("当前租户没有默认评估策略");
	}

	private EvalBootstrapResult bootstrapDefaultsInTransaction(DataAgent agent, EvalBootstrapRequest request) {
		Instant now = Instant.now();
		BootstrapUpsert<DataAgentEvalPolicy> policyUpsert = upsertDefaultPolicy(now);
		BootstrapUpsert<DataAgentEvalSubject> subjectUpsert = upsertDefaultSubject(agent, now);
		BootstrapUpsert<DataAgentEvalSuite> suiteUpsert = upsertDefaultSuite(agent, policyUpsert.entity(), subjectUpsert.entity(), now);
		BootstrapUpsert<DataAgentEvalCase> caseUpsert = upsertDefaultCase(suiteUpsert.entity(), request, now);
		return EvalBootstrapResult.builder()
			.policyId(policyUpsert.entity() == null ? null : policyUpsert.entity().getId())
			.subjectId(subjectUpsert.entity() == null ? null : subjectUpsert.entity().getId())
			.suiteId(suiteUpsert.entity() == null ? null : suiteUpsert.entity().getId())
			.caseId(caseUpsert.entity() == null ? null : caseUpsert.entity().getId())
			.policy(itemStatus(policyUpsert))
			.subject(itemStatus(subjectUpsert))
			.suite(itemStatus(suiteUpsert))
			.evalCase(itemStatus(caseUpsert))
			.build();
	}

	private BootstrapUpsert<DataAgentEvalPolicy> upsertDefaultPolicy(Instant now) {
		DataAgentEvalPolicy policy = policyMapper.findByCode(currentTenantId(), DEFAULT_POLICY_CODE);
		DataAgentEvalPolicy desired = new DataAgentEvalPolicy();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setPolicyCode(DEFAULT_POLICY_CODE);
		desired.setPolicyName("DataAgent 默认评估策略");
		desired.setVersionNo(1);
		desired.setStatus(STATUS_ENABLED);
		desired.setDefaultFlag(true);
		desired.setSubjectType(DEFAULT_SUBJECT_TYPE);
		desired.setScoreWeightsJson(writeJson(Map.of(WEIGHT_KEY_QUALITY, DEFAULT_QUALITY_WEIGHT,
				WEIGHT_KEY_SAFETY, DEFAULT_SAFETY_WEIGHT, WEIGHT_KEY_EFFICIENCY, DEFAULT_EFFICIENCY_WEIGHT,
				WEIGHT_KEY_STABILITY, DEFAULT_STABILITY_WEIGHT)));
		desired.setGraderConfigJson(writeJson(Map.of()));
		desired.setEfficiencyBudgetJson(writeJson(Map.of("maxDurationMs", DEFAULT_MAX_DURATION_MS)));
		desired.setGateRuleJson(writeJson(Map.of()));
		desired.setHardFailRuleJson(writeJson(Map.of()));
		desired.setDescription("评估中心默认策略");
		return upsertPolicy(policy, desired, now);
	}

	private BootstrapUpsert<DataAgentEvalSubject> upsertDefaultSubject(DataAgent agent, Instant now) {
		String subjectId = String.valueOf(agent.getId());
		DataAgentEvalSubject subject = subjectMapper.findByIdentity(currentTenantId(), DEFAULT_SUBJECT_TYPE, subjectId,
				DEFAULT_ADAPTER_CODE);
		DataAgentEvalSubject desired = new DataAgentEvalSubject();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setSubjectName(defaultText(agent.getName(), subjectId) + " 评估对象");
		desired.setSubjectType(DEFAULT_SUBJECT_TYPE);
		desired.setSubjectId(subjectId);
		desired.setAdapterCode(DEFAULT_ADAPTER_CODE);
		desired.setStatus(STATUS_ENABLED);
		desired.setDescription("DataAgent 评估对象");
		return upsertSubject(subject, desired, now);
	}

	private BootstrapUpsert<DataAgentEvalSuite> upsertDefaultSuite(DataAgent agent, DataAgentEvalPolicy policy,
			DataAgentEvalSubject subject, Instant now) {
		String suiteName = defaultText(agent.getName(), String.valueOf(agent.getId())) + " 默认评估集";
		DataAgentEvalSuite suite = suiteMapper.findBySubjectAndName(currentTenantId(), subject.getId(), suiteName);
		DataAgentEvalSuite desired = new DataAgentEvalSuite();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setSuiteName(suiteName);
		desired.setSubjectId(subject.getId());
		desired.setPolicyId(policy == null ? null : policy.getId());
		desired.setStatus(STATUS_ENABLED);
		desired.setDescription("DataAgent 默认评估集");
		return upsertSuite(suite, desired, now);
	}

	private EvalBootstrapResult bootstrapEmployeeDefaultsInTransaction(DigitalEmployee employee,
			EvalBootstrapRequest request) {
		Instant now = Instant.now();
		BootstrapUpsert<DataAgentEvalPolicy> policyUpsert = upsertDefaultEmployeePolicy(now);
		BootstrapUpsert<DataAgentEvalSubject> subjectUpsert = upsertDefaultEmployeeSubject(employee, now);
		BootstrapUpsert<DataAgentEvalSuite> suiteUpsert = upsertDefaultEmployeeSuite(employee, policyUpsert.entity(),
				subjectUpsert.entity(), now);
		BootstrapUpsert<DataAgentEvalCase> caseUpsert = upsertDefaultCase(suiteUpsert.entity(), request, now,
				employeeSmokeTags(employee));
		return EvalBootstrapResult.builder()
			.policyId(policyUpsert.entity() == null ? null : policyUpsert.entity().getId())
			.subjectId(subjectUpsert.entity() == null ? null : subjectUpsert.entity().getId())
			.suiteId(suiteUpsert.entity() == null ? null : suiteUpsert.entity().getId())
			.caseId(caseUpsert.entity() == null ? null : caseUpsert.entity().getId())
			.policy(itemStatus(policyUpsert))
			.subject(itemStatus(subjectUpsert))
			.suite(itemStatus(suiteUpsert))
			.evalCase(itemStatus(caseUpsert))
			.build();
	}

	private BootstrapUpsert<DataAgentEvalPolicy> upsertDefaultEmployeePolicy(Instant now) {
		DataAgentEvalPolicy policy = policyMapper.findByCode(currentTenantId(), EMPLOYEE_DEFAULT_POLICY_CODE);
		DataAgentEvalPolicy desired = new DataAgentEvalPolicy();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setPolicyCode(EMPLOYEE_DEFAULT_POLICY_CODE);
		desired.setPolicyName("数字员工默认评估策略");
		desired.setVersionNo(1);
		desired.setStatus(STATUS_ENABLED);
		desired.setDefaultFlag(true);
		desired.setSubjectType(EMPLOYEE_SUBJECT_TYPE);
		desired.setScoreWeightsJson(writeJson(Map.of(WEIGHT_KEY_QUALITY, DEFAULT_QUALITY_WEIGHT,
				WEIGHT_KEY_SAFETY, DEFAULT_SAFETY_WEIGHT, WEIGHT_KEY_EFFICIENCY, DEFAULT_EFFICIENCY_WEIGHT,
				WEIGHT_KEY_STABILITY, DEFAULT_STABILITY_WEIGHT)));
		desired.setGraderConfigJson(writeJson(Map.of()));
		desired.setEfficiencyBudgetJson(writeJson(Map.of("maxDurationMs", DEFAULT_MAX_DURATION_MS)));
		desired.setGateRuleJson(writeJson(Map.of()));
		desired.setHardFailRuleJson(writeJson(Map.of()));
		desired.setDescription("数字员工评估中心默认策略");
		return upsertPolicy(policy, desired, now);
	}

	private BootstrapUpsert<DataAgentEvalSubject> upsertDefaultEmployeeSubject(DigitalEmployee employee, Instant now) {
		String subjectId = String.valueOf(employee.getId());
		DataAgentEvalSubject subject = subjectMapper.findByIdentity(currentTenantId(), EMPLOYEE_SUBJECT_TYPE, subjectId,
				EMPLOYEE_ADAPTER_CODE);
		DataAgentEvalSubject desired = new DataAgentEvalSubject();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setSubjectName(defaultText(employee.getEmployeeName(), subjectId) + " 评估对象");
		desired.setSubjectType(EMPLOYEE_SUBJECT_TYPE);
		desired.setSubjectId(subjectId);
		desired.setAdapterCode(EMPLOYEE_ADAPTER_CODE);
		desired.setStatus(STATUS_ENABLED);
		desired.setDescription("数字员工候选评估对象（SANDBOX 真跑）");
		return upsertSubject(subject, desired, now);
	}

	private BootstrapUpsert<DataAgentEvalSuite> upsertDefaultEmployeeSuite(DigitalEmployee employee,
			DataAgentEvalPolicy policy, DataAgentEvalSubject subject, Instant now) {
		String suiteName = defaultText(employee.getEmployeeName(), String.valueOf(employee.getId())) + " 默认评估集";
		DataAgentEvalSuite suite = suiteMapper.findBySubjectAndName(currentTenantId(), subject.getId(), suiteName);
		DataAgentEvalSuite desired = new DataAgentEvalSuite();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setSuiteName(suiteName);
		desired.setSubjectId(subject.getId());
		desired.setPolicyId(policy == null ? null : policy.getId());
		desired.setStatus(STATUS_ENABLED);
		desired.setDescription("数字员工默认评估集");
		return upsertSuite(suite, desired, now);
	}

	private BootstrapUpsert<DataAgentEvalCase> upsertDefaultCase(DataAgentEvalSuite suite, EvalBootstrapRequest request,
			Instant now) {
		return upsertDefaultCase(suite, request, now, List.of("smoke"));
	}

	private BootstrapUpsert<DataAgentEvalCase> upsertDefaultCase(DataAgentEvalSuite suite, EvalBootstrapRequest request,
			Instant now, List<String> tags) {
		DataAgentEvalCase evalCase = caseMapper.findBySuiteAndName(currentTenantId(), suite.getId(), DEFAULT_SMOKE_CASE_NAME);
		DataAgentEvalCase desired = new DataAgentEvalCase();
		desired.setTenantId(currentTenantId());
		desired.setTenantCode(currentTenantCode());
		desired.setSuiteId(suite.getId());
		desired.setCaseName(DEFAULT_SMOKE_CASE_NAME);
		desired.setUserInput(defaultText(request == null ? null : request.getCaseUserInput(), DEFAULT_SMOKE_CASE_INPUT));
		desired.setExpectedOutput(normalize(request == null ? null : request.getExpectedOutput()));
		desired.setSafetyConstraintsJson(writeJson(Map.of()));
		desired.setEfficiencyBudgetJson(writeJson(Map.of()));
		desired.setTagsJson(writeJson(tags == null || tags.isEmpty() ? List.of("smoke") : tags));
		desired.setTraceSnapshotJson(writeJson(Map.of()));
		desired.setStatus(STATUS_ENABLED);
		return upsertCase(evalCase, desired, now);
	}

	private List<String> employeeSmokeTags(DigitalEmployee employee) {
		List<String> tags = new ArrayList<>();
		tags.add("smoke");
		String position = sanitizePosition(employee == null ? null : employee.getJobTitle());
		if (StringUtils.hasText(position)) {
			tags.add("position:" + position);
		}
		return List.copyOf(tags);
	}

	private String sanitizePosition(String jobTitle) {
		if (!StringUtils.hasText(jobTitle)) {
			return null;
		}
		String trimmed = jobTitle.trim().replace('\n', ' ').replace('\r', ' ');
		if (trimmed.length() > MAX_POSITION_CHARS) {
			return trimmed.substring(0, MAX_POSITION_CHARS);
		}
		return trimmed;
	}

	private DigitalEmployee requireEmployeeForBootstrap(Long employeeId) {
		if (employeeId == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(employeeId, currentTenantId());
		if (employee == null) {
			throw badRequest(AgentEvaluationErrorDict.EMPLOYEE_NOT_FOUND);
		}
		return employee;
	}

	private void ensureEmployeeSandbox(DigitalEmployee employee) {
		Long employeeId = employee.getId();
		DigitalEmployeeDeployment production = employeeDeploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.PRODUCTION.getValue());
		if (production == null || production.getActiveReleaseId() == null) {
			throw badRequest(AgentEvaluationErrorDict.EMPLOYEE_PRODUCTION_RELEASE_REQUIRED);
		}
		DigitalEmployeeDeployment sandbox = employeeDeploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.SANDBOX.getValue());
		if (sandbox != null && sandbox.getActiveReleaseId() != null) {
			return;
		}
		EmployeeDeploymentActivateReq request = new EmployeeDeploymentActivateReq();
		request.setReleaseId(production.getActiveReleaseId());
		request.setEnvironment(DeploymentEnvironmentDict.SANDBOX.getValue());
		int expectVersion = sandbox == null || sandbox.getDeploymentVersion() == null ? 0
				: sandbox.getDeploymentVersion();
		request.setExpectVersion(expectVersion);
		employeeDeploymentService.activate(employeeId, request);
		log.info("数字员工评估初始化已补齐 SANDBOX. employeeId={}, releaseId={}", employeeId, production.getActiveReleaseId());
	}

	private EvalBootstrapItemStatus itemStatus(BootstrapUpsert<?> upsert) {
		if (upsert == null || upsert.entity() == null) {
			return null;
		}
		return upsert.created() ? EvalBootstrapItemStatus.created(upsert.entity().getId())
				: EvalBootstrapItemStatus.reused(upsert.entity().getId());
	}

	private BootstrapUpsert<DataAgentEvalPolicy> upsertPolicy(DataAgentEvalPolicy existing, DataAgentEvalPolicy desired,
			Instant now) {
		if (existing == null) {
			desired.setCreateTime(now);
			desired.setLastModifyTime(now);
			desired.setDeleted(false);
			policyMapper.insert(desired);
			policyMapper.clearDefault(desired.getTenantId(), desired.getSubjectType(), desired.getId(), now);
			return new BootstrapUpsert<>(policyMapper.selectById(desired.getId()), true);
		}
		boolean changed = alignDefaultPolicy(existing, desired, now);
		if (changed) {
			policyMapper.updateById(existing);
		}
		if (Boolean.TRUE.equals(desired.getDefaultFlag())) {
			policyMapper.clearDefault(desired.getTenantId(), desired.getSubjectType(), existing.getId(), now);
		}
		return new BootstrapUpsert<>(policyMapper.selectById(existing.getId()), false);
	}

	private boolean alignDefaultPolicy(DataAgentEvalPolicy target, DataAgentEvalPolicy desired, Instant now) {
		boolean changed = false;
		changed |= setIfDifferent(() -> target.getTenantId(), target::setTenantId, desired.getTenantId());
		changed |= setIfDifferent(() -> target.getTenantCode(), target::setTenantCode, desired.getTenantCode());
		changed |= setIfDifferent(() -> target.getPolicyName(), target::setPolicyName, desired.getPolicyName());
		changed |= setIfDifferent(() -> target.getStatus(), target::setStatus, desired.getStatus());
		changed |= setIfDifferent(() -> target.getDefaultFlag(), target::setDefaultFlag, desired.getDefaultFlag());
		changed |= setIfDifferent(() -> target.getSubjectType(), target::setSubjectType, desired.getSubjectType());
		changed |= setIfDifferent(() -> target.getScoreWeightsJson(), target::setScoreWeightsJson,
				desired.getScoreWeightsJson());
		changed |= setIfDifferent(() -> target.getGraderConfigJson(), target::setGraderConfigJson,
				desired.getGraderConfigJson());
		changed |= setIfDifferent(() -> target.getEfficiencyBudgetJson(), target::setEfficiencyBudgetJson,
				desired.getEfficiencyBudgetJson());
		changed |= setIfDifferent(() -> target.getGateRuleJson(), target::setGateRuleJson, desired.getGateRuleJson());
		changed |= setIfDifferent(() -> target.getHardFailRuleJson(), target::setHardFailRuleJson,
				desired.getHardFailRuleJson());
		changed |= setIfDifferent(() -> target.getDescription(), target::setDescription, desired.getDescription());
		if (changed) {
			target.setVersionNo(value(target.getVersionNo()) + 1);
			target.setLastModifyTime(now);
		}
		return changed;
	}

	private BootstrapUpsert<DataAgentEvalSubject> upsertSubject(DataAgentEvalSubject existing, DataAgentEvalSubject desired,
			Instant now) {
		if (existing == null) {
			desired.setCreateTime(now);
			desired.setLastModifyTime(now);
			desired.setDeleted(false);
			subjectMapper.insert(desired);
			return new BootstrapUpsert<>(subjectMapper.selectById(desired.getId()), true);
		}
		boolean changed = false;
		changed |= setIfDifferent(() -> existing.getTenantId(), existing::setTenantId, desired.getTenantId());
		changed |= setIfDifferent(() -> existing.getTenantCode(), existing::setTenantCode, desired.getTenantCode());
		changed |= setIfDifferent(() -> existing.getSubjectName(), existing::setSubjectName, desired.getSubjectName());
		changed |= setIfDifferent(() -> existing.getSubjectType(), existing::setSubjectType, desired.getSubjectType());
		changed |= setIfDifferent(() -> existing.getSubjectId(), existing::setSubjectId, desired.getSubjectId());
		changed |= setIfDifferent(() -> existing.getAdapterCode(), existing::setAdapterCode, desired.getAdapterCode());
		changed |= setIfDifferent(() -> existing.getStatus(), existing::setStatus, desired.getStatus());
		changed |= setIfDifferent(() -> existing.getDescription(), existing::setDescription, desired.getDescription());
		if (changed) {
			existing.setLastModifyTime(now);
			subjectMapper.updateById(existing);
		}
		return new BootstrapUpsert<>(subjectMapper.selectById(existing.getId()), false);
	}

	private BootstrapUpsert<DataAgentEvalSuite> upsertSuite(DataAgentEvalSuite existing, DataAgentEvalSuite desired,
			Instant now) {
		if (existing == null) {
			desired.setCreateTime(now);
			desired.setLastModifyTime(now);
			desired.setDeleted(false);
			suiteMapper.insert(desired);
			return new BootstrapUpsert<>(suiteMapper.selectById(desired.getId()), true);
		}
		boolean changed = false;
		changed |= setIfDifferent(() -> existing.getTenantId(), existing::setTenantId, desired.getTenantId());
		changed |= setIfDifferent(() -> existing.getTenantCode(), existing::setTenantCode, desired.getTenantCode());
		changed |= setIfDifferent(() -> existing.getSuiteName(), existing::setSuiteName, desired.getSuiteName());
		changed |= setIfDifferent(() -> existing.getSubjectId(), existing::setSubjectId, desired.getSubjectId());
		changed |= setIfDifferent(() -> existing.getPolicyId(), existing::setPolicyId, desired.getPolicyId());
		changed |= setIfDifferent(() -> existing.getStatus(), existing::setStatus, desired.getStatus());
		changed |= setIfDifferent(() -> existing.getDescription(), existing::setDescription, desired.getDescription());
		if (changed) {
			existing.setLastModifyTime(now);
			suiteMapper.updateById(existing);
		}
		return new BootstrapUpsert<>(suiteMapper.selectById(existing.getId()), false);
	}

	private BootstrapUpsert<DataAgentEvalCase> upsertCase(DataAgentEvalCase existing, DataAgentEvalCase desired,
			Instant now) {
		if (existing == null) {
			desired.setCreateTime(now);
			desired.setLastModifyTime(now);
			desired.setDeleted(false);
			caseMapper.insert(desired);
			return new BootstrapUpsert<>(caseMapper.selectById(desired.getId()), true);
		}
		boolean changed = false;
		changed |= setIfDifferent(() -> existing.getTenantId(), existing::setTenantId, desired.getTenantId());
		changed |= setIfDifferent(() -> existing.getTenantCode(), existing::setTenantCode, desired.getTenantCode());
		changed |= setIfDifferent(() -> existing.getSuiteId(), existing::setSuiteId, desired.getSuiteId());
		changed |= setIfDifferent(() -> existing.getCaseName(), existing::setCaseName, desired.getCaseName());
		changed |= setIfDifferent(() -> existing.getUserInput(), existing::setUserInput, desired.getUserInput());
		changed |= setIfDifferent(() -> existing.getExpectedOutput(), existing::setExpectedOutput,
				desired.getExpectedOutput());
		changed |= setIfDifferent(() -> existing.getSafetyConstraintsJson(), existing::setSafetyConstraintsJson,
				desired.getSafetyConstraintsJson());
		changed |= setIfDifferent(() -> existing.getEfficiencyBudgetJson(), existing::setEfficiencyBudgetJson,
				desired.getEfficiencyBudgetJson());
		changed |= setIfDifferent(() -> existing.getTagsJson(), existing::setTagsJson, desired.getTagsJson());
		changed |= setIfDifferent(() -> existing.getTraceSnapshotJson(), existing::setTraceSnapshotJson,
				desired.getTraceSnapshotJson());
		changed |= setIfDifferent(() -> existing.getStatus(), existing::setStatus, desired.getStatus());
		if (changed) {
			existing.setLastModifyTime(now);
			caseMapper.updateById(existing);
		}
		return new BootstrapUpsert<>(caseMapper.selectById(existing.getId()), false);
	}

	private DataAgent resolveBootstrapAgent(Long agentId) {
		List<DataAgent> publishedAgents = dataAgentService.list(AgentStatusConstant.PUBLISHED, null);
		return publishedAgents.stream().filter(agent -> sameId(agent == null ? null : agent.getId(), agentId)).findFirst()
				.orElseThrow(() -> badRequest(AgentEvaluationErrorDict.AGENT_NOT_FOUND_OR_UNPUBLISHED));
	}

	private <T> boolean setIfDifferent(java.util.function.Supplier<T> currentSupplier,
			java.util.function.Consumer<T> setter, T desired) {
		T current = currentSupplier.get();
		if (Objects.equals(current, desired)) {
			return false;
		}
		setter.accept(desired);
		return true;
	}

	private boolean sameId(Long left, Long right) {
		return Objects.equals(left, right);
	}

	private AgentEvalAdapter resolveAdapter(DataAgentEvalSubject subject) {
		AgentEvalAdapter adapter = adapterRegistry.get(adapterKey(subject.getAdapterCode()));
		if (adapter == null || !adapter.supports(subject.getSubjectType())) {
			throw badRequest(AgentEvaluationErrorDict.ADAPTER_NOT_FOUND);
		}
		return adapter;
	}

	/**
	 * PR-7：校验数字员工 Release 存在且当前租户可访问（评测主体 subjectId = Release ID）。
	 * 只读消费 employee 域生命周期服务，不存在/跨租户时由其抛出（404 口径），非数字 subjectId 直接请求非法。
	 */
	private void requireDigitalEmployeeId(String subjectId) {
		try {
			Long.valueOf(subjectId.trim());
		}
		catch (NumberFormatException ex) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
	}

	private void requireDigitalEmployeeRelease(String subjectId) {
		Long releaseId;
		try {
			releaseId = Long.valueOf(subjectId.trim());
		}
		catch (NumberFormatException ex) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		employeeReleaseLifecycleService.getDetail(releaseId);
	}

	private Map<String, AgentEvalAdapter> buildAdapterRegistry() {
		Map<String, AgentEvalAdapter> registry = new LinkedHashMap<>();
		for (AgentEvalAdapter adapter : adapters == null ? List.<AgentEvalAdapter>of() : adapters) {
			String key = adapterKey(adapter.adapterCode());
			if (!StringUtils.hasText(key) || registry.putIfAbsent(key, adapter) != null) {
				throw badRequest(AgentEvaluationErrorDict.ADAPTER_CODE_DUPLICATED);
			}
		}
		return Map.copyOf(registry);
	}

	private String adapterKey(String adapterCode) {
		String normalized = normalize(adapterCode);
		return StringUtils.hasText(normalized) ? normalized.toUpperCase(Locale.ROOT) : null;
	}

	private DataAgentEvalSubject requireRunSubject(DataAgentEvalRun run) {
		DataAgentEvalSubject subject = subjectMapper.findEnabledById(run.getSubjectId());
		if (subject == null || !sameTenant(subject.getTenantId(), run.getTenantId())) {
			throw badRequest(AgentEvaluationErrorDict.SUBJECT_NOT_FOUND);
		}
		return subject;
	}

	private void insertCancelledResultsForRun(DataAgentEvalRun run) {
		List<DataAgentEvalCase> cases = caseMapper.findEnabledBySuiteId(run.getSuiteId())
			.stream()
			.filter(evalCase -> sameTenant(evalCase.getTenantId(), run.getTenantId()))
			.toList();
		for (DataAgentEvalCase evalCase : cases) {
			if (resultMapper.findMaxAttemptNo(run.getId(), evalCase.getId()) <= 0) {
				insertCancelledResult(run, evalCase);
			}
		}
		finishRunFromResults(run.getId(), true);
	}

	private void markRunFailedUnlessCancelled(Long runId, String errorMessage) {
		DataAgentEvalRun run = runMapper.selectById(runId);
		if (run != null && STATUS_CANCELLED.equals(run.getStatus())) {
			finishRunFromResults(runId, true);
			return;
		}
		markRunFailed(runId, errorMessage);
	}

	private void markRunFailed(Long runId, String errorMessage) {
		DataAgentEvalRun run = runMapper.selectById(runId);
		if (run == null || STATUS_CANCELLED.equals(run.getStatus())) {
			return;
		}
		Instant now = Instant.now();
		run.setStatus(STATUS_FAILED);
		run.setErrorMessage(errorMessage);
		run.setFinishedAt(now);
		run.setLastModifyTime(now);
		runMapper.updateById(run);
	}

	private Integer nextAttemptNo(Long runId, Long caseId) {
		return resultMapper.findMaxAttemptNo(runId, caseId) + 1;
	}

	private DataAgentEvalPolicy toPolicy(DataAgentEvalPolicy policy, EvalPolicyRequest request) {
		if (request == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		policy.setPolicyCode(normalize(request.getPolicyCode()));
		policy.setPolicyName(normalize(request.getPolicyName()));
		policy.setStatus(defaultText(request.getStatus(), STATUS_ENABLED));
		policy.setDefaultFlag(Boolean.TRUE.equals(request.getDefaultFlag()));
		policy.setSubjectType(normalize(request.getSubjectType()));
		policy.setScoreWeightsJson(request.getScoreWeightsJson());
		policy.setGraderConfigJson(request.getGraderConfigJson());
		policy.setEfficiencyBudgetJson(request.getEfficiencyBudgetJson());
		policy.setGateRuleJson(request.getGateRuleJson());
		policy.setHardFailRuleJson(request.getHardFailRuleJson());
		policy.setDescription(request.getDescription());
		return policy;
	}

	private DataAgentEvalSubject toSubject(DataAgentEvalSubject subject, EvalSubjectRequest request) {
		if (request == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		subject.setSubjectName(normalize(request.getSubjectName()));
		subject.setSubjectType(normalize(request.getSubjectType()));
		subject.setSubjectId(normalize(request.getSubjectId()));
		subject.setAdapterCode(normalize(request.getAdapterCode()));
		subject.setStatus(defaultText(request.getStatus(), STATUS_ENABLED));
		subject.setDescription(request.getDescription());
		return subject;
	}

	private DataAgentEvalSuite toSuite(DataAgentEvalSuite suite, EvalSuiteRequest request) {
		if (request == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		suite.setSuiteName(normalize(request.getSuiteName()));
		suite.setSubjectId(request.getSubjectId());
		suite.setPolicyId(request.getPolicyId());
		suite.setStatus(defaultText(request.getStatus(), STATUS_ENABLED));
		suite.setDescription(request.getDescription());
		return suite;
	}

	private DataAgentEvalCase toCase(DataAgentEvalCase evalCase, EvalCaseRequest request) {
		if (request == null) {
			throw badRequest(AgentEvaluationErrorDict.REQUEST_INVALID);
		}
		evalCase.setSuiteId(request.getSuiteId());
		evalCase.setCaseName(normalize(request.getCaseName()));
		evalCase.setUserInput(request.getUserInput());
		evalCase.setExpectedOutput(request.getExpectedOutput());
		evalCase.setSafetyConstraintsJson(request.getSafetyConstraintsJson());
		evalCase.setEfficiencyBudgetJson(request.getEfficiencyBudgetJson());
		evalCase.setTagsJson(request.getTagsJson());
		evalCase.setTraceSnapshotJson(request.getTraceSnapshotJson());
		evalCase.setCodeOracleJson(request.getCodeOracleJson());
		evalCase.setStatus(defaultText(request.getStatus(), STATUS_ENABLED));
		return evalCase;
	}

	private DataAgentEvalPolicy requireTenantPolicy(Long id, boolean enabledOnly) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.POLICY_NOT_FOUND);
		}
		DataAgentEvalPolicy policy = enabledOnly ? policyMapper.findEnabledById(id) : policyMapper.selectById(id);
		if (policy == null || Boolean.TRUE.equals(policy.getDeleted()) || !sameTenant(policy)) {
			throw badRequest(AgentEvaluationErrorDict.POLICY_NOT_FOUND);
		}
		return policy;
	}

	private DataAgentEvalSubject requireTenantSubject(Long id) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.SUBJECT_NOT_FOUND);
		}
		DataAgentEvalSubject subject = subjectMapper.findEnabledById(id);
		if (subject == null || !sameTenant(subject)) {
			throw badRequest(AgentEvaluationErrorDict.SUBJECT_NOT_FOUND);
		}
		return subject;
	}

	private DataAgentEvalSuite requireTenantSuite(Long id) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.SUITE_NOT_FOUND);
		}
		DataAgentEvalSuite suite = suiteMapper.findEnabledById(id);
		if (suite == null || !sameTenant(suite)) {
			throw badRequest(AgentEvaluationErrorDict.SUITE_NOT_FOUND);
		}
		return suite;
	}

	private DataAgentEvalCase requireTenantCase(Long id) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.CASE_NOT_FOUND);
		}
		DataAgentEvalCase evalCase = caseMapper.findEnabledById(id);
		if (evalCase == null || !sameTenant(evalCase)) {
			throw badRequest(AgentEvaluationErrorDict.CASE_NOT_FOUND);
		}
		return evalCase;
	}

	private DataAgentEvalRun requireTenantRun(Long id) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.RUN_NOT_FOUND);
		}
		DataAgentEvalRun run = runMapper.findActiveById(id);
		if (run == null || !sameTenant(run)) {
			throw badRequest(AgentEvaluationErrorDict.RUN_NOT_FOUND);
		}
		return run;
	}

	private DataAgentEvalCaseResult requireTenantResult(Long id) {
		if (id == null) {
			throw badRequest(AgentEvaluationErrorDict.CASE_NOT_FOUND);
		}
		DataAgentEvalCaseResult result = resultMapper.findActiveById(id);
		if (result == null || !sameTenant(result)) {
			throw badRequest(AgentEvaluationErrorDict.CASE_NOT_FOUND);
		}
		return result;
	}

	private boolean sameTenant(DataAgentEvalPolicy policy) {
		return policy == null || sameTenant(policy.getTenantId());
	}

	private boolean sameTenant(DataAgentEvalSubject subject) {
		return subject == null || sameTenant(subject.getTenantId());
	}

	private boolean sameTenant(DataAgentEvalSuite suite) {
		return suite == null || sameTenant(suite.getTenantId());
	}

	private boolean sameTenant(DataAgentEvalCase evalCase) {
		return evalCase == null || sameTenant(evalCase.getTenantId());
	}

	private boolean sameTenant(DataAgentEvalRun run) {
		return run == null || sameTenant(run.getTenantId());
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
			log.warn("当前租户上下文不可用, 拒绝访问租户级评测数据. rowTenantId={}", rowTenantId);
			return false;
		}
		return currentTenantId.equals(rowTenantId);
	}

	private boolean sameTenant(String rowTenantId, String expectedTenantId) {
		return !StringUtils.hasText(expectedTenantId) || !StringUtils.hasText(rowTenantId)
				|| expectedTenantId.equals(rowTenantId);
	}

	private Map<String, Object> readMap(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception ex) {
			// The payload is not logged: evaluation snapshots carry tenant business data.
			log.warn("Failed to parse evaluation JSON object, falling back to an empty map. length={}", json.length(), ex);
			return Map.of();
		}
	}

	private Long readLong(String json, String key) {
		Object value = readMap(json).get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				return Long.valueOf(text.trim());
			}
			catch (NumberFormatException ex) {
				log.warn("Evaluation JSON value is not a valid long, treating it as absent. key={}", key, ex);
				return null;
			}
		}
		return null;
	}

	private BigDecimal readDecimal(Map<String, Object> values, String key, BigDecimal defaultValue) {
		Object value = values.get(key);
		if (value instanceof Number number) {
			return BigDecimal.valueOf(number.doubleValue());
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				return new BigDecimal(text.trim());
			}
			catch (NumberFormatException ex) {
				// A silently defaulted weight or threshold changes the score without changing the report.
				log.warn("Evaluation policy value is not a valid decimal, falling back to the default. key={}, default={}",
						key, defaultValue, ex);
				return defaultValue;
			}
		}
		return defaultValue;
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			// "{}" is persisted as if it were a real snapshot, so the loss has to be traceable.
			log.warn("Failed to serialize evaluation snapshot, persisting an empty object instead. valueType={}",
					value == null ? null : value.getClass().getName(), ex);
			return "{}";
		}
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(defaultText(value, "").getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		}
		catch (Exception ex) {
			// Every hash would collapse to the same empty hash, silently making unrelated policies look identical.
			log.error("SHA-256 digest is unavailable, evaluation policy hashing is broken", ex);
			return "";
		}
	}

	private CheckedException badRequest(AgentEvaluationErrorDict dict) {
		return CheckedException.badRequest(dict.getValue(), dict.getLabel());
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			// sameTenant() 会因此拒绝全部租户级数据，必须留痕以便定位鉴权上下文故障。
			log.warn("解析评测租户上下文失败, 租户级评测数据将失败关闭", ex);
			return null;
		}
	}

	private String currentTenantCode() {
		try {
			return authenticationContext.tenantCode();
		}
		catch (Exception ex) {
			log.warn("解析评测租户编码失败, 按无租户编码处理", ex);
			return null;
		}
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			log.warn("解析评测当前用户失败, 按匿名处理", ex);
			return null;
		}
	}

	private String currentClientId() {
		try {
			return authenticationContext.clientId();
		}
		catch (Exception ex) {
			log.warn("解析评测客户端标识失败, 按无客户端处理", ex);
			return null;
		}
	}

	private List<String> currentTeamIds() {
		try {
			List<String> teamIds = authenticationContext.teamIds();
			return teamIds == null ? List.of() : teamIds;
		}
		catch (Exception ex) {
			log.warn("解析评测团队列表失败, 按无团队处理", ex);
			return List.of();
		}
	}

	private DataPermission currentDataPermission() {
		try {
			return authenticationContext.dataPermission();
		}
		catch (Exception ex) {
			log.warn("解析评测数据权限失败, 按无数据权限处理", ex);
			return null;
		}
	}

	private String buildAgentConfigSnapshot(DataAgentEvalSubject subject) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("subjectId", subject.getId());
		snapshot.put("subjectName", subject.getSubjectName());
		snapshot.put("subjectType", subject.getSubjectType());
		snapshot.put("subjectBusinessId", subject.getSubjectId());
		snapshot.put("adapterCode", subject.getAdapterCode());
		return writeJson(snapshot);
	}

	private String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String defaultText(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value.trim() : defaultValue;
	}

	private int value(Integer value) {
		return value == null ? 0 : value;
	}

	private BigDecimal value(BigDecimal value) {
		return value == null ? BigDecimal.ZERO : value;
	}

	private Long firstNonNull(Long first, Long second) {
		return first != null ? first : second;
	}

	private long firstPositive(Long first, Long second, long fallback) {
		if (first != null && first > 0L) {
			return first;
		}
		if (second != null && second > 0L) {
			return second;
		}
		return fallback;
	}

	private long safeLong(Long value) {
		return value == null ? 0L : value;
	}

	private int safeInt(Integer value) {
		return value == null ? 0 : value;
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
				log.warn("Evaluation metric value is not a valid long, treating it as absent. key={}", key, ex);
				return null;
			}
		}
		return null;
	}

	private Integer mapInt(Map<String, Object> values, String key) {
		Long value = mapLong(values, key);
		return value == null ? null : value.intValue();
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private List<Map<String, Object>> readList(String json) {
		if (!StringUtils.hasText(json)) {
			return List.of();
		}
		try {
			return objectMapper.readValue(json, LIST_MAP_TYPE);
		}
		catch (Exception ex) {
			// The payload is not logged: evaluation snapshots carry tenant business data.
			log.warn("Failed to parse evaluation JSON array, falling back to an empty list. length={}", json.length(), ex);
			return List.of();
		}
	}

	private record BootstrapUpsert<T extends SuperEntity<Long>>(T entity, boolean created) {
	}

	private record PolicySnapshot(DataAgentEvalPolicy policy, String json, String hash, ScoreWeights weights,
			String efficiencyBudgetJson) {
	}

	private record ScoreWeights(BigDecimal quality, BigDecimal safety, BigDecimal efficiency, BigDecimal stability) {
	}

	private record ScoreResult(boolean passed, boolean hardFail, boolean timeout, BigDecimal totalScore,
			BigDecimal qualityScore, BigDecimal safetyScore, BigDecimal efficiencyScore, BigDecimal stabilityScore,
			Map<String, Object> detail, List<Map<String, Object>> failureReasons) {
	}

}
