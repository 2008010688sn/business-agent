/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端附加的候选能力集,提案中的 capabilityHandle 必须命中本集合才允许编译。
 */
public record CapabilityCandidateSet(Map<String, CapabilityDescriptor> capabilities) {

	public CapabilityCandidateSet {
		capabilities = capabilities == null ? Map.of() : Map.copyOf(capabilities);
		capabilities.forEach((handle, descriptor) -> {
			if (descriptor == null || !descriptor.capabilityHandle().equals(handle)) {
				throw new IllegalArgumentException("Capability candidate set key must match descriptor handle");
			}
		});
	}

	public static CapabilityCandidateSet of(List<CapabilityDescriptor> descriptors) {
		Map<String, CapabilityDescriptor> byHandle = new LinkedHashMap<>();
		for (CapabilityDescriptor descriptor : descriptors == null ? List.<CapabilityDescriptor>of() : descriptors) {
			if (descriptor == null || byHandle.put(descriptor.capabilityHandle(), descriptor) != null) {
				throw new IllegalArgumentException("Capability candidate set contains a null or duplicate descriptor");
			}
		}
		return new CapabilityCandidateSet(byHandle);
	}

	/** 按能力句柄查找候选,不存在返回 null。 */
	public CapabilityDescriptor find(String capabilityHandle) {
		return capabilityHandle == null ? null : capabilities.get(capabilityHandle.trim());
	}

	public boolean isEmpty() {
		return capabilities.isEmpty();
	}

}
