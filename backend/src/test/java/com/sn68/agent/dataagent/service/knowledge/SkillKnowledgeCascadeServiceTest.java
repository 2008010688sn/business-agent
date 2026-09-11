/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.knowledge.SkillKnowledgeCascadeService.DeletedSkillKnowledge;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SkillKnowledgeCascadeServiceTest {

	private static final Long SKILL_ID = 7L;

	@BeforeAll
	static void initTableInfo() {
		// 级联走 Wraps 的 lambda 条件，列名解析依赖 MP 的 TableInfo 缓存；
		// 纯单测没有 Spring 做 mapper 扫描，按 DataAgentRouteProfileMapperTest 的同款写法手动注册。
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		TableInfoHelper.initTableInfo(assistant, BusinessKnowledge.class);
		TableInfoHelper.initTableInfo(assistant, SkillKnowledge.class);
	}

	private final BusinessKnowledgeMapper businessKnowledgeMapper = mock(BusinessKnowledgeMapper.class);

	private final SkillKnowledgeMapper skillKnowledgeMapper = mock(SkillKnowledgeMapper.class);

	private final AgentVectorStoreService agentVectorStoreService = mock(AgentVectorStoreService.class);

	private final SkillKnowledgeCascadeService service = new SkillKnowledgeCascadeService(businessKnowledgeMapper,
			skillKnowledgeMapper, agentVectorStoreService);

	/**
	 * 被已发布版本固定的知识行会以「新副本 + 旧行 supersededById」的形式保留下来，逐条删除入口拒绝删它们。
	 * Skill 整体删除时这批行必须一起清掉，否则最该被清理的那部分反而永远留在库里和向量库里。
	 */
	@Test
	void cascadeCoversRowsPinnedByPublishedVersionsInsteadOfOnlyCurrentRows() {
		when(businessKnowledgeMapper.selectList(any()))
			.thenReturn(List.of(businessKnowledge(11L, null), businessKnowledge(12L, 11L)));
		when(skillKnowledgeMapper.selectList(any())).thenReturn(List.of(skillKnowledge(21L, 22L)));

		DeletedSkillKnowledge deleted = service.deleteKnowledgeRows(SKILL_ID);

		assertEquals(List.of(11L, 12L), deleted.businessKnowledgeIds());
		assertEquals(List.of(21L), deleted.skillKnowledgeIds());
		ArgumentCaptor<LbqWrapper<BusinessKnowledge>> queryCaptor = ArgumentCaptor.captor();
		verify(businessKnowledgeMapper).selectList(queryCaptor.capture());
		String querySegment = queryCaptor.getValue().getSqlSegment();
		assertTrue(querySegment.contains("skill_id"), querySegment);
		assertFalse(querySegment.contains("superseded_by_id"), querySegment);
		ArgumentCaptor<LambdaUpdateWrapper<BusinessKnowledge>> updateCaptor = ArgumentCaptor.captor();
		verify(businessKnowledgeMapper).update(isNull(), updateCaptor.capture());
		LambdaUpdateWrapper<BusinessKnowledge> update = updateCaptor.getValue();
		assertTrue(update.getSqlSet().contains("deleted"), update.getSqlSet());
		// MP 的条件参数在渲染 SQL 片段时才写进 paramNameValuePairs，取参数前必须先取一次片段
		String updateSegment = update.getSqlSegment();
		assertTrue(updateSegment.contains("IN"), updateSegment);
		assertTrue(update.getParamNameValuePairs().containsValue(11L), updateSegment);
		assertTrue(update.getParamNameValuePairs().containsValue(12L), updateSegment);
	}

	/**
	 * 空集合守卫：空 {@code IN} 条件在 Wraps 侧会被静默丢弃，一旦漏掉这层守卫，无知识的 Skill 删除会退化成
	 * 「无条件逻辑删除整张表」。
	 */
	@Test
	void cascadeIssuesNoUpdateWhenTheSkillOwnsNoKnowledge() {
		when(businessKnowledgeMapper.selectList(any())).thenReturn(List.of());
		when(skillKnowledgeMapper.selectList(any())).thenReturn(List.of());

		DeletedSkillKnowledge deleted = service.deleteKnowledgeRows(SKILL_ID);

		assertEquals(List.of(), deleted.businessKnowledgeIds());
		assertEquals(List.of(), deleted.skillKnowledgeIds());
		verify(businessKnowledgeMapper, never()).update(any(), any());
		verify(skillKnowledgeMapper, never()).update(any(), any());
	}

	@Test
	void vectorPurgeSweepsBothKnowledgeVectorTypesForTheSkill() throws Exception {
		service.purgeKnowledgeVectors(SKILL_ID, new DeletedSkillKnowledge(List.of(11L), List.of(21L)));

		verify(agentVectorStoreService).deleteSkillDocumentsByVectorType("7", DocumentMetadataConstant.BUSINESS_TERM);
		verify(agentVectorStoreService).deleteSkillDocumentsByVectorType("7", DocumentMetadataConstant.SKILL_KNOWLEDGE);
	}

	/**
	 * 清理失败不能静默：行已经删掉，残留向量既无对应行也无重建入口可覆盖，必须报出来。同时第一种类型失败
	 * 不应吃掉第二种类型的清理。
	 */
	@Test
	void vectorPurgeFailureIsSurfacedAfterEveryVectorTypeHasBeenAttempted() throws Exception {
		when(agentVectorStoreService.deleteSkillDocumentsByVectorType(anyString(),
				eq(DocumentMetadataConstant.BUSINESS_TERM)))
			.thenThrow(new IllegalStateException("vector store unavailable"));

		CheckedException error = assertThrows(CheckedException.class,
				() -> service.purgeKnowledgeVectors(SKILL_ID, new DeletedSkillKnowledge(List.of(11L), List.of())));

		assertTrue(error.getMessage().contains(String.valueOf(SKILL_ID)), error.getMessage());
		assertTrue(error.getMessage().contains(DocumentMetadataConstant.BUSINESS_TERM), error.getMessage());
		verify(agentVectorStoreService).deleteSkillDocumentsByVectorType("7", DocumentMetadataConstant.SKILL_KNOWLEDGE);
	}

	@Test
	void cascadeRejectsMissingSkillId() {
		assertThrows(CheckedException.class, () -> service.deleteKnowledgeRows(null));
		assertThrows(CheckedException.class, () -> service.purgeKnowledgeVectors(null, DeletedSkillKnowledge.none()));
	}

	private BusinessKnowledge businessKnowledge(Long id, Long supersededById) {
		return BusinessKnowledge.builder().id(id).skillId(SKILL_ID).supersededById(supersededById).deleted(false)
			.build();
	}

	private SkillKnowledge skillKnowledge(Long id, Long supersededById) {
		return SkillKnowledge.builder().id(id).skillId(SKILL_ID).supersededById(supersededById).deleted(false).build();
	}

}
