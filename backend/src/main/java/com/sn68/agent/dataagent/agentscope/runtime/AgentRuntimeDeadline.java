/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import java.time.Duration;

/**
 * Request-scoped deadline shared by routing, model execution and tools.
 */
public final class AgentRuntimeDeadline {

	private final long startNanos;

	private final long deadlineNanos;

	private AgentRuntimeDeadline(long startNanos, Duration totalTimeout) {
		if (totalTimeout == null || totalTimeout.isNegative() || totalTimeout.isZero()) {
			throw new IllegalArgumentException("Agent runtime total timeout must be greater than zero");
		}
		this.startNanos = startNanos;
		this.deadlineNanos = saturatingAdd(startNanos, totalTimeout.toNanos());
	}

	public static AgentRuntimeDeadline start(Duration totalTimeout) {
		return startAt(System.nanoTime(), totalTimeout);
	}

	public static AgentRuntimeDeadline startAt(long startNanos, Duration totalTimeout) {
		return new AgentRuntimeDeadline(startNanos, totalTimeout);
	}

	/**
	 * Returns a deadline capped from the original request start, never from the
	 * point where this method is called.
	 */
	public AgentRuntimeDeadline capFromStart(Duration totalTimeout) {
		AgentRuntimeDeadline capped = new AgentRuntimeDeadline(startNanos, totalTimeout);
		return capped.deadlineNanos < deadlineNanos ? capped : this;
	}

	public Duration remaining() {
		long nanos = deadlineNanos - System.nanoTime();
		return nanos <= 0 ? Duration.ZERO : Duration.ofNanos(nanos);
	}

	/**
	 * Elapsed wall-clock milliseconds since the request started, never negative.
	 */
	public long elapsedMs() {
		long nanos = System.nanoTime() - startNanos;
		return nanos <= 0L ? 0L : Duration.ofNanos(nanos).toMillis();
	}

	public Duration timeoutFor(Duration componentTimeout, Duration finishBuffer) {
		Duration remaining = remaining();
		Duration buffer = finishBuffer == null || finishBuffer.isNegative() ? Duration.ZERO : finishBuffer;
		Duration usable = remaining.minus(buffer);
		if (usable.isNegative() || usable.isZero()) {
			return Duration.ZERO;
		}
		if (componentTimeout == null || componentTimeout.isNegative() || componentTimeout.isZero()) {
			return usable;
		}
		return componentTimeout.compareTo(usable) <= 0 ? componentTimeout : usable;
	}

	public boolean canStart(Duration finishBuffer) {
		Duration buffer = finishBuffer == null || finishBuffer.isNegative() ? Duration.ZERO : finishBuffer;
		return remaining().compareTo(buffer) > 0;
	}

	private long saturatingAdd(long left, long right) {
		long result = left + right;
		if (((left ^ result) & (right ^ result)) < 0) {
			return Long.MAX_VALUE;
		}
		return result;
	}

}
