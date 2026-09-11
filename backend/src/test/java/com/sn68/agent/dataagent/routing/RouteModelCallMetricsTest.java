/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.hc.core5.http.message.BasicHttpResponse;
import org.junit.jupiter.api.Test;

class RouteModelCallMetricsTest {

	@Test
	void recordsBoundedHttpTelemetryWithRequestIdAndP95Timers() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RouteModelCallMetrics metrics = new RouteModelCallMetrics(registry);
		RouteModelCallTelemetry.Call call = RouteModelCallTelemetry.start();
		call.markExecutorStarted();
		call.markTransportStarted("system", "user");
		try (RouteModelCallTelemetry.Scope ignored = RouteModelCallTelemetry.attach(call)) {
			RouteModelCallTelemetry.markLeaseRequested();
			RouteModelCallTelemetry.markLeaseAcquired(null);
			RouteModelCallTelemetry.markConnectionStarted();
			RouteModelCallTelemetry.markConnectionCompleted();
			BasicHttpResponse response = new BasicHttpResponse(200);
			response.addHeader("x-request-id", "upstream-request-1");
			RouteModelCallTelemetry.markFirstResponse(response);
		}

		assertTrue(call.finish("SUCCESS", null));
		RouteModelCallTelemetry.Snapshot snapshot = call.snapshot("SUCCESS", null);
		metrics.record(snapshot);

		assertEquals("upstream-request-1", snapshot.upstreamRequestId());
		assertTrue(snapshot.requestBytes() >= "systemuser".length());
		assertEquals(1L, registry.get("data.agent.routing.model.call.duration").timer().count());
		assertEquals(1D, registry.get("data.agent.routing.model.calls")
			.tags("outcome", "SUCCESS", "timeout_reason", "NONE").counter().count());
	}

	@Test
	void countsThePlatformRouteBudgetTimeoutSeparately() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		RouteModelCallMetrics metrics = new RouteModelCallMetrics(registry);
		RouteModelCallTelemetry.Call call = RouteModelCallTelemetry.start();
		call.markExecutorStarted();

		assertTrue(call.finish("TIMED_OUT", "ROUTE_MODEL_LOCAL_TIMEOUT"));
		metrics.record(call.snapshot("TIMED_OUT", "ROUTE_MODEL_LOCAL_TIMEOUT"));

		assertEquals(1D, registry.get("data.agent.routing.model.timeouts")
			.tags("reason", "ROUTE_MODEL_LOCAL_TIMEOUT").counter().count());
	}

}
