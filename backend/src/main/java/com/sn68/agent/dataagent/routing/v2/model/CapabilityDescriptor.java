/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 服务端附加的单个候选能力:对模型只暴露 capabilityHandle,端口/风险/版本均为服务端事实。
 *
 * <p>orchestration=true 表示该能力自身会再展开一层编排,用于嵌套编排深度约束。
 */
public record CapabilityDescriptor(String capabilityHandle, List<PortSpec> inputPorts, List<PortSpec> outputPorts,
		CapabilityRiskLevel riskLevel, CapabilityVersionRef versionRef, boolean orchestration) {

	public CapabilityDescriptor {
		if (!StringUtils.hasText(capabilityHandle)) {
			throw new IllegalArgumentException("Capability descriptor requires capabilityHandle");
		}
		if (versionRef == null) {
			throw new IllegalArgumentException("Capability descriptor requires a pinned versionRef");
		}
		capabilityHandle = capabilityHandle.trim();
		inputPorts = copyDistinct(inputPorts, "input");
		outputPorts = copyDistinct(outputPorts, "output");
		riskLevel = riskLevel == null ? CapabilityRiskLevel.UNKNOWN : riskLevel;
	}

	/** 按句柄查找输入端口,不存在返回 null。 */
	public PortSpec inputPort(String portHandle) {
		return find(inputPorts, portHandle);
	}

	/** 按句柄查找输出端口,不存在返回 null。 */
	public PortSpec outputPort(String portHandle) {
		return find(outputPorts, portHandle);
	}

	private static PortSpec find(List<PortSpec> ports, String portHandle) {
		if (portHandle == null) {
			return null;
		}
		return ports.stream().filter(port -> port.portHandle().equals(portHandle)).findFirst().orElse(null);
	}

	private static List<PortSpec> copyDistinct(List<PortSpec> ports, String kind) {
		List<PortSpec> copied = ports == null ? List.of() : List.copyOf(ports);
		if (copied.stream().map(PortSpec::portHandle).distinct().count() != copied.size()) {
			throw new IllegalArgumentException("Capability " + kind + " ports must use distinct handles");
		}
		return copied;
	}

}
