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
package com.sn68.agent.dataagent.service.datasource.impl;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.TableInfoBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.connector.pool.DBConnectionPool;
import com.sn68.agent.dataagent.connector.pool.DBConnectionPoolFactory;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCatalogResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCreateReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceModifyReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceResp;
import com.sn68.agent.dataagent.dto.datasource.DatasourceTypeResp;
import com.sn68.agent.dataagent.dto.schema.CreateLogicalRelationReq;
import com.sn68.agent.dataagent.dto.schema.UpdateLogicalRelationReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.entity.DatasourceTable;
import com.sn68.agent.dataagent.entity.LogicalRelation;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.dataagent.enums.ErrorCodeEnum;
import com.sn68.agent.dataagent.repository.SkillDatasourceMapper;
import com.sn68.agent.dataagent.repository.DatasourceColumnMapper;
import com.sn68.agent.dataagent.repository.DatasourceMapper;
import com.sn68.agent.dataagent.repository.DatasourcePermissionRuleMapper;
import com.sn68.agent.dataagent.repository.DatasourceTableMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.LogicalRelationMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.datasource.DatasourceConnectionGuard;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.dataagent.service.datasource.handler.DatasourceTypeHandler;
import com.sn68.agent.dataagent.service.datasource.handler.registry.DatasourceTypeHandlerRegistry;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.dataagent.util.ApiKeyUtil;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataRefType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// todo: 检查Mapper的返回值，判断是否执行成功（或者对Mapper进行AOP）
/**
 * 数据源管理实现：负责数据源 CRUD、连接测试、表/列目录（catalog）同步与技能数据源关联。
 */
@Slf4j
@Service
@AllArgsConstructor
public class DatasourceServiceImpl implements DatasourceService {

	private static final String STATUS_ACTIVE = "active";

	private static final String TEST_STATUS_UNKNOWN = "unknown";

	private final DatasourceMapper datasourceMapper;

	private final DatasourceTableMapper datasourceTableMapper;

	private final DatasourceColumnMapper datasourceColumnMapper;

	private final DatasourcePermissionRuleMapper datasourcePermissionRuleMapper;

	private final SkillDatasourceMapper skillDatasourceMapper;

	private final DataAgentService agentService;

	private final LogicalRelationMapper logicalRelationMapper;

	private final SemanticModelMapper semanticModelMapper;

	private final DataAgentSkillMapper skillMapper;

	private final DBConnectionPoolFactory poolFactory;

	private final AccessorFactory accessorFactory;

	private final DatasourceTypeHandlerRegistry datasourceTypeHandlerRegistry;

	private final AgentVectorStoreService agentVectorStoreService;

	private final SensitiveConfigCryptoService cryptoService;

	private final AuthenticationContext authenticationContext;

	private final TransactionTemplate transactionTemplate;

	@Override
	public List<DatasourceTypeResp> getDatasourceTypes() {
		return List.of(BizDataSourceTypeEnum.MYSQL, BizDataSourceTypeEnum.POSTGRESQL,
				BizDataSourceTypeEnum.DAMENG, BizDataSourceTypeEnum.SQL_SERVER, BizDataSourceTypeEnum.ORACLE,
				BizDataSourceTypeEnum.HIVE)
			.stream()
			.map(type -> DatasourceTypeResp.builder()
				.code(type.getCode())
				.typeName(type.getTypeName())
				.dialect(type.getDialect())
				.protocol(type.getProtocol())
				.displayName(type.getDialect())
				.build())
			.toList();
	}

	@Override
	public List<DatasourceResp> listDatasource(String status, String type) {
		return listPersistedDatasource(status, type).stream().map(this::toResp).toList();
	}

	@Override
	public DatasourceResp getDatasourceDetail(Long id) {
		return toResp(requirePersistedDatasource(id, requireCurrentTenantId()));
	}

	/**
	 * 取库内原始行。出参转换必须基于原始行：{@code toApiDatasource} 已经把 username 掩过一次，
	 * 再掩一次会把 {@code ****ser1} 塌缩成 {@code ****}，回显信息量凭空少一截。
	 */
	private List<Datasource> listPersistedDatasource(String status, String type) {
		String tenantId = requireCurrentTenantId();
		if (StringUtils.isNotBlank(status)) {
			return datasourceMapper.selectByStatusAndTenantId(status, tenantId);
		}
		if (StringUtils.isNotBlank(type)) {
			return datasourceMapper.selectByTypeAndTenantId(type, tenantId);
		}
		return datasourceMapper.selectByTenantId(tenantId);
	}

	@Override
	public List<Datasource> getAllDatasource() {
		return getAllDatasourceForTenant(requireCurrentTenantId());
	}

	@Override
	public List<Datasource> getAllDatasourceForTenant(String tenantId) {
		if (StringUtils.isBlank(tenantId)) {
			throw CheckedException.forbidden();
		}
		return datasourceMapper.selectByTenantId(tenantId).stream().map(this::toApiDatasource).toList();
	}

	@Override
	public List<Datasource> getDatasourceByStatus(String status) {
		return datasourceMapper.selectByStatusAndTenantId(status, requireCurrentTenantId()).stream()
			.map(this::toApiDatasource)
			.toList();
	}

	@Override
	public List<Datasource> getDatasourceByType(String type) {
		return datasourceMapper.selectByTypeAndTenantId(type, requireCurrentTenantId()).stream()
			.map(this::toApiDatasource)
			.toList();
	}

	@Override
	public Datasource getDatasourceById(Long id) {
		return requireDatasource(id);
	}

	@Override
	public Datasource requireDatasource(Long id) {
		return requireDatasourceForTenant(id, requireCurrentTenantId());
	}

	@Override
	public Datasource requireDatasourceForTenant(Long id, String tenantId) {
		return toApiDatasource(requirePersistedDatasource(id, tenantId));
	}

