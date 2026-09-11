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
import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.QueryWrap;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 数据源ColumnMapper服务契约。
 */
@Repository
public interface DatasourceColumnMapper extends SuperMapper<DatasourceColumn> {

	/**
	 * 查询数据源全部列元数据，按表名、列名升序；逻辑删除自动过滤。
	 */
	default List<DatasourceColumn> selectByDatasourceId(Long datasourceId) {
		return selectList(Wraps.<DatasourceColumn>lbQ().eq(DatasourceColumn::getDatasourceId, datasourceId)
			.orderByAsc(DatasourceColumn::getTableName, DatasourceColumn::getColumnName));
	}

	/**
	 * 查询数据源指定表的全部列元数据，按列名升序。
	 */
	default List<DatasourceColumn> selectByDatasourceIdAndTableName(Long datasourceId, String tableName) {
		return selectList(new LambdaQueryWrapper<DatasourceColumn>().eq(DatasourceColumn::getDatasourceId, datasourceId)
			.eq(DatasourceColumn::getTableName, tableName)
			.orderByAsc(DatasourceColumn::getColumnName));
	}

	/**
	 * 查询数据源全库范围的数据权限候选列（不限定表名）。
	 */
	default List<DatasourceColumn> selectPermissionCandidateColumns(Long datasourceId) {
		return selectPermissionCandidateColumns(datasourceId, null);
	}

	/**
	 * 查询数据权限候选列：启用列中列名命中 company/site/project/investor/user 模糊或 create_by/createby
	 * 精确（小写比较）的列，tableName 非空时限定单表；候选词面向权限规则配置页的推荐。
	 */
	default List<DatasourceColumn> selectPermissionCandidateColumns(Long datasourceId, String tableName) {
		QueryWrap<DatasourceColumn> queryWrapper = Wraps.<DatasourceColumn>q().eq("datasource_id", datasourceId)
			.eq("enabled", true)
			.and(wrapper -> wrapper.apply("lower(column_name) like {0}", "%company%")
				.or()
				.apply("lower(column_name) like {0}", "%site%")
				.or()
				.apply("lower(column_name) like {0}", "%project%")
				.or()
				.apply("lower(column_name) like {0}", "%investor%")
				.or()
				.apply("lower(column_name) like {0}", "%user%")
				.or()
				.apply("lower(column_name) = {0}", "create_by")
				.or()
				.apply("lower(column_name) = {0}", "createby"));
		if (tableName != null && !tableName.isBlank()) {
			queryWrapper.eq("table_name", tableName);
		}
		queryWrapper.orderByAsc("table_name", "column_name");
		return selectList(queryWrapper);
	}

	/**
	 * 查询数据源指定表已启用（enabled=true）的列，按列名升序。
	 */
	default List<DatasourceColumn> selectEnabledByDatasourceIdAndTableName(Long datasourceId, String tableName) {
		return selectList(new LambdaQueryWrapper<DatasourceColumn>().eq(DatasourceColumn::getDatasourceId, datasourceId)
			.eq(DatasourceColumn::getTableName, tableName)
			.eq(DatasourceColumn::getEnabled, true)
			.orderByAsc(DatasourceColumn::getColumnName));
	}

	/**
	 * 按数据源 + 表名 + 列名精确定位单条列元数据。
	 */
	default DatasourceColumn selectByDatasourceTableAndColumn(Long datasourceId, String tableName, String columnName) {
		return selectOne(Wraps.<DatasourceColumn>lbQ().eq(DatasourceColumn::getDatasourceId, datasourceId)
			.eq(DatasourceColumn::getTableName, tableName)
			.eq(DatasourceColumn::getColumnName, columnName)
			.last(" limit 1"));
	}

	/**
	 * 目录同步：逻辑删除表内不在 columnNames 中的失效列。
	 *
	 * <p>{@code Wraps} 跳过空值，datasourceId / tableName 为空会退化成越界删除；唯一调用方
	 * {@code DatasourceServiceImpl#syncDatasourceCatalog} 先经 {@code requireDatasource} 失败关闭，
	 * tableName 取自已过滤空白的同步表名集合。
	 */
	default int deleteMissingColumns(Long datasourceId, String tableName, List<String> columnNames) {
		LbqWrapper<DatasourceColumn> wrapper = Wraps.<DatasourceColumn>lbQ()
			.eq(DatasourceColumn::getDatasourceId, datasourceId)
			.eq(DatasourceColumn::getTableName, tableName);
		if (columnNames != null && !columnNames.isEmpty()) {
			wrapper.notIn(DatasourceColumn::getColumnName, columnNames);
		}
		return delete(wrapper);
	}

	/**
	 * 目录同步：逻辑删除不属于 tableNames 中任何表的列（表被移除后的级联清理）；
	 * tableNames 为空时清理数据源下全部列。
	 */
	default int deleteColumnsOutsideTables(Long datasourceId, List<String> tableNames) {
		LbqWrapper<DatasourceColumn> wrapper = Wraps.<DatasourceColumn>lbQ()
			.eq(DatasourceColumn::getDatasourceId, datasourceId);
		if (tableNames != null && !tableNames.isEmpty()) {
			wrapper.notIn(DatasourceColumn::getTableName, tableNames);
		}
		return delete(wrapper);
	}

	/**
	 * 目录同步：按主键刷新列的类型/注释/样例值/启用状态等目录字段。
	 */
	default int updateCatalogFields(DatasourceColumn column) {
		return update(null, Wraps.<DatasourceColumn>lbU().eq(DatasourceColumn::getId, column.getId())
			.set(DatasourceColumn::getColumnType, column.getColumnType())
			.set(DatasourceColumn::getColumnComment, column.getColumnComment())
			.set(DatasourceColumn::getSampleValues, column.getSampleValues())
			.set(DatasourceColumn::getEnabled, column.getEnabled())
			.set(DatasourceColumn::getLastModifyTime, Instant.now()));
	}

}
