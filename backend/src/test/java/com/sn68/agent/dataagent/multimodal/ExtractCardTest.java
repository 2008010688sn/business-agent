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
package com.sn68.agent.dataagent.multimodal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractCardTest {

	@Test
	void visibleFieldTruncatesLabelValueAndNormalizesConfidence() {
		ExtractCard.VisibleField field = new ExtractCard.VisibleField("L".repeat(ExtractCard.MAX_LABEL_CHARS + 8),
				"V".repeat(ExtractCard.MAX_VALUE_CHARS + 8), "HIGH");

		assertEquals(ExtractCard.MAX_LABEL_CHARS, field.label().length());
		assertEquals(ExtractCard.MAX_VALUE_CHARS, field.value().length());
		assertEquals("high", field.confidence());
		assertTrue(field.highConfidence());
	}

	@Test
	void renderTruncatesToMaxCardChars() {
		List<ExtractCard.VisibleField> fields = new ArrayList<>();
		for (int i = 0; i < ExtractCard.MAX_FIELDS; i++) {
			fields.add(new ExtractCard.VisibleField("L".repeat(ExtractCard.MAX_LABEL_CHARS),
					"V".repeat(ExtractCard.MAX_VALUE_CHARS), "high"));
		}
		ExtractCard card = new ExtractCard("很长的截图说明".repeat(20), "statement", fields, false, "",
				ExtractCard.STATUS_OK);

		String rendered = card.render();
		assertEquals(ExtractCard.MAX_CARD_CHARS, rendered.length());
		assertTrue(rendered.startsWith(ExtractCard.BLOCK_HEADER));
	}

	@Test
	void flagsEncodeUnreadFollowupFieldCountAndStatus() {
		ExtractCard failed = ExtractCard.failed("读图超时");
		assertTrue(failed.flags().startsWith(ExtractCard.HINT));
		assertTrue(failed.flags().contains("|unread=1"));
		assertTrue(failed.flags().contains("|followup=1"));
		assertTrue(failed.flags().contains("|status=" + ExtractCard.STATUS_FAILED));
		assertTrue(failed.needsVisionFollowup());

		ExtractCard skipped = ExtractCard.skipped("视觉抽取未启用");
		assertTrue(skipped.flags().contains("|unread=1"));
		assertTrue(skipped.flags().contains("|followup=0"));
		assertTrue(skipped.flags().contains("|status=" + ExtractCard.STATUS_SKIPPED));
		assertFalse(skipped.needsVisionFollowup());
	}

	@Test
	void renderKeepsBillNumberAndQueryPriorityRule() {
		ExtractCard card = new ExtractCard("对账单截图", "statement",
				List.of(new ExtractCard.VisibleField("账单编号", "ZD-20260801", "high")), false, "", ExtractCard.STATUS_OK);

		String rendered = card.render();
		assertTrue(rendered.contains("账单编号"));
		assertTrue(rendered.contains("ZD-20260801"));
		assertTrue(rendered.contains("疑似：statement"));
		assertTrue(rendered.contains(ExtractCard.QUERY_PRIORITY_RULE));
		assertEquals(ExtractCard.STATUS_OK, card.extractStatus());
	}

}