	/**
	 * 按租户取库内原始行，字段未脱敏。回写路径必须用它而不是 {@link #requireDatasourceForTenant}：
	 * 后者返回的 password 是 null、username 是掩码串，拿去做「沿用已有凭据」会把掩码写回库。
	 */
	private Datasource requirePersistedDatasource(Long id, String tenantId) {
		if (id == null) {
			throw CheckedException.badRequest("datasourceId不能为空");
		}
		if (StringUtils.isBlank(tenantId)) {
			throw CheckedException.badRequest("数据源操作需要租户上下文");
		}
		Datasource datasource = datasourceMapper.selectByIdAndTenantId(id, tenantId);
		if (datasource == null) {
			throw CheckedException.notFound("Datasource does not exist");
		}
		return datasource;
	}

	@Override
	public DatasourceResp createDatasource(DatasourceCreateReq request) {
		String tenantId = requireCurrentTenantId();
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(request.type());
		Datasource datasource = Datasource.builder()
			.tenantId(tenantId)
			.name(StringUtils.trim(request.name()))
			.type(StringUtils.trim(request.type()))
			.host(DatasourceConnectionGuard.requireSafeHost(request.host()))
			.port(DatasourceConnectionGuard.requirePortInRange(request.port()))
			.databaseName(DatasourceConnectionGuard.requireSafeDatabaseName(request.databaseName()))
			.username(StringUtils.defaultString(request.username()))
			.password(cryptoService.encryptIfNecessary(StringUtils.defaultString(request.password())))
			.status(StringUtils.defaultIfBlank(request.status(), STATUS_ACTIVE))
			.testStatus(TEST_STATUS_UNKNOWN)
			.description(request.description())
			.build();
		datasource.setConnectionUrl(buildServerSideConnectionUrl(handler, datasource));

		Instant now = Instant.now();
		datasource.setCreateTime(now);
		datasource.setLastModifyTime(now);
		datasourceMapper.insert(datasource);
		return toResp(datasource);
	}

	@Override
	public DatasourceResp updateDatasource(Long id, DatasourceModifyReq request) {
		String tenantId = requireCurrentTenantId();
		Datasource existing = requirePersistedDatasource(id, tenantId);
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(request.type());
		Datasource datasource = Datasource.builder()
			.id(id)
			.tenantId(existing.getTenantId())
			.name(StringUtils.trim(request.name()))
			.type(StringUtils.trim(request.type()))
			.host(DatasourceConnectionGuard.requireSafeHost(request.host()))
			.port(DatasourceConnectionGuard.requirePortInRange(request.port()))
			.databaseName(DatasourceConnectionGuard.requireSafeDatabaseName(request.databaseName()))
			.status(StringUtils.defaultIfBlank(request.status(), existing.getStatus()))
			.description(request.description())
			.build();

		// 出参对 password 置空、对 username 掩码。留空、回填掩码串、或整个省略这两个字段时都必须沿用库内
		// 真实凭据，否则这一次保存就会把空串或 "****" 写进库、静默毁掉数据源账号。
		datasource.setPassword(cryptoService.shouldKeepExisting(request.password()) ? existing.getPassword()
				: cryptoService.encryptIfNecessary(request.password()));
		datasource.setUsername(cryptoService.shouldKeepExisting(request.username()) ? existing.getUsername()
				: request.username());
		datasource.setConnectionUrl(buildServerSideConnectionUrl(handler, datasource));
		datasource.setLastModifyTime(Instant.now());

		evictDatasourcePool(existing);
		datasourceMapper.updateById(datasource);
		return toResp(requirePersistedDatasource(id, tenantId));
	}

	/**
	 * 组装连接串。刻意调 {@code buildConnectionUrl} 而不是 {@code resolveConnectionUrl}——后者会优先采用
	 * 实体上已有的连接串，写入路径一旦走它，调用方只要想办法把串塞进实体就能绕过模板（PRD S-2）。
	 * 生成结果再过一遍 scheme 白名单与危险参数黑名单，模板日后被改坏时立刻失败关闭。
	 */
	private String buildServerSideConnectionUrl(DatasourceTypeHandler handler, Datasource datasource) {
		String connectionUrl = handler.buildConnectionUrl(datasource);
		DatasourceConnectionGuard.requireGeneratedJdbcUrl(connectionUrl);
		return connectionUrl;
	}

	/**
	 * 删除数据源。
	 *
	 * <p>刻意不加 {@code @Transactional}：旧实现在一个事务里按关联 Skill 逐个发起向量库删除，事务时长等于 N 次
	 * 远端往返；更糟的是校验与向量删除交织在同一个循环里 —— 第 K 个 Skill 校验不通过时，前 K-1 个 Skill 的向量
	 * 已经删掉，而事务回滚只能撤销数据库改动，向量删不回来，一次「被拒绝的删除」就把知识库打残了。
	 *
	 * <p>现在改成：先一次性做完全部校验 → 短事务删库 → 事务外清理向量。
	 * <b>非事务补偿点</b>：向量清理失败时数据库删除已提交，残留的 schema 向量需要重新同步该 Skill 的知识库清除，
	 * 这里汇总失败的 Skill 显式抛错上报，不静默吞。
	 */
	@Override
	public void deleteDatasource(Long id) {
		Datasource datasource = requireDatasource(id);
		List<SkillDatasource> relations = skillDatasourceMapper.selectByDatasourceId(id).stream()
			.filter(relation -> relation != null && relation.getSkillId() != null)
			.toList();
		List<Long> boundSkillIds = relations.stream().map(SkillDatasource::getSkillId).distinct().toList();
		List<Long> activeBoundSkillIds = relations.stream()
			.filter(relation -> Boolean.TRUE.equals(relation.getIsActive()))
			.map(SkillDatasource::getSkillId)
			.distinct()
			.toList();
		ensureSkillsKeepActiveDatasource(activeBoundSkillIds);

		transactionTemplate.executeWithoutResult(status -> {
			// First, delete the associations
			skillDatasourceMapper.deleteAllByDatasourceId(id);
			semanticModelMapper.deleteByDatasourceId(id);

			// Then, delete the data source
			datasourceMapper.deleteById(id);
		});
		evictDatasourcePool(datasource);

		List<String> failedSkills = new ArrayList<>();
		for (Long skillId : boundSkillIds) {
			try {
				agentVectorStoreService.deleteSkillSchemaDocuments(String.valueOf(skillId), String.valueOf(id));
			}
			catch (Exception ex) {
				failedSkills.add(String.valueOf(skillId));
				log.error("数据源已删除但 Skill schema 向量清理失败, skillId={}, datasourceId={}", skillId, id, ex);
			}
		}
		if (!failedSkills.isEmpty()) {
			throw CheckedException.fail("数据源已删除，但以下 Skill 的 schema 向量清理失败，需要重新同步知识库: "
					+ String.join(", ", failedSkills));
		}
	}

