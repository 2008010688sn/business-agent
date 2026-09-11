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
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.sn68.agent.dataagent.entity.SkillDatasourceColumn;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Agent数据源ColumnsMapper服务契约。
 */
@Repository
public interface SkillDatasourceColumnsMapper extends SuperMapper<SkillDatasourceColumn> {

	/**
	 * 查询 Skill 数据源关联下已勾选的列，按表名、列名升序。
	 */
	default List<SkillDatasourceColumn> getSkillDatasourceColumns(Long skillDatasourceId) {
		return selectList(new LambdaQueryWrapper<SkillDatasourceColumn>()
			.eq(SkillDatasourceColumn::getSkillDatasourceId, skillDatasourceId)
			.orderByAsc(SkillDatasourceColumn::getTableName, SkillDatasourceColumn::getColumnName));
	}

	/**
	 * 删除 Skill 数据源关联下全部勾选列（解绑数据源时的级联清理）。
	 */
	default int removeAllColumns(Long skillDatasourceId) {
		return delete(new LambdaQueryWrapper<SkillDatasourceColumn>()
			.eq(SkillDatasourceColumn::getSkillDatasourceId, skillDatasourceId));
	}

	/**
	 * 删除不属于 tables 中任何表的勾选列（表勾选变更后的级联清理）；tables 为空时清理全部勾选列。
	 */
	default int removeColumnsOutsideTables(Long skillDatasourceId, List<String> tables) {
		LambdaQueryWrapper<SkillDatasourceColumn> wrapper = new LambdaQueryWrapper<SkillDatasourceColumn>()
			.eq(SkillDatasourceColumn::getSkillDatasourceId, skillDatasourceId);
		if (tables != null && !tables.isEmpty()) {
			wrapper.notIn(SkillDatasourceColumn::getTableName, tables);
		}
		return delete(wrapper);
	}

	/**
	 * 批量保存勾选列；rows 为空时不执行，返回实际保存条数。
	 */
	default int insertColumns(List<SkillDatasourceColumn> rows) {
		if (rows == null || rows.isEmpty()) {
			return 0;
		}
		Db.saveBatch(rows);
		return rows.size();
	}

}
