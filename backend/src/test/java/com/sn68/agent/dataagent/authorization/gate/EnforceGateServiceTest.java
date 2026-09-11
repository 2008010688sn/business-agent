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
package com.sn68.agent.dataagent.authorization.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateEvaluation;
import com.sn68.agent.dataagent.authorization.gate.dto.EnforceGateOverview;
import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReport;
import com.sn68.agent.dataagent.authorization.observability.ShadowDiffReportGenerator;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PR-9 灰度 ENFORCE 门禁聚焦单测（v1.2 清单 PR-9 验证项「门禁检查表」）。
 *
 * <p>覆盖 fail-closed 语义矩阵：放行 / latest 报告缺失（404 语义）/ 租户样本缺失 /
 * 零可比样本护栏（Chris 复验中危项收口）/ 差异率超标 / 门禁未启用 / 白名单边界 /
 * ENFORCE 切换保护（静默回退 + 未验证租户）。</p>
 *
 * @author Terry (PR-9 灰度 ENFORCE 门禁)
 */
class EnforceGateServiceTest {

	private ShadowDiffReportGenerator reportGenerator;

	private PepAuthorizationProperties pepProperties;

	private DataAgentProperties dataAgentProperties;

	private EnforceGateService gateService;

	@BeforeEach
	void setUp() {
		reportGenerator = mock(ShadowDiffReportGenerator.class);
		pepProperties = new PepAuthorizationProperties();
		dataAgentProperties = new DataAgentProperties();
		gateService = new EnforceGateService(reportGenerator, pepProperties, dataAgentProperties);
		stubReportAbsent();
	}

	@Test
	void evaluateAdmitsTenantPassingGate() {
		stubReport(summary("1", 99L, 0L, 0.001D, true));

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertTrue(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.ADMITTED, evaluation.getDecision());
		assertEquals(99L, evaluation.getComparable());
		assertEquals(0.001D, evaluation.getEnforceGateThreshold());
		assertEquals("1", evaluation.getTenantId());
	}

	@Test
	void evaluateFailClosedWhenLatestReportMissing() {
		// 对应 GET /authorization-shadow-reports/latest 的 404 语义：无报告不放大样
		stubReportAbsent();

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertFalse(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.REJECTED_REPORT_MISSING, evaluation.getDecision());
		assertNull(evaluation.getReportWindowTo());
	}

	@Test
	void evaluateFailClosedWhenTenantAbsentFromReport() {
		stubReport(summary("2", 10L, 0L, 0D, true));

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertFalse(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.REJECTED_TENANT_MISSING, evaluation.getDecision());
	}

	@Test
	void evaluateFailClosedOnZeroComparableSamplesEvenWhenReportGatePassed() {
		// Chris 复验中危项：PR-10 口径 comparable=0 时 gatePassed=true，
		// 门禁必须以 minComparable=1 护栏挡住「有报告但零可比样本」的误放
		stubReport(summary("1", 0L, 0L, 0D, true));

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertFalse(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.REJECTED_INSUFFICIENT_SAMPLES, evaluation.getDecision());
		assertTrue(evaluation.getReason().contains("影子可比样本不足"));
		// comparable 口径 = matched + mismatched（ORIGINAL_ONLY 不进分母）
		assertEquals(0L, evaluation.getComparable());
	}

	@Test
	void evaluateHonorsConfigurableMinComparable() {
		dataAgentProperties.getAuthorization().getEnforceGate().setMinComparable(100L);
		stubReport(summary("1", 50L, 0L, 0D, true));

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertEquals(EnforceGateDecision.REJECTED_INSUFFICIENT_SAMPLES, evaluation.getDecision());
		assertFalse(evaluation.isAllowed());
	}

	@Test
	void evaluateRejectsWhenMismatchRateExceedsThreshold() {
		// 差异率 1/100 = 1% > 0.1%：未满足「影子差异归零后」前置条件
		stubReport(summary("1", 99L, 1L, 0.01D, false));

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertFalse(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.REJECTED_THRESHOLD_EXCEEDED, evaluation.getDecision());
		assertEquals(1L, evaluation.getMismatched());
	}

	@Test
	void evaluateFailClosedWhenGateDisabled() {
		dataAgentProperties.getAuthorization().getEnforceGate().setEnabled(false);

		EnforceGateEvaluation evaluation = gateService.evaluate("1");

		assertFalse(evaluation.isAllowed());
		assertEquals(EnforceGateDecision.REJECTED_GATE_DISABLED, evaluation.getDecision());
	}

	@Test
	void evaluateRejectsBlankTenantId() {
		assertThrows(CheckedException.class, () -> gateService.evaluate(null));
		assertThrows(CheckedException.class, () -> gateService.evaluate("  "));
	}

	@Test
	void admitTenantRegistersAdmittedTenantIdempotently() {
		stubReport(summary("1", 99L, 0L, 0D, true));

		EnforceGateEvaluation first = gateService.admitTenant("1");
		EnforceGateEvaluation second = gateService.admitTenant("1");

		assertTrue(first.isAllowed());
		assertTrue(second.isAllowed());
		assertEquals(List.of("1"), gateService.overview().getAdmittedTenantIds());
	}