	/**
	 * 一次性校验「删掉本数据源后，这些 Skill 是否还留得下启用中的数据源」。
	 *
	 * <p>入参是与本数据源存在启用关联的 Skill。关联计数与 Skill 类型都改为批量取回后在内存里判定，
	 * 替代旧实现按 Skill 逐个 {@code countActiveBySkillId} + {@code selectById}。
	 */
	private void ensureSkillsKeepActiveDatasource(List<Long> activeBoundSkillIds) {
		if (activeBoundSkillIds.isEmpty()) {
			return;
		}
		Map<Long, Long> activeCountBySkillId = skillDatasourceMapper
			.selectList(SkillDatasource::getSkillId, activeBoundSkillIds)
			.stream()
			.filter(relation -> relation != null && relation.getSkillId() != null
					&& Boolean.TRUE.equals(relation.getIsActive()))
			.collect(Collectors.groupingBy(SkillDatasource::getSkillId, Collectors.counting()));
		Set<Long> querySkillIds = skillMapper.selectBatchIds(activeBoundSkillIds).stream()
			.filter(skill -> skill != null && "QUERY".equals(skill.getSkillKind()))
			.map(DataAgentSkill::getId)
			.collect(Collectors.toSet());
		for (Long skillId : activeBoundSkillIds) {
			if (activeCountBySkillId.getOrDefault(skillId, 0L) <= 1 && querySkillIds.contains(skillId)) {
				throw CheckedException.badRequest("当前Skill必须至少保留一个启用中的数据源, skillId=" + skillId);
			}
		}
	}

	@Override
	public void updateTestStatus(Long id, String testStatus) {
		requireDatasource(id);
		datasourceMapper.updateTestStatusById(id, testStatus);
	}

	@Override
	public boolean testConnection(Long id) {
		return testConnectionForTenant(id, requireCurrentTenantId());
	}

	@Override
	public boolean testConnectionForTenant(Long id, String tenantId) {
		Datasource datasource = requireDatasourceForTenant(id, tenantId);
		try {
			// ping测试
			boolean connectionSuccess = realConnectionTest(datasource);
			log.info(datasource.getName() + " test connection result: " + connectionSuccess);
			// Update test status
			datasourceMapper.updateTestStatusById(id, connectionSuccess ? "success" : "failed");

			return connectionSuccess;
		}
		catch (Exception e) {
			datasourceMapper.updateTestStatusById(id, "failed");
			log.error("Error testing connection for datasource ID " + id + ": " + e.getMessage(), e);
			return false;
		}
	}

	/**
	 * Actual connection test method
	 */
	private boolean realConnectionTest(Datasource datasource) {
		DbConfigBO config = getDbConfig(datasource);
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		if (StringUtils.isNotBlank(config.getUrl())) {
			config.setUrl(handler.normalizeTestUrl(datasource, config.getUrl()));
		}

		DBConnectionPool pool = poolFactory.getPoolByType(datasource.getType());
		if (pool == null) {
			return false;
		}

		ErrorCodeEnum result = pool.ping(config);
		return result == ErrorCodeEnum.SUCCESS;

	}

	private void evictDatasourcePool(Datasource datasource) {
		if (datasource == null || datasource.getType() == null) {
			return;
		}
		DBConnectionPool pool = poolFactory.getPoolByType(datasource.getType());
		if (pool == null) {
			return;
		}
		try {
			pool.evict(getDbConfig(datasource));
		}
		catch (Exception e) {
			log.warn("Failed to evict datasource pool for datasourceId={}: {}", datasource.getId(), e.getMessage());
		}
	}

	@Override
	public List<String> getDatasourceTables(Long datasourceId) throws Exception {
		return getDatasourceTablesForTenant(datasourceId, requireCurrentTenantId());
	}

	@Override
	public List<String> getDatasourceTablesForTenant(Long datasourceId, String tenantId) throws Exception {
		log.info("Getting tables for datasource: {}", datasourceId);

		// Get data source information
		Datasource datasource = requireDatasourceForTenant(datasourceId, tenantId);

		// Create database configuration
		DbConfigBO dbConfig = getDbConfig(datasource);

		// Create query parameters
		DbQueryParameter queryParam = DbQueryParameter.from(dbConfig);

		// 提取schema名称
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String schemaName = handler.extractSchemaName(datasource);
		queryParam.setSchema(schemaName);

		// Query table list
		Accessor dbAccessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<TableInfoBO> tableInfoList = dbAccessor.showTables(dbConfig, queryParam);

		// Extract table names
		List<String> tableNames = tableInfoList.stream()
			.map(TableInfoBO::getName)
			.filter(name -> name != null && !name.trim().isEmpty())
			.sorted()
			.toList();

		log.info("Found {} tables for datasource: {}", tableNames.size(), datasourceId);
		return tableNames;
	}

	@Override
	public DbConfigBO getDbConfig(Datasource datasource) {
		Datasource runtimeSource = datasource;
		if (runtimeSource != null && runtimeSource.getPassword() == null && runtimeSource.getId() != null) {
			Datasource persisted = datasourceMapper.selectById(runtimeSource.getId());
			if (persisted != null) {
				runtimeSource = persisted;
			}
		}
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(runtimeSource.getType());
		return handler.toDbConfig(toRuntimeDatasource(runtimeSource));
	}

	@Override
	public List<String> getTableColumns(Long datasourceId, String tableName) throws Exception {
		return getTableColumnsForTenant(datasourceId, requireCurrentTenantId(), tableName);
	}

