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
package com.sn68.agent.dataagent.constant;

/**
 * 向量库 Document metadata 中存储的 key 常量。
 */
public final class DocumentMetadataConstant {

	private DocumentMetadataConstant() {

	}

	public static final String COLUMN = "column";

	public static final String TABLE = "table";

	public static final String NAME = "name";

	public static final String TABLE_NAME = "tableName";

	public static final String VECTOR_TYPE = "vectorType";

	public static final String SKILL_KNOWLEDGE = "skillKnowledge";

	public static final String DB_SKILL_KNOWLEDGE_ID = "skillKnowledgeId";

	public static final String CONCRETE_SKILL_KNOWLEDGE_TYPE = "skillKnowledgeType";

	public static final String BUSINESS_TERM = "businessTerm";

	public static final String DB_BUSINESS_TERM_ID = "businessTermId";

	public static final String AGENT_MEMORY = "agentMemory";

	public static final String DB_AGENT_MEMORY_ID = "agentMemoryId";

	public static final String AGENT_MEMORY_USER_ID = "agentMemoryUserId";

	public static final String AGENT_MEMORY_TYPE = "agentMemoryType";

	public static final String AGENT_MEMORY_TENANT_ID = "tenantId";

	public static final String AGENT_MEMORY_SUBJECT_TYPE = "agentMemorySubjectType";

	/**
	 * 产生该向量的 embedding 模型指纹。ROUTE 链路先落地了这个 key，其余向量类型复用同一个，
	 * 保证「哪些向量已过期」全库只有一种查法：换成同维度的另一个模型时写入照常成功，但新旧向量
	 * 不在同一语义空间，没有这个字段就无法事后定位受影响的向量。
	 */
	public static final String EMBEDDING_FINGERPRINT = "embeddingFingerprint";

	/**
	 * 产生该向量时的实际维度，用于区分「同维度换模型」与「跨维度脏数据」两类失效。
	 */
	public static final String EMBEDDING_DIMENSION = "embeddingDimension";

	public static final String ROUTE = "ROUTE";

	public static final String ROUTE_TENANT_ID = "tenantId";

	public static final String ROUTE_PROFILE_ID = "profileId";

	public static final String ROUTE_ARTIFACT_ID = "artifactId";

	public static final String ROUTE_TARGET_TYPE = "targetType";

	public static final String ROUTE_TARGET_ID = "targetId";

	public static final String ROUTE_TARGET_VERSION_ID = "targetVersionId";

	public static final String ROUTE_SOURCE_CHECKSUM = "sourceChecksum";

	public static final String ROUTE_EMBEDDING_FINGERPRINT = EMBEDDING_FINGERPRINT;

	public static final String ROUTE_RISK_LEVEL = "riskLevel";

}
