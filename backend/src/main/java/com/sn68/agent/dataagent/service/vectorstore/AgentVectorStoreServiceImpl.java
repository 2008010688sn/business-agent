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
package com.sn68.agent.dataagent.service.vectorstore;

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.dto.search.AgentSearchReq;
import com.sn68.agent.dataagent.dto.search.HybridSearchReq;
import com.sn68.agent.dataagent.service.hybrid.retrieval.HybridRetrievalStrategy;
import com.sn68.agent.dataagent.service.aimodelconfig.AiModelRegistry;
import com.sn68.agent.dataagent.util.DocumentConverterUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStoreContent;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.lang.reflect.Field;
import java.util.*;

import static com.sn68.agent.dataagent.service.vectorstore.DynamicFilterService.buildFilterExpressionString;

/**
 * Agent 向量库服务实现：封装向量写入、检索与按维度过滤。
 */
@Slf4j
@Service
public class AgentVectorStoreServiceImpl implements AgentVectorStoreService {

	private static final String DEFAULT = "default";

	private static final String SKILL_ID_REQUIRED = "SkillId cannot be empty.";

	private static final String DOCUMENT_BATCHES_REQUIRED = "Document batches cannot be empty.";

	private static final String METADATA_REQUIRED = "Metadata cannot be null.";

	private final VectorStore vectorStore;

	private final Optional<HybridRetrievalStrategy> hybridRetrievalStrategy;

	private final DataAgentProperties dataAgentProperties;

	private final DynamicFilterService dynamicFilterService;

	private final Optional<SimpleVectorStoreInitialization> simpleVectorStoreInitialization;

	private final AiModelRegistry modelRegistry;

	public AgentVectorStoreServiceImpl(VectorStore vectorStore,
			Optional<HybridRetrievalStrategy> hybridRetrievalStrategy, DataAgentProperties dataAgentProperties,
			DynamicFilterService dynamicFilterService,
			Optional<SimpleVectorStoreInitialization> simpleVectorStoreInitialization,
			AiModelRegistry modelRegistry) {
		this.vectorStore = vectorStore;
		this.hybridRetrievalStrategy = hybridRetrievalStrategy;
		this.dataAgentProperties = dataAgentProperties;
		this.dynamicFilterService = dynamicFilterService;
		this.simpleVectorStoreInitialization = simpleVectorStoreInitialization;
		this.modelRegistry = modelRegistry;
		log.info("VectorStore type: {}", vectorStore.getClass().getSimpleName());
	}

	@Override
	public List<Document> search(AgentSearchReq searchRequest) {
		Assert.hasText(searchRequest.getAgentId(), "AgentId cannot be empty");
		Assert.hasText(searchRequest.getDocVectorType(), "DocVectorType cannot be empty");

		Filter.Expression filter = dynamicFilterService.buildDynamicFilter(searchRequest.getAgentId(),
				searchRequest.getDocVectorType());
		// 根据agentId vectorType找不到要 召回 的业务知识或者智能体知识
		if (filter == null) {
			log.warn(
					"Dynamic filter returned null (no valid ids), returning empty result directly.AgentId: {}, VectorType: {}",
					searchRequest.getAgentId(), searchRequest.getDocVectorType());
			return Collections.emptyList();
		}

		HybridSearchReq hybridRequest = HybridSearchReq.builder()
			.query(searchRequest.getQuery())
			.topK(searchRequest.getTopK())
			.similarityThreshold(searchRequest.getSimilarityThreshold())
			.filterExpression(filter)
			.build();

		if (dataAgentProperties.getVectorStore().isEnableHybridSearch() && hybridRetrievalStrategy.isPresent()) {
			return hybridRetrievalStrategy.get().retrieve(hybridRequest);
		}
		log.debug("Hybrid search is not enabled. use vector-search only");
		List<Document> results = vectorStore.similaritySearch(hybridRequest.toVectorSearchRequest());
		log.debug("Search completed with vectorType: {}, found {} documents for SearchRequest: {}",
				searchRequest.getDocVectorType(), results.size(), searchRequest);
		return results;

	}

