/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.tool.datasource;

/**
 * Marks a DETERMINISTIC-only static SQL guard rejection eligible for one repair.
 */
public class DeterministicSqlGuardRejectedException extends IllegalArgumentException {

	public DeterministicSqlGuardRejectedException(String message) {
		super(message);
	}

	public DeterministicSqlGuardRejectedException(String message, Throwable cause) {
		super(message, cause);
	}

}
