package com.sn68.agent.dataagent.evaluation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.evaluation.adapter.AgentEvalAdapter;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapRequest;
import com.sn68.agent.dataagent.evaluation.dto.EvalBootstrapResult;
import com.sn68.agent.dataagent.evaluation.dto.EvalFailureClusterDTO;
import com.sn68.agent.dataagent.evaluation.dto.EvalResultDetailDTO;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCaseResult;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalPolicy;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
import com.sn68.agent.dataagent.evaluation.enums.AgentEvaluationErrorDict;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalCaseResultMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalPolicyMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalRunMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSubjectMapper;
import com.sn68.agent.dataagent.evaluation.repository.DataAgentEvalSuiteMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.entity.SuperEntity;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentEvaluationServiceTest {

	private final DataAgentEvalPolicyMapper policyMapper = mock(DataAgentEvalPolicyMapper.class);

	private final DataAgentEvalSubjectMapper subjectMapper = mock(DataAgentEvalSubjectMapper.class);

	private final DataAgentEvalSuiteMapper suiteMapper = mock(DataAgentEvalSuiteMapper.class);

	private final DataAgentEvalCaseMapper caseMapper = mock(DataAgentEvalCaseMapper.class);

	private final DataAgentEvalRunMapper runMapper = mock(DataAgentEvalRunMapper.class);

	private final DataAgentEvalCaseResultMapper resultMapper = mock(DataAgentEvalCaseResultMapper.class);

	private final DataChatTurnService turnService = mock(DataChatTurnService.class);

	private final DataAgentService dataAgentService = mock(DataAgentService.class);

	private final DigitalEmployeeMapper digitalEmployeeMapper = mock(DigitalEmployeeMapper.class);

	private final EmployeeDeploymentService employeeDeploymentService = mock(EmployeeDeploymentService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

	@Test
	void initRejectsDuplicatedAdapterCode() {
		AgentEvalAdapter first = adapter("DATA_AGENT");
		AgentEvalAdapter second = adapter("data_agent");
		AgentEvaluationService service = service(List.of(first, second));

		CheckedException ex = assertThrows(CheckedException.class, service::initEvaluationExecutor);

		assertEquals(AgentEvaluationErrorDict.ADAPTER_CODE_DUPLICATED.getValue(), ex.getCode());
	}

	@Test
	void resultDetailDoesNotLoadSourceDiagnostics() {
		DataAgentEvalCaseResult result = new DataAgentEvalCaseResult();
		result.setId(1L);
		result.setTenantId("tenant-1");
		result.setSessionId(2L);
		result.setRuntimeRequestId("runtime-1");
		doReturn(result).when(resultMapper).findActiveById(1L);
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		EvalResultDetailDTO detail = service.getResultDetail(1L);

		assertSame(result, detail.result());
		assertNull(detail.diagnostics());
		verifyNoInteractions(turnService);
	}

	@Test
	void tenantScopedResultIsHiddenWhenTheTenantContextIsUnavailable() {
		DataAgentEvalCaseResult result = new DataAgentEvalCaseResult();
		result.setId(1L);
		result.setTenantId("tenant-1");
		doReturn(result).when(resultMapper).findActiveById(1L);
		when(authenticationContext.tenantId()).thenThrow(new IllegalStateException("鉴权上下文不可用"));
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.getResultDetail(1L));

		assertEquals(AgentEvaluationErrorDict.CASE_NOT_FOUND.getValue(), ex.getCode());
	}

	@Test
	void platformLevelRowWithoutOwningTenantStaysVisible() {
		DataAgentEvalCaseResult result = new DataAgentEvalCaseResult();
		result.setId(2L);
		doReturn(result).when(resultMapper).findActiveById(2L);
		when(authenticationContext.tenantId()).thenReturn(null);
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		assertSame(result, service.getResultDetail(2L).result());
	}

	@Test
	void resolvePolicyUsesTenantDefaultBeforeGlobalDefault() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		DataAgentEvalPolicy tenantDefault = policy(10L, "tenant-1");
		when(policyMapper.findDefault("tenant-1", "DATA_AGENT")).thenReturn(tenantDefault);
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		DataAgentEvalPolicy resolved = ReflectionTestUtils.invokeMethod(service, "resolvePolicy", null, null,
				"DATA_AGENT");

		assertSame(tenantDefault, resolved);
		verify(policyMapper, never()).findGlobalDefault("DATA_AGENT");
	}

	@Test
	void resolvePolicyRejectsMissingTenantDefault() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(policyMapper.findDefault("tenant-1", "DATA_AGENT")).thenReturn(null);
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		assertThrows(CheckedException.class,
				() -> ReflectionTestUtils.invokeMethod(service, "resolvePolicy", null, null, "DATA_AGENT"));
		verify(policyMapper, never()).findGlobalDefault("DATA_AGENT");
	}

	@Test
	void clusterFailuresGroupsReasonCodesAndSlowCases() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		DataAgentEvalRun run = new DataAgentEvalRun();
		run.setId(100L);
		run.setTenantId("tenant-1");
		run.setStatus("success");
		doReturn(run).when(runMapper).findActiveById(100L);
		when(resultMapper.findByRunId(100L)).thenReturn(List.of(
				evalResult(1L, "failed", true, "[{\"reasonCode\":\"CODE_ORACLE_MISMATCH\"}]", 1_000L),
				evalResult(2L, "failed", false, "[{\"reasonCode\":\"CODE_ORACLE_MISMATCH\"}]", 1_000L),
				evalResult(3L, "timeout", true, "[{\"reasonCode\":\"AGENT_INVOKE_TIMEOUT\"}]", 120_000L)));
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		EvalFailureClusterDTO cluster = service.clusterFailures(100L);

		assertEquals(3, cluster.failedCount());
		assertEquals(2, cluster.hardFailCount());
		assertEquals(2L, cluster.failureReasons()
			.stream()
			.filter(reason -> "CODE_ORACLE_MISMATCH".equals(reason.get("reasonCode")))
			.findFirst()
			.map(reason -> reason.get("count"))
			.orElse(null));
		assertTrue(cluster.slowCases().stream().anyMatch(item -> "MODEL_CALL_SLOW".equals(item.get("reasonCode"))));
		assertEquals(cluster.failureReasons(), service.getRunFailureSummary(100L).failureReasons());
		assertEquals(cluster.slowCases(), service.getRunEfficiencySummary(100L).slowReasons());
	}

	@Test
	void bootstrapDefaultsCreatesThenReusesDefaults() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-1");
		DataAgent agent = new DataAgent();
		agent.setId(100L);
		agent.setName("销售分析");
		agent.setStatus(AgentStatusConstant.PUBLISHED);
		when(dataAgentService.list(AgentStatusConstant.PUBLISHED, null)).thenReturn(List.of(agent));
		stubBootstrapTables();
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setAgentId(100L);
		request.setRunNow(false);

		EvalBootstrapResult created = service.bootstrapDefaults(request);
		EvalBootstrapResult reused = service.bootstrapDefaults(request);

		assertEquals("created", created.getPolicy().getStatus());
		assertEquals("created", created.getSubject().getStatus());
		assertEquals("created", created.getSuite().getStatus());
		assertEquals("created", created.getEvalCase().getStatus());
		assertEquals("reused", reused.getPolicy().getStatus());
		assertEquals(created.getPolicyId(), reused.getPolicyId());
		assertEquals(created.getSubjectId(), reused.getSubjectId());
		assertEquals(created.getSuiteId(), reused.getSuiteId());
		assertEquals(created.getCaseId(), reused.getCaseId());
	}

	@Test
	void bootstrapRejectsWhenBothOrNeitherOwnerGiven() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));

		assertEquals(AgentEvaluationErrorDict.REQUEST_INVALID.getValue(),
				assertThrows(CheckedException.class, () -> service.bootstrapDefaults(new EvalBootstrapRequest())).getCode());
		EvalBootstrapRequest both = new EvalBootstrapRequest();
		both.setAgentId(100L);
		both.setEmployeeId(9L);
		assertEquals(AgentEvaluationErrorDict.REQUEST_INVALID.getValue(),
				assertThrows(CheckedException.class, () -> service.bootstrapDefaults(both)).getCode());
	}

	@Test
	void employeeBootstrapCreatesThenReusesDefaults() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-1");
		DigitalEmployee employee = employee(9L, "运营分析员", "运营分析");
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "tenant-1")).thenReturn(employee);
		when(employeeDeploymentService.findCurrent(9L, "PRODUCTION")).thenReturn(deployment(21L, 3));
		when(employeeDeploymentService.findCurrent(9L, "SANDBOX")).thenReturn(deployment(21L, 1));
		stubEmployeeBootstrapTables();
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setEmployeeId(9L);
		request.setRunNow(false);

		EvalBootstrapResult created = service.bootstrapDefaults(request);
		EvalBootstrapResult reused = service.bootstrapDefaults(request);

		assertEquals("created", created.getPolicy().getStatus());
		assertEquals("created", created.getSubject().getStatus());
		assertEquals("created", created.getSuite().getStatus());
		assertEquals("created", created.getEvalCase().getStatus());
		assertEquals("reused", reused.getPolicy().getStatus());
		assertEquals(created.getPolicyId(), reused.getPolicyId());
		assertEquals(created.getSubjectId(), reused.getSubjectId());
		assertEquals(created.getSuiteId(), reused.getSuiteId());
		assertEquals(created.getCaseId(), reused.getCaseId());
		verify(employeeDeploymentService, never()).activate(anyLong(), any());
	}

	@Test
	void employeeBootstrapFillsSandboxFromProductionRelease() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-1");
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "tenant-1")).thenReturn(employee(9L, "运营分析员", null));
		when(employeeDeploymentService.findCurrent(9L, "PRODUCTION")).thenReturn(deployment(21L, 3));
		when(employeeDeploymentService.findCurrent(9L, "SANDBOX")).thenReturn(null);
		stubEmployeeBootstrapTables();
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setEmployeeId(9L);

		service.bootstrapDefaults(request);

		ArgumentCaptor<EmployeeDeploymentActivateReq> captor = ArgumentCaptor.forClass(EmployeeDeploymentActivateReq.class);
		verify(employeeDeploymentService).activate(eq(9L), captor.capture());
		assertEquals(21L, captor.getValue().getReleaseId());
		assertEquals("SANDBOX", captor.getValue().getEnvironment());
		assertEquals(0, captor.getValue().getExpectVersion());
	}

	@Test
	void employeeBootstrapSkipsSandboxActivateWhenAlreadyPinned() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-1");
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "tenant-1")).thenReturn(employee(9L, "运营分析员", null));
		when(employeeDeploymentService.findCurrent(9L, "PRODUCTION")).thenReturn(deployment(21L, 3));
		when(employeeDeploymentService.findCurrent(9L, "SANDBOX")).thenReturn(deployment(21L, 1));
		stubEmployeeBootstrapTables();
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setEmployeeId(9L);

		service.bootstrapDefaults(request);

		verify(employeeDeploymentService, never()).activate(anyLong(), any());
	}

	@Test
	void employeeBootstrapFailsWithoutProductionRelease() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "tenant-1")).thenReturn(employee(9L, "运营分析员", null));
		when(employeeDeploymentService.findCurrent(9L, "PRODUCTION")).thenReturn(null);
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setEmployeeId(9L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.bootstrapDefaults(request));

		assertEquals(AgentEvaluationErrorDict.EMPLOYEE_PRODUCTION_RELEASE_REQUIRED.getValue(), ex.getCode());
		verify(policyMapper, never()).insert(org.mockito.ArgumentMatchers.<DataAgentEvalPolicy>any());
	}

	@Test
	void employeeBootstrapFailsWhenEmployeeMissing() {
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(digitalEmployeeMapper.findByIdAndTenantId(9L, "tenant-1")).thenReturn(null);
		AgentEvaluationService service = service(List.of(adapter("DATA_AGENT")));
		EvalBootstrapRequest request = new EvalBootstrapRequest();
		request.setEmployeeId(9L);

		CheckedException ex = assertThrows(CheckedException.class, () -> service.bootstrapDefaults(request));

		assertEquals(AgentEvaluationErrorDict.EMPLOYEE_NOT_FOUND.getValue(), ex.getCode());
	}

	private static EvalRuntimeRunImportEnricher passthroughEnricher() {
		EvalRuntimeRunImportEnricher enricher = mock(EvalRuntimeRunImportEnricher.class);
		when(enricher.enrichTags(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(enricher.enrichExpectedOutput(nullable(String.class), any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(0));
		return enricher;
	}

	private AgentEvaluationService service(List<AgentEvalAdapter> adapters) {
		stubTransactionTemplate();
		return new AgentEvaluationService(policyMapper, subjectMapper, suiteMapper, caseMapper, runMapper, resultMapper,
				turnService, mock(RuntimeRunService.class), mock(AgentRuntimeArtifactMapper.class),
				passthroughEnricher(),
				dataAgentService, mock(com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService.class),
				digitalEmployeeMapper, employeeDeploymentService,
				authenticationContext, objectMapper,
				adapters, new DataAgentProperties(), transactionTemplate,
				mock(org.springframework.beans.factory.ObjectProvider.class));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private void stubTransactionTemplate() {
		when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
			TransactionCallback callback = invocation.getArgument(0);
			return callback.doInTransaction(new SimpleTransactionStatus());
		});
	}

	private void stubEmployeeBootstrapTables() {
		Map<Long, DataAgentEvalPolicy> policies = new LinkedHashMap<>();
		Map<Long, DataAgentEvalSubject> subjects = new LinkedHashMap<>();
		Map<Long, DataAgentEvalSuite> suites = new LinkedHashMap<>();
		Map<Long, DataAgentEvalCase> cases = new LinkedHashMap<>();
		stubInsertAndSelect(policyMapper, policies, new AtomicLong(1L));
		stubInsertAndSelect(subjectMapper, subjects, new AtomicLong(1L));
		stubInsertAndSelect(suiteMapper, suites, new AtomicLong(1L));
		stubInsertAndSelect(caseMapper, cases, new AtomicLong(1L));
		when(policyMapper.findByCode("tenant-1", "DIGITAL_EMPLOYEE_DEFAULT"))
			.thenAnswer(invocation -> policies.values().stream().findFirst().orElse(null));
		when(policyMapper.clearDefault(eq("tenant-1"), eq("DIGITAL_EMPLOYEE_CANDIDATE"), anyLong(), any()))
			.thenReturn(0);
		when(subjectMapper.findByIdentity("tenant-1", "DIGITAL_EMPLOYEE_CANDIDATE", "9", "DIGITAL_EMPLOYEE_CANDIDATE"))
			.thenAnswer(invocation -> subjects.values().stream().findFirst().orElse(null));
		when(suiteMapper.findBySubjectAndName(eq("tenant-1"), anyLong(), eq("运营分析员 默认评估集")))
			.thenAnswer(invocation -> suites.values().stream().findFirst().orElse(null));
		when(caseMapper.findBySuiteAndName(eq("tenant-1"), anyLong(), eq("默认冒烟用例")))
			.thenAnswer(invocation -> cases.values().stream().findFirst().orElse(null));
	}

	private DigitalEmployee employee(Long id, String name, String jobTitle) {
		DigitalEmployee employee = new DigitalEmployee();
		employee.setId(id);
		employee.setTenantId("tenant-1");
		employee.setEmployeeName(name);
		employee.setJobTitle(jobTitle);
		return employee;
	}

	private DigitalEmployeeDeployment deployment(Long activeReleaseId, int version) {
		return DigitalEmployeeDeployment.builder()
			.activeReleaseId(activeReleaseId)
			.deploymentVersion(version)
			.build();
	}

	private void stubBootstrapTables() {
		Map<Long, DataAgentEvalPolicy> policies = new LinkedHashMap<>();
		Map<Long, DataAgentEvalSubject> subjects = new LinkedHashMap<>();
		Map<Long, DataAgentEvalSuite> suites = new LinkedHashMap<>();
		Map<Long, DataAgentEvalCase> cases = new LinkedHashMap<>();
		stubInsertAndSelect(policyMapper, policies, new AtomicLong(1L));
		stubInsertAndSelect(subjectMapper, subjects, new AtomicLong(1L));
		stubInsertAndSelect(suiteMapper, suites, new AtomicLong(1L));
		stubInsertAndSelect(caseMapper, cases, new AtomicLong(1L));
		when(policyMapper.findByCode("tenant-1", "DATA_AGENT_DEFAULT"))
			.thenAnswer(invocation -> policies.values().stream().findFirst().orElse(null));
		when(policyMapper.clearDefault(eq("tenant-1"), eq("DATA_AGENT"), anyLong(), any()))
			.thenReturn(0);
		when(subjectMapper.findByIdentity("tenant-1", "DATA_AGENT", "100", "DATA_AGENT"))
			.thenAnswer(invocation -> subjects.values().stream().findFirst().orElse(null));
		when(suiteMapper.findBySubjectAndName(eq("tenant-1"), anyLong(), eq("销售分析 默认评估集")))
			.thenAnswer(invocation -> suites.values().stream().findFirst().orElse(null));
		when(caseMapper.findBySuiteAndName(eq("tenant-1"), anyLong(), eq("默认冒烟用例")))
			.thenAnswer(invocation -> cases.values().stream().findFirst().orElse(null));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private <T extends SuperEntity<Long>> void stubInsertAndSelect(SuperMapper<T> mapper, Map<Long, T> rows,
			AtomicLong sequence) {
		when(mapper.insert(org.mockito.ArgumentMatchers.<T>any())).thenAnswer(invocation -> {
			T entity = invocation.getArgument(0);
			entity.setId(sequence.getAndIncrement());
			rows.put(entity.getId(), entity);
			return 1;
		});
		when(mapper.selectById(anyLong())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
	}

	private DataAgentEvalPolicy policy(Long id, String tenantId) {
		DataAgentEvalPolicy policy = new DataAgentEvalPolicy();
		policy.setId(id);
		policy.setTenantId(tenantId);
		policy.setSubjectType("DATA_AGENT");
		policy.setStatus("enabled");
		policy.setDefaultFlag(true);
		return policy;
	}

	private DataAgentEvalCaseResult evalResult(Long id, String status, boolean hardFail, String failureReasonsJson,
			long durationMs) {
		DataAgentEvalCaseResult result = new DataAgentEvalCaseResult();
		result.setId(id);
		result.setCaseId(id);
		result.setTenantId("tenant-1");
		result.setStatus(status);
		result.setHardFail(hardFail);
		result.setFailureReasonsJson(failureReasonsJson);
		result.setDurationMs(durationMs);
		return result;
	}

	private AgentEvalAdapter adapter(String code) {
		AgentEvalAdapter adapter = mock(AgentEvalAdapter.class);
		when(adapter.adapterCode()).thenReturn(code);
		when(adapter.supports("DATA_AGENT")).thenReturn(true);
		return adapter;
	}

}
