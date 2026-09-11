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
package com.sn68.agent.dataagent.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 执行意图上下文（方案第十四章「自进化 LOOP」离线评估约束）。
 *
 * <p>离线评估（候选沙箱运行）必须强制 {@code executionIntent=DRY_RUN、writeEnabled=false、
 * externalSideEffect=false}：评估执行方在跑用例前建立 DRY_RUN 作用域，写路径关口
 * （CapabilityGateway、ToolInvoker）感知到 DRY_RUN 后对写能力/外部副作用能力
 * <b>失败关闭地拒绝并记违规</b>，绝不静默放过或真实执行。
 *
 * <p>两条传递通道（互为兜底）：
 * <ul>
 * <li><b>ThreadLocal 通道</b>：{@link TransmittableThreadLocal}，覆盖评估执行线程内的同步调用链；</li>
 * <li><b>作用域注册表通道</b>：以 scopeKey（评估侧生成，随 AgentRequest → ToolContext →
 * InvocationRequest 显式传递）注册违规采集器，覆盖运行时内部线程切换后 ThreadLocal 丢失的场景。</li>
 * </ul>
 *
 * <p>已知残余风险：不经 CapabilityGateway / ToolInvoker 的写路径（如进程内直接 DB 写、
 * 外部 MCP 服务器内部行为）无法在本上下文关口拦截，见各关口的 TODO 注释与方案报告。
 */
@Slf4j
public final class ExecutionIntentContext {

	/** 正常在线执行（默认）。 */
	public static final String INTENT_LIVE = "LIVE";

	/** 离线评估干跑：禁写、禁外部副作用。 */
	public static final String INTENT_DRY_RUN = "DRY_RUN";

	/** 违规类型：写能力在 DRY_RUN 下被调用（计入写副作用违规数）。 */
	public static final String VIOLATION_WRITE_ATTEMPT = "WRITE_ATTEMPT";

	/** 违规类型：无法证明只读的外部副作用能力在 DRY_RUN 下被调用（计入写副作用违规数）。 */
	public static final String VIOLATION_EXTERNAL_SIDE_EFFECT = "EXTERNAL_SIDE_EFFECT";

	/** 违规类型：权限或租户隔离检查拒绝（计入隔离违规数）。 */
	public static final String VIOLATION_ISOLATION = "ISOLATION";

	private static final TransmittableThreadLocal<Snapshot> CONTEXT = new TransmittableThreadLocal<>();

	/** DRY_RUN 作用域注册表：scopeKey -> 快照。评估侧开闭作用域，关口按显式 scopeKey 回查。 */
	private static final Map<String, Snapshot> SCOPES = new ConcurrentHashMap<>();

	private ExecutionIntentContext() {
	}

	/**
	 * 在 DRY_RUN 作用域内执行评估调用：同时建立 ThreadLocal 与 scopeKey 注册表两条通道，
	 * 执行结束后无论成败都关闭作用域，避免采集器泄漏到后续用例。
	 */
	public static <T> T supplyDryRun(String scopeKey, ViolationCollector collector, Supplier<T> supplier) {
		Snapshot snapshot = new Snapshot(INTENT_DRY_RUN, false, false, collector);
		Snapshot previous = CONTEXT.get();
		if (StringUtils.hasText(scopeKey)) {
			SCOPES.put(scopeKey, snapshot);
		}
		CONTEXT.set(snapshot);
		try {
			return supplier.get();
		}
		finally {
			if (previous == null) {
				CONTEXT.remove();
			}
			else {
				CONTEXT.set(previous);
			}
			if (StringUtils.hasText(scopeKey)) {
				SCOPES.remove(scopeKey);
			}
		}
	}

	/** 仅按当前线程上下文判断是否处于 DRY_RUN（ToolInvoker 等无显式意图字段的关口使用）。 */
	public static boolean dryRunActive() {
		Snapshot snapshot = CONTEXT.get();
		return snapshot != null && INTENT_DRY_RUN.equals(snapshot.executionIntent());
	}

