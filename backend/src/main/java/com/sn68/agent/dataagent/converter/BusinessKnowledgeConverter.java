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
package com.sn68.agent.dataagent.converter;

import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.CreateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.enums.EmbeddingStatus;
import com.sn68.agent.dataagent.vo.BusinessKnowledgeVO;
import org.springframework.stereotype.Component;

/**
 * 业务知识 Entity 与 VO/DTO 转换器。
 */
@Component
public class BusinessKnowledgeConverter {

	/**
	 * Entity 转列表/详情 VO（embeddingStatus 输出枚举 value 字符串）。
	 */
	public BusinessKnowledgeVO toVo(BusinessKnowledge po) {
		return BusinessKnowledgeVO.builder()
			.id(po.getId())
			.businessTerm(po.getBusinessTerm())
			.description(po.getDescription())
			.synonyms(po.getSynonyms())
			.isRecall(Boolean.TRUE.equals(po.getIsRecall()))
			.skillId(po.getSkillId())
			.createdTime(po.getCreateTime())
			.updatedTime(po.getLastModifyTime())
			.embeddingStatus(po.getEmbeddingStatus() != null ? po.getEmbeddingStatus().getValue() : null)
			.errorMsg(po.getErrorMsg())
			.build();
	}

	/**
	 * 新增请求转 Entity：初始向量化状态置 PROCESSING。
	 */
	public BusinessKnowledge toEntityForCreate(CreateBusinessKnowledgeDTO dto) {
		return BusinessKnowledge.builder()
			.businessTerm(dto.getBusinessTerm())
			.description(dto.getDescription())
			.synonyms(dto.getSynonyms())
			.skillId(dto.getSkillId())
			.isRecall(Boolean.TRUE.equals(dto.getIsRecall()))
			.deleted(false)
			.embeddingStatus(EmbeddingStatus.PROCESSING)
			.build();

	}

}
