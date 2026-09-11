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
package com.sn68.agent.dataagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopNLimitResolverTest {

	@Test
	void extractsTop10ConcatenatedWithChineseQuery() {
		assertEquals(10, TopNLimitResolver.extractRequestedRows("这个月用箱量top10客户排行").orElseThrow());
		assertEquals(10, TopNLimitResolver.extractRequestedRows("用箱量TOP 10客户").orElseThrow());
		assertEquals(10, TopNLimitResolver.extractRequestedRows("排行前10名客户").orElseThrow());
		assertEquals(5, TopNLimitResolver.extractRequestedRows("前5个客户").orElseThrow());
	}

	@Test
	void rankingQueryMatchesTopAndChineseHints() {
		assertTrue(TopNLimitResolver.isRankingQuery("这个月用箱量top10客户排行"));
		assertTrue(TopNLimitResolver.isRankingQuery("客户用箱量排行"));
		assertTrue(TopNLimitResolver.isRankingQuery("订单量排名"));
		assertTrue(TopNLimitResolver.isRankingQuery("top 3 网点"));
		assertFalse(TopNLimitResolver.isRankingQuery("这个月各项目的账单情况"));
		assertFalse(TopNLimitResolver.isRankingQuery(""));
	}

}
