/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.client5.http.io.ConnectionEndpoint;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;

/**
 * Carries one route-model call's timing across the executor, transport, and Apache
 * HTTP client without leaking it into a request payload or persistent state.
 */
public final class RouteModelCallTelemetry {

	private static final ThreadLocal<Call> ACTIVE = new ThreadLocal<>();

	private static final int MAX_UPSTREAM_REQUEST_ID_LENGTH = 128;

	private RouteModelCallTelemetry() {
	}

	public static Call start() {
		return new Call(System.nanoTime());
	}

	public static Scope attach(Call call) {
		Call previous = ACTIVE.get();
		if (call == null) {
			ACTIVE.remove();
		}
		else {
			ACTIVE.set(call);
		}
		return () -> {
			if (previous == null) {
				ACTIVE.remove();
			}
			else {
				ACTIVE.set(previous);
			}
		};
	}

	public static void markLeaseRequested() {
		current(call -> call.markLeaseRequested(System.nanoTime()));
	}

	public static void markLeaseAcquired(ConnectionEndpoint endpoint) {
		current(call -> call.markLeaseAcquired(System.nanoTime(), endpoint != null && endpoint.isConnected()));
	}

	public static void markConnectionStarted() {
		current(call -> call.markConnectionStarted(System.nanoTime()));
	}

	public static void markConnectionCompleted() {
		current(call -> call.markConnectionCompleted(System.nanoTime()));
	}

	public static void markHttpRequest(EntityDetails entityDetails) {
		long contentLength = entityDetails == null ? -1L : entityDetails.getContentLength();
		current(call -> call.markHttpRequest(System.nanoTime(), contentLength));
	}

	public static void markFirstResponse(HttpResponse response) {
		current(call -> call.markFirstResponse(System.nanoTime(), upstreamRequestId(response)));
	}

	private static void current(java.util.function.Consumer<Call> action) {
		Call call = ACTIVE.get();
		if (call != null) {
			action.accept(call);
		}
	}

	private static String upstreamRequestId(HttpResponse response) {
		if (response == null) {
			return null;
		}
		for (String headerName : new String[] { "x-request-id", "request-id", "x-amzn-requestid", "x-amz-request-id" }) {
			Header header = response.getFirstHeader(headerName);
			if (header != null && header.getValue() != null && !header.getValue().isBlank()) {
				String value = header.getValue().trim();
				return value.length() <= MAX_UPSTREAM_REQUEST_ID_LENGTH ? value
						: value.substring(0, MAX_UPSTREAM_REQUEST_ID_LENGTH);
			}
		}
		return null;
	}

	public interface Scope extends AutoCloseable {

		@Override
		void close();

	}

	public static final class Call {

		private final long submittedNanos;

		private final AtomicLong executorStartedNanos = new AtomicLong();

		private final AtomicLong transportStartedNanos = new AtomicLong();

		private final AtomicLong leaseRequestedNanos = new AtomicLong();

		private final AtomicLong connectionStartedNanos = new AtomicLong();

		private final AtomicLong executorQueueMs = new AtomicLong(-1L);

		private final AtomicLong connectionLeaseMs = new AtomicLong(-1L);

		private final AtomicLong connectionMs = new AtomicLong(-1L);

		private final AtomicLong firstByteMs = new AtomicLong(-1L);

		private final AtomicLong modelCallMs = new AtomicLong(-1L);

		private final AtomicLong requestBytes = new AtomicLong(-1L);

		private final AtomicBoolean finished = new AtomicBoolean();

		private volatile String upstreamRequestId;

		private Call(long submittedNanos) {
			this.submittedNanos = submittedNanos;
		}

		public void markExecutorStarted() {
			long now = System.nanoTime();
			if (executorStartedNanos.compareAndSet(0L, now)) {
				executorQueueMs.compareAndSet(-1L, elapsedMs(submittedNanos, now));
			}
		}

		public void markTransportStarted(String systemPrompt, String userPrompt) {
			markExecutorStarted();
			long now = System.nanoTime();
			transportStartedNanos.compareAndSet(0L, now);
			long promptBytes = utf8Length(systemPrompt) + utf8Length(userPrompt);
			if (promptBytes >= 0L) {
				requestBytes.compareAndSet(-1L, promptBytes);
			}
		}

		public void recordResponseMetadataId(String value) {
			if (upstreamRequestId == null && value != null && !value.isBlank()) {
				String normalized = value.trim();
				upstreamRequestId = normalized.length() <= MAX_UPSTREAM_REQUEST_ID_LENGTH ? normalized
						: normalized.substring(0, MAX_UPSTREAM_REQUEST_ID_LENGTH);
			}
		}

		public boolean finish(String outcome, String timeoutReason) {
			if (!finished.compareAndSet(false, true)) {
				return false;
			}
			long now = System.nanoTime();
			long transportStarted = transportStartedNanos.get();
			if (transportStarted > 0L) {
				modelCallMs.compareAndSet(-1L, elapsedMs(transportStarted, now));
			}
			return true;
		}

		public Snapshot snapshot(String outcome, String timeoutReason) {
			return new Snapshot(Math.max(0L, elapsedMs(submittedNanos, System.nanoTime())), executorQueueMs.get(),
					connectionLeaseMs.get(), connectionMs.get(), firstByteMs.get(), modelCallMs.get(), requestBytes.get(),
					upstreamRequestId, outcome, timeoutReason);
		}

		private void markLeaseRequested(long now) {
			leaseRequestedNanos.compareAndSet(0L, now);
		}

		private void markLeaseAcquired(long now, boolean reusedConnection) {
			long leaseStarted = leaseRequestedNanos.get();
			if (leaseStarted > 0L) {
				connectionLeaseMs.compareAndSet(-1L, elapsedMs(leaseStarted, now));
			}
			if (reusedConnection) {
				connectionMs.compareAndSet(-1L, 0L);
			}
		}

		private void markConnectionStarted(long now) {
			connectionStartedNanos.compareAndSet(0L, now);
		}

		private void markConnectionCompleted(long now) {
			long connectionStarted = connectionStartedNanos.get();
			if (connectionStarted > 0L) {
				connectionMs.compareAndSet(-1L, elapsedMs(connectionStarted, now));
			}
		}

		private void markHttpRequest(long now, long contentLength) {
			if (contentLength >= 0L) {
				requestBytes.set(contentLength);
			}
			transportStartedNanos.compareAndSet(0L, now);
		}

		private void markFirstResponse(long now, String requestId) {
			long transportStarted = transportStartedNanos.get();
			if (transportStarted > 0L) {
				firstByteMs.compareAndSet(-1L, elapsedMs(transportStarted, now));
			}
			recordResponseMetadataId(requestId);
		}

		private long utf8Length(String value) {
			return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
		}
	}

	public record Snapshot(long totalMs, long executorQueueMs, long connectionLeaseMs, long connectionMs,
			long firstByteMs, long modelCallMs, long requestBytes, String upstreamRequestId, String outcome,
			String timeoutReason) {
	}

	private static long elapsedMs(long startedNanos, long nowNanos) {
		return Duration.ofNanos(Math.max(0L, nowNanos - startedNanos)).toMillis();
	}

}
