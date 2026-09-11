/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

/**
 * Agent 评估适配器。
 */
public interface AgentEvalAdapter {

	/**
	 * 处理Agent评测。
	 */
	String adapterCode();

	/**
	 * 处理Agent评测。
	 */
	boolean supports(String subjectType);

	/**
	 * 处理Agent评测。
	 */
	EvalInvocationResult invoke(EvalInvocationPrepared prepared);

}
