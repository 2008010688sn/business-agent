/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DeterministicSqlGuardRejectedException;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerResult;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceExplorerService;
import com.sn68.agent.dataagent.agentscope.tool.sqlguard.SqlVerifyExplainService;
import com.sn68.agent.dataagent.agentscope.tool.sqlguard.SqlVerifyExplainService.SqlGuardVerification;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.aimodelconfig.DeterministicPlannerModel;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.tokenusage.AgentModelCallResult;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataScopeType;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

class DeterministicSkillExecutorTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final ExecutorService DEADLINE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "deterministic-executor-test");
		thread.setDaemon(true);
		return thread;
	});

	@Test
	void executeReturnsEscapedMarkdownAndUsesEffectivePolicy() throws Exception {
		Fixture fixture = fixture(Map.of());
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request())))
			.thenReturn(DatasourceExplorerResult.builder().searchReady(true).columns(List.of(Map.of("name", "id")))
				.rows(List.of(Map.of("id", "<unsafe>|value"))).returnedRows(1).hasMore(false).build());

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertTrue(result.handled());
		assertEquals(SkillExecutionOutcome.SUCCEEDED, result.outcome());
		assertEquals("DETERMINISTIC_DONE", result.stageCode());
		assertNull(result.uiMessage());
		assertTrue(result.answer().contains("## 查询结果"));
		assertTrue(result.answer().contains("&lt;unsafe&gt;\\|value"));
		assertFalse(result.answer().contains("deterministic_query_result"));
		assertFalse(result.answer().toLowerCase().contains("select id from orders"));

		ArgumentCaptor<AgentTokenUsageContext> usageCaptor = ArgumentCaptor.forClass(AgentTokenUsageContext.class);
		verify(fixture.tokenUsageService).callAndRecordDetailed(eq(fixture.chatModel), anyString(), usageCaptor.capture());
		assertEquals(2048L, usageCaptor.getValue().maxTokens());
		ArgumentCaptor<DatasourceExplorerRequest> requestCaptor = ArgumentCaptor.forClass(DatasourceExplorerRequest.class);
		verify(fixture.datasourceExplorerService).execute(requestCaptor.capture(), eq(fixture.context.request()));
		assertTrue(requestCaptor.getValue().isRequireAuthorizedBaseTable());
		assertEquals(6, requestCaptor.getValue().getStatementTimeoutSeconds());
		assertTrue(requestCaptor.getValue().isSkipPhysicalRelationMetadata());
		assertNotNull(requestCaptor.getValue().getStaticGuardPassedCallback());
		org.mockito.InOrder progressOrder = org.mockito.Mockito.inOrder(fixture.runtimeProgressService);
		progressOrder.verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("GUARD"),
				eq(AgentRuntimeProgressService.STATUS_RUNNING), isNull());
		progressOrder.verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("GUARD"),
				eq(AgentRuntimeProgressService.STATUS_SUCCESS), any());
		progressOrder.verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("SEARCH"),
				eq(AgentRuntimeProgressService.STATUS_RUNNING), isNull());
		progressOrder.verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("SEARCH"),
				eq(AgentRuntimeProgressService.STATUS_SUCCESS), any());
	}

	@Test
	void presentationBoundsRowsCellsAndMarkdown() throws Exception {
		Fixture fixture = fixture(Map.of());
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		String longCell = "x".repeat(DeterministicSkillExecutor.MAX_PRESENTATION_CELL_CHARS + 1);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int index = 0; index <= DeterministicSkillExecutor.MAX_PRESENTATION_ROWS; index++) {
			rows.add(Map.of("id", index, "payload", longCell));
		}
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request())))
			.thenReturn(DatasourceExplorerResult.builder().searchReady(true)
				.columns(List.of(Map.of("name", "id"), Map.of("name", "payload"))).rows(rows)
				.returnedRows(rows.size()).hasMore(true).build());

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals(SkillExecutionOutcome.SUCCEEDED, result.outcome());
		assertTrue(result.answer().length() <= DeterministicSkillExecutor.MAX_PRESENTATION_MARKDOWN_CHARS);
		assertTrue(result.answer().contains("展示内容已按安全上限截断。"));
		assertFalse(result.answer().contains(longCell));
		long tableLines = result.answer().lines().filter(line -> line.startsWith("| ")).count();
		assertTrue(tableLines <= DeterministicSkillExecutor.MAX_PRESENTATION_ROWS + 2L);
	}

	@Test
	void presentationDeadlineFailsWithAVisibleProgressFailure() throws Exception {
		Fixture fixture = fixture(Map.of());
		fixture.dataAgentProperties.getRuntime().getDeterministic().setTotalTimeout(Duration.ofSeconds(3));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setPlannerTimeout(Duration.ofMillis(100));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setSqlTimeout(Duration.ofSeconds(1));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setFinishBuffer(Duration.ofMillis(100));
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request())))
			.thenReturn(DatasourceExplorerResult.builder().searchReady(true).columns(List.of(Map.of("name", "id")))
				.rows(List.of(Map.of("id", new SlowPresentationValue()))).returnedRows(1).hasMore(false).build());

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_DEADLINE_EXHAUSTED", result.stageCode());
		verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("PRESENTING"),
				eq(AgentRuntimeProgressService.STATUS_RUNNING), isNull());
		verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("PRESENTING"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any());
	}

	@Test
	void lengthFinishUsesOneSharedRepairAttempt() throws Exception {
		Fixture fixture = fixture(Map.of());
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select", "LENGTH"),
					modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		stubSearchResult(fixture);

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals(SkillExecutionOutcome.SUCCEEDED, result.outcome());
		verify(fixture.tokenUsageService, times(2)).callAndRecordDetailed(eq(fixture.chatModel), anyString(), any());
		verify(fixture.dynamicModelFactory, times(2)).createDeterministicPlannerModel(eq(fixture.context.modelConfig()),
				any(Duration.class), eq(2048L), eq(DeterministicSkillExecutor.SQL_PLAN_SCHEMA));
	}

	@Test
	void malformedJsonAndGuardRejectionUseTheSameSingleRepairBudget() throws Exception {
		Fixture malformed = fixture(Map.of());
		stubPlanner(malformed);
		when(malformed.tokenUsageService.callAndRecordDetailed(eq(malformed.chatModel), anyString(), any()))
			.thenReturn(modelResult("not json", "STOP"), modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(malformed);
		stubSearchResult(malformed);

		assertEquals(SkillExecutionOutcome.SUCCEEDED, malformed.executor.execute(malformed.context).outcome());
		verify(malformed.tokenUsageService, times(2)).callAndRecordDetailed(eq(malformed.chatModel), anyString(), any());
		verify(malformed.runtimeProgressService).emit(eq(malformed.context.request()), eq("PROTOCOL_VALIDATE"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any());

		Fixture unexpectedField = fixture(Map.of());
		stubPlanner(unexpectedField);
		when(unexpectedField.tokenUsageService.callAndRecordDetailed(eq(unexpectedField.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\",\"extra\":true}", "STOP"),
					modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(unexpectedField);
		stubSearchResult(unexpectedField);

		assertEquals(SkillExecutionOutcome.SUCCEEDED, unexpectedField.executor.execute(unexpectedField.context).outcome());
		verify(unexpectedField.tokenUsageService, times(2)).callAndRecordDetailed(eq(unexpectedField.chatModel), anyString(),
				any());

		Fixture guard = fixture(Map.of());
		stubPlanner(guard);
		when(guard.tokenUsageService.callAndRecordDetailed(eq(guard.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select * from orders\"}", "STOP"),
					modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		when(guard.sqlVerifyExplainService.verifySql(anyString(), anyString(), any()))
			.thenReturn(new SqlGuardVerification(false, null, "SELECT_STAR_FORBIDDEN", "unsafe", List.of()),
					new SqlGuardVerification(true, "SELECT id FROM orders", null, "safe", List.of()));
		stubSearchResult(guard);

		assertEquals(SkillExecutionOutcome.SUCCEEDED, guard.executor.execute(guard.context).outcome());
		verify(guard.tokenUsageService, times(2)).callAndRecordDetailed(eq(guard.chatModel), anyString(), any());
	}

	@Test
	void unexpectedGuardFailureDoesNotRetry() throws Exception {
		Fixture fixture = fixture(Map.of());
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		when(fixture.sqlVerifyExplainService.verifySql(anyString(), anyString(), any()))
			.thenThrow(new IllegalArgumentException("query is required"));

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_GUARD_FAILED", result.stageCode());
		verify(fixture.tokenUsageService).callAndRecordDetailed(eq(fixture.chatModel), anyString(), any());
		verify(fixture.datasourceExplorerService, never()).execute(any(), eq(fixture.context.request()));
	}

	@Test
	void datasourceStaticGuardFailureDoesNotStartSearch() throws Exception {
		Fixture fixture = fixture(Map.of("deterministic", Map.of("maxAttempts", 1)));
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request())))
			.thenThrow(new DeterministicSqlGuardRejectedException("static guard rejected"));

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_SQL_REJECTED", result.stageCode());
		verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("GUARD"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any());
		verify(fixture.runtimeProgressService, never()).emit(eq(fixture.context.request()), eq("SEARCH"), anyString(),
				any());
	}

	@Test
	void repairablePostgreSqlStatesUseOneRepairAttempt() throws Exception {
		for (String sqlState : List.of("42601", "42P01", "42703")) {
			Fixture fixture = fixture(Map.of());
			stubPlanner(fixture);
			when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
				.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"),
						modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
			stubAllowedSql(fixture);
			when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request())))
				.thenThrow(new SQLException("database failure", sqlState))
				.thenReturn(searchResult());

			SkillExecutionResult result = fixture.executor.execute(fixture.context);

			assertEquals(SkillExecutionOutcome.SUCCEEDED, result.outcome(), sqlState);
			verify(fixture.tokenUsageService, times(2)).callAndRecordDetailed(eq(fixture.chatModel), anyString(), any());
		}
	}

	@Test
	void onlyExplicitDatasourceGuardRejectionCanRetry() throws Exception {
		Fixture guardFailure = fixture(Map.of());
		stubPlanner(guardFailure);
		when(guardFailure.tokenUsageService.callAndRecordDetailed(eq(guardFailure.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"),
					modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(guardFailure);
		when(guardFailure.datasourceExplorerService.execute(any(), eq(guardFailure.context.request())))
			.thenThrow(new DeterministicSqlGuardRejectedException("static guard rejected"))
			.thenReturn(searchResult());

		assertEquals(SkillExecutionOutcome.SUCCEEDED, guardFailure.executor.execute(guardFailure.context).outcome());
		verify(guardFailure.tokenUsageService, times(2)).callAndRecordDetailed(eq(guardFailure.chatModel), anyString(), any());

		Fixture nonGuardFailure = fixture(Map.of());
		stubPlanner(nonGuardFailure);
		when(nonGuardFailure.tokenUsageService.callAndRecordDetailed(eq(nonGuardFailure.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(nonGuardFailure);
		when(nonGuardFailure.datasourceExplorerService.execute(any(), eq(nonGuardFailure.context.request())))
			.thenThrow(new IllegalArgumentException("datasource configuration is invalid"));

		SkillExecutionResult result = nonGuardFailure.executor.execute(nonGuardFailure.context);

		assertEquals("DETERMINISTIC_SEARCH_FAILED", result.stageCode());
		verify(nonGuardFailure.tokenUsageService).callAndRecordDetailed(eq(nonGuardFailure.chatModel), anyString(), any());
	}

	@Test
	void permissionTimeoutAndModelFailuresDoNotRetry() throws Exception {
		Fixture permission = fixture(Map.of());
		stubPlanner(permission);
		when(permission.tokenUsageService.callAndRecordDetailed(eq(permission.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(permission);
		when(permission.datasourceExplorerService.execute(any(), eq(permission.context.request())))
			.thenReturn(DatasourceExplorerResult.builder().searchReady(false).build());

		assertEquals("DETERMINISTIC_PERMISSION_REJECTED", permission.executor.execute(permission.context).stageCode());
		verify(permission.tokenUsageService).callAndRecordDetailed(eq(permission.chatModel), anyString(), any());

		Fixture timeout = fixture(Map.of());
		stubPlanner(timeout);
		when(timeout.tokenUsageService.callAndRecordDetailed(eq(timeout.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(timeout);
		when(timeout.datasourceExplorerService.execute(any(), eq(timeout.context.request())))
			.thenThrow(new SQLTimeoutException("timeout", "57014"));

		assertEquals("DETERMINISTIC_SQL_TIMEOUT", timeout.executor.execute(timeout.context).stageCode());
		verify(timeout.tokenUsageService).callAndRecordDetailed(eq(timeout.chatModel), anyString(), any());

		Fixture modelFailure = fixture(Map.of());
		stubPlanner(modelFailure);
		when(modelFailure.tokenUsageService.callAndRecordDetailed(eq(modelFailure.chatModel), anyString(), any()))
			.thenThrow(new IllegalStateException("network failure"));

		assertEquals("DETERMINISTIC_MODEL_FAILED", modelFailure.executor.execute(modelFailure.context).stageCode());
		verify(modelFailure.tokenUsageService).callAndRecordDetailed(eq(modelFailure.chatModel), anyString(), any());
	}

	@Test
	void missingPermissionSnapshotNeverStartsPlanning() {
		Fixture fixture = fixture(Map.of());
		fixture.context.request().setDataPermissionSnapshot(null);

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_PERMISSION_REJECTED", result.stageCode());
		verify(fixture.dynamicModelFactory, never()).createDeterministicPlannerModel(any(), any(), anyLong(), anyString());
	}

	@Test
	void deadlinePreventsASecondPlannerCallWhenRepairWouldExhaustSearchBudget() throws Exception {
		Fixture fixture = fixture(Map.of());
		fixture.dataAgentProperties.getRuntime().getDeterministic().setTotalTimeout(Duration.ofMillis(60));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setPlannerTimeout(Duration.ofMillis(20));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setSqlTimeout(Duration.ofMillis(20));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setFinishBuffer(Duration.ofMillis(10));
		fixture.context.request().setRuntimeDeadline(AgentRuntimeDeadline.start(Duration.ofSeconds(1)));
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any())).thenAnswer(invocation -> {
			Thread.sleep(80L);
			return modelResult("{\"sql\":\"select", "LENGTH");
		});

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_DEADLINE_EXHAUSTED", result.stageCode());
		verify(fixture.tokenUsageService).callAndRecordDetailed(eq(fixture.chatModel), anyString(), any());
	}

	@Test
	void contextPreparationDeadlinePreventsPlannerCall() throws Exception {
		Fixture fixture = fixture(Map.of());
		fixture.dataAgentProperties.getRuntime().getDeterministic().setTotalTimeout(Duration.ofMillis(80));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setPlannerTimeout(Duration.ofMillis(20));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setSqlTimeout(Duration.ofMillis(20));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setFinishBuffer(Duration.ofMillis(10));
		when(fixture.businessContextService.prepare(eq(fixture.context.request()), eq(fixture.context.resources())))
			.thenAnswer(invocation -> {
				Thread.sleep(100L);
				return businessContext(fixture.context.resources());
			});

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_DEADLINE_EXHAUSTED", result.stageCode());
		verify(fixture.dynamicModelFactory, never()).createDeterministicPlannerModel(any(), any(), anyLong(), anyString());
		verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("DETERMINISTIC_PLANNING"),
				eq(AgentRuntimeProgressService.STATUS_RUNNING), isNull());
		verify(fixture.runtimeProgressService).emit(eq(fixture.context.request()), eq("DETERMINISTIC_PLANNING"),
				eq(AgentRuntimeProgressService.STATUS_FAILED), any());
	}

	@Test
	void searchDeadlineCancelsSharedDatasourceCall() throws Exception {
		Fixture fixture = fixture(Map.of());
		fixture.dataAgentProperties.getRuntime().getDeterministic().setTotalTimeout(Duration.ofMillis(150));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setPlannerTimeout(Duration.ofMillis(20));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setSqlTimeout(Duration.ofMillis(30));
		fixture.dataAgentProperties.getRuntime().getDeterministic().setFinishBuffer(Duration.ofMillis(10));
		stubPlanner(fixture);
		when(fixture.tokenUsageService.callAndRecordDetailed(eq(fixture.chatModel), anyString(), any()))
			.thenReturn(modelResult("{\"sql\":\"select id from orders\"}", "STOP"));
		stubAllowedSql(fixture);
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request()))).thenAnswer(invocation -> {
			Thread.sleep(100L);
			return searchResult();
		});

		SkillExecutionResult result = fixture.executor.execute(fixture.context);

		assertEquals("DETERMINISTIC_DEADLINE_EXHAUSTED", result.stageCode());
	}

	private void stubPlanner(Fixture fixture) {
		when(fixture.businessContextService.prepare(eq(fixture.context.request()), eq(fixture.context.resources())))
			.thenReturn(businessContext(fixture.context.resources()));
		when(fixture.dynamicModelFactory.createDeterministicPlannerModel(eq(fixture.context.modelConfig()),
				any(Duration.class), eq(2048L), eq(DeterministicSkillExecutor.SQL_PLAN_SCHEMA)))
			.thenReturn(new DeterministicPlannerModel(fixture.chatModel, ModelStructuredOutputMode.STRICT_JSON_SCHEMA));
		when(fixture.tokenUsageService.buildContext(eq(fixture.context.request()), eq(fixture.context.modelConfig()),
				eq(AgentTokenUsageService.SOURCE_SKILL_DETERMINISTIC))).thenReturn(fixture.usageContext);
	}

	private void stubAllowedSql(Fixture fixture) {
		when(fixture.sqlVerifyExplainService.verifySql(eq("query orders"), anyString(), any()))
			.thenReturn(new SqlGuardVerification(true, "SELECT id FROM orders", null, "safe", List.of()));
	}

	private void stubSearchResult(Fixture fixture) throws Exception {
		when(fixture.datasourceExplorerService.execute(any(), eq(fixture.context.request()))).thenReturn(searchResult());
	}

	private DatasourceExplorerResult searchResult() {
		return DatasourceExplorerResult.builder().searchReady(true).columns(List.of(Map.of("name", "id")))
			.rows(List.of(Map.of("id", "1"))).returnedRows(1).hasMore(false).build();
	}

	private AgentModelCallResult modelResult(String text, String finishReason) {
		return new AgentModelCallResult(text, finishReason, 10L, 20L, 30L, "ACTUAL", 5L);
	}

	private SkillBusinessContext businessContext(SkillVersionResources resources) {
		return new SkillBusinessContext(resources.skillId(), resources.skillVersionId(), "query orders",
				resources.datasource().tables(), "matched", List.of(), "none", List.of(), 0, 0, 0);
	}

	private Fixture fixture(Map<String, Object> runtimeConfig) {
		SkillBusinessContextService businessContextService = org.mockito.Mockito.mock(SkillBusinessContextService.class);
		DatasourceExplorerService datasourceExplorerService = org.mockito.Mockito.mock(DatasourceExplorerService.class);
		SqlVerifyExplainService sqlVerifyExplainService = org.mockito.Mockito.mock(SqlVerifyExplainService.class);
		DynamicModelFactory dynamicModelFactory = org.mockito.Mockito.mock(DynamicModelFactory.class);
		AgentTokenUsageService tokenUsageService = org.mockito.Mockito.mock(AgentTokenUsageService.class);
		AgentRuntimeProgressService runtimeProgressService = org.mockito.Mockito.mock(AgentRuntimeProgressService.class);
		ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
		AgentTokenUsageContext usageContext = AgentTokenUsageContext.builder().agentId(1L).maxTokens(20000L).build();
		DataAgentProperties dataAgentProperties = new DataAgentProperties();
		ModelConfigDTO modelConfig = ModelConfigDTO.builder().id(2L).modelName("test-model").build();
		DataAgentSkill skill = DataAgentSkill.builder().id(7L).skillCode("orders-query")
			.skillKind("QUERY").executionMode("DETERMINISTIC").build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(8L).skillId(7L)
			.skillKind("QUERY").executionMode("DETERMINISTIC").skillMarkdown("orders only").build();
		RouteSelection selection = new RouteSelection(
				new RouteTargetRef(RouteTargetType.SKILL, 7L, 8L, null), 11L, 12L, RouteRisk.READ_ONLY, "checksum");
		SkillVersionResources resources = new SkillVersionResources(7L, 8L,
				new SkillVersionResources.DatasourceResource(10L,
						List.of(new SkillVersionResources.TableScope("orders", List.of("id"))), true, 20, Map.of(), Map.of()),
				List.of(11L), List.of(), runtimeConfig);
		AgentRequest request = AgentRequest.builder().agentId("1").tenantIdSnapshot("tenant-1")
			.query("query orders").routedSkillId(7L).routedSkillVersionId(8L).routedSkillResources(resources)
			.runtimeDeadline(AgentRuntimeDeadline.start(Duration.ofMinutes(2))).build();
		request.setDataPermissionSnapshot(DataPermission.builder().scopeType(DataScopeType.ALL).build());
		SkillExecutionContext context = new SkillExecutionContext(request, DataAgent.builder().id(1L).build(), modelConfig,
				selection, skill, version, resources);
		DeterministicSkillExecutor executor = new DeterministicSkillExecutor(businessContextService,
				datasourceExplorerService, sqlVerifyExplainService, dynamicModelFactory, tokenUsageService, objectMapper,
				dataAgentProperties, runtimeProgressService, DEADLINE_EXECUTOR);
		return new Fixture(executor, businessContextService, datasourceExplorerService, sqlVerifyExplainService,
				dynamicModelFactory, tokenUsageService, chatModel, usageContext, dataAgentProperties, context,
				runtimeProgressService);
	}

	private record Fixture(DeterministicSkillExecutor executor, SkillBusinessContextService businessContextService,
			DatasourceExplorerService datasourceExplorerService, SqlVerifyExplainService sqlVerifyExplainService,
			DynamicModelFactory dynamicModelFactory, AgentTokenUsageService tokenUsageService, ChatModel chatModel,
			AgentTokenUsageContext usageContext, DataAgentProperties dataAgentProperties, SkillExecutionContext context,
			AgentRuntimeProgressService runtimeProgressService) {
	}

	private static final class SlowPresentationValue {

		@Override
		public String toString() {
			try {
				Thread.sleep(4000L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return "late";
		}

	}

}
