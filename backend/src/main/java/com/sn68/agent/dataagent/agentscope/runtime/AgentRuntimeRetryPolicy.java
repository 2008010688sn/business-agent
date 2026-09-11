/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import io.agentscope.core.model.transport.HttpTransportException;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Explicit retry policy for model calls. It deliberately excludes timeouts and cancellations.
 */
public final class AgentRuntimeRetryPolicy {

	public static final class ModelStreamStartedException extends RuntimeException {

		public ModelStreamStartedException(Throwable cause) {
			super("Model stream failed after the first response chunk", cause);
		}

	}

	private AgentRuntimeRetryPolicy() {
	}

	public static boolean isRetryable(Throwable throwable) {
		if (throwable instanceof ModelStreamStartedException) {
			return false;
		}
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof TimeoutException || current instanceof CancellationException
					|| current instanceof InterruptedException) {
				return false;
			}
			if (current instanceof HttpTransportException transportException) {
				return transportException.isRetryable();
			}
			if (current instanceof ModelHttpException modelHttpException) {
				Integer statusCode = modelHttpException.getStatusCode();
				return statusCode != null && (statusCode == 429 || statusCode >= 500);
			}
			if (current instanceof IOException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

}