	@Override
	public List<String> getTableColumnsForTenant(Long datasourceId, String tenantId, String tableName) throws Exception {
		log.info("Getting columns for table: {} in datasource: {}", tableName, datasourceId);

		// 获取数据源信息
		Datasource datasource = requireDatasourceForTenant(datasourceId, tenantId);

		// 创建数据库配置
		DbConfigBO dbConfig = getDbConfig(datasource);

		// 创建查询参数
		DbQueryParameter queryParam = DbQueryParameter.from(dbConfig);

		// 提取schema名称
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String schemaName = handler.extractSchemaName(datasource);
		queryParam.setSchema(schemaName);
		queryParam.setTable(tableName);

		// 查询字段列表
		Accessor dbAccessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<ColumnInfoBO> columnInfoList = dbAccessor.showColumns(dbConfig, queryParam); // 提取字段名称
		List<String> columnNames = columnInfoList.stream()
			.map(ColumnInfoBO::getName)
			.filter(name -> name != null && !name.trim().isEmpty())
			.sorted()
			.toList();

		log.info("Found {} columns for table {} in datasource: {}", columnNames.size(), tableName, datasourceId);
		return columnNames;
	}

	/**
	 * 同步数据源目录（表与列）。
	 *
	 * <p>刻意不加 {@code @Transactional}：{@code showTables} / {@code showColumns} 是对被管理数据库发起的元数据
	 * 探查，旧实现把「每张表一次 showColumns」放在事务里，事务时长等于 N 次远端往返。现在先在事务外把全部元数据
	 * 抓成内存快照，再用一个短事务整体落库。
	 */
	@Override
	public DatasourceCatalogResp syncDatasourceCatalog(Long datasourceId) throws Exception {
		log.info("Syncing datasource catalog for datasource: {}", datasourceId);
		Datasource datasource = requireDatasource(datasourceId);
		DbConfigBO dbConfig = getDbConfig(datasource);
		DbQueryParameter queryParam = DbQueryParameter.from(dbConfig);
		DatasourceTypeHandler handler = datasourceTypeHandlerRegistry.getRequired(datasource.getType());
		String schemaName = handler.extractSchemaName(datasource);
		queryParam.setSchema(schemaName);
		Accessor dbAccessor = accessorFactory.getAccessorByDbConfig(dbConfig);
		List<TableInfoBO> tableInfoList = Optional.ofNullable(dbAccessor.showTables(dbConfig, queryParam))
			.orElse(List.of());

		List<String> syncedTables = tableInfoList.stream()
			.map(TableInfoBO::getName)
			.filter(StringUtils::isNotBlank)
			.map(String::trim)
			.collect(Collectors.toCollection(LinkedHashSet::new))
			.stream()
			.sorted(String.CASE_INSENSITIVE_ORDER)
			.toList();
		Map<String, TableInfoBO> tableInfoByName = tableInfoList.stream()
			.filter(table -> table != null && StringUtils.isNotBlank(table.getName()))
			.collect(Collectors.toMap(table -> table.getName().trim(), table -> table, (left, right) -> left,
					LinkedHashMap::new));

		// 事务外抓取列元数据：单表探查失败沿用旧行为——跳过该表，不影响其余表的同步
		Map<String, List<ColumnInfoBO>> columnInfoByTable = new LinkedHashMap<>();
		for (String tableName : syncedTables) {
			queryParam.setTable(tableName);
			try {
				columnInfoByTable.put(tableName,
						Optional.ofNullable(dbAccessor.showColumns(dbConfig, queryParam)).orElse(List.of()));
			}
			catch (Exception ex) {
				log.warn("Failed to sync columns for datasourceId={}, table={}: {}", datasourceId, tableName,
						ex.getMessage());
			}
		}

		transactionTemplate.executeWithoutResult(status -> persistDatasourceCatalog(datasourceId, syncedTables,
				tableInfoByName, columnInfoByTable));
		return getDatasourceCatalog(datasourceId);
	}

	/**
	 * 事务内落库目录快照：先 upsert 表并清理服务端已删除的表/列，再逐表 upsert 列。
	 */
	private void persistDatasourceCatalog(Long datasourceId, List<String> syncedTables,
			Map<String, TableInfoBO> tableInfoByName, Map<String, List<ColumnInfoBO>> columnInfoByTable) {
		upsertCatalogTables(datasourceId, syncedTables, tableInfoByName);
		datasourceTableMapper.deleteMissingTables(datasourceId, syncedTables);
		datasourceColumnMapper.deleteColumnsOutsideTables(datasourceId, syncedTables);
		upsertCatalogColumns(datasourceId, syncedTables, columnInfoByTable);
	}

	/**
	 * 按同步到的表名逐个 upsert 表记录，保留已有记录的启用状态。
	 */
	private void upsertCatalogTables(Long datasourceId, List<String> syncedTables,
			Map<String, TableInfoBO> tableInfoByName) {
		// 一次取回本数据源已有的表，替代按表逐个 selectByDatasourceIdAndTableName
		Map<String, DatasourceTable> existingTables = datasourceTableMapper.selectByDatasourceId(datasourceId)
			.stream()
			.filter(table -> table != null && StringUtils.isNotBlank(table.getTableName()))
			.collect(Collectors.toMap(DatasourceTable::getTableName, table -> table, (left, right) -> left,
					LinkedHashMap::new));
		for (String tableName : syncedTables) {
			TableInfoBO tableInfo = tableInfoByName.get(tableName);
			DatasourceTable existing = existingTables.get(tableName);
			if (existing == null) {
				DatasourceTable row = new DatasourceTable(datasourceId, tableName,
						tableInfo == null ? null : tableInfo.getDescription(), true);
				datasourceTableMapper.insert(row);
			}
			else {
				DatasourceTable updated = DatasourceTable.builder()
					.id(existing.getId())
					.tableComment(tableInfo == null ? existing.getTableComment() : tableInfo.getDescription())
					.enabled(existing.getEnabled() == null ? true : existing.getEnabled())
					.build();
				datasourceTableMapper.updateCatalogFields(updated);
			}
		}
	}