	@Override
	public Boolean deleteDocumentsByVectorType(String agentId, String vectorType) throws Exception {
		Assert.notNull(agentId, "AgentId cannot be null.");
		Assert.notNull(vectorType, "VectorType cannot be null.");

		Map<String, Object> metadata = new HashMap<>(Map.ofEntries(Map.entry(Constant.AGENT_ID, agentId),
				Map.entry(DocumentMetadataConstant.VECTOR_TYPE, vectorType)));

		return this.deleteDocumentsByMetedata(agentId, metadata);
	}

	@Override
	public void addDocuments(String agentId, List<Document> documents) {
		validateDocumentBatch(agentId, documents);
		stampEmbeddingIdentity(documents);
		mutateVectorStore(() -> vectorStore.add(documents));
	}

	@Override
	public void addSkillDocuments(String skillId, List<Document> documents) {
		validateSkillDocumentBatch(skillId, documents);
		stampEmbeddingIdentity(documents);
		mutateVectorStore(() -> vectorStore.add(documents));
	}

	@Override
	public void addDocumentBatches(String agentId, List<List<Document>> documentBatches) {
		Assert.notNull(agentId, "AgentId cannot be null.");
		Assert.notEmpty(documentBatches, DOCUMENT_BATCHES_REQUIRED);

		List<List<Document>> validBatches = documentBatches.stream()
			.filter(batch -> batch != null && !batch.isEmpty())
			.toList();
		Assert.notEmpty(validBatches, DOCUMENT_BATCHES_REQUIRED);
		for (List<Document> batch : validBatches) {
			validateDocumentBatch(agentId, batch);
			stampEmbeddingIdentity(batch);
		}
		if (vectorStore instanceof SimpleVectorStore) {
			mutateVectorStore(() -> validBatches.forEach(vectorStore::add));
			return;
		}
		List<Document> documents = validBatches.stream().flatMap(List::stream).toList();
		mutateVectorStore(() -> vectorStore.add(documents));
	}

