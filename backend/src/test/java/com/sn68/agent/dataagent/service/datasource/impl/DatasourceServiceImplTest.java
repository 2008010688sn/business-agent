/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.datasource.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import com.sn68.agent.dataagent.connector.pool.DBConnectionPoolFactory;
import com.sn68.agent.dataagent.dto.datasource.DatasourceCreateReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceModifyReq;
import com.sn68.agent.dataagent.dto.datasource.DatasourceResp;
import com.sn68.agent.dataagent.entity.Datasource;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DatasourceColumnMapper;
import com.sn68.agent.dataagent.repository.DatasourceMapper;
import com.sn68.agent.dataagent.repository.DatasourcePermissionRuleMapper;
import com.sn68.agent.dataagent.repository.DatasourceTableMapper;
import com.sn68.agent.dataagent.repository.LogicalRelationMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import com.sn68.agent.dataagent.repository.SkillDatasourceMapper;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.datasource.handler.impl.MysqlDatasourceTypeHandler;
import com.sn68.agent.dataagent.service.datasource.handler.registry.DatasourceTypeHandlerRegistry;
import com.sn68.agent.dataagent.service.security.SensitiveConfigCryptoService;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 数据源写入路径用例：连接串只能由服务端生成（PRD S-2），凭据不会被掩码串覆盖（PRD P0-10）。
 */
class DatasourceServiceImplTest {

	private static final String TENANT_ID = "1";

	private static final Long DATASOURCE_ID = 7L;

	private static final String EXPECTED_MYSQL_URL = "jdbc:mysql://db.internal:3306/analytics"
			+ "?useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull"
			+ "&transformedBitIsBoolean=true&useSSL=false&serverTimezone=Asia/Shanghai";

	private final DatasourceMapper datasourceMapper = mock(DatasourceMapper.class);

