/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityCandidateSet;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityDescriptor;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityRiskLevel;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlan;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanIdempotencyPolicy;
import com.sn68.agent.dataagent.routing.v2.model.CompiledPlanStep;
import com.sn68.agent.dataagent.routing.v2.model.CompiledStepBinding;
import com.sn68.agent.dataagent.routing.v2.model.PlanCompileContext;
import com.sn68.agent.dataagent.routing.v2.model.PortCardinality;
import com.sn68.agent.dataagent.routing.v2.model.PortSpec;
import com.sn68.agent.dataagent.routing.v2.model.RouteBindingSourceKind;
import com.sn68.agent.dataagent.routing.v2.model.RouteControlEdge;
import com.sn68.agent.dataagent.routing.v2.model.RoutePortValueType;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalBinding;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalMode;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalStep;
import com.sn68.agent.dataagent.routing.v2.model.RouteProposalV2;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RouteProposalV2 → CompiledPlan 编译器,严格按方案第四章 10 步执行:
 * ① 完整解析 JSON(不按模型返回顺序处理)→ ② stepKey 唯一 → ③ 允许同一 capability 被多个 stepKey 复用
 * → ④ capabilityHandle 必须属于服务端候选集 → ⑤ 端口存在/类型/可空/基数/敏感级别校验
 * → ⑥ 合并数据依赖(bindings)与控制依赖(controlEdges)→ ⑦ Kahn 拓扑排序(支持乱序输入的合法 DAG)
 * → ⑧ 检测环/缺失步骤/前向引用悖论(自引用)/未绑定必填输入 → ⑨ 生成 canonical JSON 与 SHA-256 planHash
 * → ⑩ 产出不可变 CompiledPlan。
 *
 * <p>canonical JSON 的确定性来源:步骤按 Kahn 拓扑序输出(同层按 stepKey 字典序)、
 * dependsOn 与 bindings 排序稳定、字段按固定插入顺序序列化且无缩进,因此语义相同但
 * 字段/数组顺序不同的提案会得到相同的 planHash。
 */
// 持久化：本编译器保持纯内存，不感知持久化。LEGACY/事件驱动编排在 durable run 开跑前经
// DurableCompiledPlanActivator 调用 CompiledPlanPersistenceService#save 写入 ACTIVE 计划
// （方案第四章第 10 步）；影子对比仍只走 saveShadow。
// NATIVE 执行链路（CapabilityGateway 逐步调用 + SSE 切流）本轮不落地，配置 NATIVE 仍被启动校验拒绝。
@Slf4j
@Component
public class PlanCompiler {

	private static final Comparator<CompiledStepBinding> BINDING_ORDER = Comparator
		.comparing(CompiledStepBinding::targetPortHandle)
		.thenComparing(binding -> binding.sourceKind().name())
		.thenComparing(CompiledStepBinding::sourceStepKey, Comparator.nullsFirst(Comparator.naturalOrder()))
		.thenComparing(CompiledStepBinding::sourcePortHandle, Comparator.nullsFirst(Comparator.naturalOrder()));

	private final RouteProposalV2Parser parser;

	private final ProposalForbiddenFieldGuard forbiddenFieldGuard;

	private final ObjectMapper canonicalMapper;

	public PlanCompiler(RouteProposalV2Parser parser, ProposalForbiddenFieldGuard forbiddenFieldGuard,
			ObjectMapper objectMapper) {
		this.parser = parser;
		this.forbiddenFieldGuard = forbiddenFieldGuard;
		this.canonicalMapper = objectMapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
	}

	/** 从原始提案 JSON 编译:完整解析 → 禁止字段防线 → 严格 schema → 语义编译。 */
	public CompiledPlan compile(String proposalJson, CapabilityCandidateSet candidates, PlanCompileContext context) {
		JsonNode tree = parser.readTree(proposalJson);
		forbiddenFieldGuard.verify(tree);
		return compile(parser.parse(tree), candidates, context);
	}

