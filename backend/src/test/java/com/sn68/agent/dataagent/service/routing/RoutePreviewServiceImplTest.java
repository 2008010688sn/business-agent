/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.routing.RoutePreviewReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.DataAgentRouteProfile;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.dataagent.routing.CollaboratorRouteCandidateProvider;
import com.sn68.agent.dataagent.routing.HybridRouteEngine;
import com.sn68.agent.dataagent.routing.RouteScorer;
import com.sn68.agent.dataagent.routing.RouteSemanticService;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.routing.SkillRouteCandidateProvider;
import com.sn68.agent.dataagent.routing.RouteModelClient;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutePreviewServiceImplTest {

	@Test
	void orchestratorPreviewLoadsOnlyCollaboratorsWithinOwnerScope() {
		DataAgentService agentService = mock(DataAgentService.class);
		RouteProfileService profileService = mock(RouteProfileService.class);
		SkillRouteCandidateProvider skillProvider = mock(SkillRouteCandidateProvider.class);
		CollaboratorRouteCandidateProvider collaboratorProvider = mock(CollaboratorRouteCandidateProvider.class);
		AgentOrchestrationPolicyMapper policyMapper = mock(AgentOrchestrationPolicyMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		HybridRouteEngine engine = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()),
				mock(RouteSemanticService.class), mock(RouteModelClient.class), new DataAgentProperties(),
				new SimpleMeterRegistry());
		RoutePreviewServiceImpl service = new RoutePreviewServiceImpl(agentService, profileService, skillProvider,
				collaboratorProvider, policyMapper, engine, authenticationContext);
		DataAgent owner = DataAgent.builder().id(10L).tenantId("tenant-1")
			.agentType(AgentTypeConstant.ORCHESTRATOR).status("published").deleted(false).build();
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder().id(1L).status("ACTIVE").deleted(false).build();
		RoutePolicy routePolicy = RoutePolicy.initial(1L, null);
		when(agentService.requireAgent(10L)).thenReturn(owner);
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userId()).thenReturn("100");
		when(profileService.requireUsable(1L, true)).thenReturn(profile);
		when(profileService.toPolicy(profile)).thenReturn(routePolicy);
		when(policyMapper.findByAgentId(10L))
			.thenReturn(AgentOrchestrationPolicy.builder().enabled(true).maxCollaboratorsPerRun(3).build());

		service.preview(10L, new RoutePreviewReq("分别查询订单和账单", 1L, null));

		verify(collaboratorProvider).load(any(), eq(profile), eq(routePolicy), any());
		verifyNoInteractions(skillProvider);
	}

	@Test
	void previewUsesPolicyBeforeLoadingCandidatesAndAppliesKnowledgeDefault() {
		DataAgentService agentService = mock(DataAgentService.class);
		RouteProfileService profileService = mock(RouteProfileService.class);
		SkillRouteCandidateProvider skillProvider = mock(SkillRouteCandidateProvider.class);
		CollaboratorRouteCandidateProvider collaboratorProvider = mock(CollaboratorRouteCandidateProvider.class);
		AgentOrchestrationPolicyMapper policyMapper = mock(AgentOrchestrationPolicyMapper.class);
		AuthenticationContext authenticationContext = mock(AuthenticationContext.class);
		RouteSemanticService semanticService = mock(RouteSemanticService.class);
		RouteModelClient modelClient = mock(RouteModelClient.class);
		HybridRouteEngine engine = new HybridRouteEngine(new RouteScorer(new RouteTextNormalizer()), semanticService,
				modelClient, new DataAgentProperties(), new SimpleMeterRegistry());
		RoutePreviewServiceImpl service = new RoutePreviewServiceImpl(agentService, profileService, skillProvider,
				collaboratorProvider, policyMapper, engine, authenticationContext);
		DataAgent owner = DataAgent.builder().id(10L).tenantId("tenant-1").agentType(AgentTypeConstant.KNOWLEDGE_BASE)
			.status("published").deleted(false).build();
		DataAgentRouteProfile profile = DataAgentRouteProfile.builder().id(1L).status("ACTIVE").deleted(false).build();
		RoutePolicy routePolicy = RoutePolicy.initial(1L, null);
		RouteCandidate candidate = new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, 30L, 31L, 5L),
				"tenant-1", 10L, "知识问答", "知识库问答", "QA", "KNOWLEDGE", RouteRules.empty(), RouteRisk.READ_ONLY,
				0, 1L, null, null, null);
		when(agentService.requireAgent(10L)).thenReturn(owner);
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
		when(authenticationContext.userId()).thenReturn("100");
		when(profileService.requireUsable(1L, true)).thenReturn(profile);
		when(profileService.toPolicy(profile)).thenReturn(routePolicy);
		when(skillProvider.load(any(), eq(profile), eq(routePolicy), any())).thenReturn(List.of(candidate));

		var response = service.preview(10L, new RoutePreviewReq("有哪些产品", 1L, null));

		assertEquals("SELECT", response.decision());
		assertEquals("KNOWLEDGE_SINGLE_CANDIDATE", response.reasonCode());
		var ordered = inOrder(profileService, skillProvider);
		ordered.verify(profileService).toPolicy(profile);
		ordered.verify(skillProvider).load(any(), eq(profile), eq(routePolicy), any());
	}

}