	/**
	 * 逐表 upsert 列记录并清理该表下服务端已删除的列；元数据探查失败（无列信息）的表整体跳过。
	 */
	private void upsertCatalogColumns(Long datasourceId, List<String> syncedTables,
			Map<String, List<ColumnInfoBO>> columnInfoByTable) {
		// 同上，一次取回全部列（表数 × 列数 次单条查询 → 1 次）
		Map<String, DatasourceColumn> existingColumns = datasourceColumnMapper.selectByDatasourceId(datasourceId)
			.stream()
			.filter(column -> column != null && StringUtils.isNotBlank(column.getTableName())
					&& StringUtils.isNotBlank(column.getColumnName()))
			.collect(Collectors.toMap(column -> columnKey(column.getTableName(), column.getColumnName()),
					column -> column, (left, right) -> left, LinkedHashMap::new));
		for (String tableName : syncedTables) {
			List<ColumnInfoBO> columnInfoList = columnInfoByTable.get(tableName);
			if (columnInfoList == null) {
				// 元数据探查失败的表整体跳过，避免把已有列误判成「服务端已删除」而清掉
				continue;
			}
			List<String> syncedColumns = columnInfoList.stream()
				.map(ColumnInfoBO::getName)
				.filter(StringUtils::isNotBlank)
				.map(String::trim)
				.collect(Collectors.toCollection(LinkedHashSet::new))
				.stream()
				.toList();
			Map<String, ColumnInfoBO> columnInfoByName = columnInfoList.stream()
				.filter(column -> column != null && StringUtils.isNotBlank(column.getName()))
				.collect(Collectors.toMap(column -> column.getName().trim(), column -> column,
						(left, right) -> left, LinkedHashMap::new));
			for (String columnName : syncedColumns) {
				ColumnInfoBO columnInfo = columnInfoByName.get(columnName);
				DatasourceColumn existing = existingColumns.get(columnKey(tableName, columnName));
				if (existing == null) {
					DatasourceColumn row = DatasourceColumn.builder()
						.datasourceId(datasourceId)
						.tableName(tableName)
						.columnName(columnName)
						.columnType(columnInfo == null ? null : columnInfo.getType())
						.columnComment(columnInfo == null ? null : columnInfo.getDescription())
						.sampleValues(columnInfo == null ? null : columnInfo.getSamples())
						.enabled(true)
						.build();
					datasourceColumnMapper.insert(row);
				}
				else {
					DatasourceColumn updated = DatasourceColumn.builder()
						.id(existing.getId())
						.columnType(columnInfo == null ? existing.getColumnType() : columnInfo.getType())
						.columnComment(columnInfo == null ? existing.getColumnComment()
								: columnInfo.getDescription())
						.sampleValues(columnInfo == null ? existing.getSampleValues() : columnInfo.getSamples())
						.enabled(existing.getEnabled() == null ? true : existing.getEnabled())
						.build();
					datasourceColumnMapper.updateCatalogFields(updated);
				}
			}
			datasourceColumnMapper.deleteMissingColumns(datasourceId, tableName, syncedColumns);
		}
	}

	private String columnKey(String tableName, String columnName) {
		return tableName + "\u0000" + columnName;
	}

	@Override
	public DatasourceCatalogResp getDatasourceCatalog(Long datasourceId) {
		requireDatasource(datasourceId);
		List<DatasourceTable> tables = getCatalogTables(datasourceId);
		Map<String, List<DatasourceColumn>> columnsByTable = datasourceColumnMapper.selectByDatasourceId(datasourceId)
			.stream()
			.collect(Collectors.groupingBy(DatasourceColumn::getTableName, LinkedHashMap::new, Collectors.toList()));
		return DatasourceCatalogResp.builder()
			.datasourceId(datasourceId)
			.tables(tables)
			.columnsByTable(columnsByTable)
			.build();
	}

	@Override
	public List<DatasourceTable> getCatalogTables(Long datasourceId) {
		requireDatasource(datasourceId);
		return datasourceTableMapper.selectByDatasourceId(datasourceId);
	}

	@Override
	public List<DatasourceColumn> getCatalogColumns(Long datasourceId, String tableName) {
		requireDatasource(datasourceId);
		return datasourceColumnMapper.selectByDatasourceIdAndTableName(datasourceId, tableName);
	}

	@Override
	public List<String> getCatalogTableNames(Long datasourceId) {
		requireDatasource(datasourceId);
		return datasourceTableMapper.selectEnabledByDatasourceId(datasourceId)
			.stream()
			.map(DatasourceTable::getTableName)
			.toList();
	}

	@Override
	public List<String> getCatalogColumnNames(Long datasourceId, String tableName) {
		requireDatasource(datasourceId);
		return datasourceColumnMapper.selectEnabledByDatasourceIdAndTableName(datasourceId, tableName)
			.stream()
			.map(DatasourceColumn::getColumnName)
			.toList();
	}

	@Override
	public boolean hasDatasourceCatalog(Long datasourceId) {
		requireDatasource(datasourceId);
		return datasourceTableMapper.countByDatasourceId(datasourceId) > 0;
	}

	@Override
	public List<DatasourcePermissionRule> getPermissionRules(Long datasourceId) {
		requireDatasource(datasourceId);
		return datasourcePermissionRuleMapper.selectByDatasourceId(datasourceId);
	}

