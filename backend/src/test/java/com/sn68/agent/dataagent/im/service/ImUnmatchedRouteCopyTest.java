/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillBinding;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
import com.sn68.agent.dataagent.repository.DataAgentSkillBindingMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImUnmatchedRouteCopyTest {

	@Mock
	private DataAgentSkillBindingMapper bindingMapper;

	@Mock
	private DataAgentSkillMapper skillMapper;

	@Mock
	private DataAgentSkillVersionMapper versionMapper;

	@InjectMocks
	private ImUnmatchedRouteCopy copy;

	@Test
	void webChannelDoesNotBuildCopy() {
		assertNull(copy.build(AgentRequest.builder().agentId("1").tenantIdSnapshot("tenant-1").build()));
		verify(bindingMapper, never()).findEnabledByAgentId(any(), any());
	}

	@Test
	void noBindingsAskAdminToBindSkills() {
		when(bindingMapper.findEnabledByAgentId(1L, "tenant-1")).thenReturn(List.of());

		assertEquals(ImUnmatchedRouteCopy.NO_BINDING, copy.build(textRequest(null)));
	}

	@Test
	void descriptionsAreListedWithoutSkillNames() {
		DataAgentSkillBinding binding = DataAgentSkillBinding.builder().id(1L).agentId(1L).tenantId("tenant-1")
			.skillId(10L).pinnedSkillVersionId(11L).enabled(true).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(10L).tenantId("tenant-1").skillCode("demand_create")
			.skillName("客服下单").description("demand_create").build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(11L).tenantId("tenant-1").skillId(10L)
			.skillName("客服下单").description("按客户和商品创建需求单").build();
		when(bindingMapper.findEnabledByAgentId(1L, "tenant-1")).thenReturn(List.of(binding));
		when(skillMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(version));

		String text = copy.build(textRequest(null));

		assertTrue(text.contains("我可以协助办理："));
		assertTrue(text.contains("- 按客户和商品创建需求单"));
		assertTrue(text.contains(ImUnmatchedRouteCopy.ASK_TASK));
		assertFalse(text.contains("客服下单"));
		assertFalse(text.contains("demand_create"));
		assertFalse(text.contains("请选择"));
	}

	@Test
	void codeLikeDescriptionFallsBackToSkillDescription() {
		DataAgentSkillBinding binding = DataAgentSkillBinding.builder().id(1L).agentId(1L).tenantId("tenant-1")
			.skillId(10L).pinnedSkillVersionId(11L).enabled(true).build();
		DataAgentSkill skill = DataAgentSkill.builder().id(10L).tenantId("tenant-1").skillCode("sku_query")
			.skillName("商品查询").description("查询在租商品与价格").build();
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(11L).tenantId("tenant-1").skillId(10L)
			.skillName("商品查询").description("sku_query").build();
		when(bindingMapper.findEnabledByAgentId(1L, "tenant-1")).thenReturn(List.of(binding));
		when(skillMapper.selectBatchIds(List.of(10L))).thenReturn(List.of(skill));
		when(versionMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(version));

		String text = copy.build(textRequest(null));

		assertTrue(text.contains("- 查询在租商品与价格"));
		assertFalse(text.contains("商品查询"));
		assertFalse(text.contains("sku_query"));
	}

	@Test
	void pinnedVersionsDoNotReadLiveBindings() {
		DataAgentSkillVersion version = DataAgentSkillVersion.builder().id(21L).tenantId("tenant-1").skillId(20L)
			.skillName("库存查询").description("查询仓库可用库存").build();
		when(versionMapper.selectBatchIds(List.of(21L))).thenReturn(List.of(version));
		when(skillMapper.selectBatchIds(List.of(20L))).thenReturn(List.of(
				DataAgentSkill.builder().id(20L).tenantId("tenant-1").skillCode("stock_query").skillName("库存查询")
					.build()));

		String text = copy.build(textRequest(List.of(21L)));

		assertTrue(text.contains("- 查询仓库可用库存"));
		verify(bindingMapper, never()).findEnabledByAgentId(any(), any());
	}

	private AgentRequest textRequest(List<Long> pinnedSkillVersionIds) {
		return AgentRequest.builder()
			.agentId("1")
			.tenantIdSnapshot("tenant-1")
			.interactionCapabilities(Set.of(ChannelInteractionCapability.TEXT_COMMANDS))
			.pinnedSkillVersionIds(pinnedSkillVersionIds)
			.build();
	}

}
