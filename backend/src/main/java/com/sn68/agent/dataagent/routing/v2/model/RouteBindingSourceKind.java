/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 提案绑定的数据来源类别。
 *
 * <p>USER_INPUT 与 CONSTANT 仅声明取值来源;实际取值一律由服务端在执行期解析并绑定,
 * 提案 JSON 中禁止携带任何字面值(数据库 ID、凭据、幂等键等),见 ProposalForbiddenFieldGuard。
 */
public enum RouteBindingSourceKind {

	/** 上游步骤发布的结构化输出。 */
	STEP_OUTPUT,

	/** 用户输入槽位,由服务端槽位抽取与实体解析后绑定。 */
	USER_INPUT,

	/** 服务端解析的常量,由执行期从会话上下文确定性求值。 */
	CONSTANT

}
