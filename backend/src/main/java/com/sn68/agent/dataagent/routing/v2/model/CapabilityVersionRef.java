/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

import org.springframework.util.StringUtils;

/**
 * 服务端固定(pin)的能力版本引用,仅存在于服务端上下文与编译产物,不进入模型可见协议。
 */
public record CapabilityVersionRef(String capabilityKind, Long capabilityId, Long versionId, Long executionRefId) {

	public CapabilityVersionRef {
		if (!StringUtils.hasText(capabilityKind)) {
			throw new IllegalArgumentException("Capability version ref requires capabilityKind");
		}
		capabilityKind = capabilityKind.trim();
	}

}
