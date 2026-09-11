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
package com.sn68.agent.dataagent.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 业务知识Mapper服务契约。
 */
@Repository
public interface BusinessKnowledgeMapper extends SuperMapper<BusinessKnowledge> {

	/**
	 * Query business knowledge list by agent ID
	 */
	default List<BusinessKnowledge> selectBySkillId(Long skillId) {
		return selectList(new LambdaQueryWrapper<BusinessKnowledge>().eq(BusinessKnowledge::getSkillId, skillId)
			.isNull(BusinessKnowledge::getSupersededById)
			.orderByDesc(BusinessKnowledge::getCreateTime));
	}

	/**
	 * Search in a specific agent scope by keyword
	 */
	default List<BusinessKnowledge> searchInSkill(Long skillId, String keyword) {
		return selectList(new LambdaQueryWrapper<BusinessKnowledge>().eq(BusinessKnowledge::getSkillId, skillId)
			.isNull(BusinessKnowledge::getSupersededById)
			.and(item -> item.like(BusinessKnowledge::getBusinessTerm, keyword)
				.or()
				.like(BusinessKnowledge::getDescription, keyword)
				.or()
				.like(BusinessKnowledge::getSynonyms, keyword))
			.orderByDesc(BusinessKnowledge::getCreateTime));
	}

	/**
	 * 查询 Skill 下开启召回（isRecall=true）且未被取代的业务知识 ID 集合，供向量检索过滤使用。
	 */
	default List<Long> selectRecalledKnowledgeIds(Long skillId) {
		return selectList(new LambdaQueryWrapper<BusinessKnowledge>().select(BusinessKnowledge::getId)
			.eq(BusinessKnowledge::getSkillId, skillId)
			.isNull(BusinessKnowledge::getSupersededById)
			.eq(BusinessKnowledge::getIsRecall, true))
			.stream()
			.map(BusinessKnowledge::getId)
			.filter(Objects::nonNull)
			.toList();
	}

	/**
	 * 显式设置逻辑删除标记（isDeleted=true 删除、false 恢复），并刷新修改时间。
	 */
	default int logicalDelete(Long id, Boolean isDeleted) {
		return update(null, Wraps.<BusinessKnowledge>lbU().eq(BusinessKnowledge::getId, id)
			.set(BusinessKnowledge::getDeleted, isDeleted)
			.set(BusinessKnowledge::getLastModifyTime, Instant.now()));
	}

}
