/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import com.sn68.agent.dataagent.enums.KnowledgeType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

class DocumentConverterUtilTest {

	@Test
	void convertBusinessKnowledgeUsesStringSnowflakeIdMetadata() {
		BusinessKnowledge knowledge = BusinessKnowledge.builder().id(2064168410008010753L)
			.skillId(2065262710003384321L).businessTerm("product metric").build();

		Document document = DocumentConverterUtil.convertBusinessKnowledgeToDocument(knowledge);

		assertEquals("2064168410008010753",
				document.getMetadata().get(DocumentMetadataConstant.DB_BUSINESS_TERM_ID));
	}

	@Test
	void convertSkillQaUsesSkillKnowledgeMetadata() {
		SkillKnowledge knowledge = SkillKnowledge.builder().id(2065294018721169409L)
			.skillId(2065262710003384321L).type(KnowledgeType.QA).title("产品知识")
			.question("公司提供什么产品").content("智能包装产品").build();

		Document document = DocumentConverterUtil.convertSkillKnowledgeToDocument(knowledge);

		assertEquals("2065294018721169409",
				document.getMetadata().get(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID));
	}

	@Test
	void stampEmbeddingIdentityRecordsTheProducingModelAndSkipsRouteDocuments() {
		Document knowledge = new Document("知识内容", new java.util.HashMap<>(
				Map.of(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM)));
		Document route = new Document("路由内容", new java.util.HashMap<>(Map.of(
				DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE,
				DocumentMetadataConstant.EMBEDDING_FINGERPRINT, "route-owned")));

		DocumentConverterUtil.stampEmbeddingIdentity(List.of(knowledge, route), "fp-1024", 1024);

		assertEquals("fp-1024", knowledge.getMetadata().get(DocumentMetadataConstant.EMBEDDING_FINGERPRINT));
		assertEquals(1024, knowledge.getMetadata().get(DocumentMetadataConstant.EMBEDDING_DIMENSION));
		assertEquals("route-owned", route.getMetadata().get(DocumentMetadataConstant.EMBEDDING_FINGERPRINT));
	}

	@Test
	void stampEmbeddingIdentityWritesNothingWhenTheModelIdentityIsUnknown() {
		Document knowledge = new Document("知识内容", new java.util.HashMap<>(
				Map.of(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.SKILL_KNOWLEDGE)));

		DocumentConverterUtil.stampEmbeddingIdentity(List.of(knowledge), "  ", 1024);
		DocumentConverterUtil.stampEmbeddingIdentity(List.of(knowledge), "fp-1024", 0);

		assertFalse(knowledge.getMetadata().containsKey(DocumentMetadataConstant.EMBEDDING_FINGERPRINT));
		assertFalse(knowledge.getMetadata().containsKey(DocumentMetadataConstant.EMBEDDING_DIMENSION));
	}

	@Test
	void convertSkillDocumentsUsesSkillKnowledgeMetadata() {
		SkillKnowledge knowledge = SkillKnowledge.builder().id(2065294018721169409L)
			.skillId(2065262710003384321L).type(KnowledgeType.DOCUMENT).build();

		List<Document> documents = DocumentConverterUtil
			.convertSkillKnowledgeDocumentsWithMetadata(List.of(new Document("文档内容")), knowledge);

		assertEquals("2065294018721169409",
				documents.get(0).getMetadata().get(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID));
	}

}
