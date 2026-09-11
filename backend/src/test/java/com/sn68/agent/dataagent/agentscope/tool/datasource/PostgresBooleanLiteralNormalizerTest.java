/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgresBooleanLiteralNormalizerTest {

	@Test
	void rewritesDeletedEqualsZeroWhenTypeIsBoolean() {
		String rewritten = PostgresBooleanLiteralNormalizer.rewrite(
				"select customer_name from orders where deleted = 0", Map.of("orders.deleted", "boolean"));

		assertTrue(rewritten.toLowerCase().contains("deleted = false"), rewritten);
		assertFalse(rewritten.contains("deleted = 0"), rewritten);
	}

	@Test
	void rewritesDeletedEqualsOneToTrue() {
		String rewritten = PostgresBooleanLiteralNormalizer.rewrite("select id from orders where deleted = 1",
				Map.of("deleted", "boolean"));

		assertTrue(rewritten.toLowerCase().contains("deleted = true"), rewritten);
	}

	@Test
	void rewritesWrappedLimitSubquery() {
		String sql = "SELECT * FROM (SELECT customer_name FROM orders WHERE deleted = 0) dataagent_safe_limit LIMIT 11";
		String rewritten = PostgresBooleanLiteralNormalizer.rewrite(sql, Map.of("orders.deleted", "bool"));

		assertTrue(rewritten.toLowerCase().contains("deleted = false"), rewritten);
		assertTrue(rewritten.contains("dataagent_safe_limit"), rewritten);
	}

	@Test
	void rewritesAliasedDeletedWithoutQualifiedTypeKey() {
		String rewritten = PostgresBooleanLiteralNormalizer.rewrite(
				"select o.customer_name from orders o where o.deleted = 0", Map.of("orders.deleted", "boolean"));

		assertTrue(rewritten.toLowerCase().contains("deleted = false"), rewritten);
	}

	@Test
	void leavesNumericZeroUnchanged() {
		String sql = "select customer_name from orders where amount = 0";
		assertEquals(sql, PostgresBooleanLiteralNormalizer.rewrite(sql, Map.of("orders.amount", "number")));
	}

	@Test
	void leavesAlreadyBooleanLiteralUnchanged() {
		String sql = "select customer_name from orders where deleted = false";
		assertEquals(sql, PostgresBooleanLiteralNormalizer.rewrite(sql, Map.of("orders.deleted", "boolean")));
	}

	@Test
	void fallsBackToDeletedColumnWhenTypeUnknown() {
		String rewritten = PostgresBooleanLiteralNormalizer.rewrite("select id from orders where deleted = 0",
				Map.of());

		assertTrue(rewritten.toLowerCase().contains("deleted = false"), rewritten);
	}

	@Test
	void doesNotRewriteDeletedWhenTypeIsInteger() {
		String sql = "select id from orders where deleted = 0";
		assertEquals(sql, PostgresBooleanLiteralNormalizer.rewrite(sql, Map.of("orders.deleted", "int4")));
	}

	@Test
	void detectsPostgresFamilyDialects() {
		assertTrue(PostgresBooleanLiteralNormalizer.isPostgresFamily("PostgreSQL"));
		assertTrue(PostgresBooleanLiteralNormalizer.isPostgresFamily("postgresql"));
		assertFalse(PostgresBooleanLiteralNormalizer.isPostgresFamily("MySQL"));
		assertFalse(PostgresBooleanLiteralNormalizer.isPostgresFamily(null));
	}

	@Test
	void detectsBooleanIntegerMismatchFromPgMessage() {
		assertTrue(PostgresBooleanLiteralNormalizer
			.isBooleanIntegerMismatch(new SQLException("错误: 操作符不存在: boolean = integer")));
		assertFalse(PostgresBooleanLiteralNormalizer.isBooleanIntegerMismatch(new SQLException("column does not exist")));
	}

}
