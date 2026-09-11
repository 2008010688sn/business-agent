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
package com.sn68.agent.dataagent.service.report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillReportProfileParserTest {

	@Test
	void parseOnlyReadsReportSpecificationSection() {
		SkillReportProfile profile = SkillReportProfileParser.parse("""
				# 订单分析
				## 工作流
				- 不应进入报告
				## 报告规范
				### 关注指标
				- 订单量
				- 完成率
				### 重点风险
				- 超期未完成
				## 其他说明
				- 不应进入报告
				""");

		assertEquals(java.util.List.of("订单量", "完成率"), profile.getMetrics());
		assertEquals(java.util.List.of("超期未完成"), profile.getRisks());
	}

	@Test
	void parseReturnsNullWhenSkillHasNoReportSpecification() {
		assertNull(SkillReportProfileParser.parse("# 普通 Skill\n\n## 工作流\n- 查询订单"));
	}

}
