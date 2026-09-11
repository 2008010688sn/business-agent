/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.repository;

/** Persistence status values for route continuation state. */
public final class RoutePendingStatus {

	public static final String PENDING = "PENDING";

	public static final String CONSUMED = "CONSUMED";

	public static final String EXPIRED = "EXPIRED";

	private RoutePendingStatus() {
	}

}
