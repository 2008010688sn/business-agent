/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.routing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class RoutePendingSchemaContractTest {

	/**
	 * 「全新建库」路径的契约，<b>今天即可验证</b>：{@code schema-v2.sql} 与 {@code verify-v2.sql}
	 * 必须带上编排续跑五列、对应 CHECK 与 {@code data_agent_route_pending_idx_parent_step} 索引，
	 * 且 {@code interaction_type} 已退役 {@code ROUTE_RECOVERY}（历史行在 Java 侧已无代码可解释，
	 * {@code rg ROUTE_RECOVERY} 仅剩 SQL 与本测试命中）。
	 * <p>
	 * 存量库的 ALTER 迁移<b>尚不存在</b>，那半边断言拆到
	 * {@link #continuationMigrationUpgradesExistingDatabases()} 并挂 {@code @Disabled}，
	 * 以 skipped 的形式把缺口留在报告里。
	 */
	@Test
	void continuationFieldsRetireRecoveryInteractionInFreshInstallContract() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();
		String schema = Files.readString(moduleDir.resolve("src/main/resources/dataagent/sql/pg/schema-v2.sql"),
				StandardCharsets.UTF_8);
		String verifier = Files.readString(moduleDir.resolve("src/main/resources/dataagent/sql/pg/verify-v2.sql"),
				StandardCharsets.UTF_8);
		Path obsoleteMigration = moduleDir.getParent()
			.resolve("服务治理/06-ai/20260804_route_recovery_pending.sql");

		assertTrue(schema.contains("parent_run_id BIGINT"));
		assertTrue(schema.contains("parent_step_id BIGINT"));
		assertTrue(schema.contains("execution_state VARCHAR(32) NOT NULL DEFAULT 'PENDING'"));
		assertTrue(schema.contains("result_reference VARCHAR(128)"));
		assertTrue(schema.contains("legacy_interaction_type VARCHAR(32)"));
		assertTrue(schema.contains("data_agent_route_pending_execution_state_ck"));
		assertTrue(schema.contains("data_agent_route_pending_legacy_interaction_ck"));
		assertTrue(schema.contains("data_agent_route_pending_parent_binding_ck"));
		assertTrue(schema.contains("data_agent_route_pending_idx_parent_step"));
		assertTrue(schema.contains("parent_run_id IS NOT NULL AND parent_step_id IS NOT NULL"));
		assertTrue(schema.contains("interaction_type IN ('BUSINESS_CLARIFICATION', 'ROUTE_CLARIFICATION', 'CONFIRMATION')"));
		assertFalse(schema.contains("interaction_type IN ('BUSINESS_CLARIFICATION', 'ROUTE_CLARIFICATION', 'CONFIRMATION', 'ROUTE_RECOVERY')"));

		assertTrue(verifier.contains("data_agent_route_pending_execution_state_ck"));
		assertTrue(verifier.contains("data_agent_route_pending_legacy_interaction_ck"));
		assertTrue(verifier.contains("data_agent_route_pending_parent_binding_ck"));
		assertTrue(verifier.contains("data_agent_route_pending_idx_parent_step"));
		assertTrue(verifier.contains("execution_state NOT IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'FORWARDED', 'EXPIRED')"));
		assertFalse(verifier.contains("interaction_type IN ('BUSINESS_CLARIFICATION', 'ROUTE_CLARIFICATION', 'CONFIRMATION', 'ROUTE_RECOVERY')"));
		assertFalse(verifier.contains("route-recovery/v1"));

		assertFalse(Files.exists(obsoleteMigration));
	}

	/**
	 * <b>已知缺口（有意保留为 skipped，勿删断言、勿改成指向别的文件，更不要为了转绿代写迁移）</b>：
	 * {@code 服务治理/06-ai/20260804_route_pending_continuation.sql} <b>从未被创建过</b>
	 * —— 全仓库 {@code git log --diff-filter=A} 查不到该文件的任何新增记录，也不存在改名/移动的前身。
	 * <p>
	 * 后果是：{@code schema-v2.sql} 的「全新建库」路径已带上编排续跑五列与退役后的三值
	 * {@code interaction_type} 约束（上一条用例守着），但<b>存量库缺一份对应的 ALTER 迁移</b>——
	 * 已升级过的环境没有 {@code parent_run_id / parent_step_id / execution_state /
	 * result_reference / legacy_interaction_type} 五列，历史 {@code ROUTE_RECOVERY} 行 Java 侧已无法解读。
	 * <p>
	 * 迁移属 L2（PostgreSQL schema + 数据改写），有两处<b>无法从仓库安全推断</b>的取值，必须由维护者拍板：
	 * <ol>
	 * <li>退役 {@code ROUTE_RECOVERY} 行的 {@code interaction_type} 占位值取哪个
	 * —— 受 {@code data_agent_route_pending_round_ck} 约束，只有 {@code CONFIRMATION}
	 * 允许 {@code clarification_round = 0}，但这属于历史数据语义定性。</li>
	 * <li>迁移前已是 {@code CONSUMED}/{@code EXPIRED} 的行，{@code execution_state} 该回填成什么
	 * —— 落库默认值 {@code PENDING} 与其终态自相矛盾，而真实结果已不可考。</li>
	 * </ol>
	 * 其余部分是确定的：列定义、CHECK 与 {@code data_agent_route_pending_idx_parent_step}
	 * 照抄 {@code schema-v2.sql}；{@code interaction_type} 约束需先 {@code NOT VALID} 建、
	 * 改完数据再 {@code VALIDATE CONSTRAINT} —— {@code verify-v2.sql} 的
	 * {@code NOT actual.convalidated} 要求它最终必须是已验证态。
	 * <p>
	 * 因此这里保留完整断言但挂 {@code @Disabled}：迁移一旦由维护者/运维落地，去掉注解即可当验收用。
	 * 详见 {@code 服务治理/06-ai/AI模块整改治理PRD.md} F-2（88 个迁移脚本无 Flyway/人工执行）与 F-3。
	 */
	@Disabled("""
			缺失 服务治理/06-ai/20260804_route_pending_continuation.sql（存量库补齐编排续跑五列的 ALTER 迁移，从未被创建）。\
			不代写的原因：两个取值无法从仓库安全推断——退役 ROUTE_RECOVERY 行的 interaction_type 占位值、\
			已是 CONSUMED/EXPIRED 行的 execution_state 回填值——且本地无 PostgreSQL 可验证。\
			迁移由运维落地后去掉本注解即可当验收。详见 服务治理/06-ai/AI模块整改治理PRD.md 的 F-2 与 F-3。""")
	@Test
	void continuationMigrationUpgradesExistingDatabases() throws Exception {
		Path moduleDir = Path.of("").toAbsolutePath();
		String migration = Files.readString(moduleDir.getParent()
			.resolve("服务治理/06-ai/20260804_route_pending_continuation.sql"), StandardCharsets.UTF_8);

		assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS parent_run_id BIGINT"));
		assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS parent_step_id BIGINT"));
		assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS execution_state VARCHAR(32)"));
		assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS result_reference VARCHAR(128)"));
		assertTrue(migration.contains("ADD COLUMN IF NOT EXISTS legacy_interaction_type VARCHAR(32)"));
		assertTrue(migration.contains("legacy_interaction_type = 'ROUTE_RECOVERY'"));
		assertTrue(migration.contains("status = CASE WHEN status = 'PENDING' THEN 'EXPIRED' ELSE status END"));
		assertTrue(migration.contains("execution_state = 'CANCELLED'"));
		assertTrue(migration.contains("data_agent_route_pending_parent_binding_ck"));
		assertTrue(migration.contains("data_agent_route_pending_idx_parent_step"));
		assertTrue(migration.contains("CHECK (interaction_type IN ('BUSINESS_CLARIFICATION', 'ROUTE_CLARIFICATION', 'CONFIRMATION')) NOT VALID"));
		assertFalse(migration.contains("route-recovery/v1"));
	}

}
