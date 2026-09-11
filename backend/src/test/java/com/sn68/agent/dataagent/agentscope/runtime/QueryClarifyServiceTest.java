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

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryClarifyServiceTest {

	private final QueryClarifyService service = new QueryClarifyService();

	@Test
	void toMetadataIncludesStructuredSuggestedRepliesForClarification() {
		QueryClarifyService.QueryClarifyAssessment assessment = service.assess("统计销售额对比排名", null, true);

		Map<String, Object> metadata = assessment.toMetadata();

		assertTrue(Boolean.TRUE.equals(metadata.get("clarifyRequired")));
		assertTrue(metadata.containsKey("suggestedAssumptions"));
		Map<?, ?> suggestedReplies = (Map<?, ?>) metadata.get("suggestedReplies");
		assertNotNull(suggestedReplies);
		assertEquals("suggested-replies/v1", suggestedReplies.get("schemaVersion"));
		assertEquals("query-clarify", suggestedReplies.get("source"));
		assertEquals("confirm", suggestedReplies.get("submitMode"));

		List<?> groups = (List<?>) suggestedReplies.get("groups");
		assertEquals(1, groups.size());
		Map<?, ?> group = (Map<?, ?>) groups.get(0);
		assertEquals("suggested-assumptions", group.get("groupId"));
		assertEquals(1, group.get("maxSelect"));
		List<?> options = (List<?>) group.get("options");
		assertTrue(options.size() > 1);
		Map<?, ?> firstOption = (Map<?, ?>) options.get(0);
		assertEquals(firstOption.get("label"), firstOption.get("value"));
	}

	@Test
	void orchestrationDictionaryRecognizesTimeMetricAndOrderingAliases() {
		Map<String, Object> dictionary = clarificationDictionary();

		QueryClarifyService.QueryClarifyAssessment usageRanking = service.assess("该月用箱量 TOP10 的客户排行",
				null, true, dictionary);
		QueryClarifyService.QueryClarifyAssessment orderRanking = service.assess("这个月订单量最多 10 个客户",
				null, true, dictionary);

		assertFalse(usageRanking.shouldBlockExecution());
		assertTrue(usageRanking.missingDimensions().isEmpty());
		assertFalse(orderRanking.shouldBlockExecution());
		assertTrue(orderRanking.missingDimensions().isEmpty());
	}

	@Test
	void builtInCurrentMonthAliasesDoNotRequireTimeAliasConfiguration() {
		Map<String, Object> dictionary = Map.of("explicitMetricAliases", List.of("订单"));

		for (String alias : List.of("本月", "这个月", "这月")) {
			QueryClarifyService.QueryClarifyAssessment assessment = service.assess(alias + "订单", null, true,
					dictionary);

			assertFalse(assessment.missingDimensions().contains("时间范围"));
		}
	}

	@Test
	void orchestrationDictionaryKeepsConfiguredAmbiguousMetricBlocked() {
		QueryClarifyService.QueryClarifyAssessment assessment = service.assess("本月 GMV", null, true,
				clarificationDictionary());

		assertTrue(assessment.shouldBlockExecution());
		assertEquals(List.of("指标口径"), assessment.missingDimensions());
		assertEquals(1, assessment.followUpQuestions().size());
	}

	@Test
	void emptyOrchestrationDictionaryDoesNotUseLegacyBusinessTerms() {
		QueryClarifyService.QueryClarifyAssessment orchestrationAssessment = service.assess("本月 GMV", null, true,
				Map.of());
		QueryClarifyService.QueryClarifyAssessment legacyAssessment = service.assess("本月 GMV", null, true);

		assertTrue(orchestrationAssessment.missingDimensions().isEmpty());
		assertTrue(legacyAssessment.missingDimensions().contains("指标口径"));
	}

	@Test
	void orchestrationOrderingBasisOnlyUsesConfiguredAliases() {
		Map<String, Object> dictionary = Map.of("explicitMetricAliases", List.of("GMV"),
				"orderingMetricAliases", List.of());

		QueryClarifyService.QueryClarifyAssessment assessment = service.assess("本月 GMV TOP10", null, true,
				dictionary);

		assertEquals(List.of("排序依据"), assessment.missingDimensions());
	}

	@Test
	void attachmentReferentialQuerySkipsSlotClarification() {
		QueryClarifyService.QueryClarifyAssessment blocked = service.assess("看这张图统计销售额对比排名", null, true,
				null, null, false);
		QueryClarifyService.QueryClarifyAssessment skipped = service.assess("看这张图统计销售额对比排名", null, true,
				null, null, true);

		assertTrue(blocked.shouldBlockExecution());
		assertFalse(skipped.shouldBlockExecution());
	}

	@Test
	void compoundDependentQueryDoesNotBlockOnOrderingBasis() {
		QueryClarifyService.QueryClarifyAssessment assessment = service.assess(
				"帮我看一下这个月客户下单量top10的客户以及这些客户应收金额的情况", null, true);

		assertFalse(assessment.missingDimensions().contains("排序依据"));
		assertFalse(assessment.shouldBlockExecution());
	}

	@Test
	void orchestrationDictionaryAcceptsTimeFeedbackForOriginalQuestion() {
		QueryClarifyService.QueryClarifyAssessment assessment = service.assess("订单量最多 10 个客户", "按最近30天统计",
				true, clarificationDictionary());

		assertFalse(assessment.shouldBlockExecution());
		assertTrue(assessment.missingDimensions().isEmpty());
	}

	private Map<String, Object> clarificationDictionary() {
		return Map.of("timeAliases", List.of("该月", "这个月"),
				"explicitMetricAliases", List.of("用箱量", "箱量", "订单量", "订单数"),
				"ambiguousMetricAliases", List.of("GMV", "销售额", "利润"),
				"orderingMetricAliases", List.of("用箱量", "箱量", "订单量", "订单数"));
	}

}
