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
package com.sn68.agent.dataagent.linking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sn68.agent.dataagent.entity.DataChatMessage;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionLinkCarryoverTest {

	@Test
	void picksLatestUserMessageThatContainsUrl() {
		DataChatMessage older = DataChatMessage.builder().role("user")
			.content("分析 http://10.0.0.1:31770/detail?id=2087735796997206016").build();
		DataChatMessage followUp = DataChatMessage.builder().role("user").content("它包含哪些需求").build();
		DataChatMessage assistant = DataChatMessage.builder().role("assistant").content("ok").build();

		String text = SessionLinkCarryover.latestUserTextWithUrl(List.of(older, assistant, followUp));

		assertEquals(older.getContent(), text);
	}

	@Test
	void ignoresMessagesWithoutUrl() {
		assertNull(SessionLinkCarryover.latestUserTextWithUrl(List.of(
				DataChatMessage.builder().role("user").content("它包含哪些需求").build())));
	}

}
