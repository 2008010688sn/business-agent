/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RouteScorer {

	private static final int ALIAS_EXACT_SCORE = 95;

	private static final int POSITIVE_EXACT_SCORE = 90;

	private static final int PHRASE_SCORE = 70;

	private static final int NAME_OR_ALIAS_SCORE = 65;

	private static final int NEGATIVE_SCORE = 50;

	private static final int CONTINUITY_SCORE = 8;

	private final RouteTextNormalizer normalizer;

	public RouteScorer(RouteTextNormalizer normalizer) {
		this.normalizer = normalizer;
	}

	public List<ScoredCandidate> score(RouteContext context, List<RouteCandidate> candidates) {
		String query = normalizer.normalize(context == null ? null : context.query());
		if (query.isBlank() || candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		return candidates.stream()
			.map(candidate -> scoreCandidate(context, candidate, query))
			.sorted(scoredComparator())
			.toList();
	}

	private ScoredCandidate scoreCandidate(RouteContext context, RouteCandidate candidate, String query) {
		RouteRules rules = candidate.rules();
		List<String> hardExcludes = normalizedDistinct(rules.hardExcludes());
		List<String> matchedHardExcludes = hardExcludes.stream().filter(value -> containsPhrase(query, value)).toList();
		List<String> hardExcludePatterns = normalizedPatterns(rules.hardExcludePatterns());
		List<String> matchedHardExcludePatterns = hardExcludePatterns.stream().filter(pattern -> matchesPattern(query, pattern))
			.toList();
		if (!matchedHardExcludes.isEmpty() || !matchedHardExcludePatterns.isEmpty()) {
			List<String> signals = new ArrayList<>();
			signals.addAll(matchedHardExcludes.stream().map(value -> "hardExclude:" + value).toList());
			signals.addAll(matchedHardExcludePatterns.stream().map(value -> "hardExcludePattern:" + value).toList());
			return new ScoredCandidate(candidate, 0, false, true,
					List.copyOf(signals));
		}

		boolean exact = normalizedDistinct(rules.exact()).contains(query);
		Map<String, Integer> positiveScores = new HashMap<>();
		Map<String, String> positiveSignals = new HashMap<>();
		addExactSignals(query, rules.aliases(), ALIAS_EXACT_SCORE, "aliasExact", positiveScores, positiveSignals);
		addExactSignals(query, rules.positiveExamples(), POSITIVE_EXACT_SCORE, "positiveExact", positiveScores,
				positiveSignals);
		addContainsSignals(query, rules.phrases(), PHRASE_SCORE, "phrase", positiveScores, positiveSignals);
		addPatternSignals(query, rules.positivePatterns(), PHRASE_SCORE, positiveScores, positiveSignals);
		addContainsSignals(query, rules.aliases(), NAME_OR_ALIAS_SCORE, "alias", positiveScores, positiveSignals);
		addContainsSignals(query, candidate.name() == null ? List.of() : List.of(candidate.name()),
				NAME_OR_ALIAS_SCORE, "name", positiveScores,
				positiveSignals);

		int score = positiveScores.values().stream().mapToInt(Integer::intValue).sum();
		Set<String> signals = new LinkedHashSet<>(positiveSignals.values());
		for (String negative : normalizedDistinct(rules.negativeExamples())) {
			if (containsPhrase(query, negative)) {
				score -= NEGATIVE_SCORE;
				signals.add("negative:" + negative);
			}
		}
		if (isContinuity(context, candidate, query)) {
			score += CONTINUITY_SCORE;
			signals.add("continuity");
		}
		return new ScoredCandidate(candidate, Math.max(0, score), exact, false, List.copyOf(signals));
	}

	private void addExactSignals(String query, List<String> values, int score, String signal,
			Map<String, Integer> positiveScores, Map<String, String> positiveSignals) {
		for (String value : normalizedDistinct(values)) {
			if (query.equals(value)) {
				addPositive(value, score, signal, positiveScores, positiveSignals);
			}
		}
	}

	private void addContainsSignals(String query, List<String> values, int score, String signal,
			Map<String, Integer> positiveScores, Map<String, String> positiveSignals) {
		for (String value : normalizedDistinct(values)) {
			if (containsPhrase(query, value)) {
				addPositive(value, score, signal, positiveScores, positiveSignals);
			}
		}
	}

	private void addPatternSignals(String query, List<String> values, int score, Map<String, Integer> positiveScores,
			Map<String, String> positiveSignals) {
		for (String pattern : normalizedPatterns(values)) {
			if (matchesPattern(query, pattern)) {
				addPositive(pattern, score, "positivePattern", positiveScores, positiveSignals);
			}
		}
	}

	private void addPositive(String value, int score, String signal, Map<String, Integer> positiveScores,
			Map<String, String> positiveSignals) {
		Integer current = positiveScores.get(value);
		if (current == null || score > current) {
			positiveScores.put(value, score);
			positiveSignals.put(value, signal + ":" + value);
		}
	}

	private boolean containsPhrase(String query, String phrase) {
		if (phrase == null || phrase.isBlank()) {
			return false;
		}
		int index = query.indexOf(phrase);
		while (index >= 0) {
			int before = index == 0 ? -1 : query.codePointBefore(index);
			int end = index + phrase.length();
			int after = end >= query.length() ? -1 : query.codePointAt(end);
			if (hasHan(phrase) || isBoundary(before) && isBoundary(after)) {
				return true;
			}
			index = query.indexOf(phrase, index + 1);
		}
		return false;
	}

	private boolean isBoundary(int codePoint) {
		return codePoint < 0 || !Character.isLetterOrDigit(codePoint);
	}

	private boolean hasHan(String value) {
		return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
	}

	private boolean isContinuity(RouteContext context, RouteCandidate candidate, String query) {
		if (context == null || context.previousTarget() == null || context.previousQuery() == null
				|| !sameTarget(context.previousTarget(), candidate.target())) {
			return false;
		}
		if (AnalysisSessionContinuation.matches(query)) {
			return true;
		}
		if (query.length() > 32) {
			return false;
		}
		return query.startsWith("那") || query.startsWith("再") || query.startsWith("改成") || query.startsWith("换成")
				|| query.endsWith("呢") || query.contains("上个月") || query.contains("上周") || query.contains("去年");
	}

	private boolean sameTarget(com.sn68.agent.dataagent.routing.model.RouteTargetRef previous,
			com.sn68.agent.dataagent.routing.model.RouteTargetRef current) {
		return current != null && previous.targetType() == current.targetType()
				&& Objects.equals(previous.targetId(), current.targetId())
				&& Objects.equals(previous.targetVersionId(), current.targetVersionId());
	}

	private List<String> normalizedDistinct(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		Set<String> result = new LinkedHashSet<>();
		for (String value : values) {
			String normalized = normalizer.normalize(value);
			if (!normalized.isBlank()) {
				result.add(normalized);
			}
		}
		return List.copyOf(result);
	}

	private List<String> normalizedPatterns(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		Set<String> result = new LinkedHashSet<>();
		for (String value : values) {
			if (value == null || value.isBlank()) {
				continue;
			}
			String[] segments = value.split("\\*", -1);
			StringBuilder normalized = new StringBuilder(value.length());
			for (int index = 0; index < segments.length; index++) {
				if (index > 0) {
					normalized.append('*');
				}
				normalized.append(normalizer.normalize(segments[index]));
			}
			if (normalized.length() > 0) {
				result.add(normalized.toString());
			}
		}
		return List.copyOf(result);
	}

	private boolean matchesPattern(String query, String pattern) {
		String[] segments = pattern.split("\\*", -1);
		boolean startsWithWildcard = pattern.startsWith("*");
		boolean endsWithWildcard = pattern.endsWith("*");
		int firstSegment = startsWithWildcard ? 1 : 0;
		int lastSegment = endsWithWildcard ? segments.length - 1 : segments.length;
		int offset = 0;
		for (int index = firstSegment; index < lastSegment; index++) {
			String segment = segments[index];
			int match = query.indexOf(segment, offset);
			if (match < 0 || index == firstSegment && !startsWithWildcard && match != 0) {
				return false;
			}
			offset = match + segment.length();
		}
		return endsWithWildcard || offset == query.length();
	}

	private Comparator<ScoredCandidate> scoredComparator() {
		return Comparator.comparingInt(ScoredCandidate::lexicalScore)
			.reversed()
			.thenComparing(Comparator.comparingInt((ScoredCandidate value) -> value.candidate().priority()).reversed())
			.thenComparing(value -> value.candidate().target().targetType())
			.thenComparing(value -> value.candidate().target().targetId(), Comparator.nullsLast(Long::compareTo));
	}

	public record ScoredCandidate(RouteCandidate candidate, int lexicalScore, boolean exact, boolean excluded,
			List<String> matchedSignals) {

		public ScoredCandidate {
			matchedSignals = matchedSignals == null ? List.of() : List.copyOf(new ArrayList<>(matchedSignals));
		}

		public boolean relevant() {
			return !excluded && (exact || lexicalScore > 0);
		}

	}

}
