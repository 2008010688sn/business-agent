/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteSemanticMatch;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HybridRouteEngineTest {

	private RouteSemanticService semanticService;

	private RouteModelClient modelClient;

	private HybridRouteEngine engine;

	private SimpleMeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		semanticService = mock(RouteSemanticService.class);
		modelClient = mock(RouteModelClient.class);
		meterRegistry = new SimpleMeterRegistry();
		engine = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()), semanticService, modelClient,
				new DataAgentProperties(), meterRegistry);
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class))).thenReturn(List.of());
	}

	@Test
	void accidentQuerySelectsReadOnlySkillWithoutModel() {
		RouteCandidate candidate = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("用箱量"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("该月用箱量top10客户情况"), policy(), List.of(candidate));
		engine.recordDecision(result);

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("LEXICAL_HIGH_CONFIDENCE", result.reasonCode());
		assertEquals(false, result.modelInvoked());
		assertEquals(1D, meterRegistry.get("data.agent.routing.decisions")
			.tag("decision", RouteDecisionType.SELECT.name())
			.counter()
			.count());
	}

	@Test
	void writeCandidateCannotUseLexicalFastPath() {
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
	}

	@Test
	void optedInFlowCanUseLexicalFastPath() {
		RouteCandidate candidate = flowCandidate(1L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(), List.of(),
					List.of(), true));

		var result = engine.route(context("给云南万绿客户下单"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("LEXICAL_HIGH_CONFIDENCE", result.reasonCode());
	}

	@Test
	void excludedAndLookupOrderQueriesDoNotSelectDemandCreationFlow() {
		RouteCandidate candidate = flowCandidate(1L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(), List.of(),
						List.of("*怎么*下单*", "*不要*下单*", "*取消*下单*"), true));

		for (String query : List.of("怎么给云南万绿客户下单", "不要给云南万绿客户下单", "取消下单", "查询已有订单", "查运单")) {
			var result = engine.route(context(query), policy(), List.of(candidate));

			assertEquals(RouteDecisionType.NO_MATCH, result.decision(), query);
			assertTrue(result.selections().isEmpty(), query);
		}
	}

	@Test
	void flowWithoutOptInStillRequiresClarification() {
		RouteCandidate candidate = flowCandidate(1L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(), List.of(),
					List.of(), false));

		var result = engine.route(context("给云南万绿客户下单"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
	}

	@Test
	void uniqueExactCanDetermineWriteTarget() {
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of("创建订单"), List.of(), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("创建订单"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.CONFIRM_REQUIRED, result.decision());
		assertEquals("UNIQUE_EXACT_CONFIRM_REQUIRED", result.reasonCode());
	}

	@Test
	void unknownRiskExactCandidateStillRequiresClarification() {
		RouteCandidate candidate = candidate(1L, RouteRisk.UNKNOWN,
				new RouteRules(List.of("create order"), List.of(), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("create order"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
		verifyNoInteractions(modelClient);
	}

	@Test
	void knowledgeAgentSelectsSoleQaCandidateWithoutRouteSignalOrArtifact() {
		RouteCandidate candidate = knowledgeCandidate(1L, RouteRules.empty());

		var result = engine.route(context("有哪些产品", "knowledge-base"), policy(),
				List.of(candidate));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("KNOWLEDGE_SINGLE_CANDIDATE", result.reasonCode());
		assertEquals(candidate.target(), result.selections().get(0).target());
		verifyNoInteractions(semanticService, modelClient);
	}

	@Test
	void knowledgeAgentDoesNotDefaultSoleQaCandidateWhenHardExcluded() {
		RouteCandidate candidate = knowledgeCandidate(1L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("我要下单")));

		var result = engine.route(context("我要下单", AgentTypeConstant.KNOWLEDGE_BASE), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.NO_MATCH, result.decision());
		assertEquals("NO_RELEVANT_SIGNAL", result.reasonCode());
	}

	@Test
	void nonKnowledgeAgentDoesNotUseSoleQaDefault() {
		RouteCandidate candidate = knowledgeCandidate(1L, RouteRules.empty());

		var result = engine.route(context("有哪些产品", AgentTypeConstant.DATA_ANALYSIS), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.NO_MATCH, result.decision());
	}

	@Test
	void knowledgeAgentDoesNotDefaultNonQaCandidate() {
		RouteCandidate candidate = candidate(1L, RouteRisk.READ_ONLY, RouteRules.empty());

		var result = engine.route(context("有哪些产品", AgentTypeConstant.KNOWLEDGE_BASE), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.NO_MATCH, result.decision());
	}

	@Test
	void knowledgeAgentClarifiesMultipleUnmatchedQaCandidates() {
		RouteCandidate first = knowledgeCandidate(1L, RouteRules.empty());
		RouteCandidate second = knowledgeCandidate(2L, RouteRules.empty());
		RouteCandidate query = candidate(3L, RouteRisk.READ_ONLY, RouteRules.empty());

		var result = engine.route(context("有哪些产品", AgentTypeConstant.KNOWLEDGE_BASE), policy(),
				List.of(first, second, query));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("KNOWLEDGE_CANDIDATES_AMBIGUOUS", result.reasonCode());
		assertEquals(List.of(first.target(), second.target()), result.suggestions().stream()
			.map(suggestion -> suggestion.selection().target())
			.toList());
		assertTrue(result.clarification().allowFreeText());
		assertTrue(result.clarification().options().size() >= 2);
		assertEquals("请选择要办理的事项", result.clarification().title());
		assertTrue(result.clarification().prompt().contains("您是想办理"));
		assertTrue(result.clarification().options().stream().anyMatch(option -> first.name().equals(option.label())));
		assertTrue(result.clarification().options().stream().anyMatch(option -> second.name().equals(option.label())));
		assertFalse(result.clarification().prompt().contains("口径"));
		assertFalse(result.clarification().prompt().toLowerCase().contains("skill"));
		assertFalse(result.clarification().options().stream()
			.anyMatch(option -> option.label().toLowerCase().contains("skill")));
		verifyNoInteractions(modelClient);
	}

	@Test
	void knowledgeAgentDoesNotDefaultQaWhenAnotherCandidateCanCompete() {
		RouteCandidate knowledge = knowledgeCandidate(1L, RouteRules.empty());
		RouteCandidate query = candidate(2L, RouteRisk.READ_ONLY, RouteRules.empty());

		var result = engine.route(context("有哪些产品", AgentTypeConstant.KNOWLEDGE_BASE), policy(),
				List.of(knowledge, query));

		assertEquals(RouteDecisionType.NO_MATCH, result.decision());
	}

	@Test
	void knowledgeAgentSelectsExactQaAmongMultipleQaCandidates() {
		RouteCandidate exact = knowledgeCandidate(1L,
				new RouteRules(List.of("有哪些产品"), List.of(), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate other = knowledgeCandidate(2L, RouteRules.empty());

		var result = engine.route(context("有哪些产品", AgentTypeConstant.KNOWLEDGE_BASE), policy(),
				List.of(exact, other));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("UNIQUE_EXACT", result.reasonCode());
		assertEquals(exact.target(), result.selections().get(0).target());
	}

	@Test
	void vectorFailureIsVisibleAndDoesNotBecomeNoMatch() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_UNAVAILABLE", "down"));
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), semanticPolicy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("VECTOR_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verifyNoInteractions(modelClient);
	}

	@Test
	void multipleExactMatchesNeverAutoSelectWhenVectorDegrades() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_UNAVAILABLE", "down"));
		RouteCandidate stronger = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of("查询订单"), List.of("查询订单"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate other = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of("查询订单"), List.of(), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("查询订单"), semanticPolicy(), List.of(stronger, other));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("VECTOR_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verifyNoInteractions(modelClient);
	}

	@Test
	void vectorFailureKeepsTheSpecificStageReasonCode() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_TIMEOUT", "slow"));
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), semanticPolicy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("VECTOR_TIMEOUT", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verifyNoInteractions(modelClient);
	}

	@Test
	void vectorFailureWithoutLexicalSignalStaysRouteUnavailable() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_UNAVAILABLE", "down"));
		RouteCandidate candidate = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("这个月各项目的账单情况"), semanticPolicy(), List.of(candidate));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertEquals("VECTOR_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verifyNoInteractions(modelClient);
	}

	@Test
	void recallRuntimeNotReadyClarifiesLexicalCandidatesWithoutInvokingModel() {
		RouteCandidate candidate = candidateWithoutArtifact(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), recallPolicy(false), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("ROUTE_SEMANTIC_NOT_READY", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verify(semanticService, never()).search(any(), any(), any(), anyInt(), any(Duration.class));
		verifyNoInteractions(modelClient);
	}

	@Test
	void recallRuntimeNotReadyFailsUnavailableWithoutLexicalCandidatesOrModel() {
		RouteCandidate candidate = candidateWithoutArtifact(1L, RouteRisk.WRITE, RouteRules.empty());

		var result = engine.route(context("创建月度业务"), recallPolicy(false), List.of(candidate));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertEquals("ROUTE_SEMANTIC_NOT_READY", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verify(semanticService, never()).search(any(), any(), any(), anyInt(), any(Duration.class));
		verifyNoInteractions(modelClient);
	}

	@Test
	void missingArtifactsClarifyLexicalCandidatesWithoutInvokingVectorOrModel() {
		RouteCandidate candidate = candidateWithoutArtifact(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), recallPolicy(true), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("ROUTE_ARTIFACT_NOT_READY", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verify(semanticService, never()).search(any(), any(), any(), anyInt(), any(Duration.class));
		verifyNoInteractions(modelClient);
	}

	@Test
	void partialArtifactsFailVisibleBeforeVectorOrModel() {
		RouteRules ambiguousRules = new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of());
		RouteCandidate ready = candidate(1L, RouteRisk.READ_ONLY, ambiguousRules);
		RouteCandidate missing = candidateWithoutArtifact(2L, RouteRisk.READ_ONLY, ambiguousRules);

		var result = engine.route(context("query details"), recallPolicy(true), List.of(ready, missing));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("ROUTE_ARTIFACT_NOT_READY", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		verify(semanticService, never()).search(any(), any(), any(), anyInt(), any(Duration.class));
		verifyNoInteractions(modelClient);
	}

	@Test
	void degradedClarificationTextDiffersFromBusinessAmbiguityClarification() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_UNAVAILABLE", "down"));
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var degraded = engine.route(context("请创建订单"), semanticPolicy(), List.of(candidate));
		var ambiguous = engine.route(context("有哪些产品", AgentTypeConstant.KNOWLEDGE_BASE), policy(),
				List.of(knowledgeCandidate(1L, RouteRules.empty()), knowledgeCandidate(2L, RouteRules.empty())));

		assertEquals(RouteDecisionType.CLARIFY, degraded.decision());
		assertEquals(RouteDecisionType.CLARIFY, ambiguous.decision());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, degraded.degradeMode());
		assertEquals(RouteDegradeMode.NONE, ambiguous.degradeMode());
		assertNotEquals(ambiguous.clarification().prompt(), degraded.clarification().prompt());
		assertNotEquals(ambiguous.clarification().title(), degraded.clarification().title());
		assertTrue(degraded.clarification().prompt().contains("智能匹配能力当前受限"));
		assertFalse(ambiguous.clarification().prompt().contains("受限"));
	}

	@Test
	void degradedClarificationStaysActionableWithoutLeakingReasonCode() {
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenThrow(new RouteStageException("VECTOR_TIMEOUT", "slow"));
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), semanticPolicy(), List.of(candidate));

		assertEquals("VECTOR_TIMEOUT", result.reasonCode());
		assertTrue(result.clarification().prompt().contains("业务对象"));
		assertTrue(result.clarification().allowFreeText());
		assertFalse(result.clarification().prompt().contains("VECTOR"));
		assertFalse(result.clarification().prompt().contains("DEGRADED"));
		assertFalse(result.clarification().title().contains("VECTOR"));
	}

	@Test
	void artifactNotReadyDegradeUsesTheSameDegradedClarificationText() {
		RouteCandidate candidate = candidateWithoutArtifact(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), recallPolicy(true), List.of(candidate));

		assertEquals("ROUTE_ARTIFACT_NOT_READY", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_VECTOR, result.degradeMode());
		assertTrue(result.clarification().prompt().contains("智能匹配能力当前受限"));
	}

	@Test
	void recallWithoutAutomaticSelectionUsesSemanticRankingButDoesNotSelect() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY, RouteRules.empty());
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY, RouteRules.empty());
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(first.target(), 0.99D),
					new RouteSemanticMatch(second.target(), 0.70D)));

		var result = engine.route(context("monthly details"), recallPolicy(true), List.of(first, second));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
		verify(semanticService).search(any(), any(), any(), anyInt(), any(Duration.class));
		verifyNoInteractions(modelClient);
	}

	@Test
	void modelUnavailableFailsClosedWhenNoCompleteDeterministicPlanExists() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("query"), modelPolicy(false), List.of(first, second));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_MODEL, result.degradeMode());
		assertEquals(0, result.selections().size());
		assertTrue(result.suggestions().isEmpty());
		assertEquals(false, result.modelInvoked());
		verifyNoInteractions(modelClient);
	}

	@Test
	void modelUnavailableExecutesExactReadOnlyCollaboratorPlan() {
		String query = "同时查询订单和客户";
		RouteRules rules = new RouteRules(List.of(query), List.of(), List.of(), List.of(), List.of(), List.of());
		RouteCandidate first = collaboratorCandidate(1L, RouteRisk.READ_ONLY, rules, DelegationMode.AUTO_READ_ONLY);
		RouteCandidate second = collaboratorCandidate(2L, RouteRisk.READ_ONLY, rules, DelegationMode.AUTO_READ_ONLY);

		var result = engine.route(context(query, AgentTypeConstant.ORCHESTRATOR), modelPolicy(false), List.of(first, second));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_MODEL, result.degradeMode());
		assertEquals(List.of(first.target(), second.target()),
				result.selections().stream().map(selection -> selection.target()).toList());
		assertEquals(2, result.plan().steps().size());
		assertFalse(result.modelInvoked());
		verifyNoInteractions(modelClient);
	}

	@Test
	void modelUnavailableStartsExactInteractiveCollaboratorPlan() {
		String query = "同时查询订单和客户";
		RouteRules rules = new RouteRules(List.of(query), List.of(), List.of(), List.of(), List.of(), List.of());
		RouteCandidate first = collaboratorCandidate(1L, RouteRisk.READ_ONLY, rules, DelegationMode.INTERACTIVE);
		RouteCandidate second = collaboratorCandidate(2L, RouteRisk.READ_ONLY, rules, DelegationMode.INTERACTIVE);

		var result = engine.route(context(query, AgentTypeConstant.ORCHESTRATOR), modelPolicy(false), List.of(first, second));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.reasonCode());
		assertFalse(result.modelInvoked());
		assertEquals(2, result.plan().steps().size());
		verifyNoInteractions(modelClient);
	}

	@Test
	void modelUnavailableRequiresConfirmationForExactPlanConfirmCollaborators() {
		String query = "同时创建订单和任务";
		RouteRules rules = new RouteRules(List.of(query), List.of(), List.of(), List.of(), List.of(), List.of());
		RouteCandidate first = collaboratorCandidate(1L, RouteRisk.WRITE, rules, DelegationMode.PLAN_CONFIRM);
		RouteCandidate second = collaboratorCandidate(2L, RouteRisk.WRITE, rules, DelegationMode.PLAN_CONFIRM);

		var result = engine.route(context(query, AgentTypeConstant.ORCHESTRATOR), modelPolicy(false), List.of(first, second));

		assertEquals(RouteDecisionType.CONFIRM_REQUIRED, result.decision());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.reasonCode());
		assertEquals(2, result.plan().steps().size());
	}

	@Test
	void invalidModelOutputSelectsTheOnlySemanticallyRecalledSkill() {
		RouteCandidate receivable = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("客户"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate cost = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("客户"), List.of(), List.of(), List.of(), List.of()));
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(receivable.target(), 0.54D)));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenThrow(new RouteStageException("MODEL_INVALID_OUTPUT", "bad json"));

		var result = engine.route(context("这些客户应收金额情况"), semanticAndModelPolicy(),
				List.of(receivable, cost));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("MODEL_INVALID_UNIQUE_SIGNAL", result.reasonCode());
		assertEquals(receivable.target(), result.selections().get(0).target());
		assertTrue(result.modelInvoked());
	}

	@Test
	void invalidModelOutputClarifiesWhenBothSkillsHaveSemanticRecall() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(first.target(), 0.72D),
					new RouteSemanticMatch(second.target(), 0.68D)));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenThrow(new RouteStageException("MODEL_INVALID_OUTPUT", "bad json"));

		var result = engine.route(context("query"), semanticAndModelPolicy(), List.of(first, second));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
		assertTrue(result.modelInvoked());
	}

	@Test
	void modelTimeoutIsReportedAndCountedAsDegradedModel() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenThrow(new RouteStageException("MODEL_TIMEOUT", "timeout"));

		var result = engine.route(context("query"), modelPolicy(true), List.of(first, second));
		engine.recordDecision(result);

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertEquals("ROUTE_MODEL_TIMEOUT", result.reasonCode());
		assertTrue(result.modelInvoked());
		assertEquals(1D, meterRegistry.get("data.agent.routing.model.timeouts").counter().count());
		assertEquals(1D, meterRegistry.get("data.agent.routing.model.degraded").counter().count());
	}

	@Test
	void modelAcceptsOrderedDependentMultiIntentPlan() {
		RouteCandidate first = collaboratorCandidate(1L);
		RouteCandidate second = collaboratorCandidate(2L);
		RoutePlan plan = new RoutePlan(List.of(
				new RoutePlanStep("fetch_data", first.target(), "query invoices", List.of(), "invoice data"),
				new RoutePlanStep("build_summary", second.target(), "generate report", List.of("fetch_data"),
						"summary report")));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.MULTI_SELECT,
					List.of(first.target(), second.target()), 0.9D, plan, null));

		var result = engine.route(context("先 query invoices，再 generate report", AgentTypeConstant.ORCHESTRATOR),
				modelPolicy(true), List.of(first, second));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("MODEL_MULTI_SELECTED", result.reasonCode());
		assertEquals(plan, result.plan());
	}

	@Test
	void compoundOrderAndReceivableQueryRepairsSingleSelectIntoSequentialPlan() {
		String query = "帮我看一下这个月客户下单top10的客户以及这些客户应收金额的情况";
		RouteCandidate orders = collaboratorCandidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("下单"), List.of(), List.of(), List.of(), List.of()),
				DelegationMode.INTERACTIVE);
		RouteCandidate bills = collaboratorCandidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("应收"), List.of(), List.of(), List.of(), List.of()),
				DelegationMode.INTERACTIVE);
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.SELECT, List.of(bills.target()), 0.9D,
					RoutePlan.empty(), null));

		var result = engine.route(context(query, AgentTypeConstant.ORCHESTRATOR), modelPolicy(true),
				List.of(orders, bills));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("COMPOUND_INTENT_REPAIRED", result.reasonCode());
		assertEquals(2, result.plan().steps().size());
		assertEquals(orders.target(), result.plan().steps().get(0).target());
		assertEquals(bills.target(), result.plan().steps().get(1).target());
		assertEquals(List.of("s1"), result.plan().steps().get(1).dependsOn());
		assertTrue(result.plan().steps().get(0).queryFragment().contains("下单"));
		assertFalse(result.plan().steps().get(1).queryFragment().contains("下单top10"));
	}

	@Test
	void compoundOrderVolumeQueryDoesNotExcludeReceivableOnFullSentence() {
		String query = "帮我看一下这个月客户下单量top10的客户以及这些客户应收金额的情况";
		RouteCandidate orders = collaboratorCandidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("下单量"), List.of(), List.of(), List.of(), List.of()),
				DelegationMode.INTERACTIVE);
		RouteCandidate bills = collaboratorCandidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("应收金额"), List.of(), List.of(), List.of(), List.of("下单量")),
				DelegationMode.INTERACTIVE);

		var result = engine.route(context(query, AgentTypeConstant.ORCHESTRATOR), policy(), List.of(orders, bills));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("COMPOUND_INTENT_REPAIRED", result.reasonCode());
		assertEquals(2, result.plan().steps().size());
		assertEquals(orders.target(), result.plan().steps().get(0).target());
		assertEquals(bills.target(), result.plan().steps().get(1).target());
		assertEquals(List.of("s1"), result.plan().steps().get(1).dependsOn());
		assertTrue(result.plan().steps().get(0).expectedOutput().contains("结果表"));
		assertTrue(result.plan().steps().get(0).expectedOutput().contains("业务名称"));
		assertTrue(result.plan().steps().get(1).expectedOutput().contains("前置步骤"));
		assertTrue(result.plan().steps().get(1).expectedOutput().contains("当前技能可见表"));
		assertFalse(result.plan().steps().get(0).expectedOutput().contains("用箱量"));
		assertFalse(result.plan().steps().get(1).expectedOutput().contains("应收"));
		assertFalse(result.plan().steps().get(0).expectedOutput().contains("customerIds"));
		assertFalse(result.plan().steps().get(0).expectedOutput().contains("metric"));
		assertFalse(result.plan().steps().get(1).expectedOutput().toLowerCase().contains("receivable"));
		verifyNoInteractions(modelClient);
	}

	@Test
	void singleBoundReadOnlySkillIsSelectedWithoutRouteSignal() {
		RouteCandidate candidate = candidate(1L, RouteRisk.READ_ONLY, RouteRules.empty());

		var result = engine.route(context("这个月客户下单top10"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("SINGLE_BOUND_SKILL", result.reasonCode());
		assertEquals(candidate.target(), result.selections().get(0).target());
		verifyNoInteractions(modelClient);
	}

	@Test
	void writeCandidateClarificationExposesSelectableBusinessOptions() {
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE,
				new RouteRules(List.of(), List.of("创建订单"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("请创建订单"), policy(), List.of(candidate));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertTrue(result.clarification().allowFreeText());
		assertFalse(result.clarification().options().isEmpty());
		assertEquals("候选1", result.clarification().options().get(0).label());
		assertEquals("请选择要办理的事项", result.clarification().title());
		assertTrue(result.clarification().prompt().contains("候选1"));
		assertFalse(result.clarification().title().contains("统计"));
		assertFalse(result.clarification().prompt().contains("口径"));
	}

	@Test
	void uniqueRelevantKnowledgeIsSelectedAmongUnmatchedFlow() {
		RouteCandidate knowledge = knowledgeSkill(1L, "知识问答", RouteRules.empty());
		RouteCandidate flow = flowCandidate(4L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(), List.of(),
						List.of(), true));

		var result = engine.route(context("知识问答里有哪些商品"), policy(), List.of(knowledge, flow));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("UNIQUE_RELEVANT_CANDIDATE", result.reasonCode());
		assertEquals(knowledge.target(), result.selections().get(0).target());
		verifyNoInteractions(modelClient);
	}

	@Test
	void productListingSelectsKnowledgeWhenFlowHasNoSignal() {
		RouteCandidate knowledge = knowledgeSkill(1L, "知识问答", RouteRules.empty());
		RouteCandidate flow = flowCandidate(4L,
				new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"), List.of(), List.of(),
						List.of(), true));
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(knowledge.target(), 0.65D)));

		var result = engine.route(context("你有哪些商品"), semanticPolicy(), List.of(knowledge, flow));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("UNIQUE_RELEVANT_CANDIDATE", result.reasonCode());
		assertEquals(knowledge.target(), result.selections().get(0).target());
		verifyNoInteractions(modelClient);
	}

	@Test
	void competingSkillsClarifyWithBusinessChoiceInsteadOfStatisticsCaliber() {
		RouteCandidate knowledge = knowledgeSkill(1L, "知识问答",
				new RouteRules(List.of(), List.of("产品信息"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate flow = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 4L, 14L, 24L), "tenant-1",
				1L, "创建需求", "按客户和商品创建需求单", "ACTION", "FLOW",
				new RouteRules(List.of(), List.of("下单"), List.of(), List.of(), List.of(), List.of(), List.of(),
						List.of(), false),
				RouteRisk.FLOW, 0, 1L, 34L, "checksum-4", "embedding-v1");

		var result = engine.route(context("产品信息下单"), noModelPolicy(), List.of(knowledge, flow));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
		assertEquals("请选择要办理的事项", result.clarification().title());
		assertTrue(result.clarification().prompt().contains("您是想办理"));
		assertTrue(result.clarification().prompt().contains("知识问答"));
		assertTrue(result.clarification().prompt().contains("创建需求"));
		assertFalse(result.clarification().title().contains("统计"));
		assertFalse(result.clarification().prompt().contains("口径"));
		assertEquals(2, result.clarification().options().size());
	}

	@Test
	void modelSelectedInteractiveCollaboratorDoesNotCreateOuterConfirmation() {
		RouteCandidate first = collaboratorCandidate(1L);
		RouteCandidate second = collaboratorCandidate(2L);
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.SELECT, List.of(first.target()), 0.9D,
					RoutePlan.empty(), null));

		var result = engine.route(context("query", AgentTypeConstant.ORCHESTRATOR), modelPolicy(true),
				List.of(first, second));

		assertEquals(RouteDecisionType.SELECT, result.decision());
		assertEquals("MODEL_SELECTED", result.reasonCode());
		assertEquals(first.target(), result.selections().get(0).target());
	}

	@Test
	void modelMultiSelectWithoutPlanFailsClosed() {
		RouteCandidate first = collaboratorCandidate(1L);
		RouteCandidate second = collaboratorCandidate(2L);
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.MULTI_SELECT,
					List.of(first.target(), second.target()), 0.9D, RoutePlan.empty(), null));

		var result = engine.route(context("query and report", AgentTypeConstant.ORCHESTRATOR), modelPolicy(true),
				List.of(first, second));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("COMPOUND_INTENT_REPAIRED", result.reasonCode());
		assertEquals(2, result.plan().steps().size());
		assertTrue(result.plan().steps().get(0).dependsOn().isEmpty());
		assertTrue(result.plan().steps().get(1).dependsOn().isEmpty());
	}

	@Test
	void modelPlanTargetMismatchFailsClosed() {
		RouteCandidate first = collaboratorCandidate(1L);
		RouteCandidate second = collaboratorCandidate(2L);
		RouteTargetRef unknown = new RouteTargetRef(RouteTargetType.COLLABORATOR, 99L, null, 199L);
		RoutePlan plan = new RoutePlan(List.of(
				new RoutePlanStep("s1", first.target(), "query task", List.of(), "query result"),
				new RoutePlanStep("s2", unknown, "report task", List.of("s1"), "report result")));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.MULTI_SELECT,
					List.of(first.target(), second.target()), 0.9D, plan, null));

		var result = engine.route(context("query and report", AgentTypeConstant.ORCHESTRATOR), modelPolicy(true),
				List.of(first, second));

		assertEquals(RouteDecisionType.MULTI_SELECT, result.decision());
		assertEquals("COMPOUND_INTENT_REPAIRED", result.reasonCode());
		assertEquals(2, result.plan().steps().size());
	}

	@Test
	void semanticAutoSelectUsesTheRealSemanticRunnerUpForTheGap() {
		RouteCandidate lexicalAndSemanticWinner = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("alpha"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate lexicalRunnerUp = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of(), List.of("beta"), List.of(), List.of(), List.of()));
		RouteCandidate semanticRunnerUp = candidate(3L, RouteRisk.READ_ONLY, RouteRules.empty());
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(lexicalAndSemanticWinner.target(), 0.95D),
					new RouteSemanticMatch(semanticRunnerUp.target(), 0.90D),
					new RouteSemanticMatch(lexicalRunnerUp.target(), 0.70D)));

		var result = engine.route(context("alpha beta"), semanticPolicy(),
				List.of(lexicalAndSemanticWinner, lexicalRunnerUp, semanticRunnerUp));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertTrue(result.selections().isEmpty());
	}

	@Test
	void semanticAutoSelectRequiresTheSemanticAndFusedWinnersToAgree() {
		RouteCandidate fusedWinner = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("alpha"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate lexicalRunnerUp = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of(), List.of("beta"), List.of(), List.of(), List.of()));
		RouteCandidate semanticWinner = candidate(3L, RouteRisk.READ_ONLY, RouteRules.empty());
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(semanticWinner.target(), 0.99D),
					new RouteSemanticMatch(fusedWinner.target(), 0.86D),
					new RouteSemanticMatch(lexicalRunnerUp.target(), 0.70D)));

		var result = engine.route(context("alpha beta"), semanticPolicy(),
				List.of(fusedWinner, lexicalRunnerUp, semanticWinner));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertTrue(result.selections().isEmpty());
	}

	@Test
	void lowConfidenceModelNoMatchFallsBackToBusinessClarification() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenReturn(new RouteModelResult(RouteDecisionType.NO_MATCH, List.of(), 0.01D, RoutePlan.empty(), null));

		var result = engine.route(context("query"), modelPolicy(true), List.of(first, second));

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals("AMBIGUOUS_CANDIDATES", result.reasonCode());
		assertTrue(result.modelInvoked());
	}

	@Test
	void diagnosticsReuseVectorScoresFromTheRouteCall() {
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE, RouteRules.empty());
		when(semanticService.search(any(), any(), any(), anyInt(), any(Duration.class)))
			.thenReturn(List.of(new RouteSemanticMatch(candidate.target(), 0.82D)));
		RouteDiagnostics diagnostics = new RouteDiagnostics();

		var result = engine.route(context("月度统计"), semanticPolicy(), List.of(candidate), diagnostics);

		assertEquals(RouteDecisionType.CLARIFY, result.decision());
		assertEquals(candidate.target(), diagnostics.rankedCandidates().get(0).candidate().target());
		assertEquals(0.82D, diagnostics.vectorScore(candidate.target()));
	}

	@Test
	void configuredVectorTimeoutIsUsedAsTheStageMaximum() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().getRouting().setVectorTimeout(Duration.ofMillis(175));
		HybridRouteEngine configured = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()), semanticService,
				modelClient, properties, meterRegistry);
		RouteCandidate candidate = candidate(1L, RouteRisk.WRITE, RouteRules.empty());
		ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);

		configured.route(context("月度统计"), semanticPolicy(), List.of(candidate));

		verify(semanticService).search(any(), any(), any(), anyInt(), timeout.capture());
		assertEquals(Duration.ofMillis(175), timeout.getValue());
	}

	@Test
	void remainingRouteBudgetCapsEffectiveModelTimeout() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getRuntime().getRouting().setModelMinStart(Duration.ofMillis(1));
		HybridRouteEngine configured = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()), semanticService,
				modelClient, properties, meterRegistry);
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
		when(modelClient.disambiguate(any(), any(), any(), any(Duration.class)))
			.thenThrow(new RouteStageException("MODEL_TIMEOUT", "timeout"));

		configured.route(context("query", Instant.now().plusMillis(1200)), modelPolicy(true), List.of(first, second));

		verify(modelClient).disambiguate(any(), any(), any(), timeout.capture());
		assertTrue(timeout.getValue().compareTo(properties.getRuntime().getRouting().getModelTimeout()) < 0);
		assertTrue(timeout.getValue().compareTo(Duration.ZERO) > 0);
	}

	@Test
	void skipsModelWhenEffectiveBudgetAfterFinishBufferIsBelowMinimumStart() {
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));

		var result = engine.route(context("query", Instant.now().plusMillis(450)), modelPolicy(true), List.of(first, second));

		assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
		assertEquals("ROUTE_MODEL_UNAVAILABLE", result.reasonCode());
		assertEquals(RouteDegradeMode.DEGRADED_MODEL, result.degradeMode());
		assertEquals(false, result.modelInvoked());
		verifyNoInteractions(modelClient);
	}

	@Test
	void slowModelIsCancelledAtPlatformBudgetAndReturnsTerminalUnavailable() throws InterruptedException {
		ModelConfigDataService modelConfigDataService = mock(ModelConfigDataService.class);
		RouteModelAdapter slowAdapter = mock(RouteModelAdapter.class);
		RouteModelFingerprint fingerprint = new RouteModelFingerprint();
		ModelConfigDTO config = ModelConfigDTO.builder()
			.id(11L)
			.provider("custom")
			.baseUrl("https://route.example")
			.modelName("route-v2")
			.modelType(ModelType.CHAT.getCode())
			.build();
		RoutePolicy slowPolicy = new RoutePolicy(1L, true, false, true, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				11L, null, null, false, true, fingerprint.calculate(config), RouteModelOutputProtocol.JSON_OBJECT);
		RouteCandidate first = candidate(1L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		RouteCandidate second = candidate(2L, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()));
		CountDownLatch interrupted = new CountDownLatch(1);
		when(modelConfigDataService.getRuntimeConfigById(11L, ModelType.CHAT)).thenReturn(config);
		when(slowAdapter.disambiguate(any(), any(), any(), any(), any(Duration.class), any())).thenAnswer(invocation -> {
			try {
				Thread.sleep(3300L);
			}
			catch (InterruptedException ex) {
				interrupted.countDown();
				Thread.currentThread().interrupt();
				throw ex;
			}
			return null;
		});
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			DefaultRouteModelClient realClient = new DefaultRouteModelClient(modelConfigDataService, slowAdapter,
					fingerprint, executor);
			HybridRouteEngine configured = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()), semanticService,
					realClient, new DataAgentProperties(), meterRegistry);

			long started = System.nanoTime();
			var result = configured.route(context("query"), slowPolicy, List.of(first, second));
			long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

						assertEquals(RouteDecisionType.ROUTE_UNAVAILABLE, result.decision());
			assertEquals(RouteDegradeMode.DEGRADED_MODEL, result.degradeMode());
			assertEquals("ROUTE_MODEL_TIMEOUT", result.reasonCode());
			assertTrue(result.modelInvoked());
			assertTrue(elapsedMs >= 900L, "model Future returned before the configured 1100ms budget");
			assertTrue(elapsedMs < 2500L, "model Future was not cut off before the 3300ms task completed");
			assertTrue(interrupted.await(1, TimeUnit.SECONDS), "timed-out model task was not interrupted");
		}
		finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "route model executor did not terminate");
		}
	}

	private RoutePolicy policy() {
		return RoutePolicy.initial(1L, "embedding-v1");
	}

	private RoutePolicy noModelPolicy() {
		return new RoutePolicy(1L, true, false, false, 70, 15, 0D, 1D, 1D, 0.8D, null, null, "embedding-v1", false,
				false, null, RouteModelOutputProtocol.NONE);
	}

	private RoutePolicy semanticPolicy() {
		return new RoutePolicy(1L, true, true, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 22L, "embedding-v1", true, false, null, RouteModelOutputProtocol.NONE);
	}

	private RoutePolicy recallPolicy(boolean semanticRuntimeReady) {
		return new RoutePolicy(1L, true, true, false, false, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				null, 22L, "embedding-v1", semanticRuntimeReady, false, null, RouteModelOutputProtocol.NONE);
	}

	private RoutePolicy modelPolicy(boolean routeModelRuntimeReady) {
		return new RoutePolicy(1L, true, false, true, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D,
				11L, null, null, false, routeModelRuntimeReady, "route-model-v1",
				RouteModelOutputProtocol.JSON_OBJECT);
	}

	private RoutePolicy semanticAndModelPolicy() {
		return new RoutePolicy(1L, true, true, true, true, 70, 15, 0.5D, 0.85D, 0.1D, 0.8D, 11L, 22L,
				"embedding-v1", true, true, "route-model-v1", RouteModelOutputProtocol.JSON_OBJECT);
	}

	private RouteContext context(String query) {
		return context(query, Instant.now().plusSeconds(3));
	}

	private RouteContext context(String query, Instant deadline) {
		return context(query, "NORMAL", deadline);
	}

	private RouteContext context(String query, String agentType) {
		return context(query, agentType, Instant.now().plusSeconds(3));
	}

	private RouteContext context(String query, String agentType, Instant deadline) {
		return new RouteContext("tenant-1", 1L, agentType, 100L, "user-1", "run-1", query, null, null, null,
				deadline, 3);
	}

	private RouteCandidate candidate(Long id, RouteRisk risk, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				"候选" + id, "说明", "QUERY", "REACT", rules, risk, 0, 1L, 30L + id, "checksum-" + id,
				"embedding-v1");
	}

	private RouteCandidate flowCandidate(Long id, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				"order intake", "collect order details", "ACTION", "FLOW", rules, RouteRisk.FLOW, 0, 1L,
				30L + id, "checksum-" + id, "embedding-v1");
	}

	private RouteCandidate candidateWithoutArtifact(Long id, RouteRisk risk, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				"候选" + id, "说明", "QUERY", "REACT", rules, risk, 0, 1L, null, null, null);
	}

	private RouteCandidate knowledgeCandidate(Long id, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				"知识问答" + id, "知识库问答", "QA", "KNOWLEDGE", rules, RouteRisk.READ_ONLY, 0, 1L, null, null,
				null);
	}

	private RouteCandidate knowledgeSkill(Long id, String name, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				name, "咨询公司产品信息", "QA", "KNOWLEDGE", rules, RouteRisk.READ_ONLY, 0, 1L, 30L + id,
				"checksum-" + id, "embedding-v1");
	}

	private RouteCandidate collaboratorCandidate(Long id) {
		return collaboratorCandidate(id, RouteRisk.READ_ONLY,
				new RouteRules(List.of(), List.of("query"), List.of(), List.of(), List.of(), List.of()),
				DelegationMode.INTERACTIVE);
	}

	private RouteCandidate collaboratorCandidate(Long id, RouteRisk risk, RouteRules rules,
			DelegationMode delegationMode) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.COLLABORATOR, id, null, 100L + id), "tenant-1",
				1L, "协作者" + id, "说明", "QUERY", "REACT", rules, risk, 0, 1L, 30L + id,
				"checksum-" + id, "embedding-v1", delegationMode);
	}

}
