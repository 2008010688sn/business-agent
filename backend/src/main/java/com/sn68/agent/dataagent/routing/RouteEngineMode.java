/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

/**
 * 混合路由引擎的协议切流模式（方案第四章：legacy | shadow | native 灰度切流）。
 *
 * <p>LEGACY：仅走 V1 链路，无任何 V2 开销（默认）；
 * SHADOW：V1 决策照常返回、主链路不变，异步把 V1 决策确定性转换为 RouteProposalV2
 * 并经 PlanCompiler 编译，记录两代协议的对比结果；
 * NATIVE：V2 编译链路直接驱动执行——本轮未实现执行链路，配置后启动校验拒绝
 * （见 {@link RouteEngineModeProperties}），枚举值保留给后续切流。
 */
public enum RouteEngineMode {

	LEGACY,

	SHADOW,

	NATIVE

}