	@Override
	public List<DatasourcePermissionRule> getPermissionRulesForTenant(Long datasourceId, String tenantId) {
		requireDatasourceForTenant(datasourceId, tenantId);
		return datasourcePermissionRuleMapper.selectByDatasourceId(datasourceId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<DatasourcePermissionRule> savePermissionRules(Long datasourceId, List<DatasourcePermissionRule> rules) {
		requireDatasource(datasourceId);
		datasourcePermissionRuleMapper.deleteByDatasourceId(datasourceId);
		for (DatasourcePermissionRule rule : Optional.ofNullable(rules).orElse(List.of())) {
			if (rule == null || StringUtils.isAnyBlank(rule.getTableName(), rule.getColumnName(),
					rule.getDataRefType())) {
				continue;
			}
			DataRefType dataRefType = DataRefType.of(rule.getDataRefType());
			if (dataRefType == null || dataRefType == DataRefType.ALL) {
				throw CheckedException.badRequest("不支持的数据权限类型 dataRefType: " + rule.getDataRefType());
			}
			DatasourcePermissionRule row = DatasourcePermissionRule.builder()
				.datasourceId(datasourceId)
				.tableName(rule.getTableName().trim())
				.columnName(rule.getColumnName().trim())
				.dataRefType(dataRefType.getType())
				.javaType(StringUtils.defaultIfBlank(rule.getJavaType(), "String"))
				.scopeType(rule.getScopeType())
				.enabled(rule.getEnabled() == null ? true : rule.getEnabled())
				.missingPolicy(StringUtils.defaultIfBlank(rule.getMissingPolicy(),
						DatasourcePermissionRule.MISSING_POLICY_ALLOW))
				.build();
			datasourcePermissionRuleMapper.insert(row);
		}
		return getPermissionRules(datasourceId);
	}

	@Override
	public List<DatasourcePermissionRule> inferPermissionRules(Long datasourceId) {
		requireDatasource(datasourceId);
		return buildPermissionRules(datasourceId, datasourceColumnMapper.selectPermissionCandidateColumns(datasourceId));
	}

	@Override
	public List<DatasourcePermissionRule> inferPermissionRules(Long datasourceId, String tableName) {
		requireDatasource(datasourceId);
		if (StringUtils.isBlank(tableName)) {
			throw CheckedException.badRequest("tableName不能为空, datasourceId=" + datasourceId);
		}
		return buildPermissionRules(datasourceId,
				datasourceColumnMapper.selectPermissionCandidateColumns(datasourceId, tableName.trim()));
	}

	private List<DatasourcePermissionRule> buildPermissionRules(Long datasourceId, List<DatasourceColumn> columns) {
		List<DatasourcePermissionRule> inferred = new ArrayList<>();
		for (DatasourceColumn column : columns) {
			DataRefType dataRefType = inferDataRefType(column.getColumnName());
			if (dataRefType == null) {
				continue;
			}
			inferred.add(DatasourcePermissionRule.builder()
				.datasourceId(datasourceId)
				.tableName(column.getTableName())
				.columnName(column.getColumnName())
				.dataRefType(dataRefType.getType())
				.javaType(inferJavaType(column.getColumnType()))
				.enabled(true)
				.missingPolicy(DatasourcePermissionRule.MISSING_POLICY_ALLOW)
				.build());
		}
		return inferred;
	}

	@Override
	public List<LogicalRelation> getLogicalRelations(Long datasourceId) {
		return getLogicalRelationsForTenant(datasourceId, requireCurrentTenantId());
	}

	@Override
	public List<LogicalRelation> getLogicalRelationsForTenant(Long datasourceId, String tenantId) {
		requireDatasourceForTenant(datasourceId, tenantId);
		log.info("Getting logical relations for datasource: {}", datasourceId);
		return logicalRelationMapper.selectByDatasourceId(datasourceId);
	}

	@Override
	public LogicalRelation addLogicalRelation(Long datasourceId, LogicalRelation logicalRelation) {
		requireDatasource(datasourceId);
		log.info("Adding logical relation for datasource: {}", datasourceId);

		// 设置数据源ID
		logicalRelation.setDatasourceId(datasourceId);
		normalizeLogicalRelationType(logicalRelation, null);
		validateNoDuplicateLogicalRelation(datasourceId, logicalRelation, null);

		// 插入外键
		logicalRelationMapper.insert(logicalRelation);
		log.info("Logical relation added successfully with id: {}", logicalRelation.getId());

		return logicalRelation;
	}

	@Override
	public LogicalRelation addLogicalRelation(Long datasourceId, CreateLogicalRelationReq dto) {
		return addLogicalRelation(datasourceId, LogicalRelation.builder()
			.sourceTableName(dto.getSourceTableName())
			.sourceColumnName(dto.getSourceColumnName())
			.targetTableName(dto.getTargetTableName())
			.targetColumnName(dto.getTargetColumnName())
			.relationType(dto.getRelationType())
			.description(dto.getDescription())
			.build());
	}

	@Override
	public LogicalRelation updateLogicalRelation(Long datasourceId, Long logicalRelationId,
			LogicalRelation logicalRelation) {
		requireDatasource(datasourceId);
		log.info("Updating logical relation: {} for datasource: {}", logicalRelationId, datasourceId);

		// 验证外键是否存在且属于该数据源
		LogicalRelation existingRelation = logicalRelationMapper.selectByIdAndDatasourceId(logicalRelationId,
				datasourceId);
		if (existingRelation == null) {
			throw CheckedException.notFound("逻辑外键不存在，ID: " + logicalRelationId);
		}

		if (!existingRelation.getDatasourceId().equals(datasourceId)) {
			throw CheckedException.badRequest("逻辑外键不属于指定的数据源, datasourceId=" + datasourceId);
		}

		// 设置ID和数据源ID
		logicalRelation.setId(logicalRelationId);
		logicalRelation.setDatasourceId(datasourceId);
		normalizeLogicalRelationType(logicalRelation, existingRelation.getRelationType());
		validateNoDuplicateLogicalRelation(datasourceId, logicalRelation, logicalRelationId);

		// 更新外键
		int updated = logicalRelationMapper.updateByIdAndDatasourceId(datasourceId, logicalRelation);
		if (updated == 0) {
			throw CheckedException.fail("更新逻辑外键失败, ID: " + logicalRelationId);
		}

		log.info("Logical relation updated successfully: {}", logicalRelationId);

		// 返回更新后的数据
		return logicalRelationMapper.selectByIdAndDatasourceId(logicalRelationId, datasourceId);
	}

	@Override
	public LogicalRelation updateLogicalRelation(Long datasourceId, Long relationId, UpdateLogicalRelationReq dto) {
		return updateLogicalRelation(datasourceId, relationId, LogicalRelation.builder()
			.sourceTableName(dto.getSourceTableName())
			.sourceColumnName(dto.getSourceColumnName())
			.targetTableName(dto.getTargetTableName())
			.targetColumnName(dto.getTargetColumnName())
			.relationType(dto.getRelationType())
			.description(dto.getDescription())
			.build());
	}

	@Override
	public LogicalRelation updateLogicalRelation(Long datasourceId, UpdateLogicalRelationReq dto) {
		if (dto == null || dto.getId() == null) {
			throw new IllegalArgumentException("relationId cannot be null");
		}
		return updateLogicalRelation(datasourceId, dto.getId(), dto);
	}

	@Override
	public void deleteLogicalRelation(Long datasourceId, Long logicalRelationId) {
		requireDatasource(datasourceId);
		log.info("Deleting logical relation: {} for datasource: {}", logicalRelationId, datasourceId);

		// 验证外键是否属于该数据源
		LogicalRelation logicalRelation = logicalRelationMapper.selectByIdAndDatasourceId(logicalRelationId,
				datasourceId);
		if (logicalRelation == null) {
			throw CheckedException.notFound("逻辑外键不存在，ID: " + logicalRelationId);
		}

		if (!logicalRelation.getDatasourceId().equals(datasourceId)) {
			throw CheckedException.badRequest("逻辑外键不属于指定的数据源, datasourceId=" + datasourceId);
		}

		// 删除外键（逻辑删除）
		int deleted = logicalRelationMapper.deleteByIdAndDatasourceId(logicalRelationId, datasourceId);
		if (deleted == 0) {
			throw CheckedException.fail("删除逻辑外键失败, ID: " + logicalRelationId);
		}

		log.info("Logical relation deleted successfully: {}", logicalRelationId);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<LogicalRelation> saveLogicalRelations(Long datasourceId, List<LogicalRelation> logicalRelations) {
		return saveLogicalRelationsForTenant(datasourceId, requireCurrentTenantId(), logicalRelations);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public List<LogicalRelation> saveLogicalRelationsForTenant(Long datasourceId, String tenantId,
			List<LogicalRelation> logicalRelations) {
		requireDatasourceForTenant(datasourceId, tenantId);
		log.info("Saving {} logical relations for datasource: {}", logicalRelations.size(), datasourceId);

		// 获取现有的所有外键关系
		List<LogicalRelation> existingRelations = logicalRelationMapper.selectByDatasourceId(datasourceId);
		Map<Long, LogicalRelation> existingMap = existingRelations.stream()
			.collect(Collectors.toMap(LogicalRelation::getId, relation -> relation));

		// 收集传入列表中已存在的ID
		Set<Long> incomingIds = logicalRelations.stream()
			.map(LogicalRelation::getId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());

		for (LogicalRelation logicalRelation : logicalRelations) {
			if (logicalRelation.getId() != null && !existingMap.containsKey(logicalRelation.getId())) {
				throw CheckedException
					.badRequest("批量保存包含不存在或不属于当前数据源的逻辑外键ID: " + logicalRelation.getId());
			}
		}

		Map<String, LogicalRelation> uniqueRelationsByKey = new LinkedHashMap<>();
		for (LogicalRelation logicalRelation : logicalRelations) {
			String relationKey = buildLogicalRelationKey(logicalRelation);
			LogicalRelation previous = uniqueRelationsByKey.putIfAbsent(relationKey, logicalRelation);
			if (previous != null) {
				throw CheckedException.badRequest("批量保存包含重复的逻辑外键关系: " + relationKey);
			}
		}

		// 删除那些不在传入列表中的外键
		int deletedCount = 0;
		for (LogicalRelation existing : existingRelations) {
			if (!incomingIds.contains(existing.getId())) {
				logicalRelationMapper.deleteByIdAndDatasourceId(existing.getId(), datasourceId);
				deletedCount++;
				log.info("Deleted logical relation: {} -> {}", existing.getSourceTableName(),
						existing.getTargetTableName());
			}
		}
		log.info("Deleted {} logical relations for datasource: {}", deletedCount, datasourceId);

		// 插入或更新去重后的外键列表
		int insertedCount = 0;
		int updatedCount = 0;
		for (LogicalRelation logicalRelation : uniqueRelationsByKey.values()) {
			LogicalRelation existing = logicalRelation.getId() == null ? null
					: existingMap.get(logicalRelation.getId());
			normalizeLogicalRelationType(logicalRelation, existing == null ? null : existing.getRelationType());
			logicalRelation.setDatasourceId(datasourceId);
			validateNoDuplicateLogicalRelation(datasourceId, logicalRelation, logicalRelation.getId());

			if (logicalRelation.getId() != null && existingMap.containsKey(logicalRelation.getId())) {
				// 更新现有记录
				logicalRelationMapper.updateByIdAndDatasourceId(datasourceId, logicalRelation);
				updatedCount++;
				log.debug("Updated logical relation: {} -> {}", logicalRelation.getSourceTableName(),
						logicalRelation.getTargetTableName());
			}
			else {
				// 插入新记录
				logicalRelation.setId(null);
				logicalRelationMapper.insert(logicalRelation);
				insertedCount++;
				log.debug("Inserted logical relation: {} -> {}", logicalRelation.getSourceTableName(),
						logicalRelation.getTargetTableName());
			}
		}

		log.info("Saved logical relations for datasource {}: {} inserted, {} updated, {} deleted", datasourceId,
				insertedCount, updatedCount, deletedCount);

		return logicalRelationMapper.selectByDatasourceId(datasourceId);
	}

	/**
	 * 归一化逻辑关系类型：库端 CHECK 约束（logical_relation_type_ck）拒绝空串，而前端保存可能不携带类型。
	 * 空白时优先沿用库中现值（避免误清用户已登记的类型），新记录统一记为 UNKNOWN（与存量数据一致）。
	 */
	private void normalizeLogicalRelationType(LogicalRelation relation, String fallbackType) {
		if (relation == null || StringUtils.isNotBlank(relation.getRelationType())) {
			return;
		}
		relation.setRelationType(StringUtils.isNotBlank(fallbackType) ? fallbackType : "UNKNOWN");
	}

	private void validateNoDuplicateLogicalRelation(Long datasourceId, LogicalRelation logicalRelation,
			Long excludeId) {
		int exists = excludeId == null
				? logicalRelationMapper.checkExists(datasourceId, logicalRelation.getSourceTableName(),
						logicalRelation.getSourceColumnName(), logicalRelation.getTargetTableName(),
						logicalRelation.getTargetColumnName())
				: logicalRelationMapper.checkExistsExcludingId(datasourceId, logicalRelation.getSourceTableName(),
						logicalRelation.getSourceColumnName(), logicalRelation.getTargetTableName(),
						logicalRelation.getTargetColumnName(), excludeId);
		if (exists > 0) {
			throw CheckedException.badRequest("该逻辑外键关系已存在, datasourceId=" + datasourceId);
		}
	}

	private String buildLogicalRelationKey(LogicalRelation logicalRelation) {
		return logicalRelation.getSourceTableName() + "|" + logicalRelation.getSourceColumnName() + "|"
				+ logicalRelation.getTargetTableName() + "|" + logicalRelation.getTargetColumnName();
	}

	/**
	 * 完整 JDBC 串 + 账号 + 租户 ID 合起来等于内网库拓扑与账号枚举，出口只保留
	 * host/port/databaseName 供前端拼展示串，其余按「置空 + 是否已配置」或掩码回显。
	 */
	private Datasource toApiDatasource(Datasource datasource) {
		if (datasource == null) {
			return null;
		}
		datasource.setPasswordConfigured(cryptoService.hasConfiguredSecret(datasource.getPassword()));
		datasource.setPassword(null);
		datasource.setConnectionUrlConfigured(StringUtils.isNotBlank(datasource.getConnectionUrl()));
		datasource.setConnectionUrl(null);
		datasource.setUsername(ApiKeyUtil.mask(datasource.getUsername()));
		return datasource;
	}

	/**
	 * 库内原始行 → 对外响应。与 {@link #toApiDatasource} 同一套脱敏口径，区别是不改动实体实例，
	 * 且 {@code tenantId} / {@code password} / {@code connectionUrl} 在类型上就不存在，不依赖注解兜底。
	 *
	 * <p>入参必须是未脱敏的原始行：{@code username} 在这里做唯一一次掩码。
	 */
	private DatasourceResp toResp(Datasource datasource) {
		if (datasource == null) {
			return null;
		}
		return DatasourceResp.builder()
			.id(datasource.getId())
			.createBy(datasource.getCreateBy())
			.createName(datasource.getCreateName())
			.createTime(datasource.getCreateTime())
			.lastModifyTime(datasource.getLastModifyTime())
			.lastModifyBy(datasource.getLastModifyBy())
			.lastModifyName(datasource.getLastModifyName())
			.deleted(datasource.getDeleted())
			.name(datasource.getName())
			.type(datasource.getType())
			.host(datasource.getHost())
			.port(datasource.getPort())
			.databaseName(datasource.getDatabaseName())
			.username(ApiKeyUtil.mask(datasource.getUsername()))
			.passwordConfigured(cryptoService.hasConfiguredSecret(datasource.getPassword()))
			.connectionUrlConfigured(StringUtils.isNotBlank(datasource.getConnectionUrl()))
			.status(datasource.getStatus())
			.testStatus(datasource.getTestStatus())
			.description(datasource.getDescription())
			.creatorId(datasource.getCreatorId())
			.build();
	}

	private Datasource toRuntimeDatasource(Datasource datasource) {
		if (datasource == null) {
			return null;
		}
		Datasource runtime = Datasource.builder()
			.id(datasource.getId())
			.tenantId(datasource.getTenantId())
			.name(datasource.getName())
			.type(datasource.getType())
			.host(datasource.getHost())
			.port(datasource.getPort())
			.databaseName(datasource.getDatabaseName())
			.username(datasource.getUsername())
			.password(cryptoService.decryptRuntimeSecret(datasource.getPassword()))
			.connectionUrl(datasource.getConnectionUrl())
			.status(datasource.getStatus())
			.testStatus(datasource.getTestStatus())
			.description(datasource.getDescription())
			.creatorId(datasource.getCreatorId())
			.build();
		runtime.setPasswordConfigured(cryptoService.hasConfiguredSecret(datasource.getPassword()));
		return runtime;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden();
		}
		if (StringUtils.isBlank(tenantId)) {
			throw CheckedException.forbidden();
		}
		return tenantId;
	}

	private DataRefType inferDataRefType(String columnName) {
		String normalized = Optional.ofNullable(columnName).orElse("").toLowerCase(Locale.ROOT);
		normalized = normalized.replace("_", "").replace("-", "");
		if (normalized.equals("companyid") || normalized.endsWith("companyid")) {
			return DataRefType.COMPANY;
		}
		if (normalized.equals("siteid") || normalized.endsWith("siteid")) {
			return DataRefType.SITE;
		}
		if (normalized.equals("operationsiteid") || normalized.endsWith("operationsiteid")) {
			return DataRefType.OPERATION_SITE;
		}
		if (normalized.equals("projectid") || normalized.equals("twoprojectid") || normalized.endsWith("twoprojectid")) {
			return DataRefType.TWO_PROJECT;
		}
		if (normalized.equals("investorid") || normalized.endsWith("investorid")) {
			return DataRefType.INVESTOR;
		}
		if (normalized.equals("userid") || normalized.equals("createby") || normalized.endsWith("userid")) {
			return DataRefType.USER;
		}
		return null;
	}

	private String inferJavaType(String columnType) {
		String normalized = Optional.ofNullable(columnType).orElse("").toLowerCase(Locale.ROOT);
		if (normalized.contains("bigint") || normalized.contains("long")) {
			return "Long";
		}
		if (normalized.contains("int") || normalized.contains("number")) {
			return "Integer";
		}
		return "String";
	}

}