	/** 从已解析提案编译(服务端程序化构造提案时的入口)。 */
	public CompiledPlan compile(RouteProposalV2 proposal, CapabilityCandidateSet candidates,
			PlanCompileContext context) {
		requireCompileInputs(proposal, candidates, context);
		validateModeShape(proposal);
		validateUniqueStepKeys(proposal);
		Map<String, CapabilityDescriptor> capabilityByStep = resolveCapabilities(proposal, candidates);
		validateBindings(proposal, capabilityByStep, context);
		Map<String, SortedSet<String>> upstreamByStep = mergeDependencies(proposal);
		validateFanout(upstreamByStep);
		List<String> executionOrder = topologicalOrder(upstreamByStep);
		List<CompiledPlanStep> steps = buildSteps(proposal, capabilityByStep, upstreamByStep, executionOrder, context);
		String canonicalJson = canonicalJson(proposal, context, steps);
		String planHash = SecureUtil.sha256(canonicalJson);
		CompiledPlan plan = buildPlan(proposal, context, capabilityByStep, steps, canonicalJson, planHash);
		log.debug("Route proposal compiled, tenantId={}, mode={}, stepCount={}, riskLevel={}, planHash={}",
				context.tenantId(), proposal.mode(), steps.size(), plan.riskLevel(), planHash);
		return plan;
	}

	private void requireCompileInputs(RouteProposalV2 proposal, CapabilityCandidateSet candidates,
			PlanCompileContext context) {
		if (proposal == null || candidates == null || context == null) {
			throw new IllegalArgumentException("Plan compile requires proposal, candidate set and context");
		}
	}

