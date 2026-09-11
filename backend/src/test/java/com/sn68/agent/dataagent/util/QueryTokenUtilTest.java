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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTokenUtilTest {

	@Test
	void chineseQuestionYieldsBusinessTokens() {
		var tokens = QueryTokenUtil.tokenize("这个月各项目的账单情况");
		assertTrue(tokens.contains("账单"));
		assertTrue(tokens.contains("项目"));
		assertFalse(tokens.contains("这个"));
		assertFalse(tokens.contains("情况"));
	}

	@Test
	void keepsLatinTopNToken() {
		var tokens = QueryTokenUtil.tokenize("这个月用箱量top10客户排行");
		assertTrue(tokens.contains("top10") || tokens.contains("top"));
		assertTrue(tokens.contains("用箱") || tokens.contains("箱量") || tokens.contains("用箱量"));
		assertTrue(tokens.contains("排行"));
	}

}
