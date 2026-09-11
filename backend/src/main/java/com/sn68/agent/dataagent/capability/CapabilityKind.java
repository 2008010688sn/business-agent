/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

/**
 * 能力种类。统一能力网关按调用入口区分能力种类，用于白名单、检查链策略与审计分类。
 */
public enum CapabilityKind {

	/** AgentScope / Spring AI 工具调用。 */
	TOOL,

	/** Skill Executor 调用。 */
	SKILL,

	/** Flow Action 调用。 */
	FLOW,

	/** Runtime Hook 动作调用。 */
	HOOK,

	/** 自动任务调用。 */
	TASK,

	/** IM 指令调用。 */
	IM_COMMAND,

	/** API 触发器调用。 */
	API_TRIGGER

}