	@Test
	void admitTenantThrowsAndSkipsRegistrationOnRejection() {
		stubReport(summary("1", 99L, 1L, 0.01D, false));

		CheckedException exception = assertThrows(CheckedException.class, () -> gateService.admitTenant("1"));

		assertTrue(exception.getMessage().contains("未通过 ENFORCE 门禁"));
		assertTrue(gateService.overview().getAdmittedTenantIds().isEmpty());
	}

	@Test
	void overviewFlagsUnverifiedEnforcedTenantsUntilAdmitted() {
		// 白名单边界：两个租户报告均达标，仅租户 1 完成 admit → 租户 2 为未验证 ENFORCE 租户
		pepProperties.setEnforceTenantIds(List.of("1", "2"));
		stubReport(summary("1", 99L, 0L, 0D, true), summary("2", 50L, 0L, 0D, true));

		gateService.admitTenant("1");
		EnforceGateOverview overview = gateService.overview();

		assertFalse(overview.isRolloutIntegrityPassed());
		assertEquals(List.of("2"), overview.getUnverifiedEnforcedTenantIds());
		assertTrue(overview.getIntegrityActionRequired().contains("2"));
		assertThrows(CheckedException.class, gateService::assertRolloutIntegrity);
	}

	@Test
	void assertRolloutIntegrityFailsOnSilentRollback() {
		// ENFORCE 切换保护：已放行租户脱离 ENFORCE 保护（白名单被移除）构成静默回退
		pepProperties.setEnforceTenantIds(List.of("1"));
		stubReport(summary("1", 99L, 0L, 0D, true));
		gateService.admitTenant("1");

		pepProperties.setEnforceTenantIds(List.of());

		EnforceGateOverview overview = gateService.overview();
		assertEquals(List.of("1"), overview.getSilentRollbackTenantIds());
		CheckedException exception = assertThrows(CheckedException.class, gateService::assertRolloutIntegrity);
		assertTrue(exception.getMessage().contains("静默回退"));
	}

	@Test
	void assertRolloutIntegrityPassesWhenWhitelistFullyAdmitted() {
		pepProperties.setEnforceTenantIds(List.of("1", "2"));
		stubReport(summary("1", 99L, 0L, 0D, true), summary("2", 50L, 0L, 0D, true));

		gateService.admitTenant("1");
		gateService.admitTenant("2");
		gateService.assertRolloutIntegrity();

		EnforceGateOverview overview = gateService.overview();
		assertTrue(overview.isRolloutIntegrityPassed());
		assertNull(overview.getIntegrityActionRequired());
		assertTrue(overview.getSilentRollbackTenantIds().isEmpty());
		assertNotNull(overview.getReportGeneratedAt());
	}

	@Test
	void overviewEvaluatesWhitelistTenantsAndCountsDecisions() {
		pepProperties.setEnforceTenantIds(List.of("1", "2"));
		stubReport(summary("1", 99L, 0L, 0D, true), summary("2", 99L, 1L, 0.01D, false));

		EnforceGateOverview overview = gateService.overview();

		assertEquals(2, overview.getTenantEvaluations().size());
		assertTrue(overview.isReportPresent());
		assertEquals(1L, countOf(overview, EnforceGateDecision.ADMITTED));
		assertEquals(1L, countOf(overview, EnforceGateDecision.REJECTED_THRESHOLD_EXCEEDED));
	}

	private long countOf(EnforceGateOverview overview, EnforceGateDecision decision) {
		return overview.getDecisionCounts()
			.stream()
			.filter(count -> count.getDecision() == decision)
			.mapToLong(EnforceGateOverview.DecisionCount::getCount)
			.sum();
	}

	private void stubReportAbsent() {
		when(reportGenerator.latestReport()).thenReturn(null);
	}

	private void stubReport(ShadowDiffReport.TenantSummary... summaries) {
		ShadowDiffReport report = ShadowDiffReport.builder()
			.windowFrom(Instant.now().minusSeconds(86400))
			.windowTo(Instant.now())
			.generatedAt(Instant.now())
			.enforceGateThreshold(ShadowDiffReportGenerator.ENFORCE_GATE_THRESHOLD)
			.totalEvents(summaries.length)
			.tenants(List.of(summaries))
			.mismatchSamples(List.of())
			.build();
		when(reportGenerator.latestReport()).thenReturn(report);
	}

	private ShadowDiffReport.TenantSummary summary(String tenantId, long matched, long mismatched, double rate,
			boolean gatePassed) {
		return ShadowDiffReport.TenantSummary.builder()
			.tenantId(tenantId)
			.total(matched + mismatched)
			.comparable(matched + mismatched)
			.matched(matched)
			.mismatched(mismatched)
			.originalOnly(0L)
			.mismatchRate(rate)
			.enforceGatePassed(gatePassed)
			.build();
	}

}
