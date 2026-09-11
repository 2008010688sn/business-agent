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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.dto.datasource.DatasourceTypeResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCatalogResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCreateReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceModifyReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceQueryReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceTableQueryReq;
import com.sn68.agent.dataagent.dto.schema.CreateLogicalRelationReq;
import com.sn68.agent.dataagent.dto.schema.LogicalRelationDeleteReq;
import com.sn68.agent.dataagent.dto.schema.UpdateLogicalRelationReq;
import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.entity.DatasourceTable;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 维护数据源连接、目录、权限规则和逻辑关系。
 */
@RestController
@RequestMapping("/datasource")
@AllArgsConstructor
@Tag(name = "数据源", description = "维护数据源连接、目录、权限规则和逻辑关系")
public class DatasourceController {

	private final DatasourceService datasourceService;

	@Operation(summary = "查询数据源类型清单", description = "查询平台支持的数据源类型（MySQL / PostgreSQL 等），不返回任何数据源实例。")
	@GetMapping("/types")
	public List<DatasourceTypeResp> getDatasourceTypes() {
		return datasourceService.getDatasourceTypes();
	}

	@Operation(summary = "查询数据源清单", description = "查询数据源清单，用于数据源相关管理和运行场景。")
	@PostMapping("/query")
	public List<DatasourceResp> getAllDatasource(@RequestBody(required = false) DatasourceQueryReq request) {
		return datasourceService.listDatasource(request == null ? null : request.status(),
				request == null ? null : request.type());
	}

	@Operation(summary = "查询数据源详情", description = "查询数据源详情，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/detail")
	public DatasourceResp getDatasourceById(@PathVariable Long id) {
		return datasourceService.getDatasourceDetail(id);
	}

	@Operation(summary = "查询数据源数据表", description = "查询数据源数据表，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/tables")
	public List<String> getDatasourceTables(@PathVariable Long id) throws Exception {
		return datasourceService.getDatasourceTables(id);
	}

	@Operation(summary = "创建数据源", description = "创建数据源，连接串由服务端按主机、端口与数据库名组装，不接受调用方传入。")
	// 请求体携带明文 password，关闭入参记录
	@AccessLog(module = "数据源", description = "创建数据源", request = false)
	@PostMapping("/create")
	public DatasourceResp createDatasource(@Valid @RequestBody DatasourceCreateReq request) {
		return datasourceService.createDatasource(request);
	}

	@Operation(summary = "修改数据源", description = "修改数据源，连接串按主机、端口与数据库名重新生成，不接受调用方传入。")
	// 请求体携带明文 password，关闭入参记录
	@AccessLog(module = "数据源", description = "修改数据源", request = false)
	@PutMapping("/{id}/modify")
	public DatasourceResp updateDatasource(@PathVariable Long id, @Valid @RequestBody DatasourceModifyReq request) {
		return datasourceService.updateDatasource(id, request);
	}

	@Operation(summary = "删除数据源", description = "删除数据源，用于数据源相关管理和运行场景。")
	@AccessLog(module = "数据源", description = "删除数据源")
	@DeleteMapping("/{id}")
	public void deleteDatasource(@PathVariable Long id) {
		datasourceService.deleteDatasource(id);
	}

	@Operation(summary = "测试数据源", description = "测试数据源，用于数据源相关管理和运行场景。")
	@PostMapping("/{id}/test")
	public boolean testConnection(@PathVariable Long id) {
		return datasourceService.testConnection(id);
	}

	@Operation(summary = "查询数据源表字段", description = "查询数据源表字段，用于数据源相关管理和运行场景。")
	@PostMapping("/tables/columns/query")
	public List<String> getTableColumns(@Valid @RequestBody DatasourceTableQueryReq request) throws Exception {
		return datasourceService.getTableColumns(request.datasourceId(), request.tableName());
	}

	@Operation(summary = "同步数据源目录", description = "同步数据源目录，用于数据源相关管理和运行场景。")
	@PostMapping("/{id}/catalog/sync")
	public DatasourceCatalogResp syncDatasourceCatalog(@PathVariable Long id) throws Exception {
		return datasourceService.syncDatasourceCatalog(id);
	}

	@Operation(summary = "查询数据源目录", description = "查询数据源目录，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/catalog")
	public DatasourceCatalogResp getDatasourceCatalog(@PathVariable Long id) {
		return datasourceService.getDatasourceCatalog(id);
	}

