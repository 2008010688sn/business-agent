/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.evaluation.adapter.AgentEvalAdapter;
import com.sn68.agent.dataagent.evaluation.dto.EvalCaseImportRequest;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSuite;
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
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRunFeedback;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeArtifactMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评估用例从数字员工任务运行（agent_runtime_run）取材的取材映射与分片标签生成。
 *
 * <p>覆盖：Run 的 query/最终回答映射为用例输入与参考输出、最终回答缺失时退回非敏感产物、
 * tenant 分片标签只在能确定来源时才打（PR-1 拆除 Workspace 域后 position 旁路移除，
 * 防止 AgentOptimizationService 的分片门禁恒走降级分支）。
 */
class AgentEvaluationServiceRuntimeRunImportTest {

	private static final String TENANT_ID = "7";

	private static final Long RUN_ID = 101L;

	private static final Long SUITE_ID = 5L;

	private static final Long AGENT_ID = 21L;

	private static final Long RELEASE_ID = 31L;

	private static final TypeReference<List<String>> TAG_LIST_TYPE = new TypeReference<>() {
	};

	private final DataAgentEvalSuiteMapper suiteMapper = mock(DataAgentEvalSuiteMapper.class);

	private final DataAgentEvalCaseMapper caseMapper = mock(DataAgentEvalCaseMapper.class);

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final AgentRuntimeArtifactMapper artifactMapper = mock(AgentRuntimeArtifactMapper.class);

	private final DigitalEmployeeMapper employeeMapper = mock(DigitalEmployeeMapper.class);

