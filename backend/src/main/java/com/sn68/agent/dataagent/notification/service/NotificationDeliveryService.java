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
package com.sn68.agent.dataagent.notification.service;

import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.dto.NotificationDeliveryDTO;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationDelivery;
import com.sn68.agent.dataagent.notification.enums.NotificationDeliveryStatus;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationDeliveryMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知投递组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

	private final AgentNotificationDeliveryMapper deliveryMapper;

	/**
	 * 查询通知投递。
	 */
	public List<NotificationDeliveryDTO> list(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String targetAlias,
			String templateCode, String deliveryId) {
		return deliveryMapper.findRecent(agentId, skillCode, skillVersionId, resourceKey, targetAlias, templateCode,
				deliveryId)
			.stream()
			.map(this::toDTO)
			.toList();
	}

	/**
	 * 查询通知投递。
	 */
	public AgentNotificationDelivery findByIdempotencyKey(String idempotencyKey) {
		return deliveryMapper.findByIdempotencyKey(idempotencyKey);
	}

	/**
	 * 校验通知投递。
	 */
	public boolean hasSentInSession(NotificationSendRequest request, String targetAlias, String templateCode) {
		if (request == null) {
			return false;
		}
		return deliveryMapper.existsSentInSession(request.sessionId(), request.agentId(), request.skillCode(),
				request.skillVersionId(), request.resourceKey(), targetAlias, templateCode);
	}

	/**
	 * 创建通知投递。
	 */
	@Transactional(rollbackFor = Exception.class)
	public AgentNotificationDelivery createOrUpdate(NotificationSendRequest request, String connectorCode,
			String targetAlias, String templateCode, String provider, NotificationDeliveryStatus status,
			String previewSummary, String errorMessage) {
		if (request == null || !StringUtils.hasText(request.idempotencyKey())) {
			throw CheckedException.badRequest("Idempotency key is required.");
		}
		AgentNotificationDelivery delivery = deliveryMapper.findByIdempotencyKey(request.idempotencyKey());
		if (delivery == null) {
			delivery = new AgentNotificationDelivery();
			delivery.setDeliveryId(UUID.randomUUID().toString().replace("-", ""));
			delivery.setIdempotencyKey(request.idempotencyKey().trim());
		}
		delivery.setSessionId(firstText(request.sessionId(), delivery.getSessionId()));
		delivery.setRuntimeRequestId(firstText(request.runtimeRequestId(), delivery.getRuntimeRequestId()));
		delivery.setAgentId(request.agentId() == null ? delivery.getAgentId() : request.agentId());
		delivery.setSkillCode(firstText(request.skillCode(), delivery.getSkillCode()));
		delivery.setSkillVersionId(request.skillVersionId() == null ? delivery.getSkillVersionId()
				: request.skillVersionId());
		delivery.setResourceKey(firstText(request.resourceKey(), delivery.getResourceKey()));
		delivery.setConnectorCode(firstText(connectorCode, delivery.getConnectorCode()));
		delivery.setTargetAlias(firstText(targetAlias, delivery.getTargetAlias()));
		delivery.setTemplateCode(firstText(templateCode, delivery.getTemplateCode()));
		delivery.setProvider(firstText(provider, delivery.getProvider()));
		delivery.setStatus(status.name());
		delivery.setPreviewSummary(firstText(previewSummary, delivery.getPreviewSummary()));
		delivery.setErrorMessage(errorMessage);
		if (delivery.getId() == null) {
			deliveryMapper.insert(delivery);
		}
		else {
			deliveryMapper.updateById(delivery);
		}
		return delivery;
	}

	/**
	 * 保存通知投递。
	 */
	@Transactional(rollbackFor = Exception.class)
	public AgentNotificationDelivery markResult(AgentNotificationDelivery delivery, NotificationAdapterResult result) {
		if (delivery == null || delivery.getId() == null) {
			throw CheckedException.badRequest("Notification delivery is required.");
		}
		boolean success = result != null && result.success();
		delivery.setStatus(success ? NotificationDeliveryStatus.SENT.name() : NotificationDeliveryStatus.FAILED.name());
		delivery.setPlatformRequestId(result == null ? null : result.platformRequestId());
		delivery.setPlatformCode(result == null ? null : result.platformCode());
		delivery.setErrorMessage(success ? null : result == null ? "Notification adapter returned empty result."
				: result.message());
		deliveryMapper.updateById(delivery);
		return delivery;
	}

	/**
	 * 处理通知投递。
	 */
	public NotificationDeliveryDTO toDTO(AgentNotificationDelivery delivery) {
		return new NotificationDeliveryDTO(delivery.getId(), delivery.getDeliveryId(), delivery.getIdempotencyKey(),
				delivery.getSessionId(), delivery.getRuntimeRequestId(), delivery.getAgentId(), delivery.getSkillCode(),
				delivery.getSkillVersionId(), delivery.getResourceKey(), delivery.getConnectorCode(), delivery.getTargetAlias(),
				delivery.getTemplateCode(), delivery.getProvider(), delivery.getStatus(), delivery.getPreviewSummary(),
				delivery.getPlatformRequestId(), delivery.getPlatformCode(), delivery.getErrorMessage(),
				delivery.getRetryCount(), delivery.getCreateTime());
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