	@Operation(summary = "查询数据源目录数据表", description = "查询数据源目录数据表，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/catalog/tables")
	public List<DatasourceTable> getCatalogTables(@PathVariable Long id) {
		return datasourceService.getCatalogTables(id);
	}

	@Operation(summary = "查询数据源目录字段", description = "查询数据源目录字段，用于数据源相关管理和运行场景。")
	@PostMapping("/catalog/tables/columns/query")
	public List<DatasourceColumn> getCatalogColumns(@Valid @RequestBody DatasourceTableQueryReq request) {
		return datasourceService.getCatalogColumns(request.datasourceId(), request.tableName());
	}

	@Operation(summary = "查询数据源权限规则", description = "查询数据源权限规则，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/permission-rules")
	public List<DatasourcePermissionRule> getPermissionRules(@PathVariable Long id) {
		return datasourceService.getPermissionRules(id);
	}

	@Operation(summary = "修改数据源权限规则", description = "修改数据源权限规则，用于数据源相关管理和运行场景。")
	@AccessLog(module = "数据源", description = "修改数据源权限规则")
	@PutMapping("/{id}/permission-rules")
	public List<DatasourcePermissionRule> savePermissionRules(@PathVariable Long id,
			@RequestBody List<DatasourcePermissionRule> rules) {
		return datasourceService.savePermissionRules(id, rules);
	}

	@Operation(summary = "按数据源推断权限规则", description = "对整个数据源的全部数据表推断权限规则。")
	@PostMapping("/{id}/permission-rules/infer")
	public List<DatasourcePermissionRule> inferPermissionRulesByDatasource(@PathVariable Long id) {
		return datasourceService.inferPermissionRules(id);
	}

	@Operation(summary = "按数据表推断权限规则", description = "对数据源下指定的单张数据表推断权限规则。")
	@PostMapping("/permission-rules/infer")
	public List<DatasourcePermissionRule> inferPermissionRulesByTable(
			@Valid @RequestBody DatasourceTableQueryReq request) {
		return datasourceService.inferPermissionRules(request.datasourceId(), request.tableName());
	}

	@Operation(summary = "查询数据源逻辑关系", description = "查询数据源逻辑关系，用于数据源相关管理和运行场景。")
	@GetMapping("/{id}/logical-relations")
	public List<LogicalRelation> getLogicalRelations(@PathVariable(value = "id") Long datasourceId) {
		return datasourceService.getLogicalRelations(datasourceId);
	}

	@Operation(summary = "创建数据源逻辑关系", description = "创建数据源逻辑关系，用于数据源相关管理和运行场景。")
	@AccessLog(module = "数据源", description = "创建数据源逻辑关系")
	@PostMapping("/{id}/logical-relations/create")
	public LogicalRelation addLogicalRelation(@PathVariable(value = "id") Long datasourceId,
			@Valid @RequestBody CreateLogicalRelationReq dto) {
		return datasourceService.addLogicalRelation(datasourceId, dto);
	}

	@Operation(summary = "修改数据源逻辑关系", description = "修改数据源逻辑关系，用于数据源相关管理和运行场景。")
	@AccessLog(module = "数据源", description = "修改数据源逻辑关系")
	@PutMapping("/{id}/logical-relations/modify")
	public LogicalRelation updateLogicalRelation(@PathVariable(value = "id") Long datasourceId,
			@RequestBody UpdateLogicalRelationReq dto) {
		return datasourceService.updateLogicalRelation(datasourceId, dto);
	}

	@Operation(summary = "删除数据源逻辑关系", description = "删除数据源逻辑关系，用于数据源相关管理和运行场景。")
	@AccessLog(module = "数据源", description = "删除数据源逻辑关系")
	@DeleteMapping("/logical-relations")
	public void deleteLogicalRelation(@Valid @RequestBody LogicalRelationDeleteReq request) {
		datasourceService.deleteLogicalRelation(request.datasourceId(), request.relationId());
	}

	@Operation(summary = "全量覆盖数据源逻辑关系", description = "用提交的集合整体覆盖该数据源的逻辑关系，未提交的会被移除。")
	@AccessLog(module = "数据源", description = "全量覆盖数据源逻辑关系")
	@PutMapping("/{id}/logical-relations")
	public List<LogicalRelation> saveLogicalRelations(@PathVariable(value = "id") Long datasourceId,
			@RequestBody List<LogicalRelation> logicalRelations) {
		return datasourceService.saveLogicalRelations(datasourceId, logicalRelations);
	}

}
