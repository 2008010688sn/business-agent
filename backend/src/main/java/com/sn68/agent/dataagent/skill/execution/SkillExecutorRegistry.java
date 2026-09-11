/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves exactly one executor per Skill mode.
 */
@Component
public class SkillExecutorRegistry {

	private final Map<SkillExecutionMode, SkillExecutor> executors;

	public SkillExecutorRegistry(List<SkillExecutor> executors) {
		Map<SkillExecutionMode, SkillExecutor> result = new EnumMap<>(SkillExecutionMode.class);
		for (SkillExecutor executor : executors) {
			if (result.put(executor.supports(), executor) != null) {
				throw new IllegalStateException("Duplicate Skill executor for " + executor.supports());
			}
		}
		this.executors = Map.copyOf(result);
	}

	public SkillExecutor required(SkillExecutionMode mode) {
		SkillExecutor executor = executors.get(mode);
		if (executor == null) {
			throw new IllegalStateException("No Skill executor is registered for " + mode);
		}
		return executor;
	}

}
