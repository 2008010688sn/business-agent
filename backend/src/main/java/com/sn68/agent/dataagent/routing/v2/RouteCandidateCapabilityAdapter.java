/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityCandidateSet;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityDescriptor;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityRiskLevel;
import com.sn68.agent.dataagent.routing.v2.model.CapabilityVersionRef;
import com.sn68.agent.dataagent.routing.v2.model.PortSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1 RouteCandidate → V2 CapabilityCandidateSet 只读适配器:仅读取 V1 模型,不改动 V1 任何类。
 *
 * <p>能力句柄按候选顺序生成不透明的 "cap-N",避免把数据库 ID 暴露进模型可见协议;
 * 风险等级取 V1 服务端判定(RouteRiskResolver 产出),版本引用来自 RouteTargetRef 固定版本。
 * V1 候选未声明端口,默认空端口;需要端口校验的调用方使用 toDescriptor 显式补充 PortSpec。
 */
public final class RouteCandidateCapabilityAdapter {

	private static final String HANDLE_PREFIX = "cap-";

	private RouteCandidateCapabilityAdapter() {
	}

	/** 将 V1 候选列表适配为候选能力集,句柄按顺序生成 cap-1、cap-2…,端口留空。 */
	public static CapabilityCandidateSet toCandidateSet(List<RouteCandidate> candidates) {
		List<RouteCandidate> source = candidates == null ? List.of() : candidates;
		List<CapabilityDescriptor> descriptors = new ArrayList<>(source.size());
		for (int index = 0; index < source.size(); index++) {
			descriptors.add(toDescriptor(HANDLE_PREFIX + (index + 1), source.get(index), List.of(), List.of()));
		}
		return CapabilityCandidateSet.of(descriptors);
	}

	/**
	 * 生成与 {@link #toCandidateSet} 同一规则的 target → handle 反查映射,供影子链路把
	 * V1 决策目标翻译为不透明能力句柄;同一 target 重复出现时保留首个句柄(与候选集共存,语义一致)。
	 */
	public static Map<RouteTargetRef, String> toHandleByTarget(List<RouteCandidate> candidates) {
		List<RouteCandidate> source = candidates == null ? List.of() : candidates;
		Map<RouteTargetRef, String> handles = new LinkedHashMap<>();
		for (int index = 0; index < source.size(); index++) {
			RouteCandidate candidate = source.get(index);
			if (candidate != null && candidate.target() != null) {
				handles.putIfAbsent(candidate.target(), HANDLE_PREFIX + (index + 1));
			}
		}
		return handles;
	}

	/** 将单个 V1 候选适配为能力描述,由调用方指定不透明句柄与服务端端口定义。 */
	public static CapabilityDescriptor toDescriptor(String capabilityHandle, RouteCandidate candidate,
			List<PortSpec> inputPorts, List<PortSpec> outputPorts) {
		if (candidate == null) {
			throw new IllegalArgumentException("Route candidate is required");
		}
		return new CapabilityDescriptor(capabilityHandle, inputPorts, outputPorts, toRiskLevel(candidate.risk()),
				toVersionRef(candidate.target()), false);
	}

	/** V1 风险 → V2 风险分级;无法判定时按 UNKNOWN 最保守处理。 */
	public static CapabilityRiskLevel toRiskLevel(RouteRisk risk) {
		if (risk == null) {
			return CapabilityRiskLevel.UNKNOWN;
		}
		return switch (risk) {
			case READ_ONLY -> CapabilityRiskLevel.READ_ONLY;
			case WRITE -> CapabilityRiskLevel.WRITE;
			case FLOW -> CapabilityRiskLevel.FLOW;
			case DELEGATED -> CapabilityRiskLevel.DELEGATED;
			case UNKNOWN -> CapabilityRiskLevel.UNKNOWN;
		};
	}

	private static CapabilityVersionRef toVersionRef(RouteTargetRef target) {
		if (target == null || target.targetType() == null) {
			throw new IllegalArgumentException("Route candidate target is required");
		}
		return new CapabilityVersionRef(target.targetType().name(), target.targetId(), target.targetVersionId(),
				target.executionRefId());
	}

}
