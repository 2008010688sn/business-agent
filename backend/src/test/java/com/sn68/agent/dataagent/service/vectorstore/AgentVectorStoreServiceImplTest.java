/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.vectorstore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteEmbeddingFingerprint;
import com.sn68.agent.dataagent.service.aimodelconfig.AiModelRegistry;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.hybrid.retrieval.HybridRetrievalStrategy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

class AgentVectorStoreServiceImplTest {

	private static final Long PROFILE_ID = 2082998333438996481L;

	private static final Long ARTIFACT_ID = 2083057931906789377L;

	private static final String VECTOR_DOCUMENT_ID = "route-document";

	private VectorStore vectorStore;

	private EmbeddingModel embeddingModel;

	private DataAgentProperties dataAgentProperties;

	private DynamicFilterService dynamicFilterService;

	private DynamicModelFactory modelFactory;

	private ModelConfigDataService modelConfigDataService;

	private AgentVectorStoreServiceImpl service;

	@BeforeEach
	void setUp() {
		vectorStore = mock(VectorStore.class);
		embeddingModel = mock(EmbeddingModel.class);
		dataAgentProperties = mock(DataAgentProperties.class);
		dynamicFilterService = mock(DynamicFilterService.class);
		modelFactory = mock(DynamicModelFactory.class);
		modelConfigDataService = mock(ModelConfigDataService.class);
		AiModelRegistry modelRegistry = new AiModelRegistry(modelFactory, modelConfigDataService,
				new RouteEmbeddingFingerprint(),
				mock(com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService.class));
		service = new AgentVectorStoreServiceImpl(vectorStore, Optional.<HybridRetrievalStrategy>empty(),
				dataAgentProperties, dynamicFilterService, Optional.empty(), modelRegistry);
	}

	@Test
	void addSkillDocumentsRecordsTheEmbeddingModelThatProducedTheVectors() {
		ModelConfigDTO config = ModelConfigDTO.builder()
			.id(1L)
			.provider("custom")
			.baseUrl("https://embedding.example.com")
			.modelName("bge-large-zh")
			.modelType(ModelType.EMBEDDING.getCode())
			.build();
		when(modelConfigDataService.getActiveRuntimeConfigByType(ModelType.EMBEDDING)).thenReturn(config);
		when(modelFactory.createEmbeddingModel(config)).thenReturn(embeddingModel);
		when(embeddingModel.dimensions()).thenReturn(1024);
		Document document = new Document("term", new java.util.HashMap<>(Map.of(Constant.SKILL_ID, "2",
				DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM)));

		service.addSkillDocuments("2", List.of(document));

		assertEquals(new RouteEmbeddingFingerprint().calculate(config),
				document.getMetadata().get(DocumentMetadataConstant.EMBEDDING_FINGERPRINT));
		assertEquals(1024, document.getMetadata().get(DocumentMetadataConstant.EMBEDDING_DIMENSION));
		verify(vectorStore).add(List.of(document));
	}

	@Test
	void searchDoesNotPushTheFingerprintDownAsAFilter() {
		// 存量向量没有指纹，下推会让换模型后的检索在重建完成前直接返回空
		when(dynamicFilterService.buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM, null))
			.thenReturn(new FilterExpressionBuilder().eq(Constant.SKILL_ID, "2").build());
		when(dataAgentProperties.getVectorStore()).thenReturn(new DataAgentProperties.VectorStoreProperties());
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

		service.getDocumentsForSkill("2", "query", DocumentMetadataConstant.BUSINESS_TERM, 5, 0.2D);

		verify(vectorStore).similaritySearch(requestCaptor.capture());
		assertNull(findValue(requestCaptor.getValue().getFilterExpression(),
				DocumentMetadataConstant.EMBEDDING_FINGERPRINT));
	}

	@Test
	void getDocumentsForSkillPushesTheLiveRowWhitelistIntoTheVectorFilter() {
		List<Long> allowedIds = List.of(20L);

		service.getDocumentsForSkill("2", "query", DocumentMetadataConstant.BUSINESS_TERM, 5, 0.2D, allowedIds);

		verify(dynamicFilterService).buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM, allowedIds);
	}

	@Test
	void getDocumentsForSkillWithoutWhitelistKeepsTheUnrestrictedFilter() {
		service.getDocumentsForSkill("2", "query", DocumentMetadataConstant.BUSINESS_TERM, 5, 0.2D);

		verify(dynamicFilterService).buildSkillDynamicFilter("2", DocumentMetadataConstant.BUSINESS_TERM, null);
	}

	@Test
	void findSkillDocumentResourceIdsReportsWhatTheStoreActuallyHolds() {
		DataAgentProperties.VectorStoreProperties vectorStoreProperties = new DataAgentProperties.VectorStoreProperties();
		when(dataAgentProperties.getVectorStore()).thenReturn(vectorStoreProperties);
		when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
				new Document("a", "term", Map.of(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, "20")),
				new Document("b", "term", Map.of(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, "21")),
				new Document("c", "term", Map.of(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, "20"))));

		assertEquals(Set.of("20", "21"),
				service.findSkillDocumentResourceIds("2", DocumentMetadataConstant.BUSINESS_TERM));
	}

	@Test
	void hasRouteDocumentUsesStructuredFilterForSnowflakeIds() {
		when(vectorStore.similaritySearch(any(SearchRequest.class)))
			.thenReturn(List.of(new Document(VECTOR_DOCUMENT_ID, "route", Map.of())));
		ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);

		boolean exists = service.hasRouteDocument(PROFILE_ID, ARTIFACT_ID, VECTOR_DOCUMENT_ID, embeddingModel);

		assertTrue(exists);
		verify(vectorStore).similaritySearch(requestCaptor.capture());
		Filter.Expression filter = requestCaptor.getValue().getFilterExpression();
		assertEquals(PROFILE_ID, findValue(filter, DocumentMetadataConstant.ROUTE_PROFILE_ID));
		assertEquals(ARTIFACT_ID, findValue(filter, DocumentMetadataConstant.ROUTE_ARTIFACT_ID));
	}

	@Test
	void deleteRouteDocumentsUsesStructuredFilterForSnowflakeIds() {
		ArgumentCaptor<Filter.Expression> filterCaptor = ArgumentCaptor.forClass(Filter.Expression.class);

		service.deleteRouteDocuments(PROFILE_ID, ARTIFACT_ID);

		verify(vectorStore).delete(filterCaptor.capture());
		assertEquals(PROFILE_ID,
				findValue(filterCaptor.getValue(), DocumentMetadataConstant.ROUTE_PROFILE_ID));
		assertEquals(ARTIFACT_ID,
				findValue(filterCaptor.getValue(), DocumentMetadataConstant.ROUTE_ARTIFACT_ID));
	}

	private Object findValue(Filter.Operand operand, String key) {
		if (!(operand instanceof Filter.Expression expression)) {
			return null;
		}
		if (expression.left() instanceof Filter.Key filterKey && key.equals(filterKey.key())
				&& expression.right() instanceof Filter.Value filterValue) {
			return filterValue.value();
		}
		Object left = findValue(expression.left(), key);
		return left != null ? left : findValue(expression.right(), key);
	}

}
