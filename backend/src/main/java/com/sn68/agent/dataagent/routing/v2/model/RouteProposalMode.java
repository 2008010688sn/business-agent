/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * RouteProposalV2 运行模式,对应方案第三章 FrontDoor 的六种运行模式。
 *
 * <p>CHAT 闲聊问答、DIRECT 高置信度单能力、AGENT_LOOP 单智能体受限 ReAct、
 * ORCHESTRATION 一次规划的多能力编排、FLOW 确定性业务流程、CLARIFY_RESUME 澄清/审批/恢复(不重新路由)。
 */
public enum RouteProposalMode {

	CHAT,

	DIRECT,

	AGENT_LOOP,

	ORCHESTRATION,

	FLOW,

	CLARIFY_RESUME

}
