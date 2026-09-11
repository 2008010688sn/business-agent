/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class AgentRuntimeRetryPolicyTest {

	@Test
	void httpTransportExceptionComesFromAgentscopeCoreJar() {
		String location = HttpTransportException.class.getProtectionDomain().getCodeSource().getLocation().toString();
		assertTrue(location.contains("agentscope-core"), () -> "expected jar type, got " + location);
	}

	@Test
	void retriesOnlyTransportRateLimitAndServerErrors() {
		assertTrue(AgentRuntimeRetryPolicy.isRetryable(new IOException("connection reset")));
		assertTrue(AgentRuntimeRetryPolicy.isRetryable(new HttpTransportException("server", 503, "busy")));
		assertTrue(AgentRuntimeRetryPolicy.isRetryable(new ModelHttpException("rate limit", 429, "rate", "busy")));
		assertFalse(AgentRuntimeRetryPolicy.isRetryable(new ModelHttpException("bad request", 400, "bad", "invalid")));
	}

	@Test
	void wrapIfHttpMapsSpringStatusAndLeavesTransportAlone() {
		HttpTransportException transport = new HttpTransportException("server", 503, "busy");
		assertSame(transport, ModelHttpException.wrapIfHttp(transport));

		Throwable tooManyRequests = ModelHttpException.wrapIfHttp(httpError(429));
		assertTrue(tooManyRequests instanceof ModelHttpException);
		assertTrue(AgentRuntimeRetryPolicy.isRetryable(tooManyRequests));

		Throwable badRequest = ModelHttpException.wrapIfHttp(httpError(400));
		assertTrue(badRequest instanceof ModelHttpException);
		assertFalse(AgentRuntimeRetryPolicy.isRetryable(badRequest));
	}

	@Test
	void neverRetriesTimeoutCancellationOrPostStreamFailure() {
		assertFalse(AgentRuntimeRetryPolicy.isRetryable(new TimeoutException("timeout")));
		assertFalse(AgentRuntimeRetryPolicy.isRetryable(new AgentRuntimeRetryPolicy.ModelStreamStartedException(
				new IOException("connection reset after first chunk"))));
	}

	private static WebClientResponseException httpError(int status) {
		return WebClientResponseException.create(status, "HTTP " + status, HttpHeaders.EMPTY, new byte[0],
				StandardCharsets.UTF_8);
	}

}