	/** 模式与结构一致性:步骤数上限、各模式允许的步骤/控制边/澄清组合。 */
	private void validateModeShape(RouteProposalV2 proposal) {
		int stepCount = proposal.steps().size();
		if (stepCount > RoutePlanV2Constraints.MAX_STEPS) {
			throw new PlanCompileException(PlanCompileException.STEP_LIMIT_EXCEEDED,
					"Route proposal declares " + stepCount + " steps, limit is " + RoutePlanV2Constraints.MAX_STEPS);
		}
		if (proposal.clarify() != null && proposal.mode() != RouteProposalMode.CLARIFY_RESUME) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_MODE_INVALID,
					"Route proposal clarify is only allowed for CLARIFY_RESUME mode");
		}
		boolean valid = switch (proposal.mode()) {
			case CHAT, CLARIFY_RESUME -> stepCount == 0 && proposal.controlEdges().isEmpty();
			case DIRECT, FLOW, AGENT_LOOP -> stepCount == 1 && proposal.controlEdges().isEmpty();
			case ORCHESTRATION -> stepCount >= 1;
		};
		if (!valid) {
			throw new PlanCompileException(PlanCompileException.PROPOSAL_MODE_INVALID,
					"Route proposal step/edge shape does not match mode " + proposal.mode());
		}
	}

	private void validateUniqueStepKeys(RouteProposalV2 proposal) {
		Set<String> stepKeys = new HashSet<>();
		for (RouteProposalStep step : proposal.steps()) {
			if (!stepKeys.add(step.stepKey())) {
				throw new PlanCompileException(PlanCompileException.STEP_KEY_DUPLICATE,
						"Route proposal stepKey '" + step.stepKey() + "' is duplicated");
			}
		}
	}

	/** 步骤④:capabilityHandle 必须命中候选集;同一 capability 允许被多个 stepKey 复用;校验嵌套编排深度。 */
	private Map<String, CapabilityDescriptor> resolveCapabilities(RouteProposalV2 proposal,
			CapabilityCandidateSet candidates) {
		Map<String, CapabilityDescriptor> capabilityByStep = new LinkedHashMap<>();
		int planDepth = proposal.mode() == RouteProposalMode.ORCHESTRATION ? 1 : 0;
		for (RouteProposalStep step : proposal.steps()) {
			CapabilityDescriptor descriptor = candidates.find(step.capabilityHandle());
			if (descriptor == null) {
				throw new PlanCompileException(PlanCompileException.CAPABILITY_NOT_IN_CANDIDATE_SET,
						"Route proposal step '" + step.stepKey() + "' references capability '"
								+ step.capabilityHandle() + "' outside the server candidate set");
			}
			int nestedDepth = planDepth + (descriptor.orchestration() ? 1 : 0);
			if (nestedDepth > RoutePlanV2Constraints.MAX_NESTED_ORCHESTRATION_DEPTH) {
				throw new PlanCompileException(PlanCompileException.NESTED_ORCHESTRATION_DEPTH_EXCEEDED,
						"Route proposal step '" + step.stepKey() + "' would nest orchestration deeper than "
								+ RoutePlanV2Constraints.MAX_NESTED_ORCHESTRATION_DEPTH);
			}
			capabilityByStep.put(step.stepKey(), descriptor);
		}
		return capabilityByStep;
	}

	/** 步骤⑤+⑧:端口存在/类型/可空/基数/敏感级别校验,含缺失步骤、自引用与未绑定必填输入。 */
	private void validateBindings(RouteProposalV2 proposal, Map<String, CapabilityDescriptor> capabilityByStep,
			PlanCompileContext context) {
		for (RouteProposalStep step : proposal.steps()) {
			CapabilityDescriptor capability = capabilityByStep.get(step.stepKey());
			Map<String, Integer> boundCounts = new HashMap<>();
			for (RouteProposalBinding binding : step.bindings()) {
				PortSpec target = capability.inputPort(binding.targetPortHandle());
				if (target == null) {
					throw new PlanCompileException(PlanCompileException.PORT_NOT_FOUND, "Route proposal step '"
							+ step.stepKey() + "' binds unknown input port '" + binding.targetPortHandle() + "'");
				}
				requireClearance(target, context, step.stepKey());
				boundCounts.merge(target.portHandle(), 1, Integer::sum);
				if (binding.sourceKind() == RouteBindingSourceKind.STEP_OUTPUT) {
					validateStepOutputBinding(step, binding, target, capabilityByStep, context);
				}
			}
			validateCardinality(step, capability, boundCounts);
			validateRequiredInputs(step, capability, boundCounts.keySet());
		}
	}

	private void validateStepOutputBinding(RouteProposalStep step, RouteProposalBinding binding, PortSpec target,
			Map<String, CapabilityDescriptor> capabilityByStep, PlanCompileContext context) {
		if (step.stepKey().equals(binding.sourceStepKey())) {
			throw new PlanCompileException(PlanCompileException.SELF_REFERENCE,
					"Route proposal step '" + step.stepKey() + "' must not bind its own output");
		}
		CapabilityDescriptor sourceCapability = capabilityByStep.get(binding.sourceStepKey());
		if (sourceCapability == null) {
			throw new PlanCompileException(PlanCompileException.MISSING_STEP_REFERENCE, "Route proposal step '"
					+ step.stepKey() + "' binds output of missing step '" + binding.sourceStepKey() + "'");
		}
		PortSpec source = sourceCapability.outputPort(binding.sourcePortHandle());
		if (source == null) {
			throw new PlanCompileException(PlanCompileException.PORT_NOT_FOUND,
					"Route proposal step '" + step.stepKey() + "' binds unknown output port '"
							+ binding.sourcePortHandle() + "' of step '" + binding.sourceStepKey() + "'");
		}
		requireClearance(source, context, binding.sourceStepKey());
		if (source.valueType() != target.valueType()) {
			throw new PlanCompileException(PlanCompileException.PORT_TYPE_MISMATCH,
					"Route proposal binding " + binding.sourceStepKey() + "." + source.portHandle() + "("
							+ source.valueType() + ") does not match " + step.stepKey() + "." + target.portHandle()
							+ "(" + target.valueType() + ")");
		}
		if (source.valueType() == RoutePortValueType.TEXT) {
			throw new PlanCompileException(PlanCompileException.NATURAL_LANGUAGE_BINDING_FORBIDDEN,
					"Route proposal must not feed natural language output of step '" + binding.sourceStepKey()
							+ "' into downstream step '" + step.stepKey() + "'");
		}
	}

	private void requireClearance(PortSpec port, PlanCompileContext context, String stepKey) {
		if (!port.sensitivity().allowedWithin(context.sensitivityClearance())) {
			throw new PlanCompileException(PlanCompileException.SENSITIVE_PORT_DENIED,
					"Route proposal step '" + stepKey + "' touches port '" + port.portHandle() + "' with sensitivity "
							+ port.sensitivity() + " above clearance " + context.sensitivityClearance());
		}
	}

	private void validateCardinality(RouteProposalStep step, CapabilityDescriptor capability,
			Map<String, Integer> boundCounts) {
		boundCounts.forEach((portHandle, count) -> {
			if (count > 1 && capability.inputPort(portHandle).cardinality() == PortCardinality.SINGLE) {
				throw new PlanCompileException(PlanCompileException.PORT_CARDINALITY_VIOLATION,
						"Route proposal step '" + step.stepKey() + "' binds SINGLE port '" + portHandle + "' " + count
								+ " times");
			}
		});
	}

	private void validateRequiredInputs(RouteProposalStep step, CapabilityDescriptor capability,
			Set<String> boundPortHandles) {
		for (PortSpec input : capability.inputPorts()) {
			if (!input.nullable() && !boundPortHandles.contains(input.portHandle())) {
				throw new PlanCompileException(PlanCompileException.UNBOUND_REQUIRED_INPUT, "Route proposal step '"
						+ step.stepKey() + "' leaves required input port '" + input.portHandle() + "' unbound");
			}
		}
	}

	/** 步骤⑥:合并数据依赖与控制依赖为去重后的上游集合;控制边端点必须是已声明步骤且不允许自引用。 */
	private Map<String, SortedSet<String>> mergeDependencies(RouteProposalV2 proposal) {
		Map<String, SortedSet<String>> upstreamByStep = new TreeMap<>();
		proposal.steps().forEach(step -> upstreamByStep.put(step.stepKey(), new TreeSet<>()));
		for (RouteProposalStep step : proposal.steps()) {
			for (RouteProposalBinding binding : step.bindings()) {
				if (binding.sourceKind() == RouteBindingSourceKind.STEP_OUTPUT) {
					upstreamByStep.get(step.stepKey()).add(binding.sourceStepKey());
				}
			}
		}
		for (RouteControlEdge edge : proposal.controlEdges()) {
			if (!upstreamByStep.containsKey(edge.fromStepKey()) || !upstreamByStep.containsKey(edge.toStepKey())) {
				throw new PlanCompileException(PlanCompileException.MISSING_STEP_REFERENCE,
						"Route proposal control edge [" + edge.fromStepKey() + " -> " + edge.toStepKey()
								+ "] references a missing step");
			}
			if (edge.fromStepKey().equals(edge.toStepKey())) {
				throw new PlanCompileException(PlanCompileException.SELF_REFERENCE,
						"Route proposal control edge must not reference the step itself: " + edge.fromStepKey());
			}
			upstreamByStep.get(edge.toStepKey()).add(edge.fromStepKey());
		}
		return upstreamByStep;
	}

	private void validateFanout(Map<String, SortedSet<String>> upstreamByStep) {
		Map<String, Integer> fanout = new HashMap<>();
		upstreamByStep.forEach((stepKey, upstream) -> upstream.forEach(up -> fanout.merge(up, 1, Integer::sum)));
		fanout.forEach((stepKey, count) -> {
			if (count > RoutePlanV2Constraints.MAX_FANOUT) {
				throw new PlanCompileException(PlanCompileException.FANOUT_LIMIT_EXCEEDED, "Route proposal step '"
						+ stepKey + "' fans out to " + count + " steps, limit is " + RoutePlanV2Constraints.MAX_FANOUT);
			}
		});
	}

	/** 步骤⑦+⑧:Kahn 拓扑排序,同层按 stepKey 字典序,支持乱序输入的合法 DAG;剩余节点即成环。 */
	private List<String> topologicalOrder(Map<String, SortedSet<String>> upstreamByStep) {
		Map<String, Integer> indegree = new TreeMap<>();
		Map<String, SortedSet<String>> downstream = new TreeMap<>();
		upstreamByStep.forEach((stepKey, upstream) -> {
			indegree.put(stepKey, upstream.size());
			downstream.putIfAbsent(stepKey, new TreeSet<>());
			upstream.forEach(up -> downstream.computeIfAbsent(up, key -> new TreeSet<>()).add(stepKey));
		});
		PriorityQueue<String> ready = new PriorityQueue<>();
		indegree.forEach((stepKey, degree) -> {
			if (degree == 0) {
				ready.add(stepKey);
			}
		});
		List<String> order = new ArrayList<>(upstreamByStep.size());
		while (!ready.isEmpty()) {
			String current = ready.poll();
			order.add(current);
			for (String next : downstream.get(current)) {
				int remaining = indegree.merge(next, -1, Integer::sum);
				if (remaining == 0) {
					ready.add(next);
				}
			}
		}
		if (order.size() != upstreamByStep.size()) {
			List<String> cyclic = upstreamByStep.keySet().stream().filter(key -> !order.contains(key)).toList();
			throw new PlanCompileException(PlanCompileException.PLAN_CYCLE_DETECTED,
					"Route proposal dependencies contain a cycle among steps " + cyclic);
		}
		return order;
	}

	private List<CompiledPlanStep> buildSteps(RouteProposalV2 proposal,
			Map<String, CapabilityDescriptor> capabilityByStep, Map<String, SortedSet<String>> upstreamByStep,
			List<String> executionOrder, PlanCompileContext context) {
		Map<String, RouteProposalStep> stepsByKey = proposal.steps().stream()
			.collect(Collectors.toMap(RouteProposalStep::stepKey, Function.identity()));
		List<CompiledPlanStep> steps = new ArrayList<>(executionOrder.size());
		for (int order = 0; order < executionOrder.size(); order++) {
			String stepKey = executionOrder.get(order);
			RouteProposalStep step = stepsByKey.get(stepKey);
			CapabilityDescriptor capability = capabilityByStep.get(stepKey);
			steps.add(new CompiledPlanStep(stepKey, capability.capabilityHandle(), capability.versionRef(),
					step.task(), order, compileBindings(step, capability), List.copyOf(upstreamByStep.get(stepKey)),
					context.stepBudgetTokens()));
		}
		return steps;
	}

	private List<CompiledStepBinding> compileBindings(RouteProposalStep step, CapabilityDescriptor capability) {
		return step.bindings().stream()
			.map(binding -> new CompiledStepBinding(binding.targetPortHandle(), binding.sourceKind(),
					binding.sourceStepKey(), binding.sourcePortHandle(),
					capability.inputPort(binding.targetPortHandle()).valueType()))
			.sorted(BINDING_ORDER)
			.toList();
	}

	/** 步骤⑨:生成字段顺序稳定、无多余空白的 canonical JSON(planHash 的哈希原文)。 */
	private String canonicalJson(RouteProposalV2 proposal, PlanCompileContext context, List<CompiledPlanStep> steps) {
		ObjectNode root = canonicalMapper.createObjectNode();
		root.put("schemaVersion", CompiledPlan.SCHEMA_VERSION);
		root.put("tenantId", context.tenantId());
		root.put("workspaceId", context.workspaceId());
		root.put("userId", context.userId());
		root.put("releaseId", context.releaseId());
		root.put("mode", proposal.mode().name());
		root.put("clarifyQuestion", proposal.clarify() == null ? null : proposal.clarify().question());
		ArrayNode stepsNode = root.putArray("steps");
		steps.forEach(step -> stepsNode.add(canonicalStep(step)));
		try {
			return canonicalMapper.writeValueAsString(root);
		}
		catch (JsonProcessingException ex) {
			throw new PlanCompileException(PlanCompileException.PLAN_CANONICALIZE_FAILED,
					"Compiled plan canonical JSON generation failed", ex);
		}
	}

	private ObjectNode canonicalStep(CompiledPlanStep step) {
		ObjectNode node = canonicalMapper.createObjectNode();
		node.put("stepKey", step.stepKey());
		node.put("capabilityHandle", step.capabilityHandle());
		node.put("capabilityKind", step.versionRef().capabilityKind());
		node.put("capabilityId", step.versionRef().capabilityId());
		node.put("capabilityVersionId", step.versionRef().versionId());
		node.put("executionRefId", step.versionRef().executionRefId());
		node.put("task", step.task());
		ArrayNode dependsOn = node.putArray("dependsOn");
		step.dependsOn().forEach(dependsOn::add);
		ArrayNode bindings = node.putArray("bindings");
		step.bindings().forEach(binding -> bindings.add(canonicalBinding(binding)));
		return node;
	}

	private ObjectNode canonicalBinding(CompiledStepBinding binding) {
		ObjectNode node = canonicalMapper.createObjectNode();
		node.put("targetPortHandle", binding.targetPortHandle());
		node.put("sourceKind", binding.sourceKind().name());
		node.put("sourceStepKey", binding.sourceStepKey());
		node.put("sourcePortHandle", binding.sourcePortHandle());
		node.put("valueType", binding.valueType().name());
		return node;
	}

	/** 步骤⑩:产出不可变 CompiledPlan;风险只取候选集中所用能力的最高风险,审批要求由风险推导。 */
	private CompiledPlan buildPlan(RouteProposalV2 proposal, PlanCompileContext context,
			Map<String, CapabilityDescriptor> capabilityByStep, List<CompiledPlanStep> steps, String canonicalJson,
			String planHash) {
		CapabilityRiskLevel riskLevel = capabilityByStep.values().stream().map(CapabilityDescriptor::riskLevel)
			.reduce(CapabilityRiskLevel.READ_ONLY, CapabilityRiskLevel::highest);
		return new CompiledPlan(planHash, context.tenantId(), context.workspaceId(), context.userId(),
				context.releaseId(), context.policySnapshot(), context.absoluteDeadline(), proposal.mode(),
				proposal.clarify() == null ? null : proposal.clarify().question(), steps, riskLevel,
				riskLevel.requiresApproval(), context.totalBudgetTokens(),
				CompiledPlanIdempotencyPolicy.PLAN_HASH_STEP_KEY, canonicalJson);
	}

}
