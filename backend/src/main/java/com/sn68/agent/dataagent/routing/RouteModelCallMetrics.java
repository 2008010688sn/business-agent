/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Records bounded route-model diagnostics without retaining prompts or provider responses. */
@Component
public class RouteModelCallMetrics {

	private static final double P95 = 0.95D;

	private final MeterRegistry meterRegistry;

	public RouteModelCallMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public static RouteModelCallMetrics noop() {
		return new RouteModelCallMetrics(null);
	}

	public void record(RouteModelCallTelemetry.Snapshot snapshot) {
		if (meterRegistry == null || snapshot == null) {
			return;
		}
		String outcome = valueOrDefault(snapshot.outcome(), "FAILED");
		String timeoutReason = valueOrDefault(snapshot.timeoutReason(), "NONE");
		recordDuration("data.agent.routing.model.call.duration", snapshot.totalMs(), outcome, timeoutReason);
		recordDuration("data.agent.routing.model.executor.queue.duration", snapshot.executorQueueMs(), outcome,
				timeoutReason);
		recordDuration("data.agent.routing.model.connection.lease.duration", snapshot.connectionLeaseMs(), outcome,
				timeoutReason);
		recordDuration("data.agent.routing.model.connection.duration", snapshot.connectionMs(), outcome, timeoutReason);
		recordDuration("data.agent.routing.model.first-byte.duration", snapshot.firstByteMs(), outcome, timeoutReason);
		if (snapshot.requestBytes() >= 0L) {
			DistributionSummary.builder("data.agent.routing.model.request.bytes")
				.publishPercentiles(P95)
				.tag("outcome", outcome)
				.tag("timeout_reason", timeoutReason)
				.register(meterRegistry)
				.record(snapshot.requestBytes());
		}
		Counter.builder("data.agent.routing.model.calls")
			.tag("outcome", outcome)
			.tag("timeout_reason", timeoutReason)
			.register(meterRegistry)
			.increment();
		if (!"NONE".equals(timeoutReason)) {
			Counter.builder("data.agent.routing.model.timeouts")
				.tag("reason", timeoutReason)
				.register(meterRegistry)
				.increment();
		}
	}

	private void recordDuration(String metricName, long durationMs, String outcome, String timeoutReason) {
		if (durationMs < 0L) {
			return;
		}
		Timer.builder(metricName)
			.publishPercentiles(P95)
			.tag("outcome", outcome)
			.tag("timeout_reason", timeoutReason)
			.register(meterRegistry)
			.record(durationMs, TimeUnit.MILLISECONDS);
	}

	private String valueOrDefault(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

}
