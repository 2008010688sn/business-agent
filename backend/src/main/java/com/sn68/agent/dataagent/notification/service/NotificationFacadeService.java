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

import com.sn68.agent.dataagent.notification.adapter.NotificationAdapter;
import com.sn68.agent.dataagent.notification.adapter.NotificationAdapterRegistry;
import com.sn68.agent.dataagent.notification.dto.NotificationAdapterResult;
import com.sn68.agent.dataagent.notification.dto.NotificationAuthorizationDecision;
import com.sn68.agent.dataagent.notification.dto.NotificationPreview;
import com.sn68.agent.dataagent.notification.dto.NotificationRenderResult;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationSendResponse;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationDelivery;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTarget;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.dataagent.notification.enums.NotificationConfirmPolicy;
import com.sn68.agent.dataagent.notification.enums.NotificationDeliveryStatus;
import com.sn68.agent.dataagent.notification.service.NotificationTargetService.ResolvedTarget;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知Facade组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class NotificationFacadeService {

	private final NotificationConnectorService connectorService;

	private final NotificationTargetService targetService;

	private final NotificationTemplateService templateService;

	private final NotificationAuthorizationService authorizationService;

	private final NotificationDeliveryService deliveryService;

	private final NotificationAdapterRegistry adapterRegistry;

	/**
	 * 执行通知Facade。
	 */
	public NotificationSendResponse preview(NotificationSendRequest request) {
		RuntimeContext context = prepare(request, false);
		if (!context.authorization().allowed()) {
			AgentNotificationDelivery delivery = deliveryService.createOrUpdate(request, context.connectorCode(),
					context.requestedTargetAlias(), context.template().getTemplateCode(), context.connector().getProvider(),
					NotificationDeliveryStatus.DENIED, null, context.authorization().message());
			return new NotificationSendResponse(NotificationDeliveryStatus.DENIED.name(), delivery.getDeliveryId(),
					context.authorization().message(), null);
		}
		NotificationDeliveryStatus status = context.confirmRequired() ? NotificationDeliveryStatus.NEED_CONFIRMATION
				: NotificationDeliveryStatus.PREVIEW;
		AgentNotificationDelivery delivery = deliveryService.createOrUpdate(request, context.connectorCode(),
				context.requestedTargetAlias(), context.template().getTemplateCode(), context.connector().getProvider(),
				status, context.rendered().summary(), null);
		return new NotificationSendResponse(status.name(), delivery.getDeliveryId(), "Notification preview is ready.",
				context.preview());
	}

	/**
	 * 执行通知Facade。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationSendResponse send(NotificationSendRequest request) {
		return send(request, false);
	}

	/**
	 * 执行通知Facade。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationSendResponse sendFromHook(NotificationSendRequest request) {
		return send(request, true);
	}

	private NotificationSendResponse send(NotificationSendRequest request, boolean hookAction) {
		requireRequest(request);
		AgentNotificationDelivery existing = deliveryService.findByIdempotencyKey(request.idempotencyKey());
		if (isTerminal(existing)) {
			return responseFromExisting(existing);
		}
		RuntimeContext context = prepare(request, hookAction);
		if (!context.authorization().allowed()) {
			AgentNotificationDelivery delivery = deliveryService.createOrUpdate(request, context.connectorCode(),
					context.requestedTargetAlias(), context.template().getTemplateCode(), context.connector().getProvider(),
					NotificationDeliveryStatus.DENIED, null, context.authorization().message());
			return new NotificationSendResponse(NotificationDeliveryStatus.DENIED.name(), delivery.getDeliveryId(),
					context.authorization().message(), null);
		}
		if (context.confirmRequired() && !Boolean.TRUE.equals(request.confirmed())) {
			AgentNotificationDelivery delivery = deliveryService.createOrUpdate(request, context.connectorCode(),
					context.requestedTargetAlias(), context.template().getTemplateCode(), context.connector().getProvider(),
					NotificationDeliveryStatus.NEED_CONFIRMATION, context.rendered().summary(), null);
			return new NotificationSendResponse(NotificationDeliveryStatus.NEED_CONFIRMATION.name(),
					delivery.getDeliveryId(), "Notification requires user confirmation.", context.preview());
		}
		AgentNotificationDelivery delivery = deliveryService.createOrUpdate(request, context.connectorCode(),
				context.requestedTargetAlias(), context.template().getTemplateCode(), context.connector().getProvider(),
				NotificationDeliveryStatus.PREVIEW, context.rendered().summary(), null);
		NotificationAdapter adapter = adapterRegistry.get(context.connector().getProvider(), context.connector().getChannelType());
		NotificationAdapterResult result = adapter.send(context.connector(), context.target(), context.template(),
				context.rendered(), context.connectorConfig(), context.targetConfig());
		AgentNotificationDelivery updated = deliveryService.markResult(delivery, result);
		return new NotificationSendResponse(updated.getStatus(), updated.getDeliveryId(), result.message(),
				context.preview());
	}

	private RuntimeContext prepare(NotificationSendRequest request, boolean hookAction) {
		requireRequest(request);
		Map<String, Object> variables = new LinkedHashMap<>(request.variables() == null ? Map.of() : request.variables());
		ResolvedTarget resolvedTarget = targetService.resolveEnabled(request.targetAlias(), variables);
		AgentNotificationTemplate template = templateService.requireEnabled(request.templateCode());
		AgentNotificationTarget target = resolvedTarget.target();
		AgentNotificationConnector connector = connectorService.requireEnabled(target.getConnectorCode());
		validateTemplateScope(connector, template);
		NotificationAuthorizationDecision authorization = hookAction
				? authorizationService.decideForHook(request, resolvedTarget.authorizationAlias(), template.getTemplateCode())
				: authorizationService.decide(request, resolvedTarget.authorizationAlias(), template.getTemplateCode());
		NotificationRenderResult rendered = authorization.allowed() ? templateService.render(template, variables)
				: new NotificationRenderResult("", "", "", variables);
		boolean confirmRequired = authorization.allowed()
				&& requiresConfirmation(request, connector, target, template, authorization);
		NotificationPreview preview = new NotificationPreview(connector.getProvider(), connector.getChannelType(),
				connector.getConnectorCode(), resolvedTarget.requestedAlias(), target.getTargetName(),
				template.getTemplateCode(), rendered.title(), rendered.summary(), confirmRequired);
		return new RuntimeContext(connector, target, template, connectorService.runtimeConfig(connector),
				resolvedTarget.targetConfig(), rendered, preview, authorization, confirmRequired,
				resolvedTarget.requestedAlias());
	}

	private boolean requiresConfirmation(NotificationSendRequest request, AgentNotificationConnector connector,
			AgentNotificationTarget target, AgentNotificationTemplate template,
			NotificationAuthorizationDecision authorization) {
		if (forcedConfirmation(connector, target, template)) {
			return true;
		}
		String policy = authorization.confirmPolicy();
		if (NotificationConfirmPolicy.NONE.name().equalsIgnoreCase(policy)) {
			return false;
		}
		if (NotificationConfirmPolicy.SESSION_ONCE.name().equalsIgnoreCase(policy)) {
			return !deliveryService.hasSentInSession(request, request.targetAlias(), template.getTemplateCode());
		}
		return true;
	}

	private boolean forcedConfirmation(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template) {
		if (Boolean.TRUE.equals(template.getConfirmRequired())) {
			return true;
		}
		if ("HIGH".equalsIgnoreCase(template.getRiskLevel())) {
			return true;
		}
		String channel = connector.getChannelType() == null ? "" : connector.getChannelType().toUpperCase(Locale.ROOT);
		String targetType = target.getTargetType() == null ? "" : target.getTargetType().toUpperCase(Locale.ROOT);
		return channel.contains("SMS") || channel.contains("WECHAT") || "PHONE".equals(targetType)
				|| "OPENID".equals(targetType) || "GROUP".equals(targetType);
	}

	private void validateTemplateScope(AgentNotificationConnector connector, AgentNotificationTemplate template) {
		if (StringUtils.hasText(template.getConnectorCode())
				&& !template.getConnectorCode().equals(connector.getConnectorCode())) {
			throw CheckedException.badRequest("Notification template does not belong to target connector.");
		}
		if (StringUtils.hasText(template.getProvider()) && !template.getProvider().equalsIgnoreCase(connector.getProvider())) {
			throw CheckedException.badRequest("Notification template provider does not match target provider.");
		}
	}

	private boolean isTerminal(AgentNotificationDelivery delivery) {
		if (delivery == null || !StringUtils.hasText(delivery.getStatus())) {
			return false;
		}
		return NotificationDeliveryStatus.SENT.name().equals(delivery.getStatus())
				|| NotificationDeliveryStatus.FAILED.name().equals(delivery.getStatus())
				|| NotificationDeliveryStatus.DENIED.name().equals(delivery.getStatus());
	}

	private NotificationSendResponse responseFromExisting(AgentNotificationDelivery delivery) {
		NotificationPreview preview = new NotificationPreview(delivery.getProvider(), null, delivery.getConnectorCode(),
				delivery.getTargetAlias(), null, delivery.getTemplateCode(), null, delivery.getPreviewSummary(), false);
		return new NotificationSendResponse(delivery.getStatus(), delivery.getDeliveryId(),
				firstText(delivery.getErrorMessage(), "Notification delivery already exists."), preview);
	}

	private void requireRequest(NotificationSendRequest request) {
		if (request == null) {
			throw CheckedException.badRequest("Notification request is required.");
		}
		if (!StringUtils.hasText(request.targetAlias())) {
			throw CheckedException.badRequest("Notification target alias is required.");
		}
		if (!StringUtils.hasText(request.templateCode())) {
			throw CheckedException.badRequest("Notification template code is required.");
		}
		if (!StringUtils.hasText(request.idempotencyKey())) {
			throw CheckedException.badRequest("Notification idempotency key is required.");
		}
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

	private record RuntimeContext(AgentNotificationConnector connector, AgentNotificationTarget target,
			AgentNotificationTemplate template, Map<String, Object> connectorConfig, Map<String, Object> targetConfig,
			NotificationRenderResult rendered, NotificationPreview preview,
			NotificationAuthorizationDecision authorization, boolean confirmRequired, String requestedTargetAlias) {

		private String connectorCode() {
			return connector.getConnectorCode();
		}

	}

}