	private final DBConnectionPoolFactory poolFactory = mock(DBConnectionPoolFactory.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	// 本类断言的是连接串组装与凭据保留，落库值需保持明文可比；加密默认已改为开启，这里显式关掉。
	// 加密默认值与缺 key 时的启动失败由 SensitiveConfigCryptoServiceTest 覆盖。
	private final SensitiveConfigCryptoService cryptoService = new SensitiveConfigCryptoService(
			cryptoDisabledProperties());

	private final DatasourceServiceImpl service = new DatasourceServiceImpl(datasourceMapper,
			mock(DatasourceTableMapper.class), mock(DatasourceColumnMapper.class),
			mock(DatasourcePermissionRuleMapper.class), mock(SkillDatasourceMapper.class), mock(DataAgentService.class),
			mock(LogicalRelationMapper.class), mock(SemanticModelMapper.class), mock(DataAgentSkillMapper.class),
			poolFactory, mock(AccessorFactory.class),
			new DatasourceTypeHandlerRegistry(List.of(new MysqlDatasourceTypeHandler())),
			mock(AgentVectorStoreService.class), cryptoService, authenticationContext, mock(TransactionTemplate.class));

	DatasourceServiceImplTest() {
		when(authenticationContext.tenantId()).thenReturn(TENANT_ID);
	}

	private static DataAgentProperties cryptoDisabledProperties() {
		DataAgentProperties properties = new DataAgentProperties();
		properties.getCrypto().setEnabled(false);
		return properties;
	}

	/**
	 * S-2 的原始攻击载荷：请求体里带 {@code connectionUrl}，随后点「测试连接」让服务去连恶意 MySQL。
	 * 现在该字段已不在入参契约内，未知属性被全局忽略，落库的只能是模板生成的连接串。
	 */
	@Test
	void createIgnoresCallerSuppliedConnectionUrlAndBuildsItServerSide() throws Exception {
		String exploitBody = """
				{
				  "name": "analytics",
				  "type": "mysql",
				  "host": "db.internal",
				  "port": 3306,
				  "databaseName": "analytics",
				  "username": "analytics_ro",
				  "password": "s3cret-value",
				  "connectionUrl": "jdbc:mysql://attacker/x?autoDeserialize=true&allowLoadLocalInfile=true"
				}
				""";
		DatasourceCreateReq request = new ObjectMapper()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
			.readValue(exploitBody, DatasourceCreateReq.class);

		service.createDatasource(request);

		Datasource inserted = captureInserted();
		assertEquals(EXPECTED_MYSQL_URL, inserted.getConnectionUrl());
		assertFalse(inserted.getConnectionUrl().contains("attacker"));
		assertFalse(inserted.getConnectionUrl().contains("autoDeserialize"));
		assertEquals(TENANT_ID, inserted.getTenantId());
	}

	@Test
	void createRequestHasNoConnectionUrlComponent() {
		assertTrue(List.of(DatasourceCreateReq.class.getRecordComponents())
			.stream()
			.noneMatch(component -> "connectionUrl".equals(component.getName())));
		assertTrue(List.of(DatasourceModifyReq.class.getRecordComponents())
			.stream()
			.noneMatch(component -> "connectionUrl".equals(component.getName())));
		assertTrue(List.of(DatasourceResp.class.getDeclaredFields())
			.stream()
			.noneMatch(field -> "connectionUrl".equals(field.getName()) || "password".equals(field.getName())
					|| "tenantId".equals(field.getName())));
	}

	@Test
	void createMasksCredentialsInTheResponse() {
		DatasourceResp response = service.createDatasource(createReq("db.internal", 3306, "analytics"));

		assertEquals("****s_ro", response.getUsername());
		assertTrue(response.getPasswordConfigured());
		assertTrue(response.getConnectionUrlConfigured());
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "localhost", "169.254.169.254", "100.100.100.200",
			"attacker/x?autoDeserialize=true" })
	void createRejectsUnroutableOrUrlBearingHosts(String host) {
		DatasourceCreateReq request = createReq(host, 3306, "analytics");

		assertThrows(CheckedException.class, () -> service.createDatasource(request));
		verify(datasourceMapper, never()).insert(any(Datasource.class));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 65536 })
	void createRejectsPortsOutOfRange(int port) {
		DatasourceCreateReq request = createReq("db.internal", port, "analytics");

		assertThrows(CheckedException.class, () -> service.createDatasource(request));
		verify(datasourceMapper, never()).insert(any(Datasource.class));
	}

	@Test
	void createRejectsDatabaseNameCarryingDriverParameters() {
		DatasourceCreateReq request = createReq("db.internal", 3306, "x?autoDeserialize=true");

		assertThrows(CheckedException.class, () -> service.createDatasource(request));
	}

	/**
	 * 出参对 username 掩码、对 password 置空，前端原样回填是常态；这两个值绝不能覆盖库内真实凭据。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "****", "", "   ", "已配置" })
	void modifyKeepsStoredCredentialsWhenSubmittedValueIsBlankOrMasked(String submitted) {
		stubExistingDatasource();

		service.updateDatasource(DATASOURCE_ID, modifyReq(submitted, submitted));

		Datasource updated = captureUpdated();
		assertEquals("analytics_ro", updated.getUsername());
		assertEquals("s3cret-value", updated.getPassword());
	}

	@Test
	void modifyWritesNewCredentialsWhenSubmitted() {
		stubExistingDatasource();

		service.updateDatasource(DATASOURCE_ID, modifyReq("reporting_ro", "new-secret"));

		Datasource updated = captureUpdated();
		assertEquals("reporting_ro", updated.getUsername());
		assertEquals("new-secret", updated.getPassword());
	}

	/**
	 * 已接受的行为变更：当初用自定义 connectionUrl 创建的数据源，下次保存会按 host/port/database 重新生成标准串。
	 */
	@Test
	void modifyRegeneratesConnectionUrlAndDropsLegacyCustomOne() {
		Datasource existing = stubExistingDatasource();
		existing.setConnectionUrl("jdbc:mysql://attacker/x?autoDeserialize=true");

		service.updateDatasource(DATASOURCE_ID, modifyReq("****", "****"));

		assertEquals(EXPECTED_MYSQL_URL, captureUpdated().getConnectionUrl());
	}

	@Test
	void modifyRejectsUnknownDatasource() {
		when(datasourceMapper.selectByIdAndTenantId(DATASOURCE_ID, TENANT_ID)).thenReturn(null);

		assertThrows(CheckedException.class,
				() -> service.updateDatasource(DATASOURCE_ID, modifyReq("****", "****")));
	}

	private Datasource stubExistingDatasource() {
		Datasource existing = Datasource.builder()
			.id(DATASOURCE_ID)
			.tenantId(TENANT_ID)
			.name("analytics")
			.type("mysql")
			.host("db.internal")
			.port(3306)
			.databaseName("analytics")
			.username("analytics_ro")
			.password("s3cret-value")
			.connectionUrl(EXPECTED_MYSQL_URL)
			.status("active")
			.testStatus("success")
			.build();
		when(datasourceMapper.selectByIdAndTenantId(DATASOURCE_ID, TENANT_ID)).thenReturn(existing);
		return existing;
	}

	private DatasourceCreateReq createReq(String host, int port, String databaseName) {
		return new DatasourceCreateReq("analytics", "mysql", host, port, databaseName, "analytics_ro", "s3cret-value",
				null, null);
	}

	private DatasourceModifyReq modifyReq(String username, String password) {
		return new DatasourceModifyReq("analytics", "mysql", "db.internal", 3306, "analytics", username, password, null,
				null);
	}

	private Datasource captureInserted() {
		ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
		verify(datasourceMapper).insert(captor.capture());
		return captor.getValue();
	}

	private Datasource captureUpdated() {
		ArgumentCaptor<Datasource> captor = ArgumentCaptor.forClass(Datasource.class);
		verify(datasourceMapper).updateById(captor.capture());
		return captor.getValue();
	}

}
