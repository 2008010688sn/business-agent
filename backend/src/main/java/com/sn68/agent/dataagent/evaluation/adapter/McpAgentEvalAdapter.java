/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.sn68.agent.dataagent.evaluation.enums.AgentEvaluationErrorDict;
import org.springframework.stereotype.Component;

/**
 * MCP Agent 评估适配器禁用态实现。
 */
@Component
public class McpAgentEvalAdapter implements AgentEvalAdapter {

	public static final String ADAPTER_CODE = "MCP_AGENT";

	public static final String SUBJECT_TYPE = "MCP_AGENT";

	@Override
	public String adapterCode() {
		return ADAPTER_CODE;
	}

	@Override
	public boolean supports(String subjectType) {
		return SUBJECT_TYPE.equalsIgnoreCase(subjectType);
	}

	@Override
	public EvalInvocationResult invoke(EvalInvocationPrepared prepared) {
		String runtimeRequestId = "eval-disabled-" + prepared.run().getId() + "-" + prepared.evalCase().getId();
		return new EvalInvocationResult(false, null, null, null, runtimeRequestId, 0L, null, null, null, 0, 0,
				AgentEvaluationErrorDict.ADAPTER_DISABLED.getLabel());
	}

}
