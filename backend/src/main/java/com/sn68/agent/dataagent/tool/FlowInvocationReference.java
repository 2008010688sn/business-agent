/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

/**
 * 仅由服务端 FLOW 引擎构造的工具调用引用，用于校验活动实例固定版本。
 */
public record FlowInvocationReference(Long instanceId, Integer expectedLockVersion, String expectedCurrentNodeId,
		String invokingNodeId, String threadId, String userId, String runtimeRequestId) {
}