	@Override
	public void addSkillDocumentBatches(String skillId, List<List<Document>> documentBatches) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.notEmpty(documentBatches, DOCUMENT_BATCHES_REQUIRED);
		List<List<Document>> validBatches = documentBatches.stream()
			.filter(batch -> batch != null && !batch.isEmpty())
			.toList();
		Assert.notEmpty(validBatches, DOCUMENT_BATCHES_REQUIRED);
		for (List<Document> batch : validBatches) {
			validateSkillDocumentBatch(skillId, batch);
			stampEmbeddingIdentity(batch);
		}
		if (vectorStore instanceof SimpleVectorStore) {
			mutateVectorStore(() -> validBatches.forEach(vectorStore::add));
			return;
		}
		List<Document> documents = validBatches.stream().flatMap(List::stream).toList();
		mutateVectorStore(() -> vectorStore.add(documents));
	}

	/**
	 * 写入前记录产生这批向量的 embedding 模型身份。
	 * <p>
	 * 这里是 TABLE / COLUMN / BUSINESS_TERM / SKILL_KNOWLEDGE / AGENT_MEMORY 五类文档共同的写入口，
	 * 统一在此打标可以避免每个业务侧各写一遍、各漏一遍。检索侧<b>刻意没有</b>把指纹下推成 filter：存量向量
	 * 全都没有这个字段，一旦下推，换模型后所有召回会在重建完成前直接返回空——那是把静默退化换成了立即不可用。
	 * 写入侧留痕已足以让「哪些向量已过期」可查。
	 */
	private void stampEmbeddingIdentity(List<Document> documents) {
		AiModelRegistry.EmbeddingIdentity identity = modelRegistry.getEmbeddingIdentity();
		if (identity == null || !identity.isUsable()) {
			// 没有真实模型时写入随后自己会失败，这里不该补一个假身份
			log.warn("Embedding identity is unavailable, {} documents will be written without model fingerprint.",
					documents.size());
			return;
		}
		DocumentConverterUtil.stampEmbeddingIdentity(documents, identity.fingerprint(), identity.dimensions());
	}

	@Override
	public void addRouteDocument(Document document, EmbeddingModel embeddingModel) {
		Assert.notNull(document, "Route document cannot be null.");
		Map<String, Object> metadata = document.getMetadata();
		Assert.notNull(metadata, "Route document metadata cannot be null.");
		Assert.isTrue(DocumentMetadataConstant.ROUTE.equals(metadata.get(DocumentMetadataConstant.VECTOR_TYPE)),
				"Route document vectorType must be ROUTE.");
		for (String key : List.of(DocumentMetadataConstant.ROUTE_TENANT_ID,
				DocumentMetadataConstant.ROUTE_PROFILE_ID, DocumentMetadataConstant.ROUTE_ARTIFACT_ID,
				DocumentMetadataConstant.ROUTE_TARGET_TYPE, DocumentMetadataConstant.ROUTE_TARGET_ID,
				DocumentMetadataConstant.ROUTE_SOURCE_CHECKSUM,
				DocumentMetadataConstant.ROUTE_EMBEDDING_FINGERPRINT,
				DocumentMetadataConstant.ROUTE_RISK_LEVEL)) {
			Assert.notNull(metadata.get(key), "Route document metadata must contain " + key + ".");
		}
		modelRegistry.withEmbeddingModel(embeddingModel, () -> {
			mutateVectorStore(() -> vectorStore.add(List.of(document)));
			return null;
		});
	}

	@Override
	public List<Document> searchRouteDocuments(String tenantId, Long profileId, String embeddingFingerprint,
			Collection<Long> eligibleArtifactIds, String query, int topK, double threshold,
			EmbeddingModel embeddingModel) {
		Assert.hasText(tenantId, "Route tenantId cannot be empty.");
		Assert.notNull(profileId, "Route profileId cannot be null.");
		Assert.hasText(embeddingFingerprint, "Route embeddingFingerprint cannot be empty.");
		Assert.hasText(query, "Route query cannot be empty.");
		if (eligibleArtifactIds == null || eligibleArtifactIds.isEmpty()) {
			return List.of();
		}
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();
		conditions.add(builder.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE).build());
		conditions.add(builder.eq(DocumentMetadataConstant.ROUTE_TENANT_ID, tenantId).build());
		conditions.add(builder.eq(DocumentMetadataConstant.ROUTE_PROFILE_ID, profileId).build());
		conditions.add(builder.eq(DocumentMetadataConstant.ROUTE_EMBEDDING_FINGERPRINT,
				embeddingFingerprint).build());
		conditions.add(builder.in(DocumentMetadataConstant.ROUTE_ARTIFACT_ID,
				eligibleArtifactIds.stream().map(value -> (Object) value).toList()).build());
		SearchRequest request = SearchRequest.builder()
			.query(query)
			.topK(topK)
			.similarityThreshold(threshold)
			.filterExpression(DynamicFilterService.combineWithAnd(conditions))
			.build();
		return modelRegistry.withEmbeddingModel(embeddingModel, () -> vectorStore.similaritySearch(request));
	}

	@Override
	public boolean hasRouteDocument(Long profileId, Long artifactId, String vectorDocumentId,
			EmbeddingModel embeddingModel) {
		Assert.notNull(profileId, "Route profileId cannot be null.");
		Assert.notNull(artifactId, "Route artifactId cannot be null.");
		Assert.hasText(vectorDocumentId, "Route vectorDocumentId cannot be empty.");
		Map<String, Object> metadata = Map.of(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE,
				DocumentMetadataConstant.ROUTE_PROFILE_ID, profileId, DocumentMetadataConstant.ROUTE_ARTIFACT_ID,
				artifactId);
		if (vectorStore instanceof SimpleVectorStore) {
			return findSimpleVectorStoreDocumentIdsByMetadata(metadata, Integer.MAX_VALUE).contains(vectorDocumentId);
		}
		return modelRegistry.withEmbeddingModel(embeddingModel, () -> vectorStore.similaritySearch(SearchRequest.builder()
			.query(DEFAULT)
			.filterExpression(buildRouteFilterExpression(profileId, artifactId))
			.topK(10)
			.similarityThreshold(0D)
			.build()).stream().anyMatch(document -> vectorDocumentId.equals(document.getId())));
	}

	@Override
	public Boolean deleteRouteDocuments(Long profileId, Long artifactId) {
		Assert.notNull(profileId, "Route profileId cannot be null.");
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE);
		metadata.put(DocumentMetadataConstant.ROUTE_PROFILE_ID, profileId);
		if (artifactId != null) {
			metadata.put(DocumentMetadataConstant.ROUTE_ARTIFACT_ID, artifactId);
		}
		Filter.Expression filterExpression = buildRouteFilterExpression(profileId, artifactId);
		mutateVectorStore(() -> {
			if (vectorStore instanceof SimpleVectorStore) {
				deleteSimpleVectorStoreDocumentsByMetadata(metadata);
			}
			else {
				vectorStore.delete(filterExpression);
			}
		});
		return true;
	}

	private Filter.Expression buildRouteFilterExpression(Long profileId, Long artifactId) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();
		conditions.add(builder.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.ROUTE).build());
		conditions.add(builder.eq(DocumentMetadataConstant.ROUTE_PROFILE_ID, profileId).build());
		if (artifactId != null) {
			conditions.add(builder.eq(DocumentMetadataConstant.ROUTE_ARTIFACT_ID, artifactId).build());
		}
		return DynamicFilterService.combineWithAnd(conditions);
	}

	private void validateDocumentBatch(String agentId, List<Document> documents) {
		Assert.notNull(agentId, "AgentId cannot be null.");
		Assert.notEmpty(documents, "Documents cannot be empty.");
		for (Document document : documents) {
			Assert.notNull(document.getMetadata(), "Document metadata cannot be null.");
			String vectorType = (String) document.getMetadata().get(DocumentMetadataConstant.VECTOR_TYPE);
			if (DocumentMetadataConstant.TABLE.equals(vectorType)
					|| DocumentMetadataConstant.COLUMN.equals(vectorType)) {
				Assert.isTrue(document.getMetadata().containsKey(Constant.AGENT_ID),
						"Document metadata must contain agentId for TABLE/COLUMN type.");
				Assert.isTrue(document.getMetadata().get(Constant.AGENT_ID).equals(agentId),
						"Document metadata agentId does not match.");
				Assert.isTrue(document.getMetadata().containsKey(Constant.DATASOURCE_ID),
						"Document metadata must contain datasourceId for TABLE/COLUMN type.");
			}
			else {
				Assert.isTrue(document.getMetadata().containsKey(Constant.AGENT_ID),
						"Document metadata must contain agentId.");
				Assert.isTrue(document.getMetadata().get(Constant.AGENT_ID).equals(agentId),
						"Document metadata agentId does not match.");
			}
		}
	}

	private void validateSkillDocumentBatch(String skillId, List<Document> documents) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.notEmpty(documents, "Documents cannot be empty.");
		for (Document document : documents) {
			Assert.notNull(document.getMetadata(), "Document metadata cannot be null.");
			Assert.isTrue(document.getMetadata().containsKey(Constant.SKILL_ID),
					"Document metadata must contain skillId.");
			Assert.isTrue(String.valueOf(document.getMetadata().get(Constant.SKILL_ID)).equals(skillId),
					"Document metadata skillId does not match.");
			String vectorType = (String) document.getMetadata().get(DocumentMetadataConstant.VECTOR_TYPE);
			if (DocumentMetadataConstant.TABLE.equals(vectorType)
					|| DocumentMetadataConstant.COLUMN.equals(vectorType)) {
				Assert.isTrue(document.getMetadata().containsKey(Constant.DATASOURCE_ID),
						"Document metadata must contain datasourceId for TABLE/COLUMN type.");
			}
		}
	}

	@Override
	public Boolean deleteDocumentsByMetadata(Map<String, Object> metadata) {
		Assert.notNull(metadata, METADATA_REQUIRED);
		String filterExpression = buildFilterExpressionString(metadata);

		mutateVectorStore(() -> {
			if (vectorStore instanceof SimpleVectorStore) {
				deleteSimpleVectorStoreDocumentsByMetadata(metadata);
			}
			else {
				vectorStore.delete(filterExpression);
			}
		});

		return true;
	}

	@Override
	public Boolean deleteSkillDocumentsByMetadata(String skillId, Map<String, Object> metadata) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.notNull(metadata, METADATA_REQUIRED);
		Map<String, Object> scopedMetadata = new HashMap<>(metadata);
		scopedMetadata.put(Constant.SKILL_ID, skillId);
		return deleteDocumentsByMetadata(scopedMetadata);
	}

	@Override
	public Boolean deleteSkillDocumentsByVectorType(String skillId, String vectorType) throws Exception {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.hasText(vectorType, "VectorType cannot be empty.");
		return deleteSkillDocumentsByMetadata(skillId,
				Map.of(DocumentMetadataConstant.VECTOR_TYPE, vectorType));
	}

	@Override
	public Boolean deleteDocumentsByMetedata(String agentId, Map<String, Object> metadata) {
		Assert.hasText(agentId, "AgentId cannot be empty.");
		Assert.notNull(metadata, METADATA_REQUIRED);
		// 添加agentId元数据过滤条件, 用于删除指定agentId下的所有数据，因为metadata中用户调用可能忘记添加agentId
		metadata.put(Constant.AGENT_ID, agentId);
		String filterExpression = buildFilterExpressionString(metadata);

		// es的可以直接元数据删除
		mutateVectorStore(() -> {
			if (vectorStore instanceof SimpleVectorStore) {
				// 目前SimpleVectorStore不支持通过元数据删除，使用会抛出UnsupportedOperationException,现在是通过id删除
				deleteSimpleVectorStoreDocumentsByMetadata(metadata);
			}
			else {
				vectorStore.delete(filterExpression);
			}
		});

		return true;
	}

	private void mutateVectorStore(Runnable action) {
		Assert.notNull(action, "Vector store action cannot be null.");
		if (!(vectorStore instanceof SimpleVectorStore) || simpleVectorStoreInitialization.isEmpty()) {
			action.run();
			return;
		}
		synchronized (vectorStore) {
			action.run();
			simpleVectorStoreInitialization.get().save();
		}
	}

	private void batchDelDocumentsWithFilter(String filterExpression) {
		Set<String> seenDocumentIds = new HashSet<>();
		// 分批获取，因为Milvus等向量数据库的topK有限制
		List<Document> batch;
		int newDocumentsCount;
		int totalDeleted = 0;

		do {
			batch = vectorStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
				.query(DEFAULT)// 使用默认的查询字符串，因为有的嵌入模型不支持空字符串
				.filterExpression(filterExpression)
				.similarityThreshold(0.0)// 设置最低相似度阈值以获取元数据匹配的所有文档
				.topK(dataAgentProperties.getVectorStore().getBatchDelTopkLimit())
				.build());

			// 过滤掉已经处理过的文档，只删除未处理的文档
			List<String> idsToDelete = new ArrayList<>();
			newDocumentsCount = 0;

			for (Document doc : batch) {
				if (seenDocumentIds.add(doc.getId())) {
					// 如果add返回true，表示这是一个新的文档ID
					idsToDelete.add(doc.getId());
					newDocumentsCount++;
				}
			}

			// 删除这批新文档
			if (!idsToDelete.isEmpty()) {
				vectorStore.delete(idsToDelete);
				totalDeleted += idsToDelete.size();
			}

		}
		while (newDocumentsCount > 0); // 只有当获取到新文档时才继续循环

		log.info("Deleted {} documents with filter expression: {}", totalDeleted, filterExpression);
	}

	private void deleteSimpleVectorStoreDocumentsByMetadata(Map<String, Object> metadata) {
		List<String> idsToDelete = findSimpleVectorStoreDocumentIdsByMetadata(metadata, Integer.MAX_VALUE);
		if (idsToDelete.isEmpty()) {
			log.info("Deleted 0 documents with metadata: {}", metadata);
			return;
		}
		vectorStore.delete(idsToDelete);
		log.info("Deleted {} documents with metadata: {}", idsToDelete.size(), metadata);
	}

	@Override
	public List<Document> getDocumentsForAgent(String agentId, String query, String vectorType) {
		// 使用全局默认配置
		int defaultTopK = dataAgentProperties.getVectorStore().getDefaultTopkLimit();
		double defaultThreshold = dataAgentProperties.getVectorStore().getDefaultSimilarityThreshold();

		return getDocumentsForAgent(agentId, query, vectorType, defaultTopK, defaultThreshold);
	}

	@Override
	public List<Document> getDocumentsForAgent(String agentId, String query, String vectorType, int topK,
			double threshold) {
		AgentSearchReq searchRequest = AgentSearchReq.builder()
			.agentId(agentId)
			.docVectorType(vectorType)
			.query(query)
			.topK(topK) // 使用传入的参数
			.similarityThreshold(threshold) // 使用传入的参数
			.build();
		return search(searchRequest);
	}

	@Override
	public List<Document> getDocumentsForSkill(String skillId, String query, String vectorType) {
		return getDocumentsForSkill(skillId, query, vectorType,
				dataAgentProperties.getVectorStore().getDefaultTopkLimit(),
				dataAgentProperties.getVectorStore().getDefaultSimilarityThreshold());
	}

	@Override
	public List<Document> getDocumentsForSkill(String skillId, String query, String vectorType, int topK,
			double threshold) {
		return getDocumentsForSkill(skillId, query, vectorType, topK, threshold, null);
	}

	@Override
	public List<Document> getDocumentsForSkill(String skillId, String query, String vectorType, int topK,
			double threshold, Collection<Long> allowedResourceIds) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.hasText(vectorType, "DocVectorType cannot be empty.");
		Filter.Expression filter = dynamicFilterService.buildSkillDynamicFilter(skillId, vectorType,
				allowedResourceIds);
		if (filter == null) {
			log.warn("Skill {} has no recallable documents for vectorType={}", skillId, vectorType);
			return Collections.emptyList();
		}
		HybridSearchReq request = HybridSearchReq.builder()
			.query(query)
			.topK(topK)
			.similarityThreshold(threshold)
			.filterExpression(filter)
			.build();
		if (dataAgentProperties.getVectorStore().isEnableHybridSearch() && hybridRetrievalStrategy.isPresent()) {
			return hybridRetrievalStrategy.get().retrieve(request);
		}
		return vectorStore.similaritySearch(request.toVectorSearchRequest());
	}

	@Override
	public List<Document> getDocumentsOnlyByFilter(Filter.Expression filterExpression, Integer topK) {
		Assert.notNull(filterExpression, "filterExpression cannot be null.");
		if (topK == null)
			topK = dataAgentProperties.getVectorStore().getDefaultTopkLimit();
		SearchRequest searchRequest = SearchRequest.builder()
			.query(DEFAULT)
			.topK(topK)
			.filterExpression(filterExpression)
			.similarityThreshold(0.0)
			.build();
		return vectorStore.similaritySearch(searchRequest);
	}

	@Override
	public boolean hasDocuments(String agentId) {
		return hasDocumentsByMetadata(agentId, Map.of(Constant.AGENT_ID,
				agentId)); /*
							 * // 类似 MySQL 的 LIMIT 1,只检查是否存在文档 return
							 * hasDocumentsByMetadata(agentId, Map.of(Constant.AGENT_ID,
							 * agentId));
							 */
	}

	@Override
	public boolean hasSkillSchemaDocuments(String skillId, String datasourceId) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.hasText(datasourceId, "DatasourceId cannot be empty.");
		Map<String, Object> tableMetadata = Map.of(Constant.SKILL_ID, skillId, Constant.DATASOURCE_ID, datasourceId,
				DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE);
		Map<String, Object> columnMetadata = Map.of(Constant.SKILL_ID, skillId, Constant.DATASOURCE_ID, datasourceId,
				DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.COLUMN);
		return hasDocumentsByMetadata(skillId, tableMetadata) && hasDocumentsByMetadata(skillId, columnMetadata);
	}

	@Override
	public void deleteSkillSchemaDocuments(String skillId, String datasourceId) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.hasText(datasourceId, "DatasourceId cannot be empty.");
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(Constant.DATASOURCE_ID, datasourceId);
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.COLUMN);
		deleteSkillDocumentsByMetadata(skillId, metadata);
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE);
		deleteSkillDocumentsByMetadata(skillId, metadata);
	}

	@Override
	public Set<String> findSkillDocumentResourceIds(String skillId, String vectorType) {
		Assert.hasText(skillId, SKILL_ID_REQUIRED);
		Assert.hasText(vectorType, "VectorType cannot be empty.");
		String resourceIdKey = DynamicFilterService.resourceIdMetadataKey(vectorType);
		Map<String, Object> metadata = Map.of(Constant.SKILL_ID, skillId, DocumentMetadataConstant.VECTOR_TYPE,
				vectorType);
		if (vectorStore instanceof SimpleVectorStore) {
			Set<String> resourceIds = new LinkedHashSet<>();
			for (SimpleVectorStoreContent content : getSimpleVectorStoreContentMap().values()) {
				if (metadataMatches(content.getMetadata(), metadata)) {
					collectResourceId(content.getMetadata(), resourceIdKey, resourceIds);
				}
			}
			return resourceIds;
		}
		int limit = dataAgentProperties.getVectorStore().getBatchDelTopkLimit();
		List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
			.query(DEFAULT)
			.filterExpression(buildFilterExpressionString(metadata))
			.similarityThreshold(0.0)
			.topK(limit)
			.build());
		if (documents.size() >= limit) {
			// 对账结果会因此偏少，必须说出来，否则「零孤儿」是个假结论
			log.warn("Vector reconciliation hit the topK cap. skillId={}, vectorType={}, limit={}", skillId, vectorType,
					limit);
		}
		Set<String> resourceIds = new LinkedHashSet<>();
		documents.forEach(document -> collectResourceId(document.getMetadata(), resourceIdKey, resourceIds));
		return resourceIds;
	}

	private void collectResourceId(Map<String, Object> metadata, String resourceIdKey, Set<String> target) {
		Object resourceId = metadata == null ? null : metadata.get(resourceIdKey);
		if (resourceId != null) {
			target.add(String.valueOf(resourceId));
		}
	}

	private boolean hasDocumentsByMetadata(String agentId, Map<String, Object> metadata) {
		Assert.hasText(agentId, "AgentId cannot be empty.");
		Assert.notNull(metadata, METADATA_REQUIRED);
		if (vectorStore instanceof SimpleVectorStore) {
			return !findSimpleVectorStoreDocumentIdsByMetadata(metadata, 1).isEmpty();
		}
		List<Document> docs = vectorStore.similaritySearch(org.springframework.ai.vectorstore.SearchRequest.builder()
			.query(DEFAULT)
			.filterExpression(buildFilterExpressionString(metadata))
			.topK(1)
			.similarityThreshold(0.0)
			.build());
		return !docs.isEmpty();
	}

	private List<String> findSimpleVectorStoreDocumentIdsByMetadata(Map<String, Object> metadata, int limit) {
		Map<String, SimpleVectorStoreContent> store = getSimpleVectorStoreContentMap();
		if (store.isEmpty()) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		for (SimpleVectorStoreContent content : store.values()) {
			if (metadataMatches(content.getMetadata(), metadata)) {
				ids.add(content.getId());
				if (ids.size() >= limit) {
					break;
				}
			}
		}
		return ids;
	}

	@SuppressWarnings("unchecked")
	private Map<String, SimpleVectorStoreContent> getSimpleVectorStoreContentMap() {
		try {
			Field storeField = SimpleVectorStore.class.getDeclaredField("store");
			storeField.setAccessible(true);
			Object value = storeField.get(vectorStore);
			if (value instanceof Map<?, ?> map) {
				return (Map<String, SimpleVectorStoreContent>) map;
			}
			return Map.of();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to read SimpleVectorStore content", ex);
		}
	}

	private boolean metadataMatches(Map<String, Object> actualMetadata, Map<String, Object> expectedMetadata) {
		if (actualMetadata == null) {
			return false;
		}
		for (Map.Entry<String, Object> entry : expectedMetadata.entrySet()) {
			Object expected = entry.getValue();
			Object actual = actualMetadata.get(entry.getKey());
			if (expected == null) {
				if (actual != null) {
					return false;
				}
				continue;
			}
			if (actual == null) {
				return false;
			}
			if (!Objects.equals(actual, expected) && !String.valueOf(actual).equals(String.valueOf(expected))) {
				return false;
			}
		}
		return true;
	}

}
