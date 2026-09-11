/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

/**
 * FLOW 对当前 Turn 的结果，不等同于可跨 Turn 保持 WAITING 的实例状态。
 */
public enum FlowTurnOutcome {

	WAITING,

	SUCCEEDED,

	FAILED

}
