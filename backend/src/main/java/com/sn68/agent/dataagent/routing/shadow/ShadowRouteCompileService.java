/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.shadow;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.routing.RouteEngineMode;
import com.sn68.agent.dataagent.routing.RouteEngineModeProperties;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.v2.PlanCompileException;
import com.sn68.agent.dataagent.routing.v2.PlanCompiler;
import com.sn68.agent.dataagent.routing.v2.RouteCandidateCapabilityAdapter;
import com.sn68.agent.dataagent.routing.v2.RoutePlanV2Constraints;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanStep;
import com.sn68.agent.dataagent.routing.v2.model.PlanCompileContext;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Shadow 路由编译对比服务（灰度切流 SHADOW 模式的核心）：V1 决策正常返回后异步触发，
 * 把 V1 决策确定性转换为 RouteProposalV2、经 PlanCompiler 编译，产出 V1 vs V2 对比记录。
 *
 * <p>安全性硬约束：全路径 try-catch，任何异常只 warn 绝不影响主链路；任务带集中定义的
 * 超时预算；任务内不执行任何业务工具或写操作——仅编译、记录结构化日志，以及把编译成功的
 * 影子计划经 {@link CompiledPlanPersistenceService#saveShadow} 落 agent_runtime_plan（status=SHADOW，
 * 不参与执行、不影响 ACTIVE 计划）。生产路径 ACTIVE {@code save()} 由编排
 * DurableCompiledPlanActivator 在 durable run 开跑前调用，本类绝不调用 {@code save()}。
 */
@Slf4j
@Service
public class ShadowRouteCompileService {

	/** 影子对比专用 logger：字段化输出，不打印用户原文（仅 query 长度与哈希前缀）。 */
	private static final Logger ROUTE_SHADOW_LOG = LoggerFactory.getLogger("route.shadow");

	/** 影子任务总预算：编译为纯内存操作，预算主要约束排队延迟与落库耗时。 */
	static final Duration SHADOW_TASK_BUDGET = Duration.ofSeconds(2);

	/** 影子编译占位预算（仅满足编译上下文校验，不用于执行）。 */
	static final long SHADOW_STEP_BUDGET_TOKENS = 4_000L;

	static final long SHADOW_TOTAL_BUDGET_TOKENS = SHADOW_STEP_BUDGET_TOKENS * RoutePlanV2Constraints.MAX_STEPS;

	/** 与迁移期镜像 Run 相同的 workspace=0 命名空间；V1 链路无 Release 概念，0 表示未绑定。 */
	static final Long SHADOW_WORKSPACE_ID = 0L;

	static final Long SHADOW_RELEASE_ID = 0L;

	/** V1 上下文 userId 缺失或非数字时的占位值，0 表示未解析（与租户 0 约定一致）。 */
	static final Long SHADOW_UNKNOWN_USER_ID = 0L;

	private final RouteEngineModeProperties engineModeProperties;

	private final PlanCompiler planCompiler;

	private final CompiledPlanPersistenceService planPersistenceService;

	private final AgentRuntimeRunMapper runtimeRunMapper;

	private final Executor shadowExecutor;

	private final AtomicBoolean nativeModeWarned = new AtomicBoolean();

	public ShadowRouteCompileService(RouteEngineModeProperties engineModeProperties, PlanCompiler planCompiler,
			CompiledPlanPersistenceService planPersistenceService, AgentRuntimeRunMapper runtimeRunMapper,
			@Qualifier(ShadowRouteConfiguration.SHADOW_EXECUTOR_BEAN) Executor shadowExecutor) {
		this.engineModeProperties = engineModeProperties;
		this.planCompiler = planCompiler;
		this.planPersistenceService = planPersistenceService;
		this.runtimeRunMapper = runtimeRunMapper;
		this.shadowExecutor = shadowExecutor;
	}

	/**
	 * V1 决策产出后的影子对比入口：LEGACY 直接返回（一次枚举比较，零任务零分配），
	 * SHADOW 提交异步任务；提交失败（如队列满被拒绝）只 warn 丢弃，绝不反压主链路。
	 */
	public void submitCompare(RouteContext context, RouteDecision decision, List<RouteCandidate> candidates) {
		RouteEngineMode mode = engineModeProperties.getMode();
		if (mode != RouteEngineMode.SHADOW) {
			// NATIVE 已被启动校验拒绝，仅配置中心动态刷新可能绕过；此时按 LEGACY 行为兜底并
			// warn 一次——执行链路未实现，不能假装已切流（暴露真实失败）
			if (mode == RouteEngineMode.NATIVE && nativeModeWarned.compareAndSet(false, true)) {
				log.warn("Route engine mode NATIVE has no execution path yet, behaving as LEGACY without shadow compare");
			}
			return;
		}
		if (context == null || decision == null) {
			return;
		}
		try {
			Instant submittedAt = Instant.now();
			List<RouteCandidate> snapshot = candidates == null ? List.of() : List.copyOf(candidates);
			shadowExecutor.execute(() -> runCompare(context, decision, snapshot, submittedAt));
		}
		catch (RuntimeException ex) {
			log.warn("Shadow route compare submit rejected, requestId={}, errorType={}", context.runtimeRequestId(),
					ex.getClass().getSimpleName());
		}
	}

	/** 影子任务体：整体兜底 try-catch，对比失败只 warn，不向执行器抛出任何异常。 */
	void runCompare(RouteContext context, RouteDecision decision, List<RouteCandidate> candidates,
			Instant submittedAt) {
		try {
			emit(compare(context, decision, candidates, submittedAt));
		}
		catch (RuntimeException ex) {
			log.warn("Shadow route compare failed, requestId={}, errorType={}, message={}",
					context.runtimeRequestId(), ex.getClass().getSimpleName(), ex.getMessage());
		}
	}

	/** 对比主流程：预算检查 → 转换 + 编译 → 影子计划落库 → 汇总对比记录。 */
	ShadowRouteCompareResult compare(RouteContext context, RouteDecision decision, List<RouteCandidate> candidates,
			Instant submittedAt) {
		long started = System.nanoTime();
		CompareDraft draft = new CompareDraft();
		CompiledPlan plan = null;
		if (budgetExhausted(submittedAt)) {
			draft.scenario = ShadowRouteCompareResult.SCENARIO_SKIPPED;
			draft.skippedReason = "SHADOW_BUDGET_EXHAUSTED";
		}
		else {
			plan = compilePhase(context, decision, candidates, draft);
		}
		persistPhase(context, plan, submittedAt, draft);
		return buildResult(context, decision, candidates, draft, started);
	}

	/** 转换 + 编译：SKIPPED 与编译失败都完整记录原因，失败码是灰度期最重要的差异信号。 */
	private CompiledPlan compilePhase(RouteContext context, RouteDecision decision, List<RouteCandidate> candidates,
			CompareDraft draft) {
		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(decision, context, candidates);
		if (conversion.skipped()) {
			draft.scenario = ShadowRouteCompareResult.SCENARIO_SKIPPED;
			draft.skippedReason = conversion.skippedReason();
			return null;
		}
		if (!StringUtils.hasText(context.tenantId())) {
			draft.scenario = ShadowRouteCompareResult.SCENARIO_SKIPPED;
			draft.skippedReason = "CONTEXT_TENANT_MISSING";
			return null;
		}
		try {
			CompiledPlan plan = planCompiler.compile(conversion.proposal(),
					RouteCandidateCapabilityAdapter.toCandidateSet(candidates), compileContext(context, decision));
			draft.scenario = ShadowRouteCompareResult.SCENARIO_COMPILED;
			draft.v2Mode = plan.mode().name();
			draft.v2StepCount = plan.steps().size();
			draft.v2ExecutionOrder = plan.steps().stream().map(CompiledPlanStep::stepKey)
				.collect(Collectors.joining("->"));
			draft.planHash = plan.planHash();
			draft.v2RiskLevel = plan.riskLevel().name();
			draft.v2ApprovalRequired = plan.approvalRequired();
			return plan;
		}
		catch (PlanCompileException ex) {
			draft.scenario = ShadowRouteCompareResult.SCENARIO_COMPILE_REJECTED;
			draft.compileFailureCode = ex.reasonCode();
			draft.compileFailureMessage = ex.getMessage();
			return null;
		}
		catch (RuntimeException ex) {
			// 候选集 / 编译上下文构造等意外异常同样按拒绝记录，保持失败可见
			draft.scenario = ShadowRouteCompareResult.SCENARIO_COMPILE_REJECTED;
			draft.compileFailureCode = "COMPILE_UNEXPECTED_ERROR";
			draft.compileFailureMessage = ex.getClass().getSimpleName() + ": " + ex.getMessage();
			return null;
		}
	}

	/**
	 * 影子计划落库：只在编译成功且预算未耗尽时进行。V1 链路当前仅编排镜像
	 * （RuntimeMirrorService，workspace=0）会产生权威 Run；按 runtimeRequestId 取不到 Run 时
	 * 不伪造运行记录，只保留结构化日志（对比数据在日志中已完整）。
	 */
	private void persistPhase(RouteContext context, CompiledPlan plan, Instant submittedAt, CompareDraft draft) {
		if (plan == null) {
			draft.persistence = ShadowRouteCompareResult.PERSISTENCE_SKIPPED_NOT_COMPILED;
			return;
		}
		if (budgetExhausted(submittedAt)) {
			draft.persistence = ShadowRouteCompareResult.PERSISTENCE_SKIPPED_BUDGET_EXHAUSTED;
			return;
		}
		try {
			// runtimeRequestId 为空时不得查询：Wraps 会跳过空条件，导致误关联任意 Run
			AgentRuntimeRun run = StringUtils.hasText(context.runtimeRequestId())
					? runtimeRunMapper.findByRuntimeRequestId(context.runtimeRequestId()) : null;
			if (run == null || run.getId() == null) {
				draft.persistence = ShadowRouteCompareResult.PERSISTENCE_SKIPPED_NO_RUN;
				return;
			}
			AgentRuntimePlan saved = planPersistenceService.saveShadow(run.getId(), plan);
			draft.persistence = ShadowRouteCompareResult.PERSISTENCE_PERSISTED;
			draft.persistedPlanId = saved == null ? null : saved.getId();
		}
		catch (RuntimeException ex) {
			draft.persistence = ShadowRouteCompareResult.PERSISTENCE_FAILED;
			log.warn("Shadow plan persistence failed, requestId={}, planHash={}, errorType={}",
					context.runtimeRequestId(), plan.planHash(), ex.getClass().getSimpleName());
		}
	}

	/** 编译上下文全部为服务端事实：租户来自 V1 路由上下文，workspace / release 用影子占位常量。 */
	private PlanCompileContext compileContext(RouteContext context, RouteDecision decision) {
		Instant deadline = context.deadline() == null ? Instant.now().plus(SHADOW_TASK_BUDGET) : context.deadline();
		return new PlanCompileContext(context.tenantId(), SHADOW_WORKSPACE_ID, parseUserId(context.userId()),
				SHADOW_RELEASE_ID, policySnapshot(decision), deadline, SHADOW_STEP_BUDGET_TOKENS,
				SHADOW_TOTAL_BUDGET_TOKENS, null);
	}

	/** 策略快照标记影子来源，与 status=SHADOW 一起构成 agent_runtime_plan 行级溯源。 */
	private String policySnapshot(RouteDecision decision) {
		return "{\"source\":\"ROUTE_SHADOW\",\"v1Decision\":\"" + decision.decision().name() + "\",\"v1ReasonCode\":\""
				+ (decision.reasonCode() == null ? "" : decision.reasonCode()) + "\"}";
	}

	private Long parseUserId(String userId) {
		try {
			return StringUtils.hasText(userId) ? Long.valueOf(userId.trim()) : SHADOW_UNKNOWN_USER_ID;
		}
		catch (NumberFormatException ex) {
			return SHADOW_UNKNOWN_USER_ID;
		}
	}

	private boolean budgetExhausted(Instant submittedAt) {
		return submittedAt != null && Duration.between(submittedAt, Instant.now()).compareTo(SHADOW_TASK_BUDGET) > 0;
	}

	private ShadowRouteCompareResult buildResult(RouteContext context, RouteDecision decision,
			List<RouteCandidate> candidates, CompareDraft draft, long started) {
		String query = context.query() == null ? "" : context.query();
		return new ShadowRouteCompareResult(context.tenantId(), context.ownerAgentId(), context.runtimeRequestId(),
				context.sessionId(), decision.decision().name(), decision.reasonCode(), decision.degradeMode().name(),
				decision.modelInvoked(), decision.selections().size(), decision.plan().steps().size(),
				v1StepOrder(decision), candidates == null ? 0 : candidates.size(), draft.scenario,
				draft.skippedReason, draft.v2Mode, draft.v2StepCount, draft.v2ExecutionOrder, draft.planHash,
				draft.v2RiskLevel, draft.v2ApprovalRequired, draft.compileFailureCode, draft.compileFailureMessage,
				divergence(decision, draft.scenario), draft.persistence, draft.persistedPlanId, query.length(),
				queryHash(query), Duration.ofNanos(System.nanoTime() - started).toMillis());
	}

	private String v1StepOrder(RouteDecision decision) {
		return decision.plan().steps().isEmpty() ? null
				: decision.plan().steps().stream().map(RoutePlanStep::stepId).collect(Collectors.joining("->"));
	}

	private String queryHash(String query) {
		return query.isEmpty() ? null : SecureUtil.sha256(query).substring(0, 16);
	}

	/** V2 拒绝而 V1 给出可执行决策，是新协议推向生产前必须逐条分析的差异信号。 */
	private String divergence(RouteDecision decision, String scenario) {
		if (ShadowRouteCompareResult.SCENARIO_COMPILED.equals(scenario)) {
			return ShadowRouteCompareResult.DIVERGENCE_NONE;
		}
		if (ShadowRouteCompareResult.SCENARIO_COMPILE_REJECTED.equals(scenario) && executableV1(decision)) {
			return ShadowRouteCompareResult.DIVERGENCE_V2_REJECTED_V1_PASSED;
		}
		return ShadowRouteCompareResult.DIVERGENCE_NOT_COMPARED;
	}

	private boolean executableV1(RouteDecision decision) {
		RouteDecisionType type = decision.decision();
		return type == RouteDecisionType.SELECT || type == RouteDecisionType.MULTI_SELECT
				|| type == RouteDecisionType.CONFIRM_REQUIRED;
	}

	private void emit(ShadowRouteCompareResult result) {
		ROUTE_SHADOW_LOG.info("event=ROUTE_SHADOW_COMPARE scenario={} divergence={} tenantId={} agentId={} "
				+ "requestId={} sessionId={} v1Decision={} v1ReasonCode={} v1DegradeMode={} v1ModelInvoked={} "
				+ "v1SelectionCount={} v1PlanStepCount={} v1StepOrder={} candidateCount={} v2Mode={} v2StepCount={} "
				+ "v2ExecutionOrder={} planHash={} v2RiskLevel={} v2ApprovalRequired={} compileFailureCode={} "
				+ "compileFailureMessage={} skippedReason={} persistence={} persistedPlanId={} queryLength={} "
				+ "queryHash={} elapsedMs={}", result.scenario(), result.divergence(), result.tenantId(),
				result.agentId(), result.requestId(), result.sessionId(), result.v1Decision(), result.v1ReasonCode(),
				result.v1DegradeMode(), result.v1ModelInvoked(), result.v1SelectionCount(), result.v1PlanStepCount(),
				result.v1StepOrder(), result.candidateCount(), result.v2Mode(), result.v2StepCount(),
				result.v2ExecutionOrder(), result.planHash(), result.v2RiskLevel(), result.v2ApprovalRequired(),
				result.compileFailureCode(), sanitize(result.compileFailureMessage()), result.skippedReason(),
				result.persistence(), result.persistedPlanId(), result.queryLength(), result.queryHash(),
				result.elapsedMs());
	}

	/** 压平空白并去引号，保证 key=value 单行日志可被日志平台字段化解析。 */
	private String sanitize(String message) {
		return message == null ? null : "\"" + message.replaceAll("[\\s\"]+", " ").trim() + "\"";
	}

	/** 对比记录草稿：compare 各阶段的可变收集器，最终汇总为不可变记录。 */
	private static final class CompareDraft {

		private String scenario;

		private String skippedReason;

		private String v2Mode;

		private Integer v2StepCount;

		private String v2ExecutionOrder;

		private String planHash;

		private String v2RiskLevel;

		private Boolean v2ApprovalRequired;

		private String compileFailureCode;

		private String compileFailureMessage;

		private String persistence;

		private Long persistedPlanId;

	}

}
