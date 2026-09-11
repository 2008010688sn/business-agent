/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.routing.RouteUnavailableException;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentRuntimeErrorClassifierTest {

	@Test
	void classify_returnsAccessDeniedForPlain403() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(httpError(403, ""));

		assertEquals(AgentRuntimeErrorCode.ACCESS_DENIED, error.code());
		assertEquals(403, error.httpStatus());
		assertEquals("agent_runtime_access_denied", error.toMetadata("request-1").get("messageKey"));
	}

	@Test
	void classify_returnsAuthenticationFailedFor401() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(httpError(401, ""));

		assertEquals(AgentRuntimeErrorCode.AUTHENTICATION_FAILED, error.code());
		assertEquals(401, error.httpStatus());
	}

	@Test
	void classify_returnsAuthenticationFailedForLoginExpiredMessage() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(
				new IllegalStateException("Datasource exploration failed: 登录过期,请重新登录"));

		assertEquals(AgentRuntimeErrorCode.AUTHENTICATION_FAILED, error.code());
	}

	@Test
	void classify_returnsQuotaExhaustedWhen403BodyContainsQuota() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(httpError(403, "{\"code\":\"insufficient_quota\",\"message\":\"balance not enough\"}"));

		assertEquals(AgentRuntimeErrorCode.QUOTA_EXHAUSTED, error.code());
		assertEquals(403, error.httpStatus());
		assertEquals(false, error.toMetadata("request-1").get("retryable"));
	}

	@Test
	void classify_returnsRateLimitedFor429() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(httpError(429, ""));

		assertEquals(AgentRuntimeErrorCode.RATE_LIMITED, error.code());
		assertEquals(429, error.httpStatus());
		assertEquals(true, error.toMetadata("request-1").get("retryable"));
	}

	@Test
	void classify_returnsUpstreamUnavailableFor500() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(httpError(500, ""));

		assertEquals(AgentRuntimeErrorCode.UPSTREAM_UNAVAILABLE, error.code());
		assertEquals(500, error.httpStatus());
	}

	@Test
	void classify_returnsLegalRestrictedFor451() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(httpError(451, ""));

		assertEquals(AgentRuntimeErrorCode.LEGAL_RESTRICTED, error.code());
		assertEquals(451, error.httpStatus());
		assertEquals("request-1", error.toMetadata("request-1").get("runtimeRequestId"));
		assertEquals(true, error.toMetadata("request-1").get("discardPartialOutput"));
		assertEquals(false, error.toMetadata("request-1").get("retryable"));
	}

	@Test
	void classify_returnsTimeoutForTimeoutException() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(new RuntimeException(new TimeoutException()));

		assertEquals(AgentRuntimeErrorCode.TIMEOUT, error.code());
		assertEquals("agent_runtime_timeout", error.toMetadata("request-1").get("messageKey"));
	}

	@Test
	void classify_returnsTimeoutForNettyReadTimeoutWithNullMessage() {
		WebClientRequestException requestError = new WebClientRequestException(ReadTimeoutException.INSTANCE,
				HttpMethod.POST, URI.create("https://api.stepfun.com/step_plan/v1/chat/completions"), HttpHeaders.EMPTY);

		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(requestError);

		assertEquals(AgentRuntimeErrorCode.TIMEOUT, error.code());
		assertEquals("agent_runtime_timeout", error.toMetadata("request-1").get("messageKey"));
		assertEquals(true, error.toMetadata("request-1").get("retryable"));
		assertTrue(error.message().contains("模型服务响应超时"));
	}

	@Test
	void classify_returnsTimeoutWhenOrchestratorHasNoWaitBudget() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(new IllegalStateException("编排者没有剩余时间等待协作者"));

		assertEquals(AgentRuntimeErrorCode.TIMEOUT, error.code());
	}

	@Test
	void classify_mapsClarificationExpiredEnglishMessage() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(
				CheckedException.badRequest("Clarification has expired or is not valid for this session"));

		assertEquals(AgentRuntimeErrorCode.CLARIFICATION_EXPIRED, error.code());
		assertEquals("CLARIFICATION_EXPIRED", error.toMetadata("request-1").get("errorCode"));
		assertEquals("CLARIFICATION_EXPIRED", error.toMetadata("request-1").get("messageKey"));
		assertEquals("澄清已过期或已失效，请重新提问。", error.message());
	}

	@Test
	void classify_mapsClarificationConsumedEnglishMessage() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(CheckedException.badRequest("Clarification has already been consumed"));

		assertEquals(AgentRuntimeErrorCode.CLARIFICATION_CONSUMED, error.code());
		assertEquals("CLARIFICATION_CONSUMED", error.toMetadata("request-1").get("errorCode"));
		assertEquals("CLARIFICATION_CONSUMED", error.toMetadata("request-1").get("messageKey"));
		assertEquals("澄清已被使用，请重新提问。", error.message());
	}

	@Test
	void classify_returnsUnknownForUnmatchedException() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(new IllegalStateException("unexpected"));

		assertEquals(AgentRuntimeErrorCode.UNKNOWN, error.code());
	}

	@Test
	void classify_returnsStableModelProtocolError() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(new AgentRuntimeProtocolException("thinking only"));

		assertEquals(AgentRuntimeErrorCode.MODEL_PROTOCOL_ERROR, error.code());
		assertEquals("MODEL_PROTOCOL_ERROR", error.toMetadata("request-1").get("errorCode"));
	}

	@Test
	void classify_returnsEmptyCompletionForBlankModelOutput() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(new AgentRuntimeProtocolException(
				AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getLabel(),
				AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getValue()));

		assertEquals(AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION, error.code());
		assertEquals("MODEL_EMPTY_COMPLETION", error.toMetadata("request-1").get("errorCode"));
		assertTrue(error.message().contains("没有生成可用答案"));
	}

	@Test
	void classify_returnsModelRequestInvalidFor400And422WithoutInspectingBody() {
		AgentRuntimeError badRequest = AgentRuntimeErrorClassifier.classify(httpError(400, "sensitive upstream body"));
		AgentRuntimeError unprocessable = AgentRuntimeErrorClassifier.classify(httpError(422, "sensitive upstream body"));

		assertEquals(AgentRuntimeErrorCode.MODEL_REQUEST_INVALID, badRequest.code());
		assertEquals(false, badRequest.toMetadata("request-1").get("retryable"));
		assertEquals(AgentRuntimeErrorCode.MODEL_REQUEST_INVALID, unprocessable.code());
		assertEquals(422, unprocessable.httpStatus());
	}

	@Test
	void classify_returnsModelRequestInvalidForLocalProviderContractFailure() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(new RuntimeException(new AgentRuntimeModelRequestException("invalid tool name")));

		assertEquals(AgentRuntimeErrorCode.MODEL_REQUEST_INVALID, error.code());
		assertEquals(false, error.toMetadata("request-1").get("retryable"));
	}

	@Test
	void classify_returnsStableBudgetExceededError() {
		AgentRuntimeBudgetExceededException failure = new AgentRuntimeBudgetExceededException(
				AgentRuntimeBudgetExceededException.Reason.MODEL_CALLS, 5L, 5L, 1L);

		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(failure);

		assertEquals(AgentRuntimeErrorCode.BUDGET_EXCEEDED, error.code());
		assertEquals("agent_runtime_budget_exceeded", error.toMetadata("request-1").get("messageKey"));
		assertEquals(false, error.toMetadata("request-1").get("retryable"));
	}

	@Test
	void classify_mapsAgentScopeSummaryBudgetMessage() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(new IllegalStateException(
				"Maximum iterations (10) reached. Error generating summary: Agent runtime budget exceeded: "
						+ "reason=MODEL_CALLS, limit=10, current=10, attempted=1"));

		assertEquals(AgentRuntimeErrorCode.BUDGET_EXCEEDED, error.code());
		assertEquals("本次分析步骤较多，已达到智能体执行上限。请缩小问题范围后重试，或联系管理员调整执行预算。",
				error.message());
	}

	@Test
	void classify_findsBudgetExceededErrorInCauseChain() {
		AgentRuntimeBudgetExceededException failure = new AgentRuntimeBudgetExceededException(
				AgentRuntimeBudgetExceededException.Reason.PROMPT_TOKENS, 60000L, 59000L, 2000L);

		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(new IllegalStateException("reactive wrapper", new RuntimeException(failure)));

		assertEquals(AgentRuntimeErrorCode.BUDGET_EXCEEDED, error.code());
	}

	@Test
	void classify_keepsEachRouteReasonCodeAsAnOperatorDiagnostic() {
		AgentRuntimeError vectorTimeout = AgentRuntimeErrorClassifier
			.classify(new RouteUnavailableException("VECTOR_TIMEOUT"));
		AgentRuntimeError artifactNotReady = AgentRuntimeErrorClassifier
			.classify(new RouteUnavailableException("ROUTE_ARTIFACT_NOT_READY"));

		assertEquals(AgentRuntimeErrorCode.ROUTE_UNAVAILABLE, vectorTimeout.code());
		assertEquals(AgentRuntimeErrorCode.ROUTE_UNAVAILABLE, artifactNotReady.code());
		assertEquals("VECTOR_TIMEOUT", vectorTimeout.diagnosticCode());
		assertEquals("ROUTE_ARTIFACT_NOT_READY", artifactNotReady.diagnosticCode());
	}

	@Test
	void classify_defaultsRouteDiagnosticToTheStableCodeWhenReasonIsMissing() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier.classify(new RouteUnavailableException(null));

		assertEquals(AgentRuntimeErrorCode.ROUTE_UNAVAILABLE, error.code());
		assertEquals("ROUTE_UNAVAILABLE", error.diagnosticCode());
	}

	@Test
	void classify_keepsRouteReasonCodeOutOfUserFacingMessageAndMetadata() {
		AgentRuntimeError error = AgentRuntimeErrorClassifier
			.classify(new RouteUnavailableException("ROUTE_ELIGIBILITY_INVALID"));

		assertEquals(AgentRuntimeErrorCode.ROUTE_UNAVAILABLE.getLabel(), error.message());
		assertFalse(error.message().contains("ROUTE_ELIGIBILITY_INVALID"));
		assertEquals("ROUTE_UNAVAILABLE", error.toMetadata("request-1").get("errorCode"));
		assertFalse(error.toMetadata("request-1").containsValue("ROUTE_ELIGIBILITY_INVALID"));
	}

	private WebClientResponseException httpError(int status, String body) {
		return WebClientResponseException.create(status, "HTTP " + status, HttpHeaders.EMPTY,
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

}
