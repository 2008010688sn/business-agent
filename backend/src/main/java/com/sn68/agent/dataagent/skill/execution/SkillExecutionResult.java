/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill.execution;

import com.sn68.agent.dataagent.ui.AgentUiMessage;

/**
 * Skill execution outcome. Unhandled results continue through the existing AgentScope runtime.
 */
public record SkillExecutionResult(boolean handled, String answer, AgentUiMessage uiMessage, String stageCode,
		SkillExecutionOutcome outcome) {

	public SkillExecutionResult(boolean handled, String answer, AgentUiMessage uiMessage, String stageCode) {
		this(handled, answer, uiMessage, stageCode,
				handled ? SkillExecutionOutcome.SUCCEEDED : null);
	}

	public static SkillExecutionResult continueRuntime() {
		return new SkillExecutionResult(false, null, null, null, null);
	}

}
