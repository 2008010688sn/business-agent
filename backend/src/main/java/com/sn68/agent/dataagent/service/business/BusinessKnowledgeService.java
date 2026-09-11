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

import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.CreateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.dto.knowledge.businessknowledge.UpdateBusinessKnowledgeDTO;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.vo.BusinessKnowledgeVO;

import java.util.List;

// TODO 添加一个分页查询的方法
/**
 * 业务知识服务契约。
 */
public interface BusinessKnowledgeService {

	/**
	 * 查询业务知识。
	 */
	List<BusinessKnowledgeVO> listKnowledge(Long skillId, String keyword);

	/**
	 * 查询业务知识。
	 */
	List<BusinessKnowledgeVO> getKnowledge(Long skillId);

	/**
	 * 查询业务知识。
	 */
	List<BusinessKnowledgeVO> searchKnowledge(Long skillId, String keyword);

	/**
	 * 查询业务知识。
	 */
	BusinessKnowledgeVO getKnowledgeById(Long id);

	/**
	 * 校验业务知识。
	 */
	BusinessKnowledgeVO requireKnowledgeById(Long id);

	/**
	 * Resolve a business-knowledge record only when it belongs to the specified Skill.
	 */
	BusinessKnowledge requireBySkillId(Long skillId, Long id);

	/**
	 * List recalled business knowledge that belongs to the specified Skill.
	 */
	List<BusinessKnowledge> getRecalledKnowledgeBySkillId(Long skillId);

	/**
	 * List recalled business-knowledge IDs for a Skill resource snapshot.
	 */
	List<Long> getRecalledKnowledgeIds(Long skillId);

	/**
	 * 创建业务知识。
	 */
	BusinessKnowledgeVO addKnowledge(CreateBusinessKnowledgeDTO knowledgeDTO);

	/**
	 * 保存业务知识。
	 */
	BusinessKnowledgeVO updateKnowledge(Long id, UpdateBusinessKnowledgeDTO knowledgeDTO);

	/**
	 * 删除业务知识。
	 */
	void deleteKnowledge(Long id);

	/**
	 * 处理业务知识。
	 */
	void recallKnowledge(Long id, Boolean isRecall);

	/**
	 * 处理业务知识。
	 */
	void refreshAllKnowledgeToVectorStore(String skillId) throws Exception;

	/**
	 * 处理业务知识。
	 */
	void refreshAllKnowledgeToVectorStore(Long skillId);

	/**
	 * 处理业务知识。
	 */
	void retryEmbedding(Long id);

}
