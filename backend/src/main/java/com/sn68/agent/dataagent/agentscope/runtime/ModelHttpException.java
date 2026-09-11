/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import io.agentscope.core.model.transport.HttpTransportException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * HTTP failure from a model provider call. Retry only 429 and 5xx via {@link AgentRuntimeRetryPolicy}.
 */
public class ModelHttpException extends RuntimeException {

	private final Integer statusCode;

	private final String errorCode;

	private final String responseBody;

	public ModelHttpException(String message) {
		this(message, null, null, null, null);
	}

	public ModelHttpException(String message, Throwable cause) {
		this(message, null, null, null, cause);
	}

	public ModelHttpException(String message, int statusCode, String responseBody) {
		this(message, statusCode, null, responseBody, null);
	}

	public ModelHttpException(String message, int statusCode, String errorCode, String responseBody) {
		this(message, statusCode, errorCode, responseBody, null);
	}

	public ModelHttpException(String message, String errorCode, String responseBody) {
		this(message, null, errorCode, responseBody, null);
	}

	public ModelHttpException(String message, Integer statusCode, String errorCode, String responseBody,
			Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.errorCode = errorCode;
		this.responseBody = responseBody;
	}

	public static ModelHttpException create(Integer statusCode, String message, String errorCode, String responseBody) {
		if (statusCode == null) {
			return new ModelHttpException(message, errorCode, responseBody);
		}
		return new ModelHttpException(message, statusCode, errorCode, responseBody);
	}

	/**
	 * Map provider HTTP failures onto this type so 429/5xx stay retryable. AgentScope transport
	 * failures are left untouched because {@link AgentRuntimeRetryPolicy} already handles them.
	 */
	public static Throwable wrapIfHttp(Throwable throwable) {
		if (throwable == null || throwable instanceof ModelHttpException
				|| throwable instanceof HttpTransportException) {
			return throwable;
		}
		Throwable current = throwable;
		while (current != null) {
			if (current instanceof ModelHttpException || current instanceof HttpTransportException) {
				return throwable;
			}
			if (current instanceof WebClientResponseException webClientResponseException) {
				return wrapSpringHttp(webClientResponseException.getMessage(),
						webClientResponseException.getStatusCode().value(),
						webClientResponseException.getResponseBodyAsString(), throwable);
			}
			if (current instanceof HttpStatusCodeException httpStatusCodeException) {
				return wrapSpringHttp(httpStatusCodeException.getMessage(),
						httpStatusCodeException.getStatusCode().value(),
						httpStatusCodeException.getResponseBodyAsString(), throwable);
			}
			if (current instanceof RestClientResponseException restClientResponseException) {
				return wrapSpringHttp(restClientResponseException.getMessage(),
						restClientResponseException.getStatusCode().value(),
						restClientResponseException.getResponseBodyAsString(), throwable);
			}
			current = current.getCause();
		}
		return throwable;
	}

	public Integer getStatusCode() {
		return statusCode;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public String getResponseBody() {
		return responseBody;
	}

	private static ModelHttpException wrapSpringHttp(String message, int statusCode, String responseBody,
			Throwable cause) {
		return new ModelHttpException(message, statusCode, null, responseBody, cause);
	}

}
