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

import com.sn68.agent.dataagent.entity.DataChatMessage;
import java.util.List;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 追问本轮没有 URL 时，从同一会话更早的用户原文找回含 URL 的句子。不映射对象类型。
 */
public final class SessionLinkCarryover {

	private SessionLinkCarryover() {
	}

	public static String latestUserTextWithUrl(List<DataChatMessage> messages) {
		if (CollectionUtils.isEmpty(messages)) {
			return null;
		}
		for (int index = messages.size() - 1; index >= 0; index--) {
			DataChatMessage message = messages.get(index);
			if (message == null || !"user".equalsIgnoreCase(message.getRole())
					|| !StringUtils.hasText(message.getContent())) {
				continue;
			}
			if (!LinkKeyExtractor.extractUrls(message.getContent()).isEmpty()) {
				return message.getContent();
			}
		}
		return null;
	}

}
