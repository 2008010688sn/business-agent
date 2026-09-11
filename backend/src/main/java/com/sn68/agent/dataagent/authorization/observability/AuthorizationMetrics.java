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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 授权域 Prometheus 指标记录器（PR-10 可观测性基建，指标挂点唯一实现）。
 *
 * <p>指标口径（注册名 → Prometheus 导出名）：</p>
 * <ul>
 * <li>{@code data.agent.authorization.decision.duration}（Timer，tag: mode）：PDP 决策评估耗时，
 *     客户端百分位 P95/P99，挂点 {@code RuntimePolicyEvaluator.evaluate}；</li>
 * <li>{@code data.agent.authorization.decision.errors}（Counter，tag: mode）：评估异常计数
 *     （ENFORCE 失败关闭观测，SHADOW quiet 旁路同样计数）；</li>
 * <li>{@code data.agent.authorization.decisions}（Counter，tag: scene/mode/reason_code/outcome）：
 *     决策计数，挂点 {@code ShadowRecorder}（全部场景统一汇聚）；</li>
 * <li>{@code data.agent.authorization.shadow.comparison}（Counter，tag: comparison_status/tenant）：
 *     影子比对计数（MATCHED/MISMATCHED/ORIGINAL_ONLY），差异率实时数据源；</li>
 * <li>{@code data.agent.authorization.shadow.mismatch.rate}（Gauge，tag: tenant）：最近报告窗口
 *     差异率，由差异报告作业回填（ENFORCE 门禁阈值 0.1% 的告警数据源）；</li>
 * <li>{@code data.agent.authorization.shadow.window.count}（Gauge，tag: tenant/status）：最近报告
 *     窗口比对计数快照（三状态并列，大盘趋势图数据源）；</li>
 * <li>{@code data.agent.employee.token.issuance.duration}（Timer，tag: method/outcome）：IAM
 *     execution-context 签发耗时（P99 口径），经 BeanPostProcessor 无侵入代理挂点；</li>
 * <li>{@code data.agent.task.run.skipped.window}（Gauge，tag: tenant/reason）：最近报告窗口
 *     SKIPPED 台账行计数（SLOT_CONFLICT/OTHER）；</li>
 * <li>{@code data.agent.task.workitem.create.p95.seconds}（Gauge）：最近报告窗口任务受理延迟
 *     P95（秒），P3 告警（&gt;200ms）数据源；</li>
 * <li>{@code token.budget.exceeded}（Counter，tag: tenant；Prometheus 导出
 *     token_budget_exceeded_total）：Token 预算超限拒绝计数（P1 告警
 *     increase(token_budget_exceeded_total[1h])&gt;0 的唯一数据源），挂点
 *     {@code SingleTurnRuntimeStepExecutor} 预算拒绝路径。</li>
 * </ul>
 *
 * <p>租户 tag 口径与 {@code agent_runtime_event.tenant_id} 数字口径一致（未解析租户落 0）；
 * 全部方法 null 安全：{@link #noop()} 供单测与无注册表场景使用，registry 缺失时静默跳过。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Component
public class AuthorizationMetrics {

	/**
	 * 决策评估耗时（P95/P99 客户端百分位，Prometheus 导出 _seconds）。
	 */
	public static final String METRIC_DECISION_DURATION = "data.agent.authorization.decision.duration";

	/**
	 * 决策评估异常计数（导出 data_agent_authorization_decision_errors_total）。
	 */
	public static final String METRIC_DECISION_ERRORS = "data.agent.authorization.decision.errors";

	/**
	 * 授权决策计数（导出 data_agent_authorization_decisions_total）。
	 */
	public static final String METRIC_DECISIONS = "data.agent.authorization.decisions";

	/**
	 * 影子比对计数（导出 data_agent_authorization_shadow_comparison_total）。
	 */
	public static final String METRIC_SHADOW_COMPARISON = "data.agent.authorization.shadow.comparison";

	/**
	 * 最近报告窗口差异率（按租户）。
	 */
	public static final String METRIC_SHADOW_MISMATCH_RATE = "data.agent.authorization.shadow.mismatch.rate";

	/**
	 * 最近报告窗口比对计数快照（按租户 × 状态）。
	 */
	public static final String METRIC_SHADOW_WINDOW_COUNT = "data.agent.authorization.shadow.window.count";

	/**
	 * IAM 令牌签发耗时（导出 _seconds，P99 口径）。
	 */
	public static final String METRIC_TOKEN_ISSUANCE_DURATION = "data.agent.employee.token.issuance.duration";

	/**
	 * 最近报告窗口 SKIPPED 台账行计数（按租户 × 原因）。
	 */
	public static final String METRIC_TASK_SKIPPED_WINDOW = "data.agent.task.run.skipped.window";

	/**
	 * 最近报告窗口任务受理延迟 P95（秒）。
	 */
	public static final String METRIC_WORKITEM_CREATE_P95 = "data.agent.task.workitem.create.p95.seconds";

	/**
	 * Token 预算超限拒绝计数（Prometheus 导出 token_budget_exceeded_total；告警规则引用的
	 * 清单钚定指标名，注册名按 Micrometer 点号风格命名，导出时自动转下划线并追加 _total）。
	 */
	public static final String METRIC_TOKEN_BUDGET_EXCEEDED = "token.budget.exceeded";

	private static final double P95 = 0.95D;

	private static final double P99 = 0.99D;

	/**
	 * 未解析租户的指标口径（与事件表 tenant_id=0 落库口径一致）。
	 */
	private static final String TENANT_UNRESOLVED = "0";

	private final MeterRegistry meterRegistry;

	/**
	 * Gauge 取值句柄池：key = 指标名 + tag 组合，value 为最近一次报告回填值。
	 * Gauge 注册一次后靠句柄刷新，避免每轮报告重复注册。
	 */
	private final Map<String, AtomicReference<Double>> gaugeHolders = new ConcurrentHashMap<>();

	public AuthorizationMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * 空实现（单测与无注册表场景）：全部方法静默跳过。
	 */
	public static AuthorizationMetrics noop() {
		return new AuthorizationMetrics(null);
	}

	/**
	 * 记录一次授权决策（挂点 ShadowRecorder，全部场景统一汇聚）。
	 *
	 * @param scene      影子场景标识（HOOK_EXECUTE/MEMORY_READ/MEMORY_WRITE/TASK_START 等，可空归 UNKNOWN）
	 * @param mode       生效模式码（SHADOW/ENFORCE，可空归 UNKNOWN）
	 * @param reasonCode PDP 原因码（可空归 UNKNOWN）
	 * @param allowed    PDP 决策结论
	 */
	public void recordDecision(String scene, String mode, String reasonCode, boolean allowed) {
		if (meterRegistry == null) {
			return;
		}
		Counter.builder(METRIC_DECISIONS)
			.tag("scene", valueOrDefault(scene, "UNKNOWN"))
			.tag("mode", valueOrDefault(mode, "UNKNOWN"))
			.tag("reason_code", valueOrDefault(reasonCode, "UNKNOWN"))
			.tag("outcome", allowed ? "ALLOW" : "DENY")
			.register(meterRegistry)
			.increment();
	}

	/**
	 * 记录一次影子比对结论（挂点 ShadowRecorder）。
	 *
	 * @param comparisonStatus 比对状态码（MATCHED/MISMATCHED/ORIGINAL_ONLY）
	 * @param tenantId         租户ID（null 落 0，与事件表口径一致）
	 */
	public void recordShadowComparison(String comparisonStatus, Long tenantId) {
		if (meterRegistry == null) {
			return;
		}
		Counter.builder(METRIC_SHADOW_COMPARISON)
			.tag("comparison_status", valueOrDefault(comparisonStatus, "UNKNOWN"))
			.tag("tenant", tenantTag(tenantId))
			.register(meterRegistry)
			.increment();
	}

	/**
	 * 记录一次决策评估耗时（挂点 RuntimePolicyEvaluator 成功路径）。
	 *
	 * @param mode          生效模式码（可空归 UNKNOWN）
	 * @param durationNanos 评估耗时（纳秒）
	 */
	public void recordEvaluationDuration(String mode, long durationNanos) {
		recordTimer(METRIC_DECISION_DURATION, mode, null, durationNanos);
	}

	/**
	 * 记录一次决策评估异常（挂点 RuntimePolicyEvaluator 异常路径，异常原样上抛不改变语义）。
	 *
	 * @param mode          生效模式码（可空归 UNKNOWN）
	 * @param durationNanos 评估耗时（纳秒）
	 */
	public void recordEvaluationError(String mode, long durationNanos) {
		if (meterRegistry == null) {
			return;
		}
		Counter.builder(METRIC_DECISION_ERRORS)
			.tag("mode", valueOrDefault(mode, "UNKNOWN"))
			.register(meterRegistry)
			.increment();
		recordTimer(METRIC_DECISION_DURATION, mode, "ERROR", durationNanos);
	}

	/**
	 * 记录一次 IAM execution-context 签发调用（BeanPostProcessor 代理挂点）。
	 *
	 * @param method        接口方法名（issueContext/latestRevision/invalidate）
	 * @param outcome       SUCCESS/ERROR
	 * @param durationNanos 调用耗时（纳秒）
	 */
	public void recordTokenIssuance(String method, String outcome, long durationNanos) {
		if (meterRegistry == null) {
			return;
		}
		Timer.builder(METRIC_TOKEN_ISSUANCE_DURATION)
			.publishPercentiles(P95, P99)
			.tag("method", valueOrDefault(method, "UNKNOWN"))
			.tag("outcome", valueOrDefault(outcome, "ERROR"))
			.register(meterRegistry)
			.record(durationNanos, TimeUnit.NANOSECONDS);
	}

	/**
	 * 记录一次 Token 预算超限拒绝（挂点 {@code SingleTurnRuntimeStepExecutor}：
	 * {@code AgentRuntimeBudgetExceededException} 被捕获置终态处，含 cause 链包装场景）。
	 *
	 * <p>P1 告警 increase(token_budget_exceeded_total[1h])&gt;0 的唯一生产者；
	 * SHADOW/ENFORCE 均计数（预算拒绝是运行时硬限制，与授权模式无关）。</p>
	 *
	 * @param tenantId 租户ID（null 落 0，与事件表口径一致）
	 */
	public void recordTokenBudgetExceeded(Long tenantId) {
		if (meterRegistry == null) {
			return;
		}
		Counter.builder(METRIC_TOKEN_BUDGET_EXCEEDED)
			.tag("tenant", tenantTag(tenantId))
			.register(meterRegistry)
			.increment();
	}

	/**
	 * 回填最近报告窗口的租户差异率（ENFORCE 门禁阈值 0.1% 的告警数据源）。
	 *
	 * @param tenant 租户口径（事件表数字口径字符串）
	 * @param rate   差异率（0~1）
	 */
	public void updateShadowMismatchRate(String tenant, double rate) {
		updateGauge(METRIC_SHADOW_MISMATCH_RATE, new String[] { "tenant", tenantTag(tenant) }, rate);
	}

	/**
	 * 回填最近报告窗口的比对计数快照（大盘趋势图数据源）。
	 *
	 * @param tenant 租户口径
	 * @param status 比对状态码（MATCHED/MISMATCHED/ORIGINAL_ONLY）
	 * @param count  窗口计数
	 */
	public void updateShadowWindowCount(String tenant, String status, long count) {
		updateGauge(METRIC_SHADOW_WINDOW_COUNT, new String[] { "tenant", tenantTag(tenant), "status",
				valueOrDefault(status, "UNKNOWN") }, count);
	}

	/**
	 * 回填最近报告窗口的 SKIPPED 台账行计数（槽位冲突观测）。
	 *
	 * @param tenant 租户口径（任务台账 String 租户原样）
	 * @param reason 跳过原因（SLOT_CONFLICT/OTHER）
	 * @param count  窗口计数
	 */
	public void updateTaskSkippedWindow(String tenant, String reason, long count) {
		updateGauge(METRIC_TASK_SKIPPED_WINDOW,
				new String[] { "tenant", valueOrDefault(tenant, TENANT_UNRESOLVED), "reason",
						valueOrDefault(reason, "OTHER") },
				count);
	}

	/**
	 * 回填最近报告窗口的任务受理延迟 P95（秒，P3 告警 &gt;0.2 阈值数据源）。
	 *
	 * @param p95Seconds P95 秒值（null 视为无样本，回填 0）
	 */
	public void updateWorkItemCreateP95Seconds(Double p95Seconds) {
		updateGauge(METRIC_WORKITEM_CREATE_P95, new String[0], p95Seconds == null ? 0D : p95Seconds);
	}

	/**
	 * 读取 gauge 句柄当前值（单测断言口径；未注册返回 null）。
	 */
	Double gaugeValue(String metricName, String... tags) {
		AtomicReference<Double> holder = gaugeHolders.get(gaugeKey(metricName, tags));
		return holder == null ? null : holder.get();
	}

	private void recordTimer(String metricName, String mode, String outcome, long durationNanos) {
		if (meterRegistry == null) {
			return;
		}
		Timer.Builder builder = Timer.builder(metricName)
			.publishPercentiles(P95, P99)
			.tag("mode", valueOrDefault(mode, "UNKNOWN"));
		if (outcome != null) {
			builder.tag("outcome", outcome);
		}
		builder.register(meterRegistry).record(durationNanos, TimeUnit.NANOSECONDS);
	}

	private void updateGauge(String metricName, String[] tags, double value) {
		if (meterRegistry == null) {
			return;
		}
		String key = gaugeKey(metricName, tags);
		AtomicReference<Double> holder = gaugeHolders.computeIfAbsent(key, ignored -> {
			AtomicReference<Double> created = new AtomicReference<>(0D);
			Gauge.Builder<AtomicReference<Double>> builder = Gauge.builder(metricName, created, ref -> ref.get());
			for (int i = 0; i + 1 < tags.length; i += 2) {
				builder.tag(tags[i], tags[i + 1] == null ? TENANT_UNRESOLVED : tags[i + 1]);
			}
			builder.register(meterRegistry);
			return created;
		});
		holder.set(value);
	}

	private String gaugeKey(String metricName, String... tags) {
		StringBuilder key = new StringBuilder(metricName);
		for (int i = 0; i + 1 < tags.length; i += 2) {
			key.append('|').append(tags[i]).append('=').append(tags[i + 1]);
		}
		return key.toString();
	}

	private String tenantTag(Long tenantId) {
		return tenantId == null ? TENANT_UNRESOLVED : String.valueOf(tenantId);
	}

	private String tenantTag(String tenant) {
		return valueOrDefault(tenant, TENANT_UNRESOLVED);
	}

	private String valueOrDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

}
