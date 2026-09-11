/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RoutePolicy;
import com.sn68.agent.dataagent.routing.model.RouteSemanticMatch;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.service.vectorstore.AgentVectorStoreService;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class DefaultRouteSemanticService implements RouteSemanticService {

	private final AgentVectorStoreService vectorStoreService;

	private final ExecutorService routeRetrievalExecutor;

	private final RouteEmbeddingModelResolver embeddingModelResolver;

	public DefaultRouteSemanticService(AgentVectorStoreService vectorStoreService,
			@Qualifier("routeRetrievalExecutor") ExecutorService routeRetrievalExecutor,
			RouteEmbeddingModelResolver embeddingModelResolver) {
		this.vectorStoreService = vectorStoreService;
		this.routeRetrievalExecutor = routeRetrievalExecutor;
		this.embeddingModelResolver = embeddingModelResolver;
	}

	@Override
	public List<RouteSemanticMatch> search(RouteContext context, RoutePolicy policy,
			List<RouteCandidate> eligibleCandidates, int topK, Duration timeout) {
		validate(context, policy, eligibleCandidates, topK, timeout);
		Map<Long, RouteCandidate> candidatesByArtifact = eligibleCandidates.stream()
			.collect(Collectors.toMap(RouteCandidate::routeArtifactId, Function.identity(), (left, right) -> {
				throw new RouteStageException("ROUTE_ARTIFACT_DUPLICATE",
						"Eligible route candidates contain a duplicate Artifact");
			}, LinkedHashMap::new));
		Future<List<Document>> future;
		try {
			future = routeRetrievalExecutor.submit(() -> {
				EmbeddingModel embeddingModel = embeddingModelResolver.resolve(policy.embeddingModelConfigId(),
						policy.embeddingFingerprint(), timeout);
				return vectorStoreService.searchRouteDocuments(context.tenantId(), policy.profileId(),
						policy.embeddingFingerprint(), candidatesByArtifact.keySet(), context.query(), topK,
						policy.vectorRecallThreshold(), embeddingModel);
			});
		}
		catch (RejectedExecutionException ex) {
			throw new RouteStageException("VECTOR_EXECUTOR_REJECTED", "Route vector executor rejected the task", ex);
		}
		try {
			return toMatches(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS), candidatesByArtifact);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new RouteStageException("VECTOR_TIMEOUT", "Route vector search timed out", ex);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new RouteStageException("VECTOR_INTERRUPTED", "Route vector search was interrupted", ex);
		}
		catch (ExecutionException ex) {
			throw new RouteStageException("VECTOR_UNAVAILABLE", "Route vector search failed", ex.getCause());
		}
	}

	private List<RouteSemanticMatch> toMatches(List<Document> documents, Map<Long, RouteCandidate> candidates) {
		Map<RouteTargetRef, Double> scores = new LinkedHashMap<>();
		for (Document document : documents == null ? List.<Document>of() : documents) {
			Long artifactId = artifactId(document);
			RouteCandidate candidate = candidates.get(artifactId);
			if (candidate == null) {
				throw new RouteStageException("VECTOR_ELIGIBILITY_BREACH",
						"Route vector search returned an Artifact outside the eligible whitelist");
			}
			double score = document.getScore() == null ? 0D : document.getScore();
			scores.merge(candidate.target(), score, Math::max);
		}
		return scores.entrySet().stream()
			.map(entry -> new RouteSemanticMatch(entry.getKey(), entry.getValue()))
			.sorted(Comparator.comparingDouble(RouteSemanticMatch::score).reversed())
			.toList();
	}

	private Long artifactId(Document document) {
		Object value = document == null ? null
				: document.getMetadata().get(DocumentMetadataConstant.ROUTE_ARTIFACT_ID);
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			throw new RouteStageException("VECTOR_INVALID_METADATA", "Route vector result has an invalid Artifact id",
					ex);
		}
	}

	private void validate(RouteContext context, RoutePolicy policy, Collection<RouteCandidate> candidates, int topK,
			Duration timeout) {
		if (context == null || policy == null || candidates == null || candidates.isEmpty() || topK < 1
				|| timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new RouteStageException("VECTOR_INVALID_REQUEST", "Route vector request is invalid");
		}
		boolean invalid = candidates.stream().anyMatch(candidate -> candidate == null
				|| candidate.routeArtifactId() == null
				|| !Objects.equals(context.tenantId(), candidate.tenantId())
				|| !Objects.equals(policy.profileId(), candidate.routeProfileId())
				|| !Objects.equals(policy.embeddingFingerprint(), candidate.embeddingFingerprint()));
		if (invalid) {
			throw new RouteStageException("VECTOR_ELIGIBILITY_INVALID",
					"Route vector candidates do not match the request boundary");
		}
	}
}
