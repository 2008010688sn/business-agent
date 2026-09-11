package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.evaluation.dto.EvalFailureClusterDTO;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseResultMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalRunMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSubjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.evaluation.service.AgentEvaluationService;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateGenerateRequest;
import com.sn68.agent.dataagent.optimization.dto.OptCandidateRequest;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentQueryRequest;
import com.sn68.agent.dataagent.optimization.dto.OptExperimentRequest;
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
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentOptimizationServiceTest {

	private final DataAgentOptExperimentMapper experimentMapper = mock(DataAgentOptExperimentMapper.class);

	private final DataAgentOptCandidateMapper candidateMapper = mock(DataAgentOptCandidateMapper.class);

	private final DataAgentOptCandidateEvalMapper candidateEvalMapper = mock(DataAgentOptCandidateEvalMapper.class);

	private final DataAgentOptReleaseMapper releaseMapper = mock(DataAgentOptReleaseMapper.class);

	private final DataAgentEvalRunMapper runMapper = mock(DataAgentEvalRunMapper.class);

	private final DataAgentEvalCaseResultMapper resultMapper = mock(DataAgentEvalCaseResultMapper.class);

	private final DataAgentEvalCaseMapper caseMapper = mock(DataAgentEvalCaseMapper.class);

	private final DataAgentEvalSubjectMapper subjectMapper = mock(DataAgentEvalSubjectMapper.class);

	private final AgentEvaluationService evaluationService = mock(AgentEvaluationService.class);

	private final DataAgentService dataAgentService = mock(DataAgentService.class);

	private final EmployeeReleaseLifecycleService employeeReleaseLifecycleService = mock(
			EmployeeReleaseLifecycleService.class);

	private final EmployeeEvolutionApplyAdapter employeeEvolutionApplyAdapter = mock(EmployeeEvolutionApplyAdapter.class);

	private final OptCandidateGenerator optCandidateGenerator = mock(OptCandidateGenerator.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final AgentOptimizationService service = new AgentOptimizationService(experimentMapper, candidateMapper,
			candidateEvalMapper, releaseMapper, runMapper, resultMapper, caseMapper, subjectMapper, evaluationService,
			dataAgentService, employeeReleaseLifecycleService, employeeEvolutionApplyAdapter, optCandidateGenerator,
			new com.sn68.agent.dataagent.properties.DataAgentProperties(), new ObjectMapper(), authenticationContext);

	@Test
	void loopStatusSkipsShadowForDigitalEmployee() {
		mockTenant();
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setOwnerType("DIGITAL_EMPLOYEE");
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		when(candidateMapper.findByExperimentId(1L)).thenReturn(java.util.List.of());
		when(releaseMapper.findByExperimentId(1L)).thenReturn(java.util.List.of());

		com.sn68.agent.dataagent.optimization.dto.OptLoopStatusDTO status = service.getLoopStatus(1L);

		assertTrue(status.stages().stream()
			.anyMatch(stage -> "SHADOW".equals(stage.get("stage")) && "SKIPPED".equals(stage.get("status"))));
	}

	@Test
	void generateCandidateRejectsWhenDisabled() {
		mockTenant();
		when(optCandidateGenerator.autoGenerateEnabled()).thenReturn(false);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.generateCandidate(1L, new OptCandidateGenerateRequest()));

		assertEquals(AgentOptimizationErrorDict.AUTO_GENERATE_DISABLED.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	@Test
	void generateCandidateRejectsWhenBaselineHasNoFailures() {
		mockTenant();
		when(optCandidateGenerator.autoGenerateEnabled()).thenReturn(true);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		when(evaluationService.clusterFailures(100L)).thenReturn(EvalFailureClusterDTO.builder()
			.runId(100L)
			.failedCount(0)
			.hardFailCount(0)
			.failureReasons(java.util.List.of())
			.slowCases(java.util.List.of())
			.build());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.generateCandidate(1L, new OptCandidateGenerateRequest()));

		assertEquals(AgentOptimizationErrorDict.GENERATE_SATURATED.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	@Test
	void generateCandidateDoesNotInsertWhenGeneratorFails() {
		mockTenant();
		when(optCandidateGenerator.autoGenerateEnabled()).thenReturn(true);
		when(optCandidateGenerator.maxTraceChars()).thenReturn(2000);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		when(evaluationService.clusterFailures(100L)).thenReturn(EvalFailureClusterDTO.builder()
			.runId(100L)
			.failedCount(2)
			.hardFailCount(1)
			.failureReasons(java.util.List.of())
			.slowCases(java.util.List.of())
			.build());
		when(optCandidateGenerator.generate(org.mockito.ArgumentMatchers.nullable(String.class),
				org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.nullable(Long.class),
				any())).thenThrow(CheckedException.badRequest(AgentOptimizationErrorDict.GENERATE_FAILED.getValue(),
						AgentOptimizationErrorDict.GENERATE_FAILED.getLabel()));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.generateCandidate(1L, new OptCandidateGenerateRequest()));

		assertEquals(AgentOptimizationErrorDict.GENERATE_FAILED.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	@Test
	void diagnoseExperimentUsesEvaluationFailureCluster() {
		mockTenant();
		DataAgentOptExperiment experiment = experiment(1L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		when(evaluationService.clusterFailures(100L)).thenReturn(EvalFailureClusterDTO.builder()
			.runId(100L)
			.failedCount(2)
			.hardFailCount(1)
			.failureReasons(java.util.List.of(java.util.Map.of("reasonCode", "CODE_ORACLE_MISMATCH", "count", 2L)))
			.slowCases(java.util.List.of(java.util.Map.of("reasonCode", "MODEL_CALL_SLOW")))
			.build());
		java.util.concurrent.atomic.AtomicReference<DataAgentOptExperiment> stored =
				new java.util.concurrent.atomic.AtomicReference<>();
		when(experimentMapper.updateById(any(DataAgentOptExperiment.class))).thenAnswer(invocation -> {
			stored.set(invocation.getArgument(0));
			return 1;
		});
		when(experimentMapper.selectById(1L)).thenAnswer(invocation -> stored.get());

		DataAgentOptExperiment diagnosed = service.diagnoseExperiment(1L);

		assertEquals("diagnosed", diagnosed.getStatus());
		assertTrue(diagnosed.getDiagnosisJson().contains("CODE_ORACLE_MISMATCH"));
		assertTrue(diagnosed.getDiagnosisJson().contains("MODEL_CALL_SLOW"));
		verify(evaluationService).clusterFailures(100L);
	}

	@Test
	void createExperimentFillsOwnerFromDataAgentSubject() {
		mockTenant();
		DataAgentEvalRun baseline = baselineRun();
		baseline.setSubjectId(200L);
		doReturn(baseline).when(runMapper).findActiveById(100L);
		doReturn(dataAgentSubject(200L, "300")).when(subjectMapper).findEnabledById(200L);
		java.util.concurrent.atomic.AtomicReference<DataAgentOptExperiment> stored =
				new java.util.concurrent.atomic.AtomicReference<>();
		when(experimentMapper.insert(any(DataAgentOptExperiment.class))).thenAnswer(invocation -> {
			DataAgentOptExperiment inserted = invocation.getArgument(0);
			inserted.setId(9L);
			stored.set(inserted);
			return 1;
		});
		when(experimentMapper.selectById(9L)).thenAnswer(invocation -> stored.get());
		OptExperimentRequest request = new OptExperimentRequest();
		request.setExperimentName("账单问答基线");
		request.setBaselineRunId(100L);

		DataAgentOptExperiment created = service.createExperiment(request);

		assertEquals("DATA_AGENT", created.getOwnerType());
		assertEquals("300", created.getOwnerId());
		assertEquals(200L, created.getSubjectId());
	}

	@Test
	void createExperimentFillsOwnerFromEmployeeReleaseSubject() {
		mockTenant();
		DataAgentEvalRun baseline = baselineRun();
		baseline.setSubjectId(201L);
		doReturn(baseline).when(runMapper).findActiveById(100L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject subject =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject();
		subject.setId(201L);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DIGITAL_EMPLOYEE_RELEASE");
		subject.setSubjectId("88");
		doReturn(subject).when(subjectMapper).findEnabledById(201L);
		DigitalEmployeeRelease release = new DigitalEmployeeRelease();
		release.setId(88L);
		release.setEmployeeId(42L);
		when(employeeReleaseLifecycleService.getDetail(88L)).thenReturn(release);
		java.util.concurrent.atomic.AtomicReference<DataAgentOptExperiment> stored =
				new java.util.concurrent.atomic.AtomicReference<>();
		when(experimentMapper.insert(any(DataAgentOptExperiment.class))).thenAnswer(invocation -> {
			DataAgentOptExperiment inserted = invocation.getArgument(0);
			inserted.setId(9L);
			stored.set(inserted);
			return 1;
		});
		when(experimentMapper.selectById(9L)).thenAnswer(invocation -> stored.get());
		OptExperimentRequest request = new OptExperimentRequest();
		request.setExperimentName("员工进化");
		request.setBaselineRunId(100L);

		DataAgentOptExperiment created = service.createExperiment(request);

		assertEquals("DIGITAL_EMPLOYEE", created.getOwnerType());
		assertEquals("42", created.getOwnerId());
	}

	@Test
	void createExperimentRejectsOwnerMismatchWithSubject() {
		mockTenant();
		DataAgentEvalRun baseline = baselineRun();
		baseline.setSubjectId(200L);
		doReturn(baseline).when(runMapper).findActiveById(100L);
		doReturn(dataAgentSubject(200L, "300")).when(subjectMapper).findEnabledById(200L);
		OptExperimentRequest request = new OptExperimentRequest();
		request.setExperimentName("归属冲突");
		request.setBaselineRunId(100L);
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setOwnerId("42");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createExperiment(request));

		assertEquals(AgentOptimizationErrorDict.REQUEST_INVALID.getValue(), ex.getCode());
		verifyNoInteractions(experimentMapper);
	}

	@Test
	void queryExperimentsPagePassesOwnerFilter() {
		mockTenant();
		OptExperimentQueryRequest request = new OptExperimentQueryRequest();
		request.setOwnerType("DIGITAL_EMPLOYEE");
		request.setOwnerId("42");

		service.queryExperimentsPage(request);

		ArgumentCaptor<OptExperimentQueryRequest> captor = ArgumentCaptor.forClass(OptExperimentQueryRequest.class);
		verify(experimentMapper).selectExperimentPage(any(), captor.capture(),
				org.mockito.ArgumentMatchers.eq("tenant-1"));
		assertEquals("DIGITAL_EMPLOYEE", captor.getValue().getOwnerType());
		assertEquals("42", captor.getValue().getOwnerId());
	}

	@Test
	void releaseApprovalActivatesEmployeeSandboxWithoutPublishingDataAgent() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setTargetType("PROMPT");
		candidate.setGateResultJson("{\"passed\":true}");
		candidate.setPatchJson("{\"snapshotOverlay\":{\"promptSnapshot\":{\"prompt\":\"新指令\"}}}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setOwnerType("DIGITAL_EMPLOYEE");
		experiment.setOwnerId("42");
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		when(employeeEvolutionApplyAdapter.captureBeforeSnapshot(42L))
			.thenReturn("{\"promptSnapshot\":{\"prompt\":\"旧指令\"}}");
		when(employeeEvolutionApplyAdapter.applyToSandbox(eq(42L), any()))
			.thenReturn(new EmployeeEvolutionApplyAdapter.ApplyResult(900L, 900L, 100L));
		doAnswer(invocation -> {
			DataAgentOptRelease release = invocation.getArgument(0);
			release.setId(11L);
			return 1;
		}).when(releaseMapper).insert(any(DataAgentOptRelease.class));
		when(releaseMapper.selectById(11L)).thenAnswer(invocation -> {
			DataAgentOptRelease release = new DataAgentOptRelease();
			release.setId(11L);
			release.setStatus("sandbox_activated");
			release.setProductionReleaseId(900L);
			return release;
		});
		OptReleaseApprovalRequest request = new OptReleaseApprovalRequest();
		request.setCurrentHash("before-hash");

		DataAgentOptRelease saved = service.createReleaseApproval(2L, request);

		assertEquals("sandbox_activated", saved.getStatus());
		assertEquals(900L, saved.getProductionReleaseId());
		verify(employeeEvolutionApplyAdapter).applyToSandbox(eq(42L), any());
		verifyNoInteractions(dataAgentService);
		ArgumentCaptor<DataAgentOptCandidate> candidateCaptor = ArgumentCaptor.forClass(DataAgentOptCandidate.class);
		verify(candidateMapper).updateById(candidateCaptor.capture());
		assertEquals("sandbox_activated", candidateCaptor.getValue().getStatus());
	}

	@Test
	void promoteEmployeeReleaseRequiresSandboxActivatedRecord() {
		mockTenant();
		DataAgentOptRelease source = new DataAgentOptRelease();
		source.setId(11L);
		source.setTenantId("tenant-1");
		source.setActionType("release");
		source.setStatus("recorded");
		doReturn(source).when(releaseMapper).findActiveById(11L);
		OptReleasePromoteRequest request = new OptReleasePromoteRequest();
		request.setReleaseId(11L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.promoteEmployeeRelease(11L, request));

		assertEquals(AgentOptimizationErrorDict.EMPLOYEE_STAGING_REQUIRED.getValue(), ex.getCode());
		verifyNoInteractions(employeeEvolutionApplyAdapter);
	}

	@Test
	void rollbackEmployeeRestoresDraftAndRollsBackEnvironments() {
		mockTenant();
		DataAgentOptRelease sourceRelease = new DataAgentOptRelease();
		sourceRelease.setId(10L);
		sourceRelease.setTenantId("tenant-1");
		sourceRelease.setExperimentId(1L);
		sourceRelease.setCandidateId(2L);
		sourceRelease.setActionType("release");
		sourceRelease.setStatus("production_activated");
		sourceRelease.setProductionReleaseId(900L);
		doReturn(sourceRelease).when(releaseMapper).findActiveById(10L);
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setBeforeSnapshotJson("{\"promptSnapshot\":{\"prompt\":\"旧提示词\"}}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setOwnerType("DIGITAL_EMPLOYEE");
		experiment.setOwnerId("42");
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		doAnswer(invocation -> {
			DataAgentOptRelease release = invocation.getArgument(0);
			release.setId(12L);
			return 1;
		}).when(releaseMapper).insert(any(DataAgentOptRelease.class));
		when(releaseMapper.selectById(12L)).thenAnswer(invocation -> {
			DataAgentOptRelease release = new DataAgentOptRelease();
			release.setId(12L);
			return release;
		});

		service.recordRollback(10L, null);

		verify(employeeEvolutionApplyAdapter).rollbackEnvironment(42L, "PRODUCTION");
		verify(employeeEvolutionApplyAdapter).rollbackEnvironment(42L, "SANDBOX");
		verify(employeeEvolutionApplyAdapter).restoreDraft(42L, "{\"promptSnapshot\":{\"prompt\":\"旧提示词\"}}");
		verifyNoInteractions(dataAgentService);
	}

	@Test
	void createCandidateRejectsUnsupportedTargetType() {
		mockTenant();
		DataAgentOptExperiment experiment = experiment(1L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		OptCandidateRequest request = candidateRequest();
		request.setTargetType("UNKNOWN");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createCandidate(1L, request));

		assertEquals(AgentOptimizationErrorDict.TARGET_TYPE_NOT_ALLOWED.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	@Test
	void experimentIsHiddenWhenTheTenantContextIsUnavailable() {
		DataAgentOptExperiment experiment = experiment(1L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		when(authenticationContext.tenantId()).thenThrow(new IllegalStateException("鉴权上下文不可用"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getExperimentDetail(1L));

		assertEquals(AgentOptimizationErrorDict.EXPERIMENT_NOT_FOUND.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper, candidateEvalMapper);
	}

	@Test
	void releaseApprovalRejectsHashMismatch() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		OptReleaseApprovalRequest request = new OptReleaseApprovalRequest();
		request.setCurrentHash("other-hash");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createReleaseApproval(2L, request));

		assertEquals(AgentOptimizationErrorDict.HASH_MISMATCH.getValue(), ex.getCode());
		verifyNoInteractions(releaseMapper);
	}

	@Test
	void releaseApprovalRejectsFailedGate() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setGateResultJson("{\"passed\":false}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		OptReleaseApprovalRequest request = new OptReleaseApprovalRequest();
		request.setCurrentHash("before-hash");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createReleaseApproval(2L, request));

		assertEquals(AgentOptimizationErrorDict.GATE_NOT_PASSED.getValue(), ex.getCode());
		verifyNoInteractions(releaseMapper);
	}

	@Test
	void rollbackRestoresLiveAgentFromBeforeSnapshot() {
		mockTenant();
		DataAgentOptRelease sourceRelease = new DataAgentOptRelease();
		sourceRelease.setId(10L);
		sourceRelease.setTenantId("tenant-1");
		sourceRelease.setTenantCode("tenant-code");
		sourceRelease.setExperimentId(1L);
		sourceRelease.setCandidateId(2L);
		sourceRelease.setActionType("release");
		sourceRelease.setBeforeHash("before-hash");
		sourceRelease.setAfterHash("after-hash");
		doReturn(sourceRelease).when(releaseMapper).findActiveById(10L);
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setBeforeSnapshotJson(
				"{\"promptSnapshot\":{\"prompt\":\"旧提示词\",\"description\":null},\"modelConfigSnapshot\":{\"chatModelConfigId\":9}}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		doReturn(dataAgentSubject(200L, "300")).when(subjectMapper).findEnabledById(200L);
		DataAgent agent = new DataAgent();
		agent.setId(300L);
		agent.setPrompt("新提示词");
		agent.setChatModelConfigId(8L);
		when(dataAgentService.requireAgent(300L)).thenReturn(agent);
		when(dataAgentService.update(org.mockito.ArgumentMatchers.eq(300L), any(DataAgent.class))).thenReturn(agent);
		when(dataAgentService.publish(300L)).thenReturn(agent);
		doAnswer(invocation -> {
			DataAgentOptRelease release = invocation.getArgument(0);
			release.setId(11L);
			return 1;
		}).when(releaseMapper).insert(any(DataAgentOptRelease.class));
		when(releaseMapper.selectById(11L)).thenAnswer(invocation -> {
			DataAgentOptRelease release = new DataAgentOptRelease();
			release.setId(11L);
			return release;
		});
		OptRollbackRequest request = new OptRollbackRequest();
		request.setReason("人工回滚");

		service.recordRollback(10L, request);

		ArgumentCaptor<DataAgent> agentCaptor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentService).update(org.mockito.ArgumentMatchers.eq(300L), agentCaptor.capture());
		assertEquals("旧提示词", agentCaptor.getValue().getPrompt());
		assertEquals(9L, agentCaptor.getValue().getChatModelConfigId());
		verify(dataAgentService).publish(300L);
		ArgumentCaptor<DataAgentOptRelease> captor = ArgumentCaptor.forClass(DataAgentOptRelease.class);
		verify(releaseMapper).insert(captor.capture());
		DataAgentOptRelease rollback = captor.getValue();
		assertEquals(10L, rollback.getSourceReleaseId());
		assertEquals("rollback", rollback.getActionType());
		assertEquals("after-hash", rollback.getBeforeHash());
		assertEquals("before-hash", rollback.getAfterHash());
	}

	@Test
	void rollbackRejectsMissingSnapshot() {
		mockTenant();
		DataAgentOptRelease sourceRelease = new DataAgentOptRelease();
		sourceRelease.setId(10L);
		sourceRelease.setTenantId("tenant-1");
		sourceRelease.setExperimentId(1L);
		sourceRelease.setCandidateId(2L);
		sourceRelease.setActionType("release");
		doReturn(sourceRelease).when(releaseMapper).findActiveById(10L);
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.recordRollback(10L, null));

		assertEquals(AgentOptimizationErrorDict.SNAPSHOT_MISSING.getValue(), ex.getCode());
		verifyNoInteractions(dataAgentService);
	}

	@Test
	void rollbackRejectsRollbackRecord() {
		mockTenant();
		DataAgentOptRelease sourceRelease = new DataAgentOptRelease();
		sourceRelease.setId(10L);
		sourceRelease.setTenantId("tenant-1");
		sourceRelease.setActionType("rollback");
		doReturn(sourceRelease).when(releaseMapper).findActiveById(10L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.recordRollback(10L, null));

		assertEquals(AgentOptimizationErrorDict.RELEASE_NOT_ROLLBACKABLE.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper, candidateEvalMapper, experimentMapper, runMapper, resultMapper);
	}

	/** guardrail：命中方案明令禁止的产物类型（权限/凭据/审批策略等）必须显式拒绝，不落库。 */
	@Test
	void createCandidateRejectsForbiddenTargetType() {
		mockTenant();
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		OptCandidateRequest request = candidateRequest();
		request.setTargetType("PERMISSION");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createCandidate(1L, request));

		assertEquals(AgentOptimizationErrorDict.TARGET_TYPE_FORBIDDEN.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	/** guardrail：产物类型合法但补丁内容触碰凭据等禁止键，同样拒绝。 */
	@Test
	void createCandidateRejectsForbiddenPatchContent() {
		mockTenant();
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		OptCandidateRequest request = candidateRequest();
		request.setPatchJson("{\"snapshotOverlay\":{\"promptSnapshot\":{\"credentialRef\":\"secret-1\"}}}");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createCandidate(1L, request));

		assertEquals(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	/** guardrail：快照覆盖键必须落在产物类型允许的 Release 快照列内（PROMPT 不允许覆盖路由快照）。 */
	@Test
	void createCandidateRejectsOverlayKeyOutsideTargetType() {
		mockTenant();
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		OptCandidateRequest request = candidateRequest();
		request.setPatchJson("{\"snapshotOverlay\":{\"routingProfileSnapshot\":{}}}");

		CheckedException ex = assertThrows(CheckedException.class, () -> service.createCandidate(1L, request));

		assertEquals(AgentOptimizationErrorDict.PATCH_CONTENT_FORBIDDEN.getValue(), ex.getCode());
		verifyNoInteractions(candidateMapper);
	}

	/** 硬门禁：候选评估运行未按 DRY_RUN 执行时不过门禁。 */
	@Test
	void gateBlocksWhenSandboxRunIsNotDryRun() {
		DataAgentOptCandidateEval saved = evaluateWithSandboxRun(sandboxRun("LIVE", 0, 0));

		assertEquals(false, saved.getPassed());
		assertTrue(saved.getCompareJson().contains("未按 DRY_RUN 执行"));
	}

	/** 硬门禁：写副作用违规 > 0 时不过门禁。 */
	@Test
	void gateBlocksWhenWriteViolationsPresent() {
		DataAgentOptCandidateEval saved = evaluateWithSandboxRun(sandboxRun("DRY_RUN", 1, 0));

		assertEquals(false, saved.getPassed());
		assertEquals(1, saved.getWriteViolationCount());
		assertTrue(saved.getCompareJson().contains("写副作用违规"));
	}

	/** 硬门禁：权限或租户隔离违规 > 0 时不过门禁。 */
	@Test
	void gateBlocksWhenIsolationViolationsPresent() {
		DataAgentOptCandidateEval saved = evaluateWithSandboxRun(sandboxRun("DRY_RUN", 0, 2));

		assertEquals(false, saved.getPassed());
		assertEquals(2, saved.getIsolationViolationCount());
		assertTrue(saved.getCompareJson().contains("权限或租户隔离违规"));
	}

	/** 硬门禁：候选评估代码 oracle 失败时不过门禁。 */
	@Test
	void gateBlocksWhenCodeOracleFails() {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult oracleFailed = caseResult(11L, "failed");
		oracleFailed.setFailureReasonsJson("[{\"reasonCode\":\"CODE_ORACLE_MISMATCH\"}]");
		mockTenant();
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		doReturn(sandboxRun("DRY_RUN", 0, 0)).when(runMapper).findActiveById(200L);
		doReturn(java.util.List.of()).when(resultMapper).findByRunId(100L);
		doReturn(java.util.List.of(oracleFailed)).when(resultMapper).findByRunId(200L);
		doReturn(java.util.List.of()).when(caseMapper).findEnabledBySuiteId(org.mockito.ArgumentMatchers.anyLong());
		DataAgentOptCandidateEval saved = captureEvalInsert();

		service.evaluateCandidate(2L, evalRequest(200L));

		assertEquals(false, saved.getPassed());
		assertTrue(saved.getCompareJson().contains("CODE_ORACLE"));
	}

	/** DRY_RUN 且违规为 0、指标改善时门禁通过（分片维度缺失如实降级，不拦发布）。 */
	@Test
	void gatePassesOnCleanDryRunAndRecordsShardDegradation() {
		DataAgentOptCandidateEval saved = evaluateWithSandboxRun(sandboxRun("DRY_RUN", 0, 0));

		assertEquals(true, saved.getPassed());
		assertTrue(saved.getShardResultJson().contains("degraded"));
		assertTrue(saved.getShardResultJson().contains("未标注分片维度"));
	}

	/** 分片评估：小分片通过率低于基线时不过门禁，防止大租户掩盖小租户回归。 */
	@Test
	void gateBlocksWhenShardPassRateRegresses() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		doReturn(sandboxRun("DRY_RUN", 0, 0)).when(runMapper).findActiveById(200L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase smallTenantCase = evalCase(11L,
				"[\"tenant:small\"]");
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase bigTenantCase = evalCase(12L,
				"[\"tenant:big\"]");
		doReturn(java.util.List.of(smallTenantCase, bigTenantCase)).when(caseMapper).findEnabledBySuiteId(50L);
		doReturn(java.util.List.of(caseResult(11L, "success"), caseResult(12L, "success"))).when(resultMapper)
			.findByRunId(100L);
		doReturn(java.util.List.of(caseResult(11L, "failed"), caseResult(12L, "success"))).when(resultMapper)
			.findByRunId(200L);
		DataAgentOptCandidateEval saved = captureEvalInsert();

		service.evaluateCandidate(2L, evalRequest(200L));

		assertEquals(false, saved.getPassed());
		assertTrue(saved.getCompareJson().contains("分片[tenant:small]通过率低于基线"));
		assertTrue(saved.getShardResultJson().contains("\"regressed\":true"));
	}

	/** 审批通过后写入活体并调用 DataAgent.publish，不再创建 data_agent_release。 */
	@Test
	void releaseApprovalAppliesOverlayAndPublishesLiveAgent() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setCandidateName("候选A");
		candidate.setTargetType("PROMPT");
		candidate.setPatchJson("{\"snapshotOverlay\":{\"promptSnapshot\":{\"prompt\":\"新提示词\"}}}");
		candidate.setGateResultJson("{\"passed\":true}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject subject =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject();
		subject.setId(200L);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DATA_AGENT");
		subject.setSubjectId("300");
		doReturn(subject).when(subjectMapper).findEnabledById(200L);
		DataAgent agent = new DataAgent();
		agent.setId(300L);
		agent.setPrompt("旧提示词");
		when(dataAgentService.requireAgent(300L)).thenReturn(agent);
		when(dataAgentService.update(org.mockito.ArgumentMatchers.eq(300L), any(DataAgent.class))).thenReturn(agent);
		when(dataAgentService.publish(300L)).thenReturn(agent);
		doAnswer(invocation -> {
			DataAgentOptRelease release = invocation.getArgument(0);
			release.setId(11L);
			return 1;
		}).when(releaseMapper).insert(any(DataAgentOptRelease.class));
		when(releaseMapper.selectById(11L)).thenAnswer(invocation -> {
			DataAgentOptRelease release = new DataAgentOptRelease();
			release.setId(11L);
			return release;
		});
		OptReleaseApprovalRequest request = new OptReleaseApprovalRequest();
		request.setCurrentHash("before-hash");
		request.setReason("人工审批通过");

		service.createReleaseApproval(2L, request);

		ArgumentCaptor<DataAgent> agentCaptor = ArgumentCaptor.forClass(DataAgent.class);
		verify(dataAgentService).update(org.mockito.ArgumentMatchers.eq(300L), agentCaptor.capture());
		assertEquals("新提示词", agentCaptor.getValue().getPrompt());
		verify(dataAgentService).publish(300L);
		ArgumentCaptor<DataAgentOptRelease> releaseCaptor = ArgumentCaptor.forClass(DataAgentOptRelease.class);
		verify(releaseMapper).insert(releaseCaptor.capture());
		assertEquals(null, releaseCaptor.getValue().getProductionReleaseId());
		assertEquals("release", releaseCaptor.getValue().getActionType());
		assertEquals("release_approved", candidate.getStatus());
		assertTrue(candidate.getBeforeSnapshotJson().contains("旧提示词"));
	}

	@Test
	void evaluateCandidateRejectsDigitalEmployeeReplaySandbox() {
		mockTenant();
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun replay = sandboxRun("DRY_RUN", 0, 0);
		replay.setSubjectId(200L);
		doReturn(replay).when(runMapper).findActiveById(200L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject subject =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject();
		subject.setId(200L);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DIGITAL_EMPLOYEE_RELEASE");
		subject.setAdapterCode("DIGITAL_EMPLOYEE_RELEASE");
		doReturn(subject).when(subjectMapper).findEnabledById(200L);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.evaluateCandidate(2L, evalRequest(200L)));

		assertEquals(AgentOptimizationErrorDict.SANDBOX_RUN_REPLAY_NOT_ALLOWED.getValue(), ex.getCode());
		verifyNoInteractions(candidateEvalMapper);
	}

	@Test
	void startCandidateOfflineEvaluationForcesInvokeForEmployeeRelease() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		candidate.setPatchJson("{\"snapshotOverlay\":{\"promptSnapshot\":{\"prompt\":\"候选提示词\"}}}");
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun run =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun();
		run.setId(900L);
		when(evaluationService.createRun(any())).thenReturn(run);
		OptOfflineEvalRequest request = new OptOfflineEvalRequest();
		request.setCandidateId(2L);
		request.setSuiteId(50L);

		service.startCandidateOfflineEvaluation(request);

		ArgumentCaptor<com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest> captor = ArgumentCaptor
			.forClass(com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest.class);
		verify(evaluationService).createRun(captor.capture());
		assertEquals("INVOKE", captor.getValue().getEvalMode());
		assertTrue(captor.getValue().getCandidateOverlayJson().contains("候选提示词"));
	}

	@Test
	void evaluateCandidateAllowsInvokeSandboxForEmployeeRelease() {
		mockTenant();
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun invokeRun = sandboxRun("DRY_RUN", 0, 0);
		invokeRun.setSubjectId(200L);
		invokeRun.setAgentConfigSnapshotJson("{\"evalMode\":\"INVOKE\"}");
		doReturn(invokeRun).when(runMapper).findActiveById(200L);
		doReturn(java.util.List.of()).when(resultMapper).findByRunId(org.mockito.ArgumentMatchers.anyLong());
		doReturn(java.util.List.of()).when(caseMapper).findEnabledBySuiteId(org.mockito.ArgumentMatchers.anyLong());
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject subject =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject();
		subject.setId(200L);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DIGITAL_EMPLOYEE_RELEASE");
		subject.setAdapterCode("DIGITAL_EMPLOYEE_RELEASE");
		doReturn(subject).when(subjectMapper).findEnabledById(200L);
		DataAgentOptCandidateEval saved = captureEvalInsert();

		service.evaluateCandidate(2L, evalRequest(200L));

		assertEquals(true, saved.getPassed());
	}

	/** LOOP OfflineEval：发起候选离线评估时执行意图由服务端强制为 DRY_RUN，调用方不可指定。 */
	@Test
	void startCandidateOfflineEvaluationForcesDryRun() {
		mockTenant();
		DataAgentOptCandidate candidate = candidate(2L);
		doReturn(candidate).when(candidateMapper).findActiveById(2L);
		DataAgentOptExperiment experiment = experiment(1L);
		experiment.setSubjectId(200L);
		doReturn(experiment).when(experimentMapper).findActiveById(1L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun run =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun();
		run.setId(900L);
		when(evaluationService.createRun(any())).thenReturn(run);
		OptOfflineEvalRequest request = new OptOfflineEvalRequest();
		request.setCandidateId(2L);
		request.setSuiteId(50L);

		service.startCandidateOfflineEvaluation(request);

		ArgumentCaptor<com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest> captor = ArgumentCaptor
			.forClass(com.sn68.agent.dataagent.evaluation.dto.EvalRunCreateRequest.class);
		verify(evaluationService).createRun(captor.capture());
		assertEquals("DRY_RUN", captor.getValue().getExecutionIntent());
		assertEquals("INVOKE", captor.getValue().getEvalMode());
		assertEquals(50L, captor.getValue().getSuiteId());
		assertEquals(200L, captor.getValue().getSubjectId());
		assertEquals(900L, candidate.getSandboxRunId());
		assertEquals("offline_evaluating", candidate.getStatus());
	}

	/** 未终态的沙箱运行违规计数不完整，门禁判定必须失败关闭。 */
	@Test
	void evaluateCandidateRejectsUnfinishedSandboxRun() {
		mockTenant();
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun running = sandboxRun("DRY_RUN", 0, 0);
		running.setStatus("running");
		doReturn(running).when(runMapper).findActiveById(200L);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.evaluateCandidate(2L, evalRequest(200L)));

		assertEquals(AgentOptimizationErrorDict.SANDBOX_RUN_NOT_FINISHED.getValue(), ex.getCode());
		verifyNoInteractions(candidateEvalMapper);
	}

	private DataAgentOptCandidateEval evaluateWithSandboxRun(
			com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun sandboxRun) {
		mockTenant();
		doReturn(candidate(2L)).when(candidateMapper).findActiveById(2L);
		doReturn(experiment(1L)).when(experimentMapper).findActiveById(1L);
		doReturn(baselineRun()).when(runMapper).findActiveById(100L);
		doReturn(sandboxRun).when(runMapper).findActiveById(200L);
		doReturn(java.util.List.of()).when(resultMapper).findByRunId(org.mockito.ArgumentMatchers.anyLong());
		doReturn(java.util.List.of()).when(caseMapper).findEnabledBySuiteId(org.mockito.ArgumentMatchers.anyLong());
		DataAgentOptCandidateEval saved = captureEvalInsert();
		service.evaluateCandidate(2L, evalRequest(200L));
		return saved;
	}

	/** 捕获 candidateEval 插入实体（insert 时回填到同一引用，selectById 返回同一对象）。 */
	private DataAgentOptCandidateEval captureEvalInsert() {
		DataAgentOptCandidateEval holder = new DataAgentOptCandidateEval();
		doAnswer(invocation -> {
			DataAgentOptCandidateEval inserted = invocation.getArgument(0);
			inserted.setId(66L);
			org.springframework.beans.BeanUtils.copyProperties(inserted, holder);
			return 1;
		}).when(candidateEvalMapper).insert(any(DataAgentOptCandidateEval.class));
		when(candidateEvalMapper.selectById(66L)).thenReturn(holder);
		return holder;
	}

	private com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun baselineRun() {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun run =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun();
		run.setId(100L);
		run.setTenantId("tenant-1");
		run.setSuiteId(50L);
		run.setStatus("success");
		run.setExecutionIntent("LIVE");
		run.setTotalCount(2);
		run.setSuccessCount(2);
		run.setFailedCount(0);
		run.setHardFailCount(0);
		run.setWriteViolationCount(0);
		run.setIsolationViolationCount(0);
		run.setAverageScore(java.math.BigDecimal.valueOf(80));
		return run;
	}

	private com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun sandboxRun(String executionIntent,
			int writeViolations, int isolationViolations) {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun run =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun();
		run.setId(200L);
		run.setTenantId("tenant-1");
		run.setSuiteId(50L);
		run.setStatus("success");
		run.setExecutionIntent(executionIntent);
		run.setTotalCount(2);
		run.setSuccessCount(2);
		run.setFailedCount(0);
		run.setHardFailCount(0);
		run.setWriteViolationCount(writeViolations);
		run.setIsolationViolationCount(isolationViolations);
		run.setAverageScore(java.math.BigDecimal.valueOf(90));
		return run;
	}

	private com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase evalCase(Long id, String tagsJson) {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase evalCase =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase();
		evalCase.setId(id);
		evalCase.setTenantId("tenant-1");
		evalCase.setSuiteId(50L);
		evalCase.setTagsJson(tagsJson);
		return evalCase;
	}

	private com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult caseResult(Long caseId, String status) {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult result =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult();
		result.setCaseId(caseId);
		result.setStatus(status);
		result.setTenantId("tenant-1");
		return result;
	}

	private com.sn68.agent.dataagent.optimization.dto.OptCandidateEvalRequest evalRequest(Long sandboxRunId) {
		com.sn68.agent.dataagent.optimization.dto.OptCandidateEvalRequest request =
				new com.sn68.agent.dataagent.optimization.dto.OptCandidateEvalRequest();
		request.setCandidateId(2L);
		request.setSandboxRunId(sandboxRunId);
		return request;
	}

	private void mockTenant() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code");
		when(authenticationContext.userId()).thenReturn("user-1");
	}

	private DataAgentOptExperiment experiment(Long id) {
		DataAgentOptExperiment experiment = new DataAgentOptExperiment();
		experiment.setId(id);
		experiment.setTenantId("tenant-1");
		experiment.setTenantCode("tenant-code");
		experiment.setBaselineRunId(100L);
		return experiment;
	}

	private com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject dataAgentSubject(Long id,
			String agentId) {
		com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject subject =
				new com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject();
		subject.setId(id);
		subject.setTenantId("tenant-1");
		subject.setSubjectType("DATA_AGENT");
		subject.setSubjectId(agentId);
		return subject;
	}

	private DataAgentOptCandidate candidate(Long id) {
		DataAgentOptCandidate candidate = new DataAgentOptCandidate();
		candidate.setId(id);
		candidate.setTenantId("tenant-1");
		candidate.setTenantCode("tenant-code");
		candidate.setExperimentId(1L);
		candidate.setBeforeHash("before-hash");
		candidate.setAfterHash("after-hash");
		return candidate;
	}

	private OptCandidateRequest candidateRequest() {
		OptCandidateRequest request = new OptCandidateRequest();
		request.setCandidateName("候选版本");
		request.setTargetType("PROMPT");
		request.setTargetId("agent-1");
		request.setBeforeHash("before-hash");
		request.setAfterHash("after-hash");
		request.setPatchJson("{}");
		return request;
	}

}