	/**
	 * 综合显式意图、作用域注册表与线程上下文判断是否处于 DRY_RUN。
	 * 任一通道声明 DRY_RUN 即按 DRY_RUN 处理（失败关闭：宁可多拦，不可漏拦）。
	 */
	public static boolean dryRunActive(String requestIntent, String scopeKey) {
		if (INTENT_DRY_RUN.equalsIgnoreCase(StringUtils.hasText(requestIntent) ? requestIntent.trim() : null)) {
			return true;
		}
		if (StringUtils.hasText(scopeKey) && SCOPES.containsKey(scopeKey)) {
			return INTENT_DRY_RUN.equals(SCOPES.get(scopeKey) == null ? null : SCOPES.get(scopeKey).executionIntent());
		}
		return dryRunActive();
	}

	/**
	 * 记录一次 DRY_RUN 违规：优先投递到 scopeKey 对应的采集器，回退线程上下文采集器；
	 * 两者都不可达时打日志留痕（违规不会计入评估结果，属残余风险，必须可见）。
	 */
	public static void recordViolation(String scopeKey, String violationType, String capabilityCode, String detail) {
		ViolationCollector collector = resolveCollector(scopeKey);
		if (collector != null) {
			collector.record(violationType, capabilityCode, detail);
			return;
		}
		log.warn("DRY_RUN 违规无法投递到评估采集器, 仅日志留痕. violationType={}, capabilityCode={}, detail={}",
				violationType, capabilityCode, detail);
	}

	private static ViolationCollector resolveCollector(String scopeKey) {
		if (StringUtils.hasText(scopeKey)) {
			Snapshot scoped = SCOPES.get(scopeKey);
			if (scoped != null && scoped.violationCollector() != null) {
				return scoped.violationCollector();
			}
		}
		Snapshot current = CONTEXT.get();
		return current == null ? null : current.violationCollector();
	}

	/**
	 * 执行意图快照。
	 *
	 * @param executionIntent 执行意图（LIVE / DRY_RUN）
	 * @param writeEnabled 是否允许写能力，DRY_RUN 恒为 false
	 * @param externalSideEffect 是否允许外部副作用，DRY_RUN 恒为 false
	 * @param violationCollector 违规采集器，DRY_RUN 作用域内拦截到的违规写入此处
	 */
	public record Snapshot(String executionIntent, boolean writeEnabled, boolean externalSideEffect,
			ViolationCollector violationCollector) {
	}

	/**
	 * DRY_RUN 违规采集器：关口可能在评估执行线程之外记录违规，需线程安全。
	 */
	public static final class ViolationCollector {

		private final List<Violation> violations = new CopyOnWriteArrayList<>();

		public void record(String violationType, String capabilityCode, String detail) {
			violations.add(new Violation(violationType, capabilityCode, detail, Instant.now()));
		}

		/** 写副作用违规数 = 写能力拦截 + 无法证明只读的外部副作用拦截。 */
		public int writeViolationCount() {
			return (int) violations.stream()
				.filter(violation -> VIOLATION_WRITE_ATTEMPT.equals(violation.violationType())
						|| VIOLATION_EXTERNAL_SIDE_EFFECT.equals(violation.violationType()))
				.count();
		}

		/** 权限或租户隔离违规数。 */
		public int isolationViolationCount() {
			return (int) violations.stream()
				.filter(violation -> VIOLATION_ISOLATION.equals(violation.violationType()))
				.count();
		}

		public boolean hasViolations() {
			return !violations.isEmpty();
		}

		/** 导出违规明细（用于评估结果 violation_detail_json 落库，不含参数值等敏感 payload）。 */
		public List<Map<String, Object>> toDetailList() {
			return violations.stream().map(violation -> {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("violationType", violation.violationType());
				item.put("capabilityCode", violation.capabilityCode());
				item.put("detail", violation.detail());
				item.put("occurredAt", violation.occurredAt() == null ? null : violation.occurredAt().toString());
				return item;
			}).toList();
		}

		public record Violation(String violationType, String capabilityCode, String detail, Instant occurredAt) {
		}

	}

}
