/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.service.skill.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingEditorContextResp;
import com.sn68.agent.dataagent.dto.skill.AgentSkillBindingV2DTO;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteArtifactMapper;
import com.sn68.agent.dataagent.repository.DataAgentRouteProfileMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.repository.ModelConfigMapper;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver;
import com.sn68.agent.dataagent.routing.RouteArtifactChecksum;
import com.sn68.agent.dataagent.routing.RouteEmbeddingModelResolver;
import com.sn68.agent.dataagent.routing.RouteModelFingerprint;
import com.sn68.agent.dataagent.routing.RouteRiskResolver;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.RouteTextNormalizer;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.dataagent.service.routing.RouteArtifactServiceImpl;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class SkillBindingServiceImplTest {

	private final DataAgentSkillBindingMapper bindingMapper = mock(DataAgentSkillBindingMapper.class);

	private final DataAgentSkillMapper skillMapper = mock(DataAgentSkillMapper.class);

	private final DataAgentSkillVersionMapper versionMapper = mock(DataAgentSkillVersionMapper.class);

	private final DataAgentService dataAgentService = mock(DataAgentService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final RouteArtifactService routeArtifactService = mock(RouteArtifactService.class);

	private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

	private final SkillBindingServiceImpl service = new SkillBindingServiceImpl(bindingMapper, skillMapper,
			versionMapper, dataAgentService, authenticationContext, routeArtifactService, transactionTemplate);

	@BeforeEach
	void executeTransactionCallbacks() {
		doAnswer(invocation -> {
			invocation.<java.util.function.Consumer<TransactionStatus>>getArgument(0)
				.accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
	}

	@Test
	void editorContextContainsOnlyPublishedTenantSkillVersions() {
		stubAgent();
		DataAgentSkill published = skill(31L, 41L);
		DataAgentSkillVersion version = version(41L, 31L);
		when(bindingMapper.findByAgentId(7L)).thenReturn(List.of());
		when(skillMapper.findVisible("tenant-1")).thenReturn(List.of(published));
		when(versionMapper.findPublishedBySkillIds(List.of(31L))).thenReturn(List.of(version));

		AgentSkillBindingEditorContextResp context = service.editorContext(7L);

		assertEquals(1, context.skills().size());
		assertEquals(31L, context.skills().get(0).skillId());
		assertEquals(41L, context.skills().get(0).publishedVersionId());
		assertEquals(List.of(41L), context.skills().get(0).publishedVersions().stream()
			.map(item -> item.id()).toList());
		assertEquals("订单查询 V2", context.skills().get(0).publishedVersions().get(0).skillName());
		assertEquals("查询已发布订单", context.skills().get(0).publishedVersions().get(0).description());
	}

	@Test
	void invalidExistingSkillBindingIsReportedWithoutTemplateFallback() {
		stubAgent();
		DataAgentSkillBinding binding = DataAgentSkillBinding.builder().agentId(7L).tenantId("tenant-1")
			.skillId(99L).pinnedSkillVersionId(100L).priority(5).enabled(true).build();
		when(bindingMapper.findByAgentId(7L)).thenReturn(List.of(binding));
		when(skillMapper.findVisible("tenant-1")).thenReturn(List.of());

		AgentSkillBindingEditorContextResp context = service.editorContext(7L);

		assertEquals(1, context.bindings().size());
		assertTrue(context.skills().isEmpty());
		assertFalse(context.issues().isEmpty());
	}

	@Test
	void replaceRejectsVersionFromAnotherSkill() {
		stubAgent();
		DataAgentSkill skill = skill(31L, 41L);
		DataAgentSkillVersion foreignVersion = version(41L, 32L);
		when(skillMapper.selectBatchIds(List.of(31L))).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(List.of(41L))).thenReturn(List.of(foreignVersion));

		assertThrows(CheckedException.class,
				() -> service.replace(7L, List.of(new AgentSkillBindingV2DTO(31L, 41L, 0, true))));
	}

	@Test
	void replacePersistsOnlyPinnedPublishedSkillVersion() {
		stubAgent();
		DataAgentSkill skill = skill(31L, 41L);
		DataAgentSkillVersion version = version(41L, 31L);
		when(skillMapper.selectBatchIds(List.of(31L))).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(List.of(41L))).thenReturn(List.of(version));
		when(bindingMapper.findByAgentId(7L)).thenReturn(List.of());

		List<AgentSkillBindingV2DTO> result = service.replace(7L,
				List.of(new AgentSkillBindingV2DTO(31L, 41L, 3, true)));

		verify(bindingMapper).deleteByAgentId(7L);
		verify(bindingMapper).insertBatch(argThat(saved -> saved.size() == 1
				&& saved.iterator().next().getPinnedSkillVersionId().equals(41L)));
		assertTrue(result.isEmpty());
	}

	/**
	 * 协作者物料刷新发生在事务提交之后，绑定已经落库，刷新失败会变成"数据已保存却报错"的假失败。
	 *
	 * <p>这里接入真实 {@link RouteArtifactServiceImpl}，复现 agent 已下线仍被其它编排 Agent 引用为协作者的现场。
	 */
	@Test
	void replaceSavesBindingsWhenCollaboratorRefreshSkipsOfflineAgent() {
		stubAgent();
		DataAgentSkill skill = skill(31L, 41L);
		DataAgentSkillVersion version = version(41L, 31L);
		when(skillMapper.selectBatchIds(List.of(31L))).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(List.of(41L))).thenReturn(List.of(version));
		when(bindingMapper.findByAgentId(7L)).thenReturn(List.of(DataAgentSkillBinding.builder().agentId(7L)
			.tenantId("tenant-1").skillId(31L).pinnedSkillVersionId(41L).priority(3).enabled(true).build()));
		AgentCollaboratorMapper collaboratorMapper = mock(AgentCollaboratorMapper.class);
		DataAgentMapper agentMapper = mock(DataAgentMapper.class);
		when(collaboratorMapper.findEnabledByCollaboratorAgentId(7L))
			.thenReturn(List.of(AgentCollaborator.builder().id(20L).agentId(8L).collaboratorAgentId(7L)
				.delegationMode(DelegationMode.INTERACTIVE.name()).routingRules(Map.of()).priority(0).enabled(true)
				.deleted(false).build()));
		when(agentMapper.selectById(8L)).thenReturn(DataAgent.builder().id(8L).tenantId("tenant-1")
			.status("published").agentType(AgentTypeConstant.ORCHESTRATOR).deleted(false).build());
		when(agentMapper.selectById(7L)).thenReturn(DataAgent.builder().id(7L).tenantId("tenant-1")
			.status("offline").agentType(AgentTypeConstant.DATA_ANALYSIS).deleted(false).build());
		SkillBindingServiceImpl serviceWithRealRouting = new SkillBindingServiceImpl(bindingMapper, skillMapper,
				versionMapper, dataAgentService, authenticationContext,
				routeArtifactService(collaboratorMapper, agentMapper), transactionTemplate);

		List<AgentSkillBindingV2DTO> result = serviceWithRealRouting.replace(7L,
				List.of(new AgentSkillBindingV2DTO(31L, 41L, 3, true)));

		verify(bindingMapper).deleteByAgentId(7L);
		verify(bindingMapper).insertBatch(argThat(saved -> saved.size() == 1
				&& saved.iterator().next().getPinnedSkillVersionId().equals(41L)));
		assertEquals(1, result.size());
		assertEquals(41L, result.get(0).pinnedSkillVersionId());
	}

	private RouteArtifactServiceImpl routeArtifactService(AgentCollaboratorMapper collaboratorMapper,
			DataAgentMapper agentMapper) {
		return new RouteArtifactServiceImpl(mock(DataAgentRouteProfileMapper.class),
				mock(DataAgentRouteArtifactMapper.class), skillMapper, bindingMapper, versionMapper, collaboratorMapper,
				agentMapper, new RouteRulesService(new ObjectMapper(), new RouteTextNormalizer()),
				mock(AgentVectorStoreService.class), mock(RouteEmbeddingModelResolver.class),
				mock(RouteArtifactChecksum.class), mock(RouteRiskResolver.class), mock(ModelConfigMapper.class),
				mock(CollaboratorCapabilityResolver.class), new RouteModelFingerprint(), transactionTemplate);
	}

	private void stubAgent() {
		when(dataAgentService.findById(7L)).thenReturn(DataAgent.builder().id(7L).tenantId("tenant-1").build());
		when(authenticationContext.tenantId()).thenReturn("tenant-1");
	}

	private DataAgentSkill skill(Long skillId, Long publishedVersionId) {
		return DataAgentSkill.builder().id(skillId).tenantId("tenant-1").scope("TENANT")
			.skillCode("order-query-" + skillId).skillName("订单查询").executionMode("DETERMINISTIC")
			.skillKind("QUERY").status("PUBLISHED").publishedVersionId(publishedVersionId).build();
	}

	private DataAgentSkillVersion version(Long versionId, Long skillId) {
		return DataAgentSkillVersion.builder().id(versionId).tenantId("tenant-1").skillId(skillId).versionNo(2)
			.skillName("订单查询 V2").description("查询已发布订单").skillKind("QUERY")
			.executionMode("DETERMINISTIC").status("PUBLISHED").build();
	}

}
