/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.io.LeaseRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

/** Adds timing hooks to the route-model HTTP connection lease and connect lifecycle. */
public final class RouteModelTimingConnectionManager implements HttpClientConnectionManager {

	private final HttpClientConnectionManager delegate;

	public RouteModelTimingConnectionManager(HttpClientConnectionManager delegate) {
		this.delegate = delegate;
	}

	@Override
	public LeaseRequest lease(String id, HttpRoute route, Timeout requestTimeout, Object state) {
		RouteModelCallTelemetry.markLeaseRequested();
		LeaseRequest leaseRequest = delegate.lease(id, route, requestTimeout, state);
		return new LeaseRequest() {
			@Override
			public ConnectionEndpoint get(Timeout timeout)
					throws InterruptedException, ExecutionException, TimeoutException {
				ConnectionEndpoint endpoint = leaseRequest.get(timeout);
				RouteModelCallTelemetry.markLeaseAcquired(endpoint);
				return endpoint;
			}

			@Override
			public boolean cancel() {
				return leaseRequest.cancel();
			}
		};
	}

	@Override
	public void release(ConnectionEndpoint endpoint, Object state, TimeValue keepAlive) {
		delegate.release(endpoint, state, keepAlive);
	}

	@Override
	public void connect(ConnectionEndpoint endpoint, TimeValue connectTimeout, HttpContext context) throws IOException {
		RouteModelCallTelemetry.markConnectionStarted();
		try {
			delegate.connect(endpoint, connectTimeout, context);
		}
		finally {
			RouteModelCallTelemetry.markConnectionCompleted();
		}
	}

	@Override
	public void upgrade(ConnectionEndpoint endpoint, HttpContext context) throws IOException {
		delegate.upgrade(endpoint, context);
	}

	@Override
	public void close(CloseMode closeMode) {
		delegate.close(closeMode);
	}

	@Override
	public void close() throws IOException {
		delegate.close();
	}

}
