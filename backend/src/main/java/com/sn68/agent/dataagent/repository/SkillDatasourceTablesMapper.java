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
import com.sn68.agent.dataagent.entity.SkillDatasourceTable;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;

/**
 * Agent数据源TablesMapper服务契约。
 */
@Repository
public interface SkillDatasourceTablesMapper extends SuperMapper<SkillDatasourceTable> {

	/**
	 * 查询 Skill 数据源关联下已勾选的表名清单，按表名升序。
	 */
	default List<String> getSkillDatasourceTables(Long skillDatasourceId) {
		return selectList(new LambdaQueryWrapper<SkillDatasourceTable>()
			.eq(SkillDatasourceTable::getSkillDatasourceId, skillDatasourceId)
			.orderByAsc(SkillDatasourceTable::getTableName))
			.stream()
			.map(SkillDatasourceTable::getTableName)
			.toList();
	}

	/**
	 * 删除不在 tables 中的失效勾选表；tables 为空时删除该关联下全部勾选表。
	 */
	default int removeExpireTables(Long skillDatasourceId, List<String> tables) {
		LambdaQueryWrapper<SkillDatasourceTable> wrapper = new LambdaQueryWrapper<SkillDatasourceTable>()
			.eq(SkillDatasourceTable::getSkillDatasourceId, skillDatasourceId);
		if (tables != null && !tables.isEmpty()) {
			wrapper.notIn(SkillDatasourceTable::getTableName, tables);
		}
		return delete(wrapper);
	}

	/**
	 * 删除 Skill 数据源关联下全部勾选表（解绑数据源时的级联清理）。
	 */
	default int removeAllTables(Long skillDatasourceId) {
		return delete(new LambdaQueryWrapper<SkillDatasourceTable>()
			.eq(SkillDatasourceTable::getSkillDatasourceId, skillDatasourceId));
	}

	/**
	 * 增量插入新勾选表：与既有清单差集后批量保存，返回实际新增条数。
	 */
	default int insertNewTables(Long skillDatasourceId, List<String> tables) {
		if (tables == null || tables.isEmpty()) {
			return 0;
		}
		Set<String> existingTables = Set.copyOf(getSkillDatasourceTables(skillDatasourceId));
		List<SkillDatasourceTable> rows = tables.stream()
			.filter(tableName -> !existingTables.contains(tableName))
			.map(tableName -> {
				SkillDatasourceTable row = new SkillDatasourceTable();
				row.setSkillDatasourceId(skillDatasourceId);
				row.setTableName(tableName);
				return row;
			})
			.toList();
		if (rows.isEmpty()) {
			return 0;
		}
		Db.saveBatch(rows);
		return rows.size();
	}

	/**
	 * 全量刷新勾选表清单：先删失效再增新表；tables 为空直接抛异常（禁止清空式误操作）。
	 */
	default int updateSkillDatasourceTables(Long skillDatasourceId, List<String> tables) {
		if (tables.isEmpty()) {
			throw new IllegalArgumentException("tables cannot be empty");
		}
		int deleteCount = removeExpireTables(skillDatasourceId, tables);
		int insertCount = insertNewTables(skillDatasourceId, tables);
		return deleteCount + insertCount;
	}

}
