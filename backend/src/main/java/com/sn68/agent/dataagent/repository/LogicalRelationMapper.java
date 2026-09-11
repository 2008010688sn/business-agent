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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * LogicalRelationMapper服务契约。
 */
@Repository
public interface LogicalRelationMapper extends SuperMapper<LogicalRelation> {

	/**
	 * 按主键查询逻辑外键关系并校验数据源归属，防止跨数据源操作。
	 */
	default LogicalRelation selectByIdAndDatasourceId(Long id, Long datasourceId) {
		return selectOne(new LambdaQueryWrapper<LogicalRelation>().eq(LogicalRelation::getId, id)
			.eq(LogicalRelation::getDatasourceId, datasourceId)
			.last(" limit 1"));
	}

	/**
	 * 查询数据源下全部逻辑外键关系，按创建时间倒序。
	 */
	default List<LogicalRelation> selectByDatasourceId(Long datasourceId) {
		return selectList(new LambdaQueryWrapper<LogicalRelation>().eq(LogicalRelation::getDatasourceId, datasourceId)
			.orderByDesc(LogicalRelation::getCreateTime));
	}

	/**
	 * 按主键 + 数据源归属做非空字段的局部更新（null 字段保持原值）。
	 */
	default int updateByIdAndDatasourceId(Long datasourceId, LogicalRelation logicalRelation) {
		LambdaUpdateWrapper<LogicalRelation> wrapper = Wraps.<LogicalRelation>lbU()
			.eq(LogicalRelation::getId, logicalRelation.getId())
			.eq(LogicalRelation::getDatasourceId, datasourceId);
		if (logicalRelation.getSourceTableName() != null) {
			wrapper.set(LogicalRelation::getSourceTableName, logicalRelation.getSourceTableName());
		}
		if (logicalRelation.getSourceColumnName() != null) {
			wrapper.set(LogicalRelation::getSourceColumnName, logicalRelation.getSourceColumnName());
		}
		if (logicalRelation.getTargetTableName() != null) {
			wrapper.set(LogicalRelation::getTargetTableName, logicalRelation.getTargetTableName());
		}
		if (logicalRelation.getTargetColumnName() != null) {
			wrapper.set(LogicalRelation::getTargetColumnName, logicalRelation.getTargetColumnName());
		}
		if (logicalRelation.getRelationType() != null) {
			wrapper.set(LogicalRelation::getRelationType, logicalRelation.getRelationType());
		}
		if (logicalRelation.getDescription() != null) {
			wrapper.set(LogicalRelation::getDescription, logicalRelation.getDescription());
		}
		wrapper.set(LogicalRelation::getLastModifyTime, Instant.now());
		return update(null, wrapper);
	}

	/**
	 * 按主键逻辑删除逻辑外键关系（显式置 deleted=true），同时校验数据源归属。
	 */
	default int deleteByIdAndDatasourceId(Long id, Long datasourceId) {
		return update(null, Wraps.<LogicalRelation>lbU().eq(LogicalRelation::getId, id)
			.eq(LogicalRelation::getDatasourceId, datasourceId)
			.set(LogicalRelation::getDeleted, true)
			.set(LogicalRelation::getLastModifyTime, Instant.now()));
	}

	/**
	 * 按数据源 ID 逻辑删除其全部逻辑外键关系（数据源删除时的级联清理）。
	 */
	default int deleteByDatasourceId(Long datasourceId) {
		return update(null, Wraps.<LogicalRelation>lbU().eq(LogicalRelation::getDatasourceId, datasourceId)
			.set(LogicalRelation::getDeleted, true)
			.set(LogicalRelation::getLastModifyTime, Instant.now()));
	}

	/**
	 * 统计同一数据源下完全相同（源表.源列 → 目标表.目标列）的关系条数，用于新增判重。
	 */
	default int checkExists(Long datasourceId, String sourceTableName, String sourceColumnName,
			String targetTableName, String targetColumnName) {
		return Math.toIntExact(selectCount(new LambdaQueryWrapper<LogicalRelation>()
			.eq(LogicalRelation::getDatasourceId, datasourceId)
			.eq(LogicalRelation::getSourceTableName, sourceTableName)
			.eq(LogicalRelation::getSourceColumnName, sourceColumnName)
			.eq(LogicalRelation::getTargetTableName, targetTableName)
			.eq(LogicalRelation::getTargetColumnName, targetColumnName)));
	}

	/**
	 * 统计排除自身（excludeId）后相同映射的关系条数，用于修改判重。
	 */
	default int checkExistsExcludingId(Long datasourceId, String sourceTableName, String sourceColumnName,
			String targetTableName, String targetColumnName, Long excludeId) {
		return Math.toIntExact(selectCount(new LambdaQueryWrapper<LogicalRelation>()
			.eq(LogicalRelation::getDatasourceId, datasourceId)
			.eq(LogicalRelation::getSourceTableName, sourceTableName)
			.eq(LogicalRelation::getSourceColumnName, sourceColumnName)
			.eq(LogicalRelation::getTargetTableName, targetTableName)
			.eq(LogicalRelation::getTargetColumnName, targetColumnName)
			.ne(LogicalRelation::getId, excludeId)));
	}

}
