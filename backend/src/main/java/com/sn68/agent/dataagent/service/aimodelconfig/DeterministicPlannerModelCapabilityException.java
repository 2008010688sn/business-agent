/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.aimodelconfig;

/**
 * Raised when a configured model cannot honor deterministic planner controls.
 */
public class DeterministicPlannerModelCapabilityException extends IllegalArgumentException {

	public DeterministicPlannerModelCapabilityException(String message) {
		super(message);
	}

	public DeterministicPlannerModelCapabilityException(String message, Throwable cause) {
		super(message, cause);
	}

}
