/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.adapter;

import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalSubject;
import java.time.Duration;

/**
 * 评估调用准备结果。
 *
 * @param executionScopeKey DRY_RUN 违规采集作用域键；仅当 run.executionIntent=DRY_RUN 时非空，
 *        适配器需将其与 DRY_RUN 意图一并写入 AgentRequest，供写路径关口跨线程回投违规。
 */
public record EvalInvocationPrepared(DataAgentEvalSubject subject, DataAgentEvalCase evalCase, DataAgentEvalRun run,
		Duration runtimeTimeout, String executionScopeKey) {

	public EvalInvocationPrepared(DataAgentEvalSubject subject, DataAgentEvalCase evalCase, DataAgentEvalRun run,
			Duration runtimeTimeout) {
		this(subject, evalCase, run, runtimeTimeout, null);
	}

}
