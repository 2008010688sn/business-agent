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

import com.sn68.agent.dataagent.entity.DatasourceTable;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperMapper;
import com.sn68.agent.framework.db.mybatisplus.wrap.Wraps;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 数据源TableMapper服务契约。
 */
@Repository
public interface DatasourceTableMapper extends SuperMapper<DatasourceTable> {

	/**
	 * 查询数据源全部表元数据，按表名升序；逻辑删除自动过滤。
	 */
	default List<DatasourceTable> selectByDatasourceId(Long datasourceId) {
		return selectList(Wraps.<DatasourceTable>lbQ().eq(DatasourceTable::getDatasourceId, datasourceId)
			.orderByAsc(DatasourceTable::getTableName));
	}

	/**
	 * 查询数据源已启用（enabled=true）的表，按表名升序。
	 */
	default List<DatasourceTable> selectEnabledByDatasourceId(Long datasourceId) {
		return selectList(Wraps.<DatasourceTable>lbQ().eq(DatasourceTable::getDatasourceId, datasourceId)
			.eq(DatasourceTable::getEnabled, true)
			.orderByAsc(DatasourceTable::getTableName));
	}

	/**
	 * 按数据源 + 表名精确定位单条表元数据。
	 */
	default DatasourceTable selectByDatasourceIdAndTableName(Long datasourceId, String tableName) {
		return selectOne(Wraps.<DatasourceTable>lbQ().eq(DatasourceTable::getDatasourceId, datasourceId)
			.eq(DatasourceTable::getTableName, tableName)
			.last(" limit 1"));
	}

	/**
	 * 统计数据源下未删除的表数量。
	 */
	default int countByDatasourceId(Long datasourceId) {
		return Math.toIntExact(
				selectCount(Wraps.<DatasourceTable>lbQ().eq(DatasourceTable::getDatasourceId, datasourceId)));
	}

	/**
	 * 目录同步：逻辑删除不在 tableNames 中的失效表；tableNames 为空时清理数据源下全部表。
	 *
	 * <p>{@code Wraps} 跳过空值，datasourceId 为空会退化成全表删除；唯一调用方
	 * {@code DatasourceServiceImpl#syncDatasourceCatalog} 先经 {@code requireDatasource} 失败关闭。
	 */
	default int deleteMissingTables(Long datasourceId, List<String> tableNames) {
		LbqWrapper<DatasourceTable> wrapper = Wraps.<DatasourceTable>lbQ()
			.eq(DatasourceTable::getDatasourceId, datasourceId);
		if (tableNames != null && !tableNames.isEmpty()) {
			wrapper.notIn(DatasourceTable::getTableName, tableNames);
		}
		return delete(wrapper);
	}

	/**
	 * 目录同步：按主键刷新表注释与启用状态。
	 */
	default int updateCatalogFields(DatasourceTable table) {
		return update(null, Wraps.<DatasourceTable>lbU().eq(DatasourceTable::getId, table.getId())
			.set(DatasourceTable::getTableComment, table.getTableComment())
			.set(DatasourceTable::getEnabled, table.getEnabled())
			.set(DatasourceTable::getLastModifyTime, Instant.now()));
	}

}
