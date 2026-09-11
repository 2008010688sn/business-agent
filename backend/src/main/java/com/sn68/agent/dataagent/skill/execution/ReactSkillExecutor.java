/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * REACT continues through the mature AgentScope runtime after routing pins the selected Skill.
 */
@Component
@RequiredArgsConstructor
public class ReactSkillExecutor implements SkillExecutor {

	private final SkillBusinessContextService businessContextService;

	@Override
	public SkillExecutionMode supports() {
		return SkillExecutionMode.REACT;
	}

	@Override
	public SkillExecutionResult execute(SkillExecutionContext context) {
		if (!context.hasMatchingRoutedSnapshot()) {
			return new SkillExecutionResult(true, "Published Skill resource snapshot is required", null,
					"REACT_RESOURCE_SNAPSHOT_MISSING", SkillExecutionOutcome.FAILED);
		}
		businessContextService.prepare(context.request(), context.resources());
		return SkillExecutionResult.continueRuntime();
	}

}
