/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.routing.RouteScorer.ScoredCandidate;
import com.sn68.agent.dataagent.routing.model.RouteCandidate;
import com.sn68.agent.dataagent.routing.model.RouteContext;
import com.sn68.agent.dataagent.routing.model.RouteRisk;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.routing.model.RouteTargetRef;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteScorerTest {

	private final RouteScorer scorer = new RouteScorer(new RouteTextNormalizer());

	@Test
	void phraseMatchesInsideAccidentQuery() {
		ScoredCandidate result = scorer.score(context("该月用箱量top10客户情况", null, null),
				List.of(candidate(1L, 0, rules(List.of("用箱量"), List.of(), List.of())))).get(0);

		assertEquals(70, result.lexicalScore());
		assertTrue(result.matchedSignals().contains("phrase:用箱量"));
	}

	@Test
	void duplicatePhraseAcrossFieldsOnlyUsesHighestPositiveScore() {
		RouteRules rules = new RouteRules(List.of(), List.of("用箱量"), List.of("用箱量"),
				List.of(), List.of(), List.of());

		assertEquals(70, scorer.score(context("查询用箱量", null, null), List.of(candidate(1L, 0, rules)))
			.get(0)
			.lexicalScore());
	}

	@Test
	void hardExcludeRemovesCandidateAndNegativeExampleOnlyLowersScore() {
		ScoredCandidate excluded = scorer.score(context("不要查询用箱量", null, null),
				List.of(candidate(1L, 0, rules(List.of("用箱量"), List.of(), List.of("不要查询"))))).get(0);
		ScoredCandidate softened = scorer.score(context("异常用箱量", null, null),
				List.of(candidate(1L, 0, rules(List.of("用箱量"), List.of("异常"), List.of())))).get(0);

		assertTrue(excluded.excluded());
		assertFalse(excluded.relevant());
		assertEquals(20, softened.lexicalScore());
	}

	@Test
	void continuityAddsWeakSignalWithoutCreatingUnrelatedCandidate() {
		RouteCandidate previous = candidate(1L, 0, rules(List.of(), List.of(), List.of()));
		RouteCandidate other = candidate(2L, 100, rules(List.of(), List.of(), List.of()));
		List<ScoredCandidate> results = scorer.score(context("那上个月呢", "这个月用箱量", previous.target()),
				List.of(other, previous));

		assertEquals(8, results.get(0).lexicalScore());
		assertEquals(1L, results.get(0).candidate().target().targetId());
		assertEquals(0, results.get(1).lexicalScore());
	}

	@Test
	void analysisSessionDrillKeepsContinuityOnLongQuery() {
		RouteCandidate previous = candidate(1L, 0, rules(List.of(), List.of(), List.of()));
		RouteCandidate other = candidate(2L, 100, rules(List.of(), List.of(), List.of()));
		String query = "请在同一分析会话中只看「太阳一号项目」的更细粒度结果，保持只读，不要办理。原问题：查询上个月各项目的账单情况";
		List<ScoredCandidate> results = scorer.score(context(query, "查询上个月各项目的账单情况", previous.target()),
				List.of(other, previous));

		assertTrue(query.length() > 32);
		assertEquals(8, results.get(0).lexicalScore());
		assertTrue(results.get(0).matchedSignals().contains("continuity"));
		assertEquals(1L, results.get(0).candidate().target().targetId());
	}

	@Test
	void positivePatternMatchesVariableCustomerName() {
		RouteRules rules = new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"),
				List.of(), List.of(), List.of(), true);

		ScoredCandidate result = scorer.score(context("给云南万绿客户下单", null, null),
				List.of(candidate(1L, 0, rules))).get(0);

		assertEquals(70, result.lexicalScore());
		assertTrue(result.matchedSignals().contains("positivePattern:*下单*"));
	}

	@Test
	void standardGlobHonorsPrefixSuffixContainsAndOrderedSegments() {
		assertTrue(matches("foo*", "foobar"));
		assertFalse(matches("foo*", "xfoo"));
		assertTrue(matches("*foo", "xfoo"));
		assertFalse(matches("*foo", "foox"));
		assertTrue(matches("*foo*", "xfoox"));
		assertTrue(matches("foo*bar", "foo-middle-bar"));
		assertFalse(matches("foo*bar", "xfoo-middle-bar"));
		assertFalse(matches("foo*bar", "foo-middle-bar-x"));
	}

	@Test
	void hardExcludePatternWinsOverPositivePattern() {
		RouteRules rules = new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"),
				List.of(), List.of(), List.of("*怎么*下单*", "*不要*下单*", "*取消*下单*"), true);

		ScoredCandidate result = scorer.score(context("怎么给云南万绿客户下单", null, null),
				List.of(candidate(1L, 0, rules))).get(0);

		assertTrue(result.excluded());
		assertTrue(result.matchedSignals().contains("hardExcludePattern:*怎么*下单*"));

		ScoredCandidate cancelled = scorer.score(context("不要给云南万绿客户下单", null, null),
				List.of(candidate(1L, 0, rules))).get(0);

		assertTrue(cancelled.excluded());
		assertTrue(cancelled.matchedSignals().contains("hardExcludePattern:*不要*下单*"));

		ScoredCandidate cancelledOrder = scorer.score(context("取消给云南万绿客户下单", null, null),
				List.of(candidate(1L, 0, rules))).get(0);

		assertTrue(cancelledOrder.excluded());
	}

	@Test
	void orderLookupQueriesDoNotMatchDemandCreation() {
		RouteRules rules = new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of("*下单*"),
				List.of(), List.of(), List.of(), true);
		RouteCandidate echoCandidate = new RouteCandidate(
				new RouteTargetRef(RouteTargetType.SKILL, 1L, 11L, 21L), "tenant-1", 1L, "需求单创建", "创建需求单",
				"ACTION", "FLOW", rules, RouteRisk.FLOW, 0, 30L, 41L, "checksum-demand", "embedding-v1");

		for (String query : List.of("查询已有订单", "查运单")) {
			ScoredCandidate result = scorer.score(context(query, null, null), List.of(echoCandidate)).get(0);
			assertFalse(result.relevant(), query);
			assertEquals(0, result.lexicalScore(), query);
		}
	}

	private RouteRules rules(List<String> phrases, List<String> negatives, List<String> hardExcludes) {
		return new RouteRules(List.of(), phrases, List.of(), List.of(), negatives, hardExcludes);
	}

	private boolean matches(String pattern, String query) {
		RouteRules rules = new RouteRules(List.of(), List.of(), List.of(), List.of(), List.of(pattern), List.of(),
				List.of(), List.of(), false);
		return scorer.score(context(query, null, null), List.of(candidate(1L, 0, rules))).get(0).relevant();
	}

	private RouteCandidate candidate(Long id, int priority, RouteRules rules) {
		return new RouteCandidate(new RouteTargetRef(RouteTargetType.SKILL, id, 10L + id, 20L + id), "tenant-1", 1L,
				"运单查询", "查询用箱量", "QUERY", "REACT", rules, RouteRisk.READ_ONLY, priority, 30L, 40L + id,
				"checksum-" + id, "embedding-v1");
	}

	private RouteContext context(String query, String previousQuery, RouteTargetRef previousTarget) {
		return new RouteContext("tenant-1", 1L, "NORMAL", 100L, "user-1", "run-1", query, previousQuery,
				previousTarget, null, Instant.now().plusSeconds(2), 1);
	}

}
