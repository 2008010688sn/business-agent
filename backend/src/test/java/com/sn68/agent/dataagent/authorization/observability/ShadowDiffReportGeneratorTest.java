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
package com.sn68.agent.dataagent.authorization.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowComparisonAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowMismatchSample;
import com.sn68.agent.dataagent.authorization.observability.dto.TaskSkipAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.WorkItemLatencyAggregate;
import com.sn68.agent.dataagent.authorization.observability.repository.AuthorizationObservabilityMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 影子差异报告生成器聚焦单测（PR-10 交付物 4：差异率口径 / Gauge 发布 / 报告文件输出）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
class ShadowDiffReportGeneratorTest {

	private AuthorizationObservabilityMapper mapper;

	private SimpleMeterRegistry registry;

	private AuthorizationMetrics metrics;

	private AuthorizationObservabilityProperties properties;

	private ShadowDiffReportGenerator generator;

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@BeforeEach
	void setUp() {
		mapper = mock(AuthorizationObservabilityMapper.class);
		registry = new SimpleMeterRegistry();
		metrics = new AuthorizationMetrics(registry);
		properties = new AuthorizationObservabilityProperties();
		// 默认不落盘（避免用例向工程目录写报告文件），文件输出用例显式指定 @TempDir
		properties.setReportOutputDir("");
		generator = new ShadowDiffReportGenerator(mapper, metrics, properties, objectMapper);
		stubEmptyWindow();
	}

	@Test
	void generatePivotsTenantsAndComputesGateRate() {
		when(mapper.aggregateShadowComparison(any(), any())).thenReturn(List.of(
				row(1L, "MATCHED", 99L),
				row(1L, "MISMATCHED", 1L),
				row(2L, "ORIGINAL_ONLY", 5L),
				row(3L, "MATCHED", 10L),
				row(3L, null, 5L)));

		ShadowDiffReport report = generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		assertEquals(120L, report.getTotalEvents());
		assertEquals(3, report.getTenants().size());
		// 排序：差异率降序，租户 1（0.01）居首
		ShadowDiffReport.TenantSummary worst = report.getTenants().get(0);
		assertEquals("1", worst.getTenantId());
		assertEquals(100L, worst.getComparable());
		assertEquals(99L, worst.getMatched());
		assertEquals(1L, worst.getMismatched());
		assertEquals(0.01D, worst.getMismatchRate());
		assertFalse(worst.isEnforceGatePassed());
		// ORIGINAL_ONLY 不进差异率分母：comparable=0 → rate=0 且门禁通过
		ShadowDiffReport.TenantSummary originalOnlyTenant = tenantOf(report, "2");
		assertEquals(5L, originalOnlyTenant.getOriginalOnly());
		assertEquals(0L, originalOnlyTenant.getComparable());
		assertEquals(0D, originalOnlyTenant.getMismatchRate());
		assertTrue(originalOnlyTenant.isEnforceGatePassed());
		// 历史空明细（状态码缺失）按 MATCHED 口径累计，不构成差异证据
		ShadowDiffReport.TenantSummary unknownTenant = tenantOf(report, "3");
		assertEquals(15L, unknownTenant.getMatched());
		assertTrue(unknownTenant.isEnforceGatePassed());
	}

