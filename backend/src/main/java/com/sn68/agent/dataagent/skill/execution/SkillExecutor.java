/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.skill.SkillExecutionMode;

/**
 * Mode-specific Skill executor.
 */
public interface SkillExecutor {

	SkillExecutionMode supports();

	SkillExecutionResult execute(SkillExecutionContext context);

}
