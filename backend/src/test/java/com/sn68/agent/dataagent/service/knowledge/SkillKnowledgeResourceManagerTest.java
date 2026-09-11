/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.knowledge;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SkillKnowledgeResourceManagerTest {

	private static final Long SKILL_ID = 2065262710003384321L;

	private static final Long KNOWLEDGE_ID = 2065294018721169409L;

	private AgentVectorStoreService vectorStoreService;

	private SkillKnowledgeResourceManager resourceManager;

	@BeforeEach
	void setUp() {
		vectorStoreService = mock(AgentVectorStoreService.class);
		resourceManager = new SkillKnowledgeResourceManager(mock(TextSplitterFactory.class),
				mock(LocalFileService.class), vectorStoreService);
	}

	@Test
	void reEmbedRemovesThePreviousVectorsBeforeWritingTheNewOnes() {
		when(vectorStoreService.deleteSkillDocumentsByMetadata(anyString(), any())).thenReturn(true);

		resourceManager.embed(qaKnowledge());

		InOrder inOrder = inOrder(vectorStoreService);
		inOrder.verify(vectorStoreService).deleteSkillDocumentsByMetadata(eq(String.valueOf(SKILL_ID)),
				eq(Map.of(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID, String.valueOf(KNOWLEDGE_ID))));
		inOrder.verify(vectorStoreService).addSkillDocuments(eq(String.valueOf(SKILL_ID)), anyList());
	}

	/**
	 * 分片 id 每次随机生成，重复写入是叠加而非覆盖，且新旧分片带同一个 skillKnowledgeId，检索侧无从分辨。
	 * 所以旧向量没删掉时必须中止，否则用户会同时召回到两份内容。
	 */
	@Test
	void reEmbedAbortsInsteadOfDuplicatingWhenThePreviousVectorsCannotBeRemoved() {
		when(vectorStoreService.deleteSkillDocumentsByMetadata(anyString(), any()))
			.thenThrow(new IllegalStateException("vector store unavailable"));

		assertThrows(IllegalStateException.class, () -> resourceManager.embed(qaKnowledge()));

		verify(vectorStoreService, never()).addSkillDocuments(anyString(), anyList());
	}

	private SkillKnowledge qaKnowledge() {
		return SkillKnowledge.builder()
			.id(KNOWLEDGE_ID)
			.skillId(SKILL_ID)
			.type(KnowledgeType.QA)
			.question("什么是循环箱")
			.content("可重复使用的物流包装箱")
			.isRecall(true)
			.deleted(false)
			.build();
	}

}
