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
package com.sn68.agent.dataagent.service.agent.orchestration;

import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteDegradeMode;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RoutePlanStep;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteTiming;
import com.sn68.agent.dataagent.routing.shadow.RouteDecisionProposalConverter;
import com.sn68.agent.dataagent.routing.shadow.ShadowProposalConversion;
import com.sn68.agent.dataagent.routing.v2.PlanCompileException;
import com.sn68.agent.dataagent.routing.v2.PlanCompiler;
import com.sn68.agent.dataagent.routing.v2.RouteCandidateCapabilityAdapter;
import com.sn68.agent.dataagent.routing.v2.RoutePlanV2Constraints;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityCandidateSet;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.PlanCompileContext;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalStep;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimePlan;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.service.CompiledPlanPersistenceService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 编排 durable run 开跑前的 CompiledPlan ACTIVE 持久化闸门（方案第四章第 10 步）。
 *
 * <p>生产路径复用影子链路的 V1→V2 确定性转换（{@link RouteDecisionProposalConverter}）与
 * {@link PlanCompiler}，把编译成功的计划经 {@link CompiledPlanPersistenceService#save}
 * 落 {@code status=ACTIVE}。影子对比仍只走 {@code saveShadow}，本类绝不写 SHADOW 行。</p>
 *
 * <p>NATIVE 执行链路本轮不落地：不按 CompiledPlanStep 生成步骤、不经 CapabilityGateway
 * 逐步调用、不切 SSE。本闸门只让「计划持久化后才允许执行」在现有 LEGACY / 事件驱动链路上成立；
 * 步骤执行体仍是协作者调用，依赖输入仍走编排内存结果集。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DurableCompiledPlanActivator {

	static final long STEP_BUDGET_TOKENS = 4_000L;

	static final long TOTAL_BUDGET_TOKENS = STEP_BUDGET_TOKENS * RoutePlanV2Constraints.MAX_STEPS;

	private static final Duration DEFAULT_DEADLINE = Duration.ofMinutes(30);

	private static final Long UNKNOWN_USER_ID = 0L;

	private static final Long UNBOUND_RELEASE_ID = 0L;

	private final PlanCompiler planCompiler;

	private final CompiledPlanPersistenceService planPersistenceService;

	private final AgentRuntimePlanMapper planMapper;

	private final AgentRuntimeRunMapper runMapper;

	/**
	 * durable run 真正交给调度器开跑前调用：已有 ACTIVE 计划则直接放行（续跑/对账幂等）；
	 * 否则从当前协作者路由还原 V1 RoutePlan，转换编译后 {@code save(runId, plan)}。
	 * 转换/编译/落库任一失败都关闭，不吞异常、不假装已持久化。
	 */
	public void requireActivePlan(Long durableRunId, AgentOrchestrationRun legacyRun,
			List<CollaboratorRoute> routes) {
		if (durableRunId == null) {
			throw failClosed(null, "RUN_ID_MISSING", "durable runId 为空, 拒绝开跑");
		}
		AgentRuntimePlan existing = planMapper.findActiveByRunId(durableRunId);
		if (existing != null) {
			return;
		}
		AgentRuntimeRun runtimeRun = runMapper.selectById(durableRunId);
		if (runtimeRun == null) {
			throw failClosed(durableRunId, "RUN_NOT_FOUND", "权威运行不存在, 拒绝开跑");
		}
		CompiledPlan plan = compileFromRoutes(durableRunId, runtimeRun, legacyRun, routes);
		planPersistenceService.save(durableRunId, plan);
	}

	private CompiledPlan compileFromRoutes(Long durableRunId, AgentRuntimeRun runtimeRun,
			AgentOrchestrationRun legacyRun, List<CollaboratorRoute> routes) {
		if (routes == null || routes.isEmpty()) {
			throw failClosed(durableRunId, "EMPTY_ROUTES", "编排路由为空, 无法编译执行计划");
		}
		V1Snapshot snapshot;
		try {
			snapshot = toV1Snapshot(durableRunId, runtimeRun, legacyRun, routes);
		}
		catch (RuntimeException ex) {
			if (ex instanceof CheckedException checked) {
				throw checked;
			}
			throw failClosed(durableRunId, "V1_SNAPSHOT_INVALID", ex.getMessage());
		}
		ShadowProposalConversion conversion = RouteDecisionProposalConverter.convert(snapshot.decision(),
				snapshot.context(), snapshot.candidates());
		if (conversion.skipped()) {
			throw failClosed(durableRunId, conversion.skippedReason(), "V1 RoutePlan 无法转为 RouteProposalV2");
		}
		CapabilityCandidateSet candidates = RouteCandidateCapabilityAdapter.toCandidateSet(snapshot.candidates());
		PlanCompileContext context = compileContext(runtimeRun, snapshot.decision().reasonCode(), null);
		try {
			return planCompiler.compile(conversion.proposal(), candidates, context);
		}
		catch (PlanCompileException ex) {
			return recoverPortSpecGap(durableRunId, conversion.proposal(), candidates, runtimeRun,
					snapshot.decision().reasonCode(), ex);
		}
		catch (RuntimeException ex) {
			if (ex instanceof CheckedException) {
				throw ex;
			}
			throw failClosed(durableRunId, "COMPILE_UNEXPECTED_ERROR",
					ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
	}

	/**
	 * V1 候选未声明 PortSpec 时，带 inputMappings 的计划编译必然 PORT_NOT_FOUND。
	 * 不得把「端口绑定已编译成功」当成既成事实；控制依赖 DAG 仍可编译，用于本轮事件驱动执行
	 * （依赖输入走内存结果集，不走端口绑定）。连控制 DAG 也编不过则失败关闭。
	 */
	private CompiledPlan recoverPortSpecGap(Long durableRunId, RouteProposalV2 proposal,
			CapabilityCandidateSet candidates, AgentRuntimeRun runtimeRun, String v1ReasonCode,
			PlanCompileException ex) {
		if (!PlanCompileException.PORT_NOT_FOUND.equals(ex.reasonCode())) {
			throw failClosed(durableRunId, ex.reasonCode(), ex.getMessage());
		}
		log.error("V1 候选未声明 PortSpec, 带端口绑定的计划无法编译. reasonCode={}, runId={}, message={}. "
				+ "NATIVE 执行链路本轮不落地, 本轮只持久化去掉绑定后的控制依赖 DAG", ex.reasonCode(), durableRunId,
				ex.getMessage());
		PlanCompileContext retryContext = compileContext(runtimeRun, v1ReasonCode, ex.reasonCode());
		try {
			return planCompiler.compile(stripBindings(proposal), candidates, retryContext);
		}
		catch (PlanCompileException retry) {
			throw failClosed(durableRunId, retry.reasonCode(), retry.getMessage());
		}
	}

	private V1Snapshot toV1Snapshot(Long durableRunId, AgentRuntimeRun runtimeRun, AgentOrchestrationRun legacyRun,
			List<CollaboratorRoute> routes) {
		List<RoutePlanStep> steps = new ArrayList<>(routes.size());
		List<RouteSelection> selections = new ArrayList<>(routes.size());
		List<RouteCandidate> candidates = new ArrayList<>(routes.size());
		for (int index = 0; index < routes.size(); index++) {
			CollaboratorRoute route = routes.get(index);
			if (route == null || !StringUtils.hasText(route.stepId())) {
				throw failClosed(durableRunId, "ROUTE_STEP_ID_MISSING", "编排路由缺少 stepId, 无法编译执行计划");
			}
			RouteTargetRef target = targetOf(route, index);
			steps.add(new RoutePlanStep(route.stepId(), target, route.task(), route.dependsOn(),
					route.expectedOutput(), route.outputBindings(), route.inputMappings(), route.delegationMode(),
					route.collaboratorAgentId()));
			selections.add(new RouteSelection(target, null, null, RouteRisk.UNKNOWN, "legacy-orchestration"));
			candidates.add(candidateOf(target, runtimeRun, route, index));
		}
		RouteDecisionType type = selections.size() == 1 ? RouteDecisionType.SELECT : RouteDecisionType.MULTI_SELECT;
		String reason = routes.get(0).reason();
		RouteDecision decision = new RouteDecision(type, StringUtils.hasText(reason) ? reason : "LEGACY_ORCHESTRATION",
				RouteDegradeMode.NONE, selections, List.of(), null, false, RouteTiming.empty(), null,
				new RoutePlan(steps));
		return new V1Snapshot(decision, routeContext(runtimeRun, legacyRun, routes.size()), candidates);
	}

	/**
	 * 每步独立 targetId：编排测试与部分生产路由会复用同一协作者行，RouteDecision 要求 selection
	 * target 互异；句柄仍按候选顺序生成 cap-N，与影子转换规则一致。
	 */
	private RouteTargetRef targetOf(CollaboratorRoute route, int index) {
		AgentCollaborator collaborator = route.collaborator();
		Long executionRefId = collaborator == null ? null : collaborator.getId();
		return new RouteTargetRef(RouteTargetType.COLLABORATOR, (long) (index + 1), null, executionRefId);
	}

	private RouteCandidate candidateOf(RouteTargetRef target, AgentRuntimeRun runtimeRun, CollaboratorRoute route,
			int index) {
		DataAgent agent = route.dataAgent();
		String name = agent != null && StringUtils.hasText(agent.getName()) ? agent.getName()
				: "collaborator-" + (index + 1);
		String tenantId = runtimeRun.getTenantId();
		return new RouteCandidate(target, tenantId, runtimeRun.getAgentId(), name, name, "QUERY", "REACT",
				RouteRules.empty(), RouteRisk.UNKNOWN, index + 1, null, null, "legacy-orchestration", null);
	}

	private RouteContext routeContext(AgentRuntimeRun runtimeRun, AgentOrchestrationRun legacyRun, int maxSelections) {
		String tenantId = runtimeRun.getTenantId() == null ? null : String.valueOf(runtimeRun.getTenantId());
		String query = StringUtils.hasText(runtimeRun.getQuery()) ? runtimeRun.getQuery()
				: legacyRun == null ? null : legacyRun.getQuery();
		String userId = runtimeRun.getOwnerId() == null ? String.valueOf(UNKNOWN_USER_ID)
				: String.valueOf(runtimeRun.getOwnerId());
		Instant deadline = runtimeRun.getDeadlineAt() == null ? Instant.now().plus(DEFAULT_DEADLINE)
				: runtimeRun.getDeadlineAt();
		return new RouteContext(tenantId, runtimeRun.getAgentId(), "ORCHESTRATOR", null, userId,
				runtimeRun.getRuntimeRequestId(), query, null, null, null, deadline, Math.max(1, maxSelections));
	}

	private PlanCompileContext compileContext(AgentRuntimeRun runtimeRun, String v1ReasonCode,
			String bindingReasonCode) {
		if (runtimeRun.getTenantId() == null) {
			throw failClosed(runtimeRun.getId(), "CONTEXT_TENANT_MISSING", "权威运行缺少租户, 拒绝持久化编译计划");
		}
		Instant deadline = runtimeRun.getDeadlineAt() == null ? Instant.now().plus(DEFAULT_DEADLINE)
				: runtimeRun.getDeadlineAt();
		Long userId = runtimeRun.getOwnerId() == null ? UNKNOWN_USER_ID : runtimeRun.getOwnerId();
		Long releaseId = runtimeRun.getReleaseId() == null ? UNBOUND_RELEASE_ID : runtimeRun.getReleaseId();
		return new PlanCompileContext(String.valueOf(runtimeRun.getTenantId()), 0L, userId, releaseId,
				policySnapshot(v1ReasonCode, bindingReasonCode), deadline, STEP_BUDGET_TOKENS, TOTAL_BUDGET_TOKENS,
				null);
	}

	private String policySnapshot(String v1ReasonCode, String bindingReasonCode) {
		String reason = sanitize(v1ReasonCode);
		if (!StringUtils.hasText(bindingReasonCode)) {
			return "{\"source\":\"LEGACY_ORCHESTRATION\",\"v1ReasonCode\":\"" + reason + "\"}";
		}
		return "{\"source\":\"LEGACY_ORCHESTRATION\",\"v1ReasonCode\":\"" + reason
				+ "\",\"bindingCompileReasonCode\":\"" + sanitize(bindingReasonCode) + "\"}";
	}

	private RouteProposalV2 stripBindings(RouteProposalV2 proposal) {
		List<RouteProposalStep> steps = proposal.steps().stream()
			.map(step -> new RouteProposalStep(step.stepKey(), step.capabilityHandle(), step.task(), List.of()))
			.toList();
		return new RouteProposalV2(proposal.schemaVersion(), proposal.mode(), steps, proposal.controlEdges(),
				proposal.clarify());
	}

	private String sanitize(String value) {
		return value == null ? "" : value.replace("\"", "").replaceAll("\\s+", " ").trim();
	}

	private CheckedException failClosed(Long runId, String reasonCode, String message) {
		String code = StringUtils.hasText(reasonCode) ? reasonCode : "COMPILED_PLAN_ACTIVATION_FAILED";
		String detail = StringUtils.hasText(message) ? message : "编译计划激活失败";
		return CheckedException.fail("编排运行拒绝执行: reasonCode=" + code + ", runId=" + runId + ", " + detail);
	}

	private record V1Snapshot(RouteDecision decision, RouteContext context, List<RouteCandidate> candidates) {
	}

}
