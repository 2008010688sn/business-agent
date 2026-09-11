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
package com.sn68.agent.dataagent.service.knowledge;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;

import java.util.List;
import org.springframework.lang.Nullable;

/**
 * Domain知识Search服务契约。
 */
public interface DomainKnowledgeSearchService {

	/**
	 * Searches business knowledge frozen in one routed Skill version. Agent knowledge is
	 * deliberately excluded from this entry point.
	 */
	DomainKnowledgeSearchResult searchSkillBusinessKnowledge(Long skillId, List<Long> allowedKnowledgeIds,
			DomainKnowledgeSearchRequest request, @Nullable AgentRequest agentRequest);

	/** Searches only the Skill-owned document/QA/FAQ resources frozen in a QA version. */
	DomainKnowledgeSearchResult searchSkillKnowledge(Long skillId, List<Long> allowedKnowledgeIds,
			DomainKnowledgeSearchRequest request, @Nullable AgentRequest agentRequest);

	record DomainKnowledgeSearchRequest(String query, List<String> knowledgeTypes, Integer topK,
			Double similarityThreshold) {
	}

	record DomainKnowledgeSearchResult(List<KnowledgeHit> hits, List<String> warnings, String resolution) {
	}

	record KnowledgeHit(String vectorType, String knowledgeId, String title, String summary, String snippet,
			String source, String concreteType) {
	}

}
