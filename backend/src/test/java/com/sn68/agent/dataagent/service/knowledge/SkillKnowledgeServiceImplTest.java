/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.converter.SkillKnowledgeConverter;
import com.sn68.agent.dataagent.dto.skill.SkillKnowledgeUpdateReq;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.repository.SkillKnowledgeMapper;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.skill.PublishedSkillResourceReferenceService;
import com.sn68.agent.dataagent.service.skill.SkillResourceAccessService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class SkillKnowledgeServiceImplTest {

	@Test
	void publishedResourceRejectsEveryInPlaceMutation() {
		SkillKnowledgeMapper mapper = mock(SkillKnowledgeMapper.class);
		PublishedSkillResourceReferenceService references = mock(PublishedSkillResourceReferenceService.class);
		SkillKnowledgeServiceImpl service = new SkillKnowledgeServiceImpl(mapper, new SkillKnowledgeConverter(),
				mock(SkillResourceAccessService.class), mock(LocalFileService.class),
				mock(ApplicationEventPublisher.class), references);
		SkillKnowledge knowledge = SkillKnowledge.builder().id(11L).skillId(7L).embeddingStatus(EmbeddingStatus.FAILED)
			.isRecall(true).deleted(false).build();
		when(mapper.selectById(11L)).thenReturn(knowledge);
		when(references.isSkillKnowledgeReferenced(7L, 11L)).thenReturn(true);
		SkillKnowledgeUpdateReq update = new SkillKnowledgeUpdateReq();
		update.setContent("new content");

		assertThrows(CheckedException.class, () -> service.update(7L, 11L, update));
		assertThrows(CheckedException.class, () -> service.delete(7L, 11L));
		assertThrows(CheckedException.class, () -> service.updateRecallStatus(7L, 11L, false));
		assertThrows(CheckedException.class, () -> service.retryEmbedding(7L, 11L));

		verify(mapper, never()).touch(knowledge);
		verify(mapper, never()).deleteById(11L);
	}

	@Test
	void publishedResourceCanStillBeRead() {
		SkillKnowledgeMapper mapper = mock(SkillKnowledgeMapper.class);
		PublishedSkillResourceReferenceService references = mock(PublishedSkillResourceReferenceService.class);
		SkillKnowledgeServiceImpl service = new SkillKnowledgeServiceImpl(mapper, new SkillKnowledgeConverter(),
				mock(SkillResourceAccessService.class), mock(LocalFileService.class),
				mock(ApplicationEventPublisher.class), references);
		SkillKnowledge knowledge = SkillKnowledge.builder().id(11L).skillId(7L).deleted(false).build();
		when(mapper.selectById(11L)).thenReturn(knowledge);
		when(references.isSkillKnowledgeReferenced(7L, 11L)).thenReturn(true);

		assertTrue(service.get(7L, 11L).getPublishedReferenced());
	}

}
