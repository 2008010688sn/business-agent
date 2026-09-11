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
package com.sn68.agent.dataagent.im.service;

import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;
import com.dingtalk.open.app.stream.protocol.event.AckPayload;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 钉钉 Stream 机器人消息处理器。
 */
@Slf4j
@Service
public class DingTalkStreamMessageHandler {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ImCallbackService callbackService;

	private final ObjectMapper objectMapper;

	private final Executor imCallbackExecutor;

	public DingTalkStreamMessageHandler(ImCallbackService callbackService, ObjectMapper objectMapper,
			@Qualifier("imCallbackExecutor") Executor imCallbackExecutor) {
		this.callbackService = callbackService;
		this.objectMapper = objectMapper;
		this.imCallbackExecutor = imCallbackExecutor;
	}

	/**
	 * 查询DingTalkStreamMessage。
	 */
	public OpenDingTalkCallbackListener<ChatbotMessage, AckPayload> listener(AgentImConnector connector) {
		return new OpenDingTalkCallbackListener<>() {
			@Override
			public AckPayload execute(ChatbotMessage request) {
				try {
					ImCallbackMessage message = toMessage(connector, request);
					CompletableFuture.runAsync(() -> callbackService.handleStreamMessage(connector, message),
							imCallbackExecutor)
						.exceptionally(ex -> {
							log.warn("钉钉 Stream 消息异步处理失败。connectorCode={}", connector.getConnectorCode(), ex);
							return null;
						});
					return ack(EventAckStatus.SUCCESS, "OK");
				}
				catch (Exception ex) {
					log.warn("钉钉 Stream 消息派发失败。connectorCode={}", connector.getConnectorCode(), ex);
					return ack(EventAckStatus.LATER, "消息派发失败");
				}
			}
		};
	}

	private ImCallbackMessage toMessage(AgentImConnector connector, ChatbotMessage message) {
		Map<String, Object> rawPayload = objectMapper.convertValue(message, MAP_TYPE);
		String externalUserId = firstText(message.getSenderStaffId());
		if (!StringUtils.hasText(externalUserId)) {
			log.warn("钉钉 Stream 消息缺少 senderStaffId, 无法绑定系统账号。connectorCode={}, senderIdPresent={}, conversationType={}",
					connector.getConnectorCode(), StringUtils.hasText(message.getSenderId()),
					message.getConversationType());
		}
		String externalConversationId = firstText(message.getConversationId(), externalUserId);
		return new ImCallbackMessage(connector.getProvider(), connector.getConnectorCode(), message.getMsgId(),
				conversationType(message, externalConversationId, externalUserId), externalConversationId, externalUserId,
				null, null, messageType(message.getMsgtype()), text(message), Boolean.TRUE.equals(message.getInAtList())
						|| (message.getAtUsers() != null && !message.getAtUsers().isEmpty()),
				message.getSessionWebhook(), rawPayload);
	}

	private String conversationType(ChatbotMessage message, String externalConversationId, String externalUserId) {
		String raw = message.getConversationType();
		if (StringUtils.hasText(raw)) {
			String normalized = raw.trim().toUpperCase();
			if ("1".equals(normalized) || "SINGLE".equals(normalized) || "PRIVATE".equals(normalized)) {
				return ImConstants.CONVERSATION_SINGLE;
			}
			return ImConstants.CONVERSATION_GROUP;
		}
		return StringUtils.hasText(externalConversationId) && !externalConversationId.equals(externalUserId)
				? ImConstants.CONVERSATION_GROUP : ImConstants.CONVERSATION_SINGLE;
	}

	private String messageType(String value) {
		String type = firstText(value, "text");
		return "text".equalsIgnoreCase(type) ? "TEXT" : type.toUpperCase();
	}

	private String text(ChatbotMessage message) {
		MessageContent text = message.getText();
		if (text != null && StringUtils.hasText(text.getContent())) {
			return text.getContent();
		}
		MessageContent content = message.getContent();
		if (content != null) {
			return firstText(content.getContent(), content.getText(), richText(content.getRichText()));
		}
		return null;
	}

	private String richText(List<MessageContent> items) {
		if (items == null || items.isEmpty()) {
			return null;
		}
		return items.stream()
			.map(item -> firstText(item.getText(), item.getContent()))
			.filter(StringUtils::hasText)
			.reduce((left, right) -> left + "\n" + right)
			.orElse(null);
	}

	private AckPayload ack(EventAckStatus status, String message) {
		AckPayload payload = new AckPayload();
		payload.setStatus(status);
		payload.setMessage(message);
		return payload;
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

}
