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
package com.sn68.agent.dataagent.service.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisAssociation.Endpoint;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig.AnalysisSource;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision.Intent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AnalysisTurnIntentClassifierTest {

	private final AnalysisTurnIntentClassifier classifier = new AnalysisTurnIntentClassifier();

	@Test
	void attachmentPlusFileStatsIsFileOnlyWithoutJdbc() {
		AnalysisTurnDecision decision = classifier.classify("把这份 Excel 合计一下", true, fileOnlyConfig(), Set.of());

		assertEquals(Intent.FILE_ONLY, decision.intent());
		assertFalse(decision.jdbcToolsEnabled());
		assertFalse(decision.askJoin());
		assertNull(decision.missingJoinKey());
	}

	@Test
	void attachmentPlusSystemReconcileIsFileJoin() {
		AnalysisTurnDecision decision = classifier.classify("用这份对账单对一下系统里的单", true, tableConfig(),
				Set.of("order_no"));

		assertEquals(Intent.FILE_JOIN, decision.intent());
		assertTrue(decision.jdbcToolsEnabled());
		assertNull(decision.missingJoinKey());
	}

	@Test
	void noAttachmentUsesTableQueryPath() {
		AnalysisTurnDecision decision = classifier.classify("这个月库存多少", false, tableConfig(), Set.of());

		assertEquals(Intent.TABLE_QUERY, decision.intent());
		assertTrue(decision.jdbcToolsEnabled());
		assertFalse(decision.askJoin());
	}

	@Test
	void uncertainAttachmentAsksBeforeJoinAndNeverSilentJoins() {
		AnalysisTurnDecision decision = classifier.classify("帮我看下", true, tableConfig(), Set.of("order_no"));

		assertEquals(Intent.FILE_ONLY, decision.intent());
		assertFalse(decision.jdbcToolsEnabled());
		assertTrue(decision.askJoin());
		assertEquals(AnalysisTurnIntentClassifier.ASK_JOIN_NOTE, decision.note());
	}

	@Test
	void fileJoinWithoutTableSourcesDegradesToFileOnly() {
		AnalysisTurnDecision decision = classifier.classify("和库存对一下", true, fileOnlyConfig(), Set.of("order_no"));

		assertEquals(Intent.FILE_ONLY, decision.intent());
		assertFalse(decision.jdbcToolsEnabled());
		assertEquals(AnalysisTurnIntentClassifier.NO_TABLE_NOTE, decision.note());
	}

	@Test
	void fileJoinWithoutExtractedKeysMarksMissingJoinKeyAndDoesNotEnableJdbc() {
		AnalysisTurnDecision decision = classifier.classify("用这份对账单对一下系统里的单", true, tableConfig(),
				Set.of());

		assertEquals(Intent.FILE_JOIN, decision.intent());
		assertFalse(decision.jdbcToolsEnabled());
		assertEquals("order_no", decision.missingJoinKey());
	}

	@Test
	void fileJoinDoesNotSubstituteSemanticSimilarKeys() {
		AnalysisTurnDecision decision = classifier.classify("用这份对账单对一下系统里的单", true, tableConfig(),
				Set.of("订单号", "billNo"));

		assertEquals(Intent.FILE_JOIN, decision.intent());
		assertFalse(decision.jdbcToolsEnabled());
		assertEquals("order_no", decision.missingJoinKey());
	}

	@Test
	void fileJoinOnlyRequiresExtractedKeysOnTheFileSide() {
		AnalysisConfig config = new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false),
						new AnalysisSource("f1", "FILE_TABLE", null, null, List.of(), null, true)),
				List.of(new AnalysisAssociation(new Endpoint("f1", "bill_no"), new Endpoint("t1", "order_no"), "EXACT")),
				"order", List.of(), List.of());

		AnalysisTurnDecision decision = classifier.classify("用这份对账单对一下系统里的单", true, config,
				Set.of("bill_no"));

		assertEquals(Intent.FILE_JOIN, decision.intent());
		assertTrue(decision.jdbcToolsEnabled());
		assertNull(decision.missingJoinKey());
	}

	private AnalysisConfig fileOnlyConfig() {
		return new AnalysisConfig(List.of(new AnalysisSource("f1", "FILE_TABLE", null, null, List.of(), null, true)),
				List.of(), null, List.of(), List.of());
	}

	private AnalysisConfig tableConfig() {
		return new AnalysisConfig(
				List.of(new AnalysisSource("t1", "TABLE", 3L, "dis_demand", List.of("order_no"), null, false),
						new AnalysisSource("f1", "FILE_TABLE", null, null, List.of(), null, true)),
				List.of(new AnalysisAssociation(new Endpoint("f1", "order_no"), new Endpoint("t1", "order_no"), "EXACT")),
				"order", List.of(), List.of());
	}
}
