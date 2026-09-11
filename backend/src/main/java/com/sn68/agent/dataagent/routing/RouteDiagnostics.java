/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteSemanticMatch;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-local route stage details shared by runtime and preview routing. */
public final class RouteDiagnostics {

	public static final String ARTIFACT_QUERY_DISABLED = "ARTIFACT_QUERY_DISABLED";

	public static final String ARTIFACT_PROFILE_NOT_READY = "ARTIFACT_PROFILE_NOT_READY";

	public static final String ARTIFACT_NOT_READY = "ARTIFACT_NOT_READY";

	public static final String ARTIFACT_CHECKSUM_MISMATCH = "ARTIFACT_CHECKSUM_MISMATCH";

	public static final String ARTIFACT_FINGERPRINT_MISMATCH = "ARTIFACT_FINGERPRINT_MISMATCH";

	public static final String ARTIFACT_READY = "ARTIFACT_READY";

	private List<ScoredCandidate> rankedCandidates = List.of();

	private Map<RouteTargetRef, Double> vectorScores = Map.of();

	private final Map<RouteTargetRef, String> artifactStatuses = new LinkedHashMap<>();

	private final Map<RouteTargetRef, List<String>> ineligibleTargets = new LinkedHashMap<>();

	private boolean artifactQueryExecuted;

	void captureLexical(List<ScoredCandidate> candidates) {
		rankedCandidates = candidates == null ? List.of() : List.copyOf(candidates);
	}

	void captureSemantic(List<ScoredCandidate> candidates, List<RouteSemanticMatch> matches) {
		rankedCandidates = candidates == null ? List.of() : List.copyOf(candidates);
		Map<RouteTargetRef, Double> scores = new LinkedHashMap<>();
		if (matches != null) {
			for (RouteSemanticMatch match : matches) {
				if (match != null && match.target() != null) {
					scores.merge(match.target(), match.score(), Math::max);
				}
			}
		}
		vectorScores = Map.copyOf(scores);
	}

	void markArtifactQueryExecuted() {
		artifactQueryExecuted = true;
	}

	void captureArtifact(RouteTargetRef target, String status) {
		if (target != null && status != null) {
			artifactStatuses.put(target, status);
		}
	}

	/**
	 * 记录被跳过的候选及其生命周期漂移原因（版本缺失、回退草稿、协作者下线等）。
	 *
	 * <p>这些候选不会进入打分，调用方只能从这里知道"某条绑定为什么没参与本轮路由"。
	 */
	void captureIneligible(RouteTargetRef target, List<String> failures) {
		if (target != null && failures != null && !failures.isEmpty()) {
			ineligibleTargets.put(target, List.copyOf(failures));
		}
	}

	public List<ScoredCandidate> rankedCandidates() {
		return rankedCandidates;
	}

	public Double vectorScore(RouteTargetRef target) {
		return vectorScores.get(target);
	}

	public boolean artifactQueryExecuted() {
		return artifactQueryExecuted;
	}

	public String artifactStatus(RouteTargetRef target) {
		return artifactStatuses.get(target);
	}

	public Map<RouteTargetRef, String> artifactStatuses() {
		return Map.copyOf(artifactStatuses);
	}

	public List<String> ineligibleFailures(RouteTargetRef target) {
		return ineligibleTargets.getOrDefault(target, List.of());
	}

	public Map<RouteTargetRef, List<String>> ineligibleTargets() {
		return Map.copyOf(ineligibleTargets);
	}

}
