/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteModelResult;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DefaultRouteModelClient implements RouteModelClient {

	private final ModelConfigDataService modelConfigDataService;

	private final RouteModelAdapter modelAdapter;

	private final RouteModelFingerprint modelFingerprint;

	private final ExecutorService routeModelExecutor;

	public DefaultRouteModelClient(ModelConfigDataService modelConfigDataService, RouteModelAdapter modelAdapter,
			RouteModelFingerprint modelFingerprint,
			@Qualifier("routeModelExecutor") ExecutorService routeModelExecutor) {
		this.modelConfigDataService = modelConfigDataService;
		this.modelAdapter = modelAdapter;
		this.modelFingerprint = modelFingerprint;
		this.routeModelExecutor = routeModelExecutor;
	}

	@Override
	public RouteModelResult disambiguate(RouteContext context, RoutePolicy policy, List<ScoredCandidate> candidates,
			Duration timeout) {
		validate(context, policy, candidates, timeout);
		ModelConfigDTO config = modelConfigDataService.getRuntimeConfigById(policy.routeModelConfigId(), ModelType.CHAT);
		if (!policy.routeModelFingerprint().equals(modelFingerprint.calculate(config))) {
			throw new RouteStageException("MODEL_UNAVAILABLE", "Route model capability is stale");
		}
		RouteModelCallTelemetry.Call telemetry = RouteModelCallTelemetry.start();
		Future<RouteModelResult> future;
		try {
			future = routeModelExecutor.submit(() -> {
				telemetry.markExecutorStarted();
				return modelAdapter.disambiguate(config, policy.routeModelProtocol(), context, candidates, timeout, telemetry);
			});
		}
		catch (RejectedExecutionException ex) {
			throw new RouteStageException("MODEL_EXECUTOR_REJECTED", "Route model executor rejected the task", ex);
		}
		try {
			return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			modelAdapter.recordLocalTimeout(telemetry, "ROUTE_MODEL_LOCAL_TIMEOUT");
			throw new RouteStageException("MODEL_TIMEOUT", "Route model call timed out", ex);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new RouteStageException("MODEL_INTERRUPTED", "Route model call was interrupted", ex);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RouteStageException routeStageException) {
				throw routeStageException;
			}
			throw new RouteStageException("MODEL_UNAVAILABLE", "Route model call failed", cause);
		}
	}

	private void validate(RouteContext context, RoutePolicy policy, List<ScoredCandidate> candidates,
			Duration timeout) {
		if (context == null || policy == null || policy.profileId() == null || policy.routeModelConfigId() == null
				|| !policy.routeModelRuntimeReady() || policy.routeModelProtocol() == null
				|| policy.routeModelProtocol() == RouteModelOutputProtocol.NONE
				|| policy.routeModelFingerprint() == null || candidates == null || candidates.isEmpty()
				|| candidates.size() > HybridRouteEngine.MODEL_CANDIDATE_LIMIT || timeout == null || timeout.isZero()
				|| timeout.isNegative()) {
			throw new RouteStageException("MODEL_UNAVAILABLE", "Route model request is not eligible");
		}
	}

}
