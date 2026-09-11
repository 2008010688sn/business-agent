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

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowComparisonAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.ShadowMismatchSample;
import com.sn68.agent.dataagent.authorization.observability.dto.TaskSkipAggregate;
import com.sn68.agent.dataagent.authorization.observability.dto.WorkItemLatencyAggregate;
import com.sn68.agent.dataagent.authorization.observability.repository.AuthorizationObservabilityMapper;
import com.sn68.agent.dataagent.authorization.pep.ShadowComparisonStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 影子差异比对报告生成器（PR-10 交付物 4 核心）。
 *
 * <p>聚合 {@code agent_runtime_event}（event_type=AUTHORIZATION_DECISION）窗口内的
 * comparison_status，按租户产出 MATCHED/MISMATCHED/ORIGINAL_ONLY 汇总与差异率
 * （ENFORCE 门禁阈值 &lt;0.1% 的数据来源）；顺带聚合任务侧观测（SKIPPED 槽位冲突 +
 * 受理延迟 P95）。产出四路：Prometheus Gauge（告警数据源）、JSON+CSV 报告文件
 * （可配置目录）、内存最新报告（REST 查询）、结构化日志摘要。</p>
 *
 * <p>生成全程幂等可重入：只读聚合，重复执行只覆盖内存与文件（文件名带生成时间戳，
 * 历史报告保留）。文件写失败 quiet 不抛——报告仍可通过指标与 REST 获取。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShadowDiffReportGenerator {

	/**
	 * ENFORCE 门禁差异率阈值：0.1%（v1.2 清单 PR-9 前置门禁口径）。
	 */
	public static final double ENFORCE_GATE_THRESHOLD = 0.001D;

	/**
	 * 差异样本 CSV 表头（清单钦定四字段 + 租户/原因码定位列）。
	 */
	private static final String CSV_HEADER = "tenant_id,decision_id,policy_hash,reason_code,comparison_status,"
			+ "comparison_timestamp";

	private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
		.withZone(java.time.ZoneOffset.UTC);

	private final AuthorizationObservabilityMapper observabilityMapper;

	private final AuthorizationMetrics metrics;

	private final AuthorizationObservabilityProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * 最新报告（REST 查询口径；首次生成前为 null）。
	 */
	private final AtomicReference<ShadowDiffReport> latestReport = new AtomicReference<>();

	/**
	 * 按配置窗口生成报告（观测作业与手动重建入口）：[now - window, now)。
	 *
	 * @return 报告；reportEnabled=false 或窗口非法时返回 null
	 */
	public ShadowDiffReport generateWindowReport() {
		if (!properties.isReportEnabled()) {
			log.debug("授权影子差异报告未开启(reportEnabled=false), 跳过本轮生成");
			return null;
		}
		Instant to = Instant.now();
		Instant from = to.minus(properties.getReportWindow());
		return generate(from, to);
	}

	/**
	 * 按显式窗口生成报告（幂等，只读聚合）。
	 *
	 * @param from 窗口起点（含）
	 * @param to   窗口终点（不含）
	 * @return 报告（聚合查询异常时上抛由调用方裁决：观测作业 quiet、手动重建透出）
	 */
	public synchronized ShadowDiffReport generate(Instant from, Instant to) {
		Map<String, TenantAccumulator> byTenant = pivotByTenant(
				observabilityMapper.aggregateShadowComparison(from, to));
		List<ShadowMismatchSample> samples = observabilityMapper.listMismatchSamples(from, to,
				Math.max(1, properties.getMismatchSampleLimit()));

		List<ShadowDiffReport.TenantSummary> summaries = byTenant.entrySet()
			.stream()
			.map(entry -> entry.getValue().toSummary(entry.getKey(), ENFORCE_GATE_THRESHOLD))
			.sorted(Comparator.comparingDouble(ShadowDiffReport.TenantSummary::getMismatchRate).reversed()
				.thenComparing(ShadowDiffReport.TenantSummary::getTenantId))
			.toList();
		long totalEvents = summaries.stream().mapToLong(ShadowDiffReport.TenantSummary::getTotal).sum();

		publishGauges(summaries);

		ShadowDiffReport.ShadowDiffReportBuilder builder = ShadowDiffReport.builder()
			.windowFrom(from)
			.windowTo(to)
			.generatedAt(Instant.now())
			.enforceGateThreshold(ENFORCE_GATE_THRESHOLD)
			.totalEvents(totalEvents)
			.tenants(summaries)
			.mismatchSamples(samples)
			.taskObservation(properties.isTaskObservationEnabled() ? buildTaskObservation(from, to) : null);

		ShadowDiffReport report = builder.build();
		latestReport.set(report);
		writeReportFilesQuietly(report);
		log.info("授权影子差异报告已生成. windowFrom={}, windowTo={}, totalEvents={}, tenants={}, "
				+ "mismatched={}, originalOnly={}, sampleCount={}, gateFailedTenants={}",
				from, to, totalEvents, summaries.size(),
				summaries.stream().mapToLong(ShadowDiffReport.TenantSummary::getMismatched).sum(),
				summaries.stream().mapToLong(ShadowDiffReport.TenantSummary::getOriginalOnly).sum(),
				samples.size(),
				summaries.stream()
					.filter(summary -> !summary.isEnforceGatePassed())
					.map(ShadowDiffReport.TenantSummary::getTenantId)
					.toList());
		return report;
	}

	/**
	 * 最新报告（REST 查询口径；尚未生成时为 null，由调用方裁决提示）。
	 */
	public ShadowDiffReport latestReport() {
		return latestReport.get();
	}

	/**
	 * 聚合行透视：租户 → 三状态累加器（保持租户升序稳定输出）。
	 */
	private Map<String, TenantAccumulator> pivotByTenant(List<ShadowComparisonAggregate> aggregates) {
		Map<String, TenantAccumulator> byTenant = new TreeMap<>();
		for (ShadowComparisonAggregate aggregate : aggregates) {
			String tenant = String.valueOf(aggregate.getTenantId() == null ? 0L : aggregate.getTenantId());
			TenantAccumulator accumulator = byTenant.computeIfAbsent(tenant, key -> new TenantAccumulator());
			accumulator.add(aggregate.getComparisonStatus(), aggregate.getTotal());
		}
		return byTenant;
	}

	/**
	 * 任务侧观测段：SKIPPED 聚合（槽位冲突归类）+ 受理延迟 P95。
	 */
	private ShadowDiffReport.TaskObservation buildTaskObservation(Instant from, Instant to) {
		List<TaskSkipAggregate> skips = observabilityMapper.aggregateTaskSkips(from, to);
		long slotConflict = skips.stream()
			.filter(skip -> "SLOT_CONFLICT".equals(skip.getSkipReason()))
			.mapToLong(TaskSkipAggregate::getTotal)
			.sum();
		long other = skips.stream()
			.filter(skip -> !"SLOT_CONFLICT".equals(skip.getSkipReason()))
			.mapToLong(TaskSkipAggregate::getTotal)
			.sum();

		WorkItemLatencyAggregate latency = observabilityMapper.aggregateWorkItemCreateLatency(from, to);
		double p95 = latency == null || latency.getP95Seconds() == null ? 0D : latency.getP95Seconds();
		long sampleTotal = latency == null || latency.getSampleTotal() == null ? 0L : latency.getSampleTotal();

		metrics.updateWorkItemCreateP95Seconds(p95);
		for (TaskSkipAggregate skip : skips) {
			metrics.updateTaskSkippedWindow(skip.getTenantId(), skip.getSkipReason(),
					skip.getTotal() == null ? 0L : skip.getTotal());
		}

		return ShadowDiffReport.TaskObservation.builder()
			.skippedTotal(slotConflict + other)
			.slotConflictTotal(slotConflict)
			.otherSkipTotal(other)
			.workItemCreateP95Seconds(p95)
			.workItemSampleTotal(sampleTotal)
			.skipAggregates(skips)
			.build();
	}

	/**
	 * 报告结果回填 Gauge（告警与大盘数据源）：租户差异率 + 三状态窗口计数。
	 */
	private void publishGauges(List<ShadowDiffReport.TenantSummary> summaries) {
		for (ShadowDiffReport.TenantSummary summary : summaries) {
			metrics.updateShadowMismatchRate(summary.getTenantId(), summary.getMismatchRate());
			metrics.updateShadowWindowCount(summary.getTenantId(), "MATCHED", summary.getMatched());
			metrics.updateShadowWindowCount(summary.getTenantId(), "MISMATCHED", summary.getMismatched());
			metrics.updateShadowWindowCount(summary.getTenantId(), "ORIGINAL_ONLY", summary.getOriginalOnly());
		}
	}

	/**
	 * 报告文件输出（quiet）：JSON 完整报告 + CSV 差异样本明细；目录空则跳过。
	 */
	private void writeReportFilesQuietly(ShadowDiffReport report) {
		String outputDir = properties.getReportOutputDir();
		if (StrUtil.isBlank(outputDir)) {
			return;
		}
		try {
			Path directory = Paths.get(outputDir);
			Files.createDirectories(directory);
			String fileTimestamp = FILE_TS.format(report.getGeneratedAt());
			Path jsonFile = directory.resolve("shadow-diff-report-" + fileTimestamp + ".json");
			Files.writeString(jsonFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			Path csvFile = directory.resolve("shadow-diff-report-" + fileTimestamp + ".csv");
			Files.writeString(csvFile, buildSamplesCsv(report), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			log.info("授权影子差异报告文件已输出. jsonFile={}, csvFile={}", jsonFile, csvFile);
		}
		catch (IOException ex) {
			// 报告文件属旁路产物：写失败不影响指标/内存报告/REST 查询
			log.warn("授权影子差异报告文件输出失败(quiet). outputDir={}, errorType={}, errorMessage={}",
					outputDir, ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/**
	 * 差异样本 CSV（表头 + 样本行；无样本时仅表头，保持文件结构稳定可被下游解析）。
	 */
	private String buildSamplesCsv(ShadowDiffReport report) {
		StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
		for (ShadowMismatchSample sample : report.getMismatchSamples()) {
			csv.append(csvCell(sample.getTenantId() == null ? "0" : String.valueOf(sample.getTenantId())))
				.append(',')
				.append(csvCell(sample.getDecisionId()))
				.append(',')
				.append(csvCell(sample.getPolicyHash()))
				.append(',')
				.append(csvCell(sample.getReasonCode()))
				.append(',')
				.append(csvCell(sample.getComparisonStatus()))
				.append(',')
				.append(csvCell(sample.getComparisonTimestamp() == null ? null
						: sample.getComparisonTimestamp().toString()))
				.append('\n');
		}
		return csv.toString();
	}

	/**
	 * CSV 单元格兜底：null 转 N/A；样本字段均为受控字符集（UUID/哈希/枚举码），无需转义展开。
	 */
	private String csvCell(String value) {
		return value == null ? "N/A" : value;
	}

	/**
	 * 租户三状态累加器（透视中间结构）。
	 */
	private static final class TenantAccumulator {

		private long matched;

		private long mismatched;

		private long originalOnly;

		private void add(String comparisonStatus, Long total) {
			long count = total == null ? 0L : total;
			ShadowComparisonStatus status = ShadowComparisonStatus.fromCode(comparisonStatus);
			if (ShadowComparisonStatus.MISMATCHED == status) {
				mismatched += count;
				return;
			}
			if (ShadowComparisonStatus.ORIGINAL_ONLY == status) {
				originalOnly += count;
				return;
			}
			// MATCHED 与未知状态码均按一致口径累计（未知码不构成差异证据，单独暴露在样本定位中）
			matched += count;
		}

		private ShadowDiffReport.TenantSummary toSummary(String tenantId, double gateThreshold) {
			long comparable = matched + mismatched;
			double rate = comparable == 0 ? 0D : (double) mismatched / comparable;
			return ShadowDiffReport.TenantSummary.builder()
				.tenantId(tenantId)
				.total(matched + mismatched + originalOnly)
				.comparable(comparable)
				.matched(matched)
				.mismatched(mismatched)
				.originalOnly(originalOnly)
				.mismatchRate(rate)
				.enforceGatePassed(rate < gateThreshold)
				.build();
		}

	}

}