	@Test
	void generatePublishesGaugesForAlertAndDashboard() {
		when(mapper.aggregateShadowComparison(any(), any()))
			.thenReturn(List.of(row(1L, "MATCHED", 99L), row(1L, "MISMATCHED", 1L)));

		generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		// 告警数据链路：P2 差异率 Gauge（ENFORCE 门禁阈值 0.1% 比对对象）
		assertEquals(0.01D, metrics.gaugeValue(AuthorizationMetrics.METRIC_SHADOW_MISMATCH_RATE, "tenant", "1"));
		assertEquals(0.01D,
				registry.get(AuthorizationMetrics.METRIC_SHADOW_MISMATCH_RATE).tag("tenant", "1").gauge().value());
		// 大盘数据链路：三状态窗口计数快照
		assertEquals(99D,
				metrics.gaugeValue(AuthorizationMetrics.METRIC_SHADOW_WINDOW_COUNT, "tenant", "1", "status", "MATCHED"));
		assertEquals(1D, metrics.gaugeValue(AuthorizationMetrics.METRIC_SHADOW_WINDOW_COUNT, "tenant", "1",
				"status", "MISMATCHED"));
		assertEquals(0D, metrics.gaugeValue(AuthorizationMetrics.METRIC_SHADOW_WINDOW_COUNT, "tenant", "1",
				"status", "ORIGINAL_ONLY"));
	}

	@Test
	void generateWritesJsonAndCsvReportFiles(@TempDir Path tempDir) throws IOException {
		properties.setReportOutputDir(tempDir.toString());
		when(mapper.aggregateShadowComparison(any(), any()))
			.thenReturn(List.of(row(1L, "MATCHED", 9L), row(1L, "MISMATCHED", 1L)));
		when(mapper.listMismatchSamples(any(), any(), anyInt())).thenReturn(List.of(sample("dec-1", "hash-1")));

		generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		Path jsonFile = soleFile(tempDir, ".json");
		Path csvFile = soleFile(tempDir, ".csv");
		// JSON 完整报告（Instant 经 JavaTimeModule 序列化）
		JsonNode node = objectMapper.readTree(Files.readString(jsonFile));
		assertEquals(10L, node.get("totalEvents").asLong());
		assertEquals(0.001D, node.get("enforceGateThreshold").asDouble());
		assertEquals(1, node.get("mismatchSamples").size());
		assertEquals("dec-1", node.get("mismatchSamples").get(0).get("decisionId").asText());
		// CSV 差异样本明细（清单钦定表头 + 定位列）
		String csv = Files.readString(csvFile);
		assertTrue(csv.startsWith("tenant_id,decision_id,policy_hash,reason_code,comparison_status,comparison_timestamp"));
		assertTrue(csv.contains("dec-1"));
		assertTrue(csv.contains("hash-1"));
		assertTrue(csv.contains("MISMATCHED"));
	}

	@Test
	void generateSurvivesFileWriteFailureQuietly(@TempDir Path tempDir) throws IOException {
		Path occupied = tempDir.resolve("occupied");
		Files.writeString(occupied, "not-a-directory");
		properties.setReportOutputDir(occupied.toString());
		when(mapper.aggregateShadowComparison(any(), any())).thenReturn(List.of(row(1L, "MATCHED", 1L)));

		ShadowDiffReport report = generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		// 报告文件属旁路产物：写失败不影响指标与内存报告（REST 仍可查）
		assertNotNull(report);
		assertNotNull(generator.latestReport());
	}

	@Test
	void windowReportSkipsWhenDisabled() {
		properties.setReportEnabled(false);
		assertNull(generator.generateWindowReport());
	}

	@Test
	void taskObservationAggregatesSkipsAndLatency() {
		when(mapper.aggregateTaskSkips(any(), any()))
			.thenReturn(List.of(skip("1", "SLOT_CONFLICT", 3L), skip("1", "OTHER", 2L)));
		when(mapper.aggregateWorkItemCreateLatency(any(), any())).thenReturn(latency(0.25D, 50L));

		ShadowDiffReport report = generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		ShadowDiffReport.TaskObservation task = report.getTaskObservation();
		assertNotNull(task);
		assertEquals(5L, task.getSkippedTotal());
		assertEquals(3L, task.getSlotConflictTotal());
		assertEquals(2L, task.getOtherSkipTotal());
		assertEquals(0.25D, task.getWorkItemCreateP95Seconds());
		assertEquals(50L, task.getWorkItemSampleTotal());
		// SKIPPED / 受理延迟观测 Gauge（P3 告警与大盘数据源）
		assertEquals(3D,
				metrics.gaugeValue(AuthorizationMetrics.METRIC_TASK_SKIPPED_WINDOW, "tenant", "1", "reason", "SLOT_CONFLICT"));
		assertEquals(2D,
				metrics.gaugeValue(AuthorizationMetrics.METRIC_TASK_SKIPPED_WINDOW, "tenant", "1", "reason", "OTHER"));
		assertEquals(0.25D, metrics.gaugeValue(AuthorizationMetrics.METRIC_WORKITEM_CREATE_P95));
	}

