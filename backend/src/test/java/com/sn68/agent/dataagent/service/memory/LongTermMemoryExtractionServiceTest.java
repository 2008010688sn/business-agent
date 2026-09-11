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
package com.sn68.agent.dataagent.service.memory;

import com.sn68.agent.dataagent.enums.AgentMemoryType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryExtractionServiceTest {

	private final LongTermMemoryExtractionService service = new LongTermMemoryExtractionService(null, null, null,
			new DataAgentProperties(), null, null, null);

	@Test
	void extractCandidate_allowsUserPreference() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"以后账单金额都按客户维度汇总。", "好的，后续按客户维度汇总。");

		assertTrue(candidate.isPresent());
		assertEquals(AgentMemoryType.PREFERENCE, candidate.get().memoryType());
	}

	@Test
	void extractCandidate_rejectsOneTimeBusinessResultByDefault() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"查询本月客户金额排行", "客户A金额10000元，客户B金额8000元。");

		assertTrue(candidate.isEmpty());
	}

	@Test
	void extractCandidate_allowsEpisodicWhenUserExplicitlyAsksToRemember() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"记住这个结论：华东区域本月费用异常需要后续复核。", "已记录。");

		assertTrue(candidate.isPresent());
		assertEquals(AgentMemoryType.EPISODIC, candidate.get().memoryType());
	}

	@Test
	void extractCandidate_rejectsRememberedSensitiveNumbers() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"记住华东库存 300", "已记下。");

		assertTrue(candidate.isEmpty());
	}

	@Test
	void extractCandidate_rejectsMobileNumberEvenWhenRemembered() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"记住联系人手机号 13812345678", "已记下。");

		assertTrue(candidate.isEmpty());
	}

	@Test
	void extractCandidate_rejectsIdCardEvenWhenRemembered() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"记住身份证 110101199001011234", "已记下。");

		assertTrue(candidate.isEmpty());
	}

	@Test
	void extractCandidate_allowsPickupSitePreference() {
		Optional<LongTermMemoryExtractionService.MemoryCandidate> candidate = service.extractCandidate(
				"默认提货网点太阳食品", "好的，后续按太阳食品提货。");

		assertTrue(candidate.isPresent());
		assertEquals(AgentMemoryType.PREFERENCE, candidate.get().memoryType());
	}

}
