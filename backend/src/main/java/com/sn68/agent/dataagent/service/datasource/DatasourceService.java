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
package com.sn68.agent.dataagent.service.datasource;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCatalogResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCreateReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceModifyReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceTypeResp;
import com.sn68.agent.dataagent.dto.schema.CreateLogicalRelationReq;
import com.sn68.agent.dataagent.dto.schema.UpdateLogicalRelationReq;
import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.entity.DatasourceTable;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import java.util.List;

/**
 * 数据源服务契约。
 */
public interface DatasourceService {

	/**
	 * 查询数据源。
	 */
	List<DatasourceTypeResp> getDatasourceTypes();

	/**
	 * 查询数据源清单（对外出参，已脱敏）。
	 */
	List<DatasourceResp> listDatasource(String status, String type);

	/**
	 * 查询数据源详情（对外出参，已脱敏）。
	 */
	DatasourceResp getDatasourceDetail(Long id);

	/**
	 * Get all data source list
	 */
	List<Datasource> getAllDatasource();

	List<Datasource> getAllDatasourceForTenant(String tenantId);

	/**
	 * Get data source list by status
	 */
	List<Datasource> getDatasourceByStatus(String status);

	/**
	 * Get data source list by type
	 */
	List<Datasource> getDatasourceByType(String type);

	/**
	 * Get data source details by ID
	 */
	Datasource getDatasourceById(Long id);

	/**
	 * 校验数据源。
	 */
	Datasource requireDatasource(Long id);

	Datasource requireDatasourceForTenant(Long id, String tenantId);

	/**
	 * 创建数据源。连接串由服务端按 host/port/databaseName 组装，不接受调用方传入（PRD S-2）。
	 */
	DatasourceResp createDatasource(DatasourceCreateReq request);

	/**
	 * 修改数据源。连接串每次保存都重新生成；username/password 留空或为掩码串时沿用库内已有凭据。
	 */
	DatasourceResp updateDatasource(Long id, DatasourceModifyReq request);

	/**
	 * Delete data source
	 */
	void deleteDatasource(Long id);

	/**
	 * Update data source test status
	 */
	void updateTestStatus(Long id, String testStatus);

	/**
	 * Test data source connection
	 */
	boolean testConnection(Long id);

	boolean testConnectionForTenant(Long id, String tenantId);

	/**
	 * 查询数据源。
	 */
	List<String> getDatasourceTables(Long datasourceId) throws Exception;

	List<String> getDatasourceTablesForTenant(Long datasourceId, String tenantId) throws Exception;

	/**
	 * 获取数据源表的字段列表
	 */
	List<String> getTableColumns(Long datasourceId, String tableName) throws Exception;

	List<String> getTableColumnsForTenant(Long datasourceId, String tenantId, String tableName) throws Exception;

	/**
	 * 处理数据源。
	 */
	DatasourceCatalogResp syncDatasourceCatalog(Long datasourceId) throws Exception;

	/**
	 * 查询数据源。
	 */
	DatasourceCatalogResp getDatasourceCatalog(Long datasourceId);

	/**
	 * 查询数据源。
	 */
	List<DatasourceTable> getCatalogTables(Long datasourceId);

	/**
	 * 查询数据源。
	 */
	List<DatasourceColumn> getCatalogColumns(Long datasourceId, String tableName);

	/**
	 * 查询数据源。
	 */
	List<String> getCatalogTableNames(Long datasourceId);

	/**
	 * 查询数据源。
	 */
	List<String> getCatalogColumnNames(Long datasourceId, String tableName);

	/**
	 * 校验数据源。
	 */
	boolean hasDatasourceCatalog(Long datasourceId);

	/**
	 * 查询数据源。
	 */
	List<DatasourcePermissionRule> getPermissionRules(Long datasourceId);

	/**
	 * 按租户查询数据源权限规则（任务/工具线程用快照租户，避免依赖现场登录）。
	 */
	List<DatasourcePermissionRule> getPermissionRulesForTenant(Long datasourceId, String tenantId);

	/**
	 * 保存数据源。
	 */
	List<DatasourcePermissionRule> savePermissionRules(Long datasourceId, List<DatasourcePermissionRule> rules);

	/**
	 * 处理数据源。
	 */
	List<DatasourcePermissionRule> inferPermissionRules(Long datasourceId);

	/**
	 * 处理数据源。
	 */
	List<DatasourcePermissionRule> inferPermissionRules(Long datasourceId, String tableName);

	/**
	 * 查询数据源。
	 */
	DbConfigBO getDbConfig(Datasource datasource);

	/**
	 * 获取数据源的逻辑外键列表
	 */
	List<LogicalRelation> getLogicalRelations(Long datasourceId);

	List<LogicalRelation> getLogicalRelationsForTenant(Long datasourceId, String tenantId);

	/**
	 * 添加逻辑外键
	 */
	LogicalRelation addLogicalRelation(Long datasourceId, LogicalRelation logicalRelation);

	/**
	 * 创建数据源。
	 */
	LogicalRelation addLogicalRelation(Long datasourceId, CreateLogicalRelationReq dto);

	/**
	 * 更新逻辑外键
	 */
	LogicalRelation updateLogicalRelation(Long datasourceId, Long relationId, LogicalRelation logicalRelation);

	/**
	 * 保存数据源。
	 */
	LogicalRelation updateLogicalRelation(Long datasourceId, Long relationId, UpdateLogicalRelationReq dto);

	/**
	 * 保存数据源。
	 */
	LogicalRelation updateLogicalRelation(Long datasourceId, UpdateLogicalRelationReq dto);

	/**
	 * 删除逻辑外键
	 */
	void deleteLogicalRelation(Long datasourceId, Long logicalRelationId);

	/**
	 * 批量保存逻辑外键（替换现有的所有外键）
	 */
	List<LogicalRelation> saveLogicalRelations(Long datasourceId, List<LogicalRelation> logicalRelations);

	List<LogicalRelation> saveLogicalRelationsForTenant(Long datasourceId, String tenantId,
			List<LogicalRelation> logicalRelations);

}
