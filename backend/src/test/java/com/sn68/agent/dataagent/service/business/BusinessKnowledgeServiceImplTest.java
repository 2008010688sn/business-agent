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
package com.sn68.agent.dataagent.service.business;

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.converter.BusinessKnowledgeConverter;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import com.sn68.agent.dataagent.service.skill.PublishedSkillResourceReferenceService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessKnowledgeServiceImplTest {

	@Test
	void retryEmbedding_usesStringKnowledgeIdMetadataForSnowflakeIds() {
		BusinessKnowledgeMapper businessKnowledgeMapper = mock(BusinessKnowledgeMapper.class);
		AgentVectorStoreService vectorStoreService = mock(AgentVectorStoreService.class);
		BusinessKnowledgeServiceImpl service = new BusinessKnowledgeServiceImpl(businessKnowledgeMapper,
				vectorStoreService, new BusinessKnowledgeConverter(), mock(PublishedSkillResourceReferenceService.class),
				directTransactionTemplate());
		BusinessKnowledge knowledge = new BusinessKnowledge();
		knowledge.setId(2064168410008010753L);
		knowledge.setSkillId(2065262710003384321L);
		knowledge.setBusinessTerm("product metric");
		knowledge.setIsRecall(true);
		knowledge.setEmbeddingStatus(EmbeddingStatus.FAILED);
		when(businessKnowledgeMapper.selectById(2064168410008010753L)).thenReturn(knowledge);

		service.retryEmbedding(2064168410008010753L);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass(Map.class);
		verify(vectorStoreService).deleteSkillDocumentsByMetadata(eq("2065262710003384321"),
				metadataCaptor.capture());
		verify(vectorStoreService).addSkillDocuments(eq("2065262710003384321"), anyList());
		assertEquals("2064168410008010753",
				metadataCaptor.getValue().get(DocumentMetadataConstant.DB_BUSINESS_TERM_ID));
		assertNull(metadataCaptor.getValue().get(Constant.AGENT_ID));
	}

	@SuppressWarnings("unchecked")
	private TransactionTemplate directTransactionTemplate() {
		TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			invocation.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
			return null;
		}).when(transactionTemplate).executeWithoutResult(any());
		return transactionTemplate;
	}

}
