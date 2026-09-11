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
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 语义模型Mapper服务契约。
 */
@Repository
public interface SemanticModelMapper extends SuperMapper<SemanticModel> {

	/**
	 * Query semantic model list by Skill ID
	 */
	default List<SemanticModel> selectBySkillId(Long skillId) {
		return selectList(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getSkillId, skillId)
			.isNull(SemanticModel::getSupersededById)
			.orderByDesc(SemanticModel::getCreateTime));
	}

	/**
	 * Search semantic models by keyword
	 */
	default List<SemanticModel> searchByKeyword(String keyword) {
		return selectList(new LambdaQueryWrapper<SemanticModel>()
			.and(item -> item.like(SemanticModel::getColumnName, keyword)
				.or()
				.like(SemanticModel::getTableName, keyword)
				.or()
				.like(SemanticModel::getBusinessName, keyword)
				.or()
				.like(SemanticModel::getBusinessDescription, keyword)
				.or()
				.like(SemanticModel::getSynonyms, keyword)
				.or()
				.like(SemanticModel::getColumnComment, keyword)
				.or()
				.like(SemanticModel::getDataType, keyword))
			.orderByDesc(SemanticModel::getCreateTime));
	}

	/**
	 * 在 Skill 范围内按关键字搜索有效（未被取代）的语义模型，匹配列名/表名/业务名/描述/同义词/注释/类型。
	 */
	default List<SemanticModel> searchByKeywordAndSkillId(Long skillId, String keyword) {
		return selectList(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getSkillId, skillId)
			.isNull(SemanticModel::getSupersededById)
			.and(item -> item.like(SemanticModel::getColumnName, keyword)
				.or()
				.like(SemanticModel::getTableName, keyword)
				.or()
				.like(SemanticModel::getBusinessName, keyword)
				.or()
				.like(SemanticModel::getBusinessDescription, keyword)
				.or()
				.like(SemanticModel::getSynonyms, keyword)
				.or()
				.like(SemanticModel::getColumnComment, keyword)
				.or()
				.like(SemanticModel::getDataType, keyword))
			.orderByDesc(SemanticModel::getCreateTime));
	}

	/**
	 * Batch enable fields
	 */
	default int enableById(Long id) {
		return update(null, Wraps.<SemanticModel>lbU().eq(SemanticModel::getId, id)
			.set(SemanticModel::getStatus, true)
			.set(SemanticModel::getLastModifyTime, Instant.now()));
	}

	/**
	 * Batch disable fields
	 */
	default int disableById(Long id) {
		return update(null, Wraps.<SemanticModel>lbU().eq(SemanticModel::getId, id)
			.set(SemanticModel::getStatus, false)
			.set(SemanticModel::getLastModifyTime, Instant.now()));
	}

	/**
	 * Query semantic models by Skill ID and enabled status
	 */
	default List<SemanticModel> selectEnabledBySkillId(Long skillId) {
		return selectList(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getSkillId, skillId)
			.isNull(SemanticModel::getSupersededById)
			.eq(SemanticModel::getStatus, true)
			.orderByDesc(SemanticModel::getCreateTime));
	}

	/**
	 * 查询 Skill 在指定数据源下已启用且未被取代的语义模型，按创建时间倒序。
	 */
	default List<SemanticModel> selectEnabledBySkillIdAndDatasourceId(Long skillId, Long datasourceId) {
		return selectList(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getSkillId, skillId)
			.eq(SemanticModel::getDatasourceId, datasourceId)
			.isNull(SemanticModel::getSupersededById)
			.eq(SemanticModel::getStatus, true)
			.orderByDesc(SemanticModel::getCreateTime));
	}

	/**
	 * Query enabled semantic models by Skill ID and table names
	 */
	default List<SemanticModel> selectEnabledBySkillIdAndTableNames(Long skillId, List<String> tableNames) {
		Set<String> normalizedTableNames = normalizeTableNames(tableNames);
		if (normalizedTableNames.isEmpty()) {
			return List.of();
		}
		return selectEnabledBySkillId(skillId).stream()
			.filter(model -> normalizedTableNames.contains(normalizeTableName(model.getTableName())))
			.toList();
	}

	/**
	 * 查询 Skill + 数据源下、表名命中给定集合（大小写不敏感）的已启用语义模型；表名集合为空返回空集。
	 */
	default List<SemanticModel> selectEnabledBySkillIdAndDatasourceIdAndTableNames(Long skillId, Long datasourceId,
			List<String> tableNames) {
		Set<String> normalizedTableNames = normalizeTableNames(tableNames);
		if (normalizedTableNames.isEmpty()) {
			return List.of();
		}
		return selectEnabledBySkillIdAndDatasourceId(skillId, datasourceId).stream()
			.filter(model -> normalizedTableNames.contains(normalizeTableName(model.getTableName())))
			.toList();
	}

	/**
	 * Query semantic model based on skillId, datasourceId, tableName, and columnName
	 */
	default SemanticModel selectBySkillIdAndDatasourceIdAndTableNameAndColumnName(Long skillId, Long datasourceId,
			String tableName, String columnName) {
		return selectOne(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getSkillId, skillId)
			.eq(SemanticModel::getDatasourceId, datasourceId)
			.eq(SemanticModel::getTableName, tableName)
			.eq(SemanticModel::getColumnName, columnName)
			.isNull(SemanticModel::getSupersededById)
			.last(" limit 1"));
	}

	/**
	 * 按数据源 ID 逻辑删除其全部语义模型（数据源删除时的级联清理）。
	 */
	default int deleteByDatasourceId(Long datasourceId) {
		return delete(new LambdaQueryWrapper<SemanticModel>().eq(SemanticModel::getDatasourceId, datasourceId));
	}

	/**
	 * 表名集合归一化（去空白、小写）；null 返回空集。
	 */
	private static Set<String> normalizeTableNames(List<String> tableNames) {
		if (tableNames == null) {
			return Set.of();
		}
		return tableNames.stream().map(SemanticModelMapper::normalizeTableName).collect(Collectors.toSet());
	}

	/**
	 * 单表名归一化：null 转空串、去空白并小写。
	 */
	private static String normalizeTableName(String tableName) {
		return tableName == null ? "" : tableName.trim().toLowerCase(Locale.ROOT);
	}

}
