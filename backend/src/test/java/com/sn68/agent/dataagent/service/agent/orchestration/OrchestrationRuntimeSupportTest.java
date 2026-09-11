/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.constant.OrchestrationStatus;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.multimodal.ExtractCard;
import com.sn68.agent.dataagent.multimodal.FusionBlock;
import com.sn68.agent.dataagent.multimodal.TurnArtifact;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.routing.RouteUnavailableException;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeMirrorService;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDependencyValueType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteStepInputMapping;
import com.sn68.agent.dataagent.routing.model.RouteStepOutputBinding;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.service.agent.AgentCollaboratorService;
import com.sn68.agent.dataagent.service.agent.AgentOrchestrationPolicyService;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.OrchestrationContinuation;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.OrchestrationExecutionPolicy;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrchestrationRuntimeSupportTest {

	private final AgentCollaboratorService collaboratorService = mock(AgentCollaboratorService.class);

	private final AgentOrchestrationPolicyService policyService = mock(AgentOrchestrationPolicyService.class);

	private final AgentOrchestrationRunMapper runMapper = mock(AgentOrchestrationRunMapper.class);

	private final AgentOrchestrationStepMapper stepMapper = mock(AgentOrchestrationStepMapper.class);

	private final DataAgentMapper dataAgentMapper = mock(DataAgentMapper.class);

	private final CollaboratorDependencyContractService dependencyContractService = mock(
			CollaboratorDependencyContractService.class);

	private final DataAgentProperties properties = new DataAgentProperties();

	// W2 双写镜像为 best-effort（幂等、失败不阻断编排），mock 即可，不断言镜像细节。
	private final RuntimeMirrorService runtimeMirrorService = mock(RuntimeMirrorService.class);

	private final OrchestrationRuntimeSupport support = new OrchestrationRuntimeSupport(collaboratorService,
			policyService, runMapper, stepMapper, dataAgentMapper, properties, dependencyContractService,
			runtimeMirrorService);

	@BeforeEach
	void setUp() {
		when(dependencyContractService.bind(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> invocation.getArgument(0));
		when(dependencyContractService.dependencyInputs(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyList())).thenReturn(Map.of());
		when(dependencyContractService.extractOutput(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.anyString())).thenReturn(Map.of());
	}

	@Test
	void prepareContextLoadsPolicyRunAndCollaboratorsWithoutRoutingModel() {
		AgentOrchestrationPolicy policy = policy();
		policy.setEnabled(true);
		policy.setFailureStrategy("continue");
		policy.setExposeTrace(true);
		AgentCollaborator collaborator = collaborator(20L, 10L);
		AgentOrchestrationRun persistedRun = AgentOrchestrationRun.builder().id(100L).build();
		when(policyService.getOrCreate(1L)).thenReturn(policy);
		when(collaboratorService.listEnabled(1L)).thenReturn(List.of(collaborator));
		doAnswer(invocation -> {
			invocation.<AgentOrchestrationRun>getArgument(0).setId(100L);
			return 1;
		}).when(runMapper).insert(org.mockito.ArgumentMatchers.any(AgentOrchestrationRun.class));
		when(runMapper.selectById(100L)).thenReturn(persistedRun);

		AgentRequest request = parentRequest();
		OrchestrationRuntimeSupport.OrchestrationContext context = support.prepareOrCreateContext(request,
				DataAgent.builder().id(1L).tenantId("tenant-1").build(), modelConfig(99L, "main-model"));

		assertEquals(99L, context.modelConfig().getId());
		assertEquals(List.of(collaborator), context.collaborators());
		assertEquals(100L, context.run().getId());
		assertEquals("continue", request.getOrchestrationFailureStrategy());
		assertTrue(request.getOrchestrationExposeTrace());
	}

	@Test
	void policyFromContinuationUsesFrozenExecutionPolicy() {
		OrchestrationContinuation continuation = new OrchestrationContinuation(100L, 401L, "10", "child-thread",
				"child-runtime", "snapshot task", Map.of(), null, null, DelegationMode.INTERACTIVE.name(),
				new OrchestrationExecutionPolicy("fail_fast", false));

		AgentOrchestrationPolicy policy = support.policyFromContinuation(continuation);

		assertEquals("fail_fast", policy.getFailureStrategy());
		assertFalse(policy.getExposeTrace());
		assertTrue(policy.getEnabled());
		assertThrows(CheckedException.class, () -> support.policyFromContinuation(new OrchestrationContinuation(100L,
				401L, "10", "child-thread", "child-runtime", "snapshot task", Map.of(), null, null,
				DelegationMode.INTERACTIVE.name())));
	}

	@Test
	void buildCollaboratorRequestUsesCollaboratorOwnTimeout() {
		AgentRequest parent = parentRequest();
		parent.setQuery("完整父问题");
		parent.setOrchestrationFailureStrategy("continue");
		parent.setOrchestrationExposeTrace(true);
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).name("协作者10").status("published").runtimeTimeoutSeconds(30).build(),
				"当前子任务", "MODEL_MULTI_SELECTED", "仅返回本步骤结果");

		AgentRequest child = support.buildCollaboratorRequest(parent, route);

		assertEquals(Duration.ofSeconds(30), child.getRuntimeTimeout());
		assertEquals("10", child.getAgentId());
		assertTrue(child.isIsolatedMemory());
		assertTrue(child.isCollaboratorChild());
		assertFalse(child.isReportIntentDetectionEnabled());
		assertEquals("continue", child.getOrchestrationFailureStrategy());
		assertTrue(child.getOrchestrationExposeTrace());
		assertTrue(child.getQuery().startsWith("协作任务：当前子任务\n期望输出：仅返回本步骤结果"));
		assertTrue(child.getQuery().contains("不要输出 JSON"));
		assertFalse(child.getQuery().contains("未匹配到名称"));
		assertNotEquals(parent.getThreadId(), child.getThreadId());
	}

	@Test
	void buildCollaboratorRequestAppendsFusionSummaryWithoutAttachments() {
		AgentRequest parent = parentRequest();
		parent.setTurnArtifact(new com.sn68.agent.dataagent.multimodal.TurnArtifact("art-1", List.of(), List.of(), 10,
				"附件：图片 1 张"));
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).name("协作者10").status("published").runtimeTimeoutSeconds(30).build(),
				"当前子任务", "MODEL_MULTI_SELECTED", "仅返回本步骤结果");

		AgentRequest child = support.buildCollaboratorRequest(parent, route);

		assertTrue(child.getQuery().contains("附件摘要"));
		assertTrue(child.getQuery().contains("图片 1 张"));
		assertTrue(child.getAttachments() == null || child.getAttachments().isEmpty());
	}

	@Test
	void buildCollaboratorRequestKeepsWarningWhenParentQueryAlreadyContainsRouteSummary() {
		String routeSummary = "附件：图片 1 张";
		AgentRequest parent = parentRequest();
		parent.setQuery("请根据附件查账单\n" + routeSummary);
		parent.setTurnArtifact(new TurnArtifact("art-1", List.of(), List.of(), 10, routeSummary));
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).name("协作者10").status("published").runtimeTimeoutSeconds(30).build(),
				parent.getQuery(), "MODEL_MULTI_SELECTED", "仅返回本步骤结果");

		AgentRequest child = support.buildCollaboratorRequest(parent, route);

		assertTrue(child.getQuery().contains("不要假设你看见了原图或原文件")
				|| child.getQuery().contains(ExtractCard.BLOCK_HEADER));
		assertTrue(child.getAttachments() == null || child.getAttachments().isEmpty());
	}

	@Test
	void buildCollaboratorRequestCopiesPointerArtifactAndExtractCard() {
		ExtractCard card = new ExtractCard("一张带表格的对账单截图", "statement",
				List.of(new ExtractCard.VisibleField("账单编号", "D020260528-689", "high")), false, "",
				ExtractCard.STATUS_OK);
		TurnArtifact parentArtifact = new TurnArtifact("art-1",
				List.of(FusionBlock.image(new byte[] { 1, 2, 3 }, "image/png", "x", "key")), List.of(), 10,
				"附件：图片 1 张").withExtractCard(card);
		AgentRequest parent = parentRequest();
		parent.setOriginalUserQuery("查一下这张对账单");
		parent.setTurnArtifact(parentArtifact);
		parent.setExtractCard(card);
		parent.setTurnArtifactId("art-1");
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).name("协作者10").status("published").runtimeTimeoutSeconds(30).build(),
				"当前子任务", "MODEL_MULTI_SELECTED", "仅返回本步骤结果");

		AgentRequest child = support.buildCollaboratorRequest(parent, route);

		assertTrue(child.getQuery().contains(ExtractCard.BLOCK_HEADER));
		assertTrue(child.getQuery().contains(ExtractCard.NOT_ORIGINAL_IMAGE));
		assertFalse(child.getQuery().contains("不要假设你看见了原图或原文件"));
		assertFalse(child.getTurnArtifact().hasPixelImage());
		assertTrue(parent.getTurnArtifact().hasPixelImage());
		assertTrue(child.getAttachments() == null || child.getAttachments().isEmpty());
		assertEquals("查一下这张对账单", child.getOriginalUserQuery());
		assertEquals("查一下这张对账单", child.getRankingIntentQuery());
		assertFalse(child.getRankingIntentQuery().contains(ExtractCard.BLOCK_HEADER));
		assertEquals(card, child.getExtractCard());
		assertEquals("art-1", child.getTurnArtifactId());
	}

	@Test
	void buildCollaboratorRequestStripsPixelPayloadToPointer() {
		AgentRequest parent = parentRequest();
		parent.setTurnArtifact(new TurnArtifact("art-1",
				List.of(FusionBlock.image(new byte[] { 1, 2, 3 }, "image/png", "x", "key")), List.of(), 10,
				"附件：图片 1 张"));

		AgentRequest child = support.buildCollaboratorRequest(parent, route(10L, 30));

		FusionBlock block = child.getTurnArtifact().blocks().get(0);
		assertNull(block.payload());
		assertEquals("key", block.sourceRef());
		assertTrue(block.keepAsImage());
		assertFalse(child.getTurnArtifact().hasPixelImage());
		assertTrue(parent.getTurnArtifact().hasPixelImage());
	}

	@Test
	void collaboratorRouteCarriesPlanStepIdentityAndDependencies() {
		List<String> componentNames = Arrays.stream(CollaboratorRoute.class.getRecordComponents())
			.map(java.lang.reflect.RecordComponent::getName)
			.toList();

		assertTrue(componentNames.contains("stepId"));
		assertTrue(componentNames.contains("dependsOn"));
	}

	@Test
	void createStepPersistsStableRoutePlanStepId() {
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).build();
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).status("published").build(), "task", "matched", "output", "s1",
				List.of());
		AgentOrchestrationStep persisted = AgentOrchestrationStep.builder().id(101L).routeStepId("s1").build();
		doAnswer(invocation -> {
			invocation.<AgentOrchestrationStep>getArgument(0).setId(101L);
			return 1;
		}).when(stepMapper).insert(org.mockito.ArgumentMatchers.any(AgentOrchestrationStep.class));
		when(stepMapper.selectById(101L)).thenReturn(persisted);
		when(stepMapper.findByRunId(100L)).thenReturn(List.of());

		AgentOrchestrationStep step = support.createStep(run, route);

		assertEquals("s1", step.getRouteStepId());
		org.mockito.ArgumentCaptor<AgentOrchestrationStep> captor = org.mockito.ArgumentCaptor
			.forClass(AgentOrchestrationStep.class);
		verify(stepMapper).insert(captor.capture());
		assertEquals("s1", captor.getValue().getRouteStepId());
		assertEquals(10L, captor.getValue().getCollaboratorAgentId());
	}

	@Test
	void createStepDoesNotPersistUnavailableSnapshotCollaboratorId() {
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).build();
		CollaboratorRoute route = new CollaboratorRoute(AgentCollaborator.builder().id(20L).agentId(1L).enabled(false)
			.build(), null, "task", "snapshot unavailable", "output", "s1", List.of());
		AgentOrchestrationStep persisted = AgentOrchestrationStep.builder().id(101L).routeStepId("s1").build();
		doAnswer(invocation -> {
			invocation.<AgentOrchestrationStep>getArgument(0).setId(101L);
			return 1;
		}).when(stepMapper).insert(org.mockito.ArgumentMatchers.any(AgentOrchestrationStep.class));
		when(stepMapper.selectById(101L)).thenReturn(persisted);
		when(stepMapper.findByRunId(100L)).thenReturn(List.of());

		support.createStep(run, route);

		org.mockito.ArgumentCaptor<AgentOrchestrationStep> captor = org.mockito.ArgumentCaptor
			.forClass(AgentOrchestrationStep.class);
		verify(stepMapper).insert(captor.capture());
		assertNull(captor.getValue().getCollaboratorAgentId());
	}

	@Test
	void markStepFailedStoresRouteUnavailableReasonCode() {
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().id(101L).build();

		support.markStepFailed(step, new RouteUnavailableException("CHILD_ROUTE_NO_MATCH"));

		assertEquals(OrchestrationStatus.FAILED, step.getStatus());
		assertEquals("CHILD_ROUTE_NO_MATCH", step.getErrorCode());
		assertEquals("CHILD_ROUTE_NO_MATCH", step.getErrorMessage());
		verify(stepMapper).updateById(step);
	}

	@Test
	void loadTerminalResultsRestoresOnlyCompletedPlanSteps() {
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).build();
		CollaboratorRoute first = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).status("published").build(), "first", "matched", "first output", "s1",
				List.of());
		CollaboratorRoute second = new CollaboratorRoute(collaborator(21L, 11L),
				DataAgent.builder().id(11L).status("published").build(), "second", "matched", "second output", "s2",
				List.of("s1"));
		when(stepMapper.findByRunId(100L)).thenReturn(List.of(
				AgentOrchestrationStep.builder().routeStepId("s1").status("success").answer("first answer")
					.durationMs(12L).build(),
				AgentOrchestrationStep.builder().routeStepId("s2").status("failed").errorMessage("failed")
					.durationMs(8L).build(),
				AgentOrchestrationStep.builder().routeStepId("unknown").status("success").answer("ignore").build()));

		Map<String, CollaboratorExecutionResult> results = support.loadTerminalResults(run, List.of(first, second));

		assertEquals(2, results.size());
		assertTrue(results.get("s1").success());
		assertEquals("first answer", results.get("s1").answer());
		assertFalse(results.get("s2").success());
		assertEquals(8L, results.get("s2").durationMs());
	}

	@Test
	void normalizeRoutesPreservesSeparateStepsAssignedToTheSameCollaborator() {
		DataAgent collaboratorAgent = DataAgent.builder().id(10L).status("published").build();
		CollaboratorRoute first = new CollaboratorRoute(collaborator(20L, 10L), collaboratorAgent,
				"first task", "matched", "first result", "s1", List.of());
		CollaboratorRoute second = new CollaboratorRoute(collaborator(21L, 10L), collaboratorAgent,
				"second task", "matched", "second result", "s2", List.of("s1"));

		List<CollaboratorRoute> normalized = support.normalizeRoutes(List.of(first, second), 2);

		assertEquals(List.of("s1", "s2"), normalized.stream().map(CollaboratorRoute::stepId).toList());
	}

	@Test
	void normalizeRoutesRejectsAPlanThatExceedsThePolicyLimit() {
		List<CollaboratorRoute> routes = List.of(route(10L, 30), route(11L, 30));

		assertThrows(RuntimeException.class, () -> support.normalizeRoutes(routes, 1));
	}

	@Test
	void buildCollaboratorRequestCapsTimeoutAtParentRemainingDeadline() {
		AgentRequest parent = parentRequest();
		parent.setRuntimeDeadline(AgentRuntimeDeadline.start(Duration.ofSeconds(5)));
		parent.setRuntimeFinishBuffer(Duration.ZERO);

		AgentRequest child = support.buildCollaboratorRequest(parent, route(10L, 30));

		assertTrue(child.getRuntimeTimeout().compareTo(Duration.ofSeconds(5)) <= 0);
		assertTrue(!child.getRuntimeTimeout().isZero());
	}

	@Test
	void collaboratorRequestUsesOnlyServerBoundDependencyInputs() {
		CollaboratorRoute firstBase = route(10L, 30);
		CollaboratorRoute unrelatedBase = route(11L, 30);
		CollaboratorRoute targetBase = route(12L, 30);
		CollaboratorRoute first = new CollaboratorRoute(firstBase.collaborator(), firstBase.dataAgent(), "first task",
				"matched", "first output", "s1", List.of());
		CollaboratorRoute unrelated = new CollaboratorRoute(unrelatedBase.collaborator(), unrelatedBase.dataAgent(),
				"unrelated task", "matched", "unrelated output", "s9", List.of());
		CollaboratorRoute target = new CollaboratorRoute(targetBase.collaborator(), targetBase.dataAgent(), "target task",
				"matched", "target output", "s2", List.of("s1"), List.of(),
				List.of(new RouteStepInputMapping("s1", "customerIds", "customerIds", RouteDependencyValueType.ID)));
		when(dependencyContractService.dependencyInputs(org.mockito.ArgumentMatchers.eq(target),
				org.mockito.ArgumentMatchers.anyList())).thenReturn(Map.of("customerIds", List.of(101L, 102L)));

		AgentRequest child = support.buildCollaboratorRequest(parentRequest(), target,
				List.of(new CollaboratorExecutionResult(first, "untrusted answer", null, 10L,
						null, null, Map.of("customerIds", List.of(101L, 102L))),
						new CollaboratorExecutionResult(unrelated, "unrelated answer", null, 10L)));

		assertTrue(child.getQuery().contains("customerIds(ID)"));
		assertFalse(child.getQuery().contains("untrusted answer"));
		assertFalse(child.getQuery().contains("unrelated answer"));
		assertEquals(List.of(101L, 102L), child.getOrchestrationDependencyInputs().get("customerIds"));
	}

	@Test
	void dependentRequestFallsBackToPredecessorTextWhenContractIsMissing() {
		CollaboratorRoute firstBase = route(10L, 30);
		CollaboratorRoute targetBase = route(12L, 30);
		CollaboratorRoute first = new CollaboratorRoute(firstBase.collaborator(), firstBase.dataAgent(),
				"统计本月下单 Top10", "matched", "customerIds JSON", "s1", List.of());
		CollaboratorRoute target = new CollaboratorRoute(targetBase.collaborator(), targetBase.dataAgent(),
				"查询指定客户应收", "matched", "receivables JSON", "s2", List.of("s1"));

		AgentRequest child = support.buildCollaboratorRequest(parentRequest(), target,
				List.of(new CollaboratorExecutionResult(first, "客户A、客户B", null, 10L)));

		assertTrue(child.getQuery().contains("查询指定客户应收"));
		assertTrue(child.getQuery().contains("客户A、客户B"));
		assertFalse(child.getQuery().contains("Return a concise business result"));
		assertFalse(child.getQuery().contains("中文客户名称"));
	}

	@Test
	void dependentRequestPrefersAlignmentNamesOverPredecessorProse() {
		CollaboratorRoute firstBase = route(10L, 30);
		CollaboratorRoute targetBase = route(12L, 30);
		CollaboratorRoute first = new CollaboratorRoute(firstBase.collaborator(), firstBase.dataAgent(),
				"统计本月下单 Top10", "matched", "中文结论", "s1", List.of());
		CollaboratorRoute target = new CollaboratorRoute(targetBase.collaborator(), targetBase.dataAgent(),
				"查询指定客户应收", "matched", "中文结论", "s2", List.of("s1"));
		CollaboratorExecutionResult predecessor = new CollaboratorExecutionResult(first,
				"本月客户用箱量 Top 10 的客户名单已在结果表展示。", null, 10L, null, null, Map.of(),
				List.of("客户A", "客户B", "客户C"));

		AgentRequest child = support.buildCollaboratorRequest(parentRequest(), target, List.of(predecessor));

		assertTrue(child.getQuery().contains("前置步骤已确认的业务对象"));
		assertTrue(child.getQuery().contains("- 客户A"));
		assertTrue(child.getQuery().contains("- 客户B"));
		assertTrue(child.getQuery().contains("不要另探无关表"));
		assertFalse(child.getQuery().contains("名单已在结果表展示"));
		assertFalse(child.getQuery().contains("中文客户名称"));
		assertEquals("查询指定客户应收", child.getRankingIntentQuery());
	}

	@Test
	void childRuntimeRequestIdIsOpaqueAndUnique() {
		AgentRequest parent = parentRequest();
		CollaboratorRoute route = route(987654321L, 30);

		AgentRequest first = support.buildCollaboratorRequest(parent, route);
		AgentRequest second = support.buildCollaboratorRequest(parent, route);

		assertDoesNotThrow(() -> UUID.fromString(first.getRuntimeRequestId()));
		assertDoesNotThrow(() -> UUID.fromString(second.getRuntimeRequestId()));
		assertNotEquals(first.getRuntimeRequestId(), second.getRuntimeRequestId());
		assertFalse(first.getRuntimeRequestId().contains("987654321"));
	}

	@Test
	void routesFromSelectionsMaterializesAndFreezesSingleSelectionPlan() {
		AgentRequest request = parentRequest();
		request.setQuery("query the current order summary");
		AgentCollaborator collaborator = collaborator(20L, 10L);
		collaborator.setDelegationMode(DelegationMode.INTERACTIVE.name());
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(collaborator));
		stubCollaboratorAgents(DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build());
		RouteSelection selection = selection(20L);
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty());

		List<CollaboratorRoute> initialRoutes = support.routesFromSelections(request, context, decision);

		assertEquals("s1", initialRoutes.get(0).stepId());
		assertEquals(request.getQuery(), initialRoutes.get(0).task());
		assertEquals(DelegationMode.INTERACTIVE.name(), initialRoutes.get(0).delegationMode());
		RoutePlanStep snapshotStep = request.getOrchestrationRouteSnapshot().plan().steps().get(0);
		assertEquals("s1", snapshotStep.stepId());
		assertEquals(request.getQuery(), snapshotStep.queryFragment());
		assertEquals(DelegationMode.INTERACTIVE.name(), snapshotStep.delegationMode());
		assertEquals(10L, snapshotStep.collaboratorAgentId());

		collaborator.setDelegationMode(DelegationMode.AUTO_READ_ONLY.name());
		List<CollaboratorRoute> resumedRoutes = support.routesFromSnapshot(parentRequest(), context,
				request.getOrchestrationRouteSnapshot());

		assertEquals("s1", resumedRoutes.get(0).stepId());
		assertEquals(request.getQuery(), resumedRoutes.get(0).task());
		assertEquals(DelegationMode.INTERACTIVE.name(), resumedRoutes.get(0).delegationMode());
		verify(dependencyContractService, never()).bind(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void routesFromSelectionsRevalidatesRelationAndUsesControlledTaskFields() {
		AgentRequest request = parentRequest();
		AgentCollaborator collaborator = collaborator(20L, 10L);
		collaborator.setDelegationMode(DelegationMode.AUTO_READ_ONLY.name());
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(collaborator));
		DataAgent collaboratorAgent = DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build();
		stubCollaboratorAgents(collaboratorAgent);
		RouteSelection selection = new RouteSelection(
				new RouteTargetRef(RouteTargetType.COLLABORATOR, 20L, null, 20L), 30L, 40L,
				RouteRisk.DELEGATED, "checksum");
		RoutePlan plan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(),
				"query monthly invoices", List.of(), "return the monthly invoice summary")));
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, plan);

		List<CollaboratorRoute> routes = support.routesFromSelections(request, context, decision);

		assertEquals(1, routes.size());
		assertEquals("query monthly invoices", routes.get(0).task());
		assertEquals("MODEL_SELECT", routes.get(0).reason());
		assertEquals("return the monthly invoice summary", routes.get(0).expectedOutput());
		assertEquals(DelegationMode.AUTO_READ_ONLY.name(), routes.get(0).delegationMode());
		assertEquals(DelegationMode.AUTO_READ_ONLY.name(),
				request.getOrchestrationRouteSnapshot().plan().steps().get(0).delegationMode());
		assertEquals(10L, request.getOrchestrationRouteSnapshot().plan().steps().get(0).collaboratorAgentId());
		AgentRequest child = support.buildCollaboratorRequest(request, routes.get(0));
		assertTrue(child.getQuery().contains("query monthly invoices"));
		assertFalse(child.getQuery().contains(request.getQuery()));
		assertEquals(DelegationMode.AUTO_READ_ONLY.name(), child.getCollaboratorDelegationMode());
		verify(dataAgentMapper).selectBatchIds(List.of(10L));
	}

	@Test
	void routesFromSelectionsRejectsCollaboratorThatBecameOrchestrator() {
		AgentCollaborator collaborator = collaborator(20L, 10L);
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(collaborator));
		stubCollaboratorAgents(DataAgent.builder().id(10L).tenantId("tenant-1")
			.status("published").agentType(AgentTypeConstant.ORCHESTRATOR).build());
		RouteSelection selection = selection(20L);
		RouteDecision decision = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null,
				RoutePlan.single(selection, "query monthly invoices", "return the monthly invoice summary"));

		assertThrows(RuntimeException.class,
				() -> support.routesFromSelections(parentRequest(), context, decision));
	}

	@Test
	void routesFromSelectionsFollowPlanOrderInsteadOfSelectionOrder() {
		AgentCollaborator firstRelation = collaborator(20L, 10L);
		AgentCollaborator secondRelation = collaborator(21L, 11L);
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(firstRelation, secondRelation));
		stubCollaboratorAgents(DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build(),
				DataAgent.builder().id(11L).tenantId("tenant-1").status("published").build());
		RouteSelection first = selection(20L);
		RouteSelection second = selection(21L);
		RoutePlan plan = new RoutePlan(List.of(
				new RoutePlanStep("s1", first.target(), "first task", List.of(), "first result"),
				new RoutePlanStep("s2", second.target(), "second task", List.of("s1"), "second result")));
		RouteDecision decision = new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED",
				RouteDegradeMode.NONE, List.of(second, first), List.of(), null, true, RouteTiming.empty(), null, plan);

		List<CollaboratorRoute> routes = support.routesFromSelections(parentRequest(), context, decision);

		assertEquals(List.of("first task", "second task"), routes.stream().map(CollaboratorRoute::task).toList());
		assertEquals(List.of("first result", "second result"),
				routes.stream().map(CollaboratorRoute::expectedOutput).toList());
		assertEquals(List.of("s1", "s2"), routes.stream().map(CollaboratorRoute::stepId).toList());
		assertEquals(List.of(List.of(), List.of("s1")),
				routes.stream().map(CollaboratorRoute::dependsOn).toList());
	}

	@Test
	void routesFromSnapshotKeepsBoundPlanWithoutRebinding() {
		AgentCollaborator firstRelation = collaborator(20L, 10L);
		AgentCollaborator secondRelation = collaborator(21L, 11L);
		firstRelation.setDelegationMode(DelegationMode.AUTO_READ_ONLY.name());
		secondRelation.setDelegationMode(DelegationMode.INTERACTIVE.name());
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(firstRelation, secondRelation));
		stubCollaboratorAgents(DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build(),
				DataAgent.builder().id(11L).tenantId("tenant-1").status("published").build());
		RouteSelection first = selection(20L);
		RouteSelection second = selection(21L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(
				new RoutePlanStep("s1", first.target(), "snapshot source task", List.of(), "source output",
						List.of(new RouteStepOutputBinding("customerIds", RouteDependencyValueType.ID)), List.of(),
						DelegationMode.INTERACTIVE.name(), 10L),
				new RoutePlanStep("s2", second.target(), "snapshot dependent task", List.of("s1"), "dependent output",
						List.of(), List.of(new RouteStepInputMapping("s1", "customerIds", "customerIds",
								RouteDependencyValueType.ID)), DelegationMode.AUTO_READ_ONLY.name(), 11L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.MULTI_SELECT, "MODEL_MULTI_SELECTED",
				RouteDegradeMode.NONE, List.of(first, second), List.of(), null, true, RouteTiming.empty(), null,
				snapshotPlan);

		AgentRequest request = parentRequest();

		List<CollaboratorRoute> routes = support.routesFromSnapshot(request, context, snapshot);

		assertEquals(List.of("snapshot source task", "snapshot dependent task"),
				routes.stream().map(CollaboratorRoute::task).toList());
		assertEquals(List.of(List.of(), List.of("s1")),
				routes.stream().map(CollaboratorRoute::dependsOn).toList());
		assertEquals(snapshotPlan.steps().get(0).outputBindings(), routes.get(0).outputBindings());
		assertEquals(snapshotPlan.steps().get(1).inputMappings(), routes.get(1).inputMappings());
		assertEquals(List.of(DelegationMode.INTERACTIVE.name(), DelegationMode.AUTO_READ_ONLY.name()),
				routes.stream().map(CollaboratorRoute::delegationMode).toList());
		assertEquals(snapshot, request.getOrchestrationRouteSnapshot());
		verify(dependencyContractService, never()).bind(org.mockito.ArgumentMatchers.any(),
				org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void routesFromSnapshotRejectsCollaboratorTargetChangedAfterWait() {
		AgentCollaborator reassignedRelation = collaborator(20L, 11L);
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(reassignedRelation));
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);

		assertThrows(CheckedException.class, () -> support.routesFromSnapshot(parentRequest(), context, snapshot));

		verifyCollaboratorAgentNeverLoaded(11L);
	}

	@Test
	void routesFromSnapshotRejectsSnapshotWithoutPlan() {
		RouteSelection selection = selection(20L);
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty());

		assertThrows(CheckedException.class, () -> support.routesFromSnapshot(parentRequest(), context(List.of()), snapshot));

		verify(dataAgentMapper, never()).selectBatchIds(org.mockito.ArgumentMatchers.anyCollection());
	}

	@Test
	void restoreRoutesFromSnapshotReturnsFrozenFailureForUnfinishedReassignedCollaborator() {
		AgentCollaborator reassignedRelation = collaborator(20L, 11L);
		OrchestrationRuntimeSupport.OrchestrationContext context = context(List.of(reassignedRelation));
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);

		OrchestrationRuntimeSupport.SnapshotRouteRestore restored = support.restoreRoutesFromSnapshot(parentRequest(),
				context, snapshot);

		assertEquals(1, restored.routes().size());
		assertNull(restored.routes().get(0).collaboratorAgentId());
		assertNull(restored.routes().get(0).dataAgent());
		assertEquals("COLLABORATOR_ROUTE_SNAPSHOT_TARGET_CHANGED",
				restored.invalidStepResults().get("s1").errorMessage());
		verifyCollaboratorAgentNeverLoaded(11L);
	}

	@Test
	void restoreRoutesFromSnapshotDoesNotInvalidateTerminalStepAfterRelationChanges() {
		AgentCollaborator reassignedRelation = collaborator(20L, 11L);
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).build();
		OrchestrationRuntimeSupport.OrchestrationContext context = contextWithRun(run, List.of(reassignedRelation));
		when(stepMapper.findByRunId(100L)).thenReturn(List.of(
				AgentOrchestrationStep.builder().routeStepId("s1").status(OrchestrationStatus.SUCCESS).build()));
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);

		OrchestrationRuntimeSupport.SnapshotRouteRestore restored = support.restoreRoutesFromSnapshot(parentRequest(),
				context, snapshot);

		assertEquals(10L, restored.routes().get(0).collaboratorAgentId());
		assertTrue(restored.invalidStepResults().isEmpty());
		verifyCollaboratorAgentNeverLoaded(11L);
	}

	@Test
	void validateResumedStepSnapshotRequiresFrozenStepAgentAndDelegationMode() {
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().routeStepId("s1").collaboratorAgentId(10L)
			.build();
		AgentRequest childRequest = AgentRequest.builder().collaboratorDelegationMode(DelegationMode.INTERACTIVE.name())
			.build();
		OrchestrationRuntimeSupport.ResumedCollaborator resumed = new OrchestrationRuntimeSupport.ResumedCollaborator(
				AgentOrchestrationRun.builder().id(100L).build(), step, childRequest);
		OrchestrationContinuation continuation = continuation(snapshot, "10", DelegationMode.INTERACTIVE.name());

		assertDoesNotThrow(() -> support.validateResumedStepSnapshot(resumed, continuation));

		AgentOrchestrationStep wrongStep = AgentOrchestrationStep.builder().routeStepId("s2").collaboratorAgentId(10L)
			.build();
		assertThrows(CheckedException.class, () -> support.validateResumedStepSnapshot(
				new OrchestrationRuntimeSupport.ResumedCollaborator(resumed.run(), wrongStep, childRequest), continuation));
		assertThrows(CheckedException.class, () -> support.validateResumedStepSnapshot(resumed,
				continuation(snapshot, "11", DelegationMode.INTERACTIVE.name())));
		assertThrows(CheckedException.class, () -> support.validateResumedStepSnapshot(resumed,
				continuation(snapshot, "10", DelegationMode.AUTO_READ_ONLY.name())));
		AgentOrchestrationStep deletedChildStep = AgentOrchestrationStep.builder().routeStepId("s1").build();
		assertDoesNotThrow(() -> support.validateResumedStepSnapshot(
				new OrchestrationRuntimeSupport.ResumedCollaborator(resumed.run(), deletedChildStep, childRequest), continuation));
	}

	@Test
	void requireResumedCollaboratorDefersUnavailableChildToFrozenRestore() {
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).agentId(1L).threadId("123")
			.status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().id(401L).runId(100L).routeStepId("s1")
			.collaboratorAgentId(10L).status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		when(runMapper.findByIdAndAgentId(100L, 1L)).thenReturn(run);
		when(stepMapper.selectById(401L)).thenReturn(step);
		when(dataAgentMapper.findById(10L))
			.thenReturn(DataAgent.builder().id(10L).tenantId("other-tenant").status("draft").build());

		OrchestrationRuntimeSupport.ResumedCollaborator resumed = support.requireResumedCollaborator(parentRequest(),
				continuation(snapshot, "10", DelegationMode.INTERACTIVE.name()));

		assertEquals(10L, resumed.step().getCollaboratorAgentId());
		assertEquals("10", resumed.childRequest().getAgentId());

		step.setCollaboratorAgentId(null);
		when(dataAgentMapper.findById(10L)).thenReturn(null);
		OrchestrationRuntimeSupport.ResumedCollaborator deleted = support.requireResumedCollaborator(parentRequest(),
				continuation(snapshot, "10", DelegationMode.INTERACTIVE.name()));
		assertNull(deleted.step().getCollaboratorAgentId());
		OrchestrationRuntimeSupport.SnapshotRouteRestore restored = support.restoreRoutesFromSnapshot(parentRequest(),
				context(List.of(collaborator(20L, 10L))), snapshot);
		assertEquals("COLLABORATOR_ROUTE_SNAPSHOT_TARGET_UNAVAILABLE",
				restored.invalidStepResults().get("s1").errorMessage());
	}

	@Test
	void collaboratorPromptDoesNotIncludeParentFeedback() {
		AgentRequest request = parentRequest();
		request.setQuery("完整父问题");
		request.setHumanFeedback(true);
		request.setHumanFeedbackContent("按最近30天统计");

		AgentRequest child = support.buildCollaboratorRequest(request, route(10L, 30));

		assertFalse(child.getQuery().contains("完整父问题"));
		assertFalse(child.getQuery().contains("按最近30天统计"));
	}

	@Test
	void newQuestionDoesNotInheritFeedbackWithoutFeedbackFlags() {
		AgentRequest request = parentRequest();
		request.setHumanFeedback(false);
		request.setHumanFeedbackContent(null);

		assertEquals("问题", support.effectiveQuery(request));
	}

	@Test
	void summaryPromptNeverFallsBackToInternalCollaboratorAgentId() {
		AgentCollaborator relation = collaborator(20L, 987654321L);
		relation.setRoleName(null);
		CollaboratorRoute namedRoute = new CollaboratorRoute(relation,
				DataAgent.builder().id(987654321L).name("财务分析").build(), "任务", "匹配", "输出");
		CollaboratorRoute anonymousRoute = new CollaboratorRoute(relation,
				DataAgent.builder().id(987654321L).build(), "任务", "匹配", "输出");

		String namedPrompt = support.buildSummaryPrompt(null, "问题",
				List.of(new CollaboratorExecutionResult(namedRoute, "结果", null, 1L)));
		String anonymousPrompt = support.buildSummaryPrompt(null, "问题",
				List.of(new CollaboratorExecutionResult(anonymousRoute, "结果", null, 1L)));

		assertTrue(namedPrompt.contains("财务分析 -> 结果"));
		assertTrue(anonymousPrompt.contains("协作者 -> 结果"));
		assertFalse(namedPrompt.contains("987654321"));
		assertFalse(anonymousPrompt.contains("987654321"));
		assertFalse(namedPrompt.contains("禁止输出 json"));
		String presentationPrompt = support.buildSummaryPrompt(null, "问题",
				List.of(new CollaboratorExecutionResult(namedRoute, "```json\n{\"customerIds\":[\"1\"]}\n```", null, 1L)),
				true);
		assertTrue(presentationPrompt.contains("禁止输出 json"));
		assertTrue(presentationPrompt.contains("不要按协作者角色"));
		assertTrue(presentationPrompt.contains("不要按客户或对象编号罗列"));
		assertFalse(presentationPrompt.contains("明细交给结果表，正文简短"));
	}

	@Test
	void jsonExpectedOutputAsksCollaboratorForDisplayNames() {
		CollaboratorRoute route = new CollaboratorRoute(collaborator(20L, 10L),
				DataAgent.builder().id(10L).name("协作者10").status("published").runtimeTimeoutSeconds(30).build(),
				"本月下单量top10", "COMPOUND_INTENT_REPAIRED",
				"中文业务结果。完整名单由结果表展示。");

		AgentRequest child = support.buildCollaboratorRequest(parentRequest(), route);

		assertTrue(child.getQuery().contains("期望输出：中文业务结果。完整名单由结果表展示。"));
		assertTrue(child.getQuery().contains("不要输出 JSON"));
		assertEquals("本月下单量top10", child.getRankingIntentQuery());
		assertFalse(child.getQuery().contains("metric"));
		assertFalse(child.getQuery().contains("用箱量"));
	}

	@Test
	void collaboratorRequestInheritsParentReleaseAnchor() {
		// PR-4：协作者子请求透传父请求的 ownerType/ownerId/releaseId（Release 锚一致），tenantIdSnapshot 原有透传保持
		AgentRequest parent = parentRequest();
		parent.setOwnerType("DIGITAL_EMPLOYEE");
		parent.setOwnerId(77L);
		parent.setReleaseId(88L);

		AgentRequest child = support.buildCollaboratorRequest(parent, route(10L, 30));

		assertEquals("DIGITAL_EMPLOYEE", child.getOwnerType());
		assertEquals(77L, child.getOwnerId());
		assertEquals(88L, child.getReleaseId());
		assertEquals("tenant-1", child.getTenantIdSnapshot());
	}

	@Test
	void collaboratorRequestKeepsNullAnchorForPlainAgents() {
		// PR-4：普通智能体编排（无 owner 锚）透传 null，行为零变化
		AgentRequest child = support.buildCollaboratorRequest(parentRequest(), route(10L, 30));

		assertNull(child.getOwnerType());
		assertNull(child.getOwnerId());
		assertNull(child.getReleaseId());
	}

	@Test
	void resumedCollaboratorInheritsParentReleaseAnchor() {
		// PR-4：恢复型协作者子请求同样保持父请求锚一致
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).agentId(1L).threadId("123")
				.status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().id(401L).runId(100L).routeStepId("s1")
				.collaboratorAgentId(10L).status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		when(runMapper.findByIdAndAgentId(100L, 1L)).thenReturn(run);
		when(stepMapper.selectById(401L)).thenReturn(step);
		when(dataAgentMapper.findById(10L))
				.thenReturn(DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build());
		AgentRequest parent = parentRequest();
		parent.setOwnerType("DIGITAL_EMPLOYEE");
		parent.setOwnerId(77L);
		parent.setReleaseId(88L);

		OrchestrationRuntimeSupport.ResumedCollaborator resumed = support.requireResumedCollaborator(parent,
				continuation(snapshot, "10", DelegationMode.INTERACTIVE.name()));

		assertEquals("DIGITAL_EMPLOYEE", resumed.childRequest().getOwnerType());
		assertEquals(77L, resumed.childRequest().getOwnerId());
		assertEquals(88L, resumed.childRequest().getReleaseId());
	}

	@Test
	void requireResumedCollaboratorCopiesOriginalUserQueryAndPointerArtifact() {
		RouteSelection selection = selection(20L);
		RoutePlan snapshotPlan = new RoutePlan(List.of(new RoutePlanStep("s1", selection.target(), "snapshot task",
				List.of(), "snapshot result", List.of(), List.of(), DelegationMode.INTERACTIVE.name(), 10L)));
		RouteDecision snapshot = new RouteDecision(RouteDecisionType.SELECT, "MODEL_SELECT", RouteDegradeMode.NONE,
				List.of(selection), List.of(), null, true, RouteTiming.empty(), null, snapshotPlan);
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).agentId(1L).threadId("123")
				.status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		AgentOrchestrationStep step = AgentOrchestrationStep.builder().id(401L).runId(100L).routeStepId("s1")
				.collaboratorAgentId(10L).status(OrchestrationStatus.WAITING_CLARIFICATION).build();
		when(runMapper.findByIdAndAgentId(100L, 1L)).thenReturn(run);
		when(stepMapper.selectById(401L)).thenReturn(step);
		when(dataAgentMapper.findById(10L))
				.thenReturn(DataAgent.builder().id(10L).tenantId("tenant-1").status("published").build());
		ExtractCard card = new ExtractCard("对账单截图", "statement", List.of(), false, "", ExtractCard.STATUS_OK);
		AgentRequest parent = parentRequest();
		parent.setOriginalUserQuery("用户原话");
		parent.setTurnArtifactId("art-1");
		parent.setExtractCard(card);
		parent.setTurnArtifact(new TurnArtifact("art-1",
				List.of(FusionBlock.image(new byte[] { 9 }, "image/png", "x", "key")), List.of(), 10, "附件：图片 1 张")
				.withExtractCard(card));

		OrchestrationRuntimeSupport.ResumedCollaborator resumed = support.requireResumedCollaborator(parent,
				continuation(snapshot, "10", DelegationMode.INTERACTIVE.name()));

		AgentRequest child = resumed.childRequest();
		assertEquals("用户原话", child.getOriginalUserQuery());
		assertEquals("art-1", child.getTurnArtifactId());
		assertEquals(card, child.getExtractCard());
		assertEquals("用户原话", child.getRankingIntentQuery());
		assertFalse(child.getTurnArtifact().hasPixelImage());
		assertEquals("key", child.getTurnArtifact().blocks().get(0).sourceRef());
		assertTrue(child.getAttachments() == null || child.getAttachments().isEmpty());
	}

	private AgentRequest parentRequest() {
		return AgentRequest.builder().agentId("1").threadId("123").runtimeRequestId("run-1").query("问题")
			.tenantIdSnapshot("tenant-1").build();
	}

	private OrchestrationRuntimeSupport.OrchestrationContext context(List<AgentCollaborator> collaborators) {
		return new OrchestrationRuntimeSupport.OrchestrationContext(
				DataAgent.builder().id(1L).tenantId("tenant-1").name("编排智能体").prompt("编排说明").build(),
				policy(), null, collaborators, modelConfig(99L, "main-model"));
	}

	private OrchestrationRuntimeSupport.OrchestrationContext contextWithRun(AgentOrchestrationRun run,
			List<AgentCollaborator> collaborators) {
		return new OrchestrationRuntimeSupport.OrchestrationContext(
				DataAgent.builder().id(1L).tenantId("tenant-1").build(), policy(), run, collaborators,
				modelConfig(99L, "main-model"));
	}

	private AgentOrchestrationPolicy policy() {
		return AgentOrchestrationPolicy.builder()
			.maxCollaboratorsPerRun(3)
			.build();
	}

	private CollaboratorRoute route(Long id, int timeoutSeconds) {
		AgentCollaborator collaborator = collaborator(id + 10L, id);
		DataAgent agent = DataAgent.builder().id(id).name("协作者" + id).status("published")
			.runtimeTimeoutSeconds(timeoutSeconds).build();
		return new CollaboratorRoute(collaborator, agent, "问题", "匹配", "输出");
	}

	/**
	 * 协作 Agent 现在是整批取回的，按请求到的 id 子集回放，替代逐个 findById 打桩。
	 */
	private void stubCollaboratorAgents(DataAgent... agents) {
		List<DataAgent> pool = List.of(agents);
		when(dataAgentMapper.selectBatchIds(org.mockito.ArgumentMatchers.anyCollection()))
			.thenAnswer(invocation -> {
				java.util.Collection<?> ids = invocation.getArgument(0);
				return pool.stream().filter(agent -> ids.contains(agent.getId())).toList();
			});
	}

	private void verifyCollaboratorAgentNeverLoaded(Long collaboratorAgentId) {
		verify(dataAgentMapper, never())
			.selectBatchIds(org.mockito.ArgumentMatchers.argThat(ids -> ids.contains(collaboratorAgentId)));
	}

	private AgentCollaborator collaborator(Long relationId, Long collaboratorAgentId) {
		return AgentCollaborator.builder().id(relationId).agentId(1L).collaboratorAgentId(collaboratorAgentId)
			.roleName("协作者" + collaboratorAgentId)
			.priority(100).enabled(true).build();
	}

	private RouteSelection selection(Long relationId) {
		return new RouteSelection(new RouteTargetRef(RouteTargetType.COLLABORATOR, relationId, null, relationId),
				30L, 40L, RouteRisk.READ_ONLY, "checksum-" + relationId);
	}

	private OrchestrationContinuation continuation(RouteDecision snapshot, String childAgentId, String delegationMode) {
		return new OrchestrationContinuation(100L, 401L, childAgentId, "child-thread", "child-runtime", "snapshot task",
				Map.of(), null, snapshot, delegationMode);
	}

	private ModelConfigDTO modelConfig(Long id, String modelName) {
		return ModelConfigDTO.builder().id(id).provider("qwen").baseUrl("https://example.com").apiKey("key")
			.modelName(modelName).modelType("CHAT").build();
	}

}
