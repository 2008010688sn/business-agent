/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy of the License at
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

import java.util.List;
import org.junit.jupiter.api.Test;

class AttachmentAnswerGuardTest {

	@Test
	void replacesAskWhenExtractCardAlreadyHasIdentifier() {
		ExtractCard card = new ExtractCard("签收单", "pod",
				List.of(new ExtractCard.VisibleField("账单编号", "ZD-1", "high")), false, "", ExtractCard.STATUS_OK);
		String guarded = AttachmentAnswerGuard.apply("这张对账单", card, "请提供账单编号后再查询。");
		assertEquals(AttachmentAnswerGuard.IDENT_KNOWN_BUT_UNFINISHED, guarded);
		assertFalse(guarded.contains("请提供"));
	}

	@Test
	void replacesAskWhenImageUnreadAndUserReferredToAttachment() {
		ExtractCard unread = ExtractCard.failed("当前模型不支持视觉");
		String guarded = AttachmentAnswerGuard.apply("这张签收图写了什么", unread, "请提供运单号。");
		assertEquals(AttachmentAnswerGuard.IMAGE_UNREAD, guarded);
	}

	@Test
	void leavesAnswerUnchangedWhenNotAskingForIdentifier() {
		ExtractCard card = new ExtractCard("签收单", "pod",
				List.of(new ExtractCard.VisibleField("运单号", "YD1", "high")), false, "", ExtractCard.STATUS_OK);
		assertEquals("运单 YD1 已签收。", AttachmentAnswerGuard.apply("这张签收图", card, "运单 YD1 已签收。"));
	}

	@Test
	void leavesAskUnchangedWhenOriginalQueryDoesNotReferToAttachment() {
		String answer = "请提供客户和时间范围。";
		assertEquals(answer, AttachmentAnswerGuard.apply("本月应收", null, answer));
	}

}
