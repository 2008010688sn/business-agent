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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 拦截协作者把「请提供编号」直接透出给用户。
 */
public final class AttachmentAnswerGuard {

	static final String IDENT_KNOWN_BUT_UNFINISHED = "已从附件读出关键标识，但本轮未完成查询。请重试或改用可复制文档。";

	static final String IMAGE_UNREAD = "未能读取图片内容，请切换支持视觉的模型或改传可复制文档。";

	private static final Pattern ASK_IDENT = Pattern.compile(
			"(请提供|请补充|需要.*提供|烦请提供).*(编号|单号|账单号|运单|客户|时间范围)|请提供账单编号", Pattern.DOTALL);

	private AttachmentAnswerGuard() {
	}

	public static String apply(AgentRequest request, String answer) {
		if (request == null) {
			return apply(null, null, answer);
		}
		return apply(request.getOriginalUserQuery(), request.getExtractCard(), answer);
	}

	public static String apply(String originalUserQuery, ExtractCard card, String answer) {
		if (!asksForIdentifier(answer)) {
			return answer;
		}
		if (card != null && card.hasHighConfidenceField()) {
			return IDENT_KNOWN_BUT_UNFINISHED;
		}
		if (unreadOrMissing(card) && TurnFusionService.refersToAttachment(originalUserQuery)) {
			return IMAGE_UNREAD;
		}
		return answer;
	}

	private static boolean unreadOrMissing(ExtractCard card) {
		if (card == null) {
			return true;
		}
		if (card.hasUnreadReason() || !card.hasHighConfidenceField()) {
			return true;
		}
		return ExtractCard.STATUS_SKIPPED.equals(card.extractStatus())
				|| ExtractCard.STATUS_FAILED.equals(card.extractStatus());
	}

	private static boolean asksForIdentifier(String answer) {
		return StringUtils.hasText(answer) && ASK_IDENT.matcher(answer).find();
	}

}