	@Test
	void taskObservationDisabledOmitsSection() {
		properties.setTaskObservationEnabled(false);

		ShadowDiffReport report = generator.generate(Instant.now().minusSeconds(3600), Instant.now());

		assertNull(report.getTaskObservation());
	}

	@Test
	void latestReportTracksLastGeneration() {
		Instant from = Instant.now().minusSeconds(7200);
		Instant middle = Instant.now().minusSeconds(3600);
		Instant to = Instant.now();

		generator.generate(from, middle);
		generator.generate(middle, to);

		assertEquals(middle, generator.latestReport().getWindowFrom());
		assertEquals(to, generator.latestReport().getWindowTo());
	}

	private void stubEmptyWindow() {
		when(mapper.aggregateShadowComparison(any(), any())).thenReturn(List.of());
		when(mapper.listMismatchSamples(any(), any(), anyInt())).thenReturn(List.of());
		when(mapper.aggregateTaskSkips(any(), any())).thenReturn(List.of());
		when(mapper.aggregateWorkItemCreateLatency(any(), any())).thenReturn(null);
	}

	private ShadowDiffReport.TenantSummary tenantOf(ShadowDiffReport report, String tenantId) {
		return report.getTenants()
			.stream()
			.filter(summary -> tenantId.equals(summary.getTenantId()))
			.findFirst()
			.orElseThrow();
	}

	private Path soleFile(Path directory, String suffix) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			List<Path> matched = files.filter(path -> path.getFileName().toString().endsWith(suffix)).toList();
			assertEquals(1, matched.size(), "目录下应有且仅有一个 " + suffix + " 报告文件");
			return matched.get(0);
		}
	}

	private ShadowComparisonAggregate row(Long tenantId, String status, long total) {
		ShadowComparisonAggregate aggregate = new ShadowComparisonAggregate();
		aggregate.setTenantId(tenantId);
		aggregate.setComparisonStatus(status);
		aggregate.setTotal(total);
		return aggregate;
	}

	private ShadowMismatchSample sample(String decisionId, String policyHash) {
		ShadowMismatchSample mismatchSample = new ShadowMismatchSample();
		mismatchSample.setDecisionId(decisionId);
		mismatchSample.setTenantId(1L);
		mismatchSample.setPolicyHash(policyHash);
		mismatchSample.setReasonCode("POLICY_DENIED");
		mismatchSample.setComparisonStatus("MISMATCHED");
		mismatchSample.setComparisonTimestamp(Instant.now());
		return mismatchSample;
	}

	private TaskSkipAggregate skip(String tenantId, String reason, long total) {
		TaskSkipAggregate taskSkipAggregate = new TaskSkipAggregate();
		taskSkipAggregate.setTenantId(tenantId);
		taskSkipAggregate.setSkipReason(reason);
		taskSkipAggregate.setTotal(total);
		return taskSkipAggregate;
	}

	private WorkItemLatencyAggregate latency(double p95Seconds, long sampleTotal) {
		WorkItemLatencyAggregate workItemLatencyAggregate = new WorkItemLatencyAggregate();
		workItemLatencyAggregate.setP95Seconds(p95Seconds);
		workItemLatencyAggregate.setSampleTotal(sampleTotal);
		return workItemLatencyAggregate;
	}

}