	private final AgentRuntimeRunFeedbackMapper feedbackMapper = mock(AgentRuntimeRunFeedbackMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final AtomicLong caseIdSequence = new AtomicLong(1000L);

	private AgentEvaluationService service;

	@BeforeEach
	void setUp() {
		when(authenticationContext.tenantId()).thenReturn(String.valueOf(TENANT_ID));
		when(authenticationContext.tenantCode()).thenReturn("tenant-code-7");
		DataAgentEvalSuite suite = new DataAgentEvalSuite();
		suite.setId(SUITE_ID);
		suite.setTenantId(String.valueOf(TENANT_ID));
		when(suiteMapper.findEnabledById(SUITE_ID)).thenReturn(suite);
		when(caseMapper.insert(any(DataAgentEvalCase.class))).thenAnswer(invocation -> {
			DataAgentEvalCase inserted = invocation.getArgument(0);
			inserted.setId(caseIdSequence.getAndIncrement());
			when(caseMapper.selectById(inserted.getId())).thenReturn(inserted);
			return 1;
		});
		when(artifactMapper.listByRunId(anyLong())).thenReturn(List.of());
		when(feedbackMapper.listByTenantAndRun(any(), anyLong())).thenReturn(List.of());
		service = new AgentEvaluationService(mock(DataAgentEvalPolicyMapper.class),
				mock(DataAgentEvalSubjectMapper.class), suiteMapper, caseMapper, mock(DataAgentEvalRunMapper.class),
				mock(DataAgentEvalCaseResultMapper.class), mock(DataChatTurnService.class), runtimeRunService,
				artifactMapper, new EvalRuntimeRunImportEnricher(employeeMapper, feedbackMapper),
				mock(DataAgentService.class),
				mock(com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService.class),
				employeeMapper,
				mock(com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService.class), authenticationContext,
				objectMapper,
				List.of(mock(AgentEvalAdapter.class)), new DataAgentProperties(), mock(TransactionTemplate.class),
				mock(org.springframework.beans.factory.ObjectProvider.class));
	}

	@Test
	void importsRunQueryAsInputAndFinalAnswerAsExpectedOutput() {
		stubRun(run("生成本月结算日报", "本月结算完成，共 12 笔"));

		DataAgentEvalCase imported = service.importCase(importRequest());

		assertEquals("生成本月结算日报", imported.getUserInput());
		assertEquals("本月结算完成，共 12 笔", imported.getExpectedOutput());
		assertEquals("运行回流-" + RUN_ID, imported.getCaseName());
		assertTrue(imported.getTraceSnapshotJson().contains("RUNTIME_RUN"));
	}

	@Test
	void tagsTenantShardEvenWhenRunHasNoDigitalEmployee() {
		stubRun(run("生成本月结算日报", "已完成"));

		List<String> tags = readTags(service.importCase(importRequest()));

		assertEquals(List.of("tenant:" + TENANT_ID), tags);
	}

	@Test
	void tagsPositionWhenEmployeeJobTitlePresent() {
		AgentRuntimeRun source = run("生成本月结算日报", "已完成");
		source.setDigitalEmployeeId(9L);
		stubRun(source);
		when(employeeMapper.findByIdAndTenantId(9L, String.valueOf(TENANT_ID)))
			.thenReturn(DigitalEmployee.builder().id(9L).jobTitle("运营分析").build());

		List<String> tags = readTags(service.importCase(importRequest()));

		assertEquals(List.of("tenant:" + TENANT_ID, "position:运营分析"), tags);
	}

	@Test
	void doesNotTagPositionWhenJobTitleBlank() {
		AgentRuntimeRun source = run("生成本月结算日报", "已完成");
		source.setDigitalEmployeeId(9L);
		stubRun(source);
		when(employeeMapper.findByIdAndTenantId(9L, String.valueOf(TENANT_ID)))
			.thenReturn(DigitalEmployee.builder().id(9L).jobTitle("  ").build());

		List<String> tags = readTags(service.importCase(importRequest()));

		assertEquals(List.of("tenant:" + TENANT_ID), tags);
	}

	@Test
	void downFeedbackCommentBecomesExpectedOutputWhenAnswerBlank() {
		stubRun(run("汇总昨日异常箱", null));
		when(feedbackMapper.listByTenantAndRun(String.valueOf(TENANT_ID), RUN_ID))
			.thenReturn(List.of(AgentRuntimeRunFeedback.builder()
				.rating("DOWN")
				.comment("应列出华东仓库存")
				.build()));

		DataAgentEvalCase imported = service.importCase(importRequest());

		assertEquals("应列出华东仓库存", imported.getExpectedOutput());
		assertTrue(readTags(imported).contains("feedback:DOWN"));
	}

	@Test
	void keepsCallerTagsAndDeduplicatesTenantShard() {
		stubRun(run("生成本月结算日报", "已完成"));
		EvalCaseImportRequest request = importRequest();
		request.setTags(List.of("bizType:settlement", "tenant:" + TENANT_ID));

		List<String> tags = readTags(service.importCase(request));

		assertEquals(List.of("bizType:settlement", "tenant:" + TENANT_ID), tags);
	}

	@Test
	void fallsBackToLatestNonSensitiveArtifactWhenFinalAnswerIsBlank() {
		stubRun(run("汇总昨日异常箱", null));
		when(artifactMapper.listByRunId(RUN_ID))
			.thenReturn(List.of(artifact("INTERNAL", "{\"total\":3}"), artifact("SENSITIVE", "{\"idCard\":\"x\"}")));

		DataAgentEvalCase imported = service.importCase(importRequest());

		assertEquals("{\"total\":3}", imported.getExpectedOutput());
	}

	@Test
	void leavesExpectedOutputEmptyWhenOnlySensitiveArtifactsExist() {
		stubRun(run("汇总昨日异常箱", null));
		when(artifactMapper.listByRunId(RUN_ID)).thenReturn(List.of(artifact("SENSITIVE", "{\"idCard\":\"x\"}")));

		assertNull(service.importCase(importRequest()).getExpectedOutput());
	}

	@Test
	void rejectsRunWithoutQuery() {
		stubRun(run("   ", "已完成"));

		CheckedException ex = assertThrows(CheckedException.class, () -> service.importCase(importRequest()));

		assertTrue(ex.getMessage().contains("无法作为评估用例输入"));
	}

	private EvalCaseImportRequest importRequest() {
		EvalCaseImportRequest request = new EvalCaseImportRequest();
		request.setSuiteId(SUITE_ID);
		request.setRuntimeRunId(RUN_ID);
		return request;
	}

	private AgentRuntimeRun run(String query, String finalAnswer) {
		return AgentRuntimeRun.builder()
			.id(RUN_ID)
			.tenantId(TENANT_ID)
			.agentId(AGENT_ID)
			.releaseId(RELEASE_ID)
			.runtimeRequestId("rr-1")
			.triggerSource("SCHEDULE")
			.runMode("ORCHESTRATION")
			.state("SUCCEEDED")
			.query(query)
			.finalAnswer(finalAnswer)
			.build();
	}

	private void stubRun(AgentRuntimeRun run) {
		RuntimeRunResp resp = RuntimeRunResp.from(run);
		when(runtimeRunService.detail(TENANT_ID, RUN_ID))
			.thenReturn(new RuntimeRunDetailResp(resp, run.getFinalAnswer(), null, null, List.of(), 0L));
	}

	private AgentRuntimeArtifact artifact(String sensitivity, String data) {
		return AgentRuntimeArtifact.builder().runId(RUN_ID).sensitivity(sensitivity).data(data).build();
	}

	private List<String> readTags(DataAgentEvalCase imported) {
		try {
			return objectMapper.readValue(imported.getTagsJson(), TAG_LIST_TYPE);
		}
		catch (Exception ex) {
			throw new IllegalStateException("解析用例标签失败: " + imported.getTagsJson(), ex);
		}
	}

}
