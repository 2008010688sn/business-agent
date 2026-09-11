/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing.model;

import java.util.Locale;

/**
 * Controls how an orchestrator may start a collaborator.
 */
public enum DelegationMode {

	AUTO_READ_ONLY,

	PLAN_CONFIRM,

	INTERACTIVE;

	/**
	 * Legacy collaborator rows did not persist a delegation mode and must remain
	 * conservative after the upgrade.
	 */
	public static DelegationMode resolve(String value) {
		if (value == null || value.isBlank()) {
			return INTERACTIVE;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported collaborator delegationMode: " + value, ex);
		}
	}

}
