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

import com.sn68.agent.dataagent.dto.search.AgentSearchReq;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;

/**
 * AgentVectorStore服务契约。
 */
public interface AgentVectorStoreService {

	/**
	 * 查询某个Agent的文档 总入口
	 */
	List<Document> search(AgentSearchReq searchRequest);

	/**
	 * Search resources that belong to one Skill rather than Agent-scoped knowledge.
	 */
	List<Document> getDocumentsForSkill(String skillId, String query, String vectorType);

	/**
	 * Search Skill resources with an explicit retrieval policy.
	 */
	List<Document> getDocumentsForSkill(String skillId, String query, String vectorType, int topK, double threshold);

	/**
	 * Search Skill resources restricted to the caller's live row whitelist.
	 *
	 * <p>调用方传入的是数据库侧算出的「存活且允许召回」主键集合，会被下推为向量过滤条件。这样即使向量
	 * 清理失败留下孤儿向量，已删除内容也不会被检索到，也不会挤占 topK 名额。
	 * @param allowedResourceIds 允许命中的业务主键；{@code null} 表示不做行级约束
	 */
	List<Document> getDocumentsForSkill(String skillId, String query, String vectorType, int topK, double threshold,
			Collection<Long> allowedResourceIds);

	/**
	 * List the distinct business ids that the vector store currently holds for one Skill
	 * and vector type. Used to reconcile the store against live database rows.
	 */
	Set<String> findSkillDocumentResourceIds(String skillId, String vectorType);

	/**
	 * 删除AgentVectorStore。
	 */
	Boolean deleteDocumentsByVectorType(String agentId, String vectorType) throws Exception;

	/**
	 * 删除AgentVectorStore。
	 */
	Boolean deleteDocumentsByMetedata(String agentId, Map<String, Object> metadata);

	/**
	 * 删除AgentVectorStore。
	 */
	Boolean deleteDocumentsByMetadata(Map<String, Object> metadata);

	/**
	 * Delete documents scoped to one Skill.
	 */
	Boolean deleteSkillDocumentsByMetadata(String skillId, Map<String, Object> metadata);

	/**
	 * Delete one kind of document scoped to one Skill.
	 */
	Boolean deleteSkillDocumentsByVectorType(String skillId, String vectorType) throws Exception;

	/**
	 * Get documents for specified agent
	 */
	List<Document> getDocumentsForAgent(String agentId, String query, String vectorType);

	/**
	 * 查询AgentVectorStore。
	 */
	List<Document> getDocumentsForAgent(String agentId, String query, String vectorType, int topK, double threshold);

	/**
	 * 仅按元数据过滤精确查找文档，不做向量相似度检索。
	 */
	List<Document> getDocumentsOnlyByFilter(Filter.Expression filterExpression, Integer topK);

	/**
	 * 校验AgentVectorStore。
	 */
	boolean hasDocuments(String agentId);

	/**
	 * Check whether a Skill has its schema documents for a datasource.
	 */
	boolean hasSkillSchemaDocuments(String skillId, String datasourceId);

	/**
	 * Delete schema documents scoped to one Skill.
	 */
	void deleteSkillSchemaDocuments(String skillId, String datasourceId);

	/**
	 * 创建AgentVectorStore。
	 */
	void addDocuments(String agentId, List<Document> documents);

	/**
	 * Add documents scoped to one Skill.
	 */
	void addSkillDocuments(String skillId, List<Document> documents);

	/**
	 * 创建AgentVectorStore。
	 */
	void addDocumentBatches(String agentId, List<List<Document>> documentBatches);

	/**
	 * Add document batches scoped to one Skill.
	 */
	void addSkillDocumentBatches(String skillId, List<List<Document>> documentBatches);

	/**
	 * Add one route document after validating its immutable routing metadata.
	 */
	void addRouteDocument(Document document, EmbeddingModel embeddingModel);

	/**
	 * Search only inside the caller-provided eligible route Artifact whitelist.
	 */
	List<Document> searchRouteDocuments(String tenantId, Long profileId, String embeddingFingerprint,
			Collection<Long> eligibleArtifactIds, String query, int topK, double threshold,
			EmbeddingModel embeddingModel);

	boolean hasRouteDocument(Long profileId, Long artifactId, String vectorDocumentId,
			EmbeddingModel embeddingModel);

	Boolean deleteRouteDocuments(Long profileId, Long artifactId);

}
