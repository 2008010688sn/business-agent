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

import com.sn68.agent.dataagent.notification.dto.NotificationRenderResult;
import com.sn68.agent.dataagent.notification.dto.NotificationTemplateDTO;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationConnector;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationTemplate;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationTemplateMapper;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知模板组件，封装 DataAgent 对应业务入口。
 */
@Service
@RequiredArgsConstructor
public class NotificationTemplateService {

	private final AgentNotificationTemplateMapper templateMapper;

	private final NotificationConnectorService connectorService;

	private final NotificationJsonSupport jsonSupport;

	private final PlatformScopePermissionService platformScopePermissionService;

	/**
	 * 查询通知模板。
	 */
	public List<NotificationTemplateDTO> list(String connectorCode) {
		return templateMapper.findAllOrdered(requireCurrentTenantId(), connectorCode).stream().map(this::toDTO).toList();
	}

	/**
	 * 校验通知模板。
	 */
	public AgentNotificationTemplate requireEnabled(String templateCode) {
		AgentNotificationTemplate template = templateMapper.findEnabledByTemplateCode(templateCode);
		if (template == null) {
			throw CheckedException.notFound("Notification template is disabled or does not exist.");
		}
		return template;
	}

	/**
	 * 处理通知模板。
	 */
	public NotificationRenderResult render(AgentNotificationTemplate template, Map<String, Object> variables) {
		Map<String, Object> safeVariables = new LinkedHashMap<>(variables == null ? Map.of() : variables);
		validateVariables(template, safeVariables);
		String title = renderText(template.getTitleTemplate(), safeVariables);
		String content = renderText(template.getContentTemplate(), safeVariables);
		String summary = summarize(title, content);
		return new NotificationRenderResult(title, content, summary, safeVariables);
	}

	/**
	 * 创建通知模板。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationTemplateDTO create(NotificationTemplateDTO request) {
		if (request == null || !StringUtils.hasText(request.templateCode())) {
			throw CheckedException.badRequest("Template code is required.");
		}
		if (!StringUtils.hasText(request.templateName()) || !StringUtils.hasText(request.contentTemplate())) {
			throw CheckedException.badRequest("Template name and content are required.");
		}
		if (templateMapper.findByTemplateCode(request.templateCode()) != null) {
			throw CheckedException.badRequest("Template code already exists.");
		}
		AgentNotificationTemplate template = new AgentNotificationTemplate();
		apply(template, request, true);
		templateMapper.insert(template);
		return toDTO(template);
	}

	/**
	 * 保存通知模板。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationTemplateDTO update(Long id, NotificationTemplateDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Template ID is required.");
		}
		AgentNotificationTemplate template = templateMapper.selectById(id);
		if (template == null) {
			throw CheckedException.notFound("Notification template does not exist.");
		}
		apply(template, request, false);
		templateMapper.updateById(template);
		return toDTO(template);
	}

	/**
	 * 清理通知模板。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			templateMapper.deleteById(id);
		}
	}

	private void apply(AgentNotificationTemplate template, NotificationTemplateDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("Template config is required.");
		}
		if (create || StringUtils.hasText(request.templateCode())) {
			template.setTemplateCode(request.templateCode().trim());
		}
		if (StringUtils.hasText(request.templateName())) {
			template.setTemplateName(request.templateName().trim());
		}
		if (StringUtils.hasText(request.connectorCode())) {
			AgentNotificationConnector connector = connectorService.require(request.connectorCode());
			template.setConnectorCode(connector.getConnectorCode());
			template.setProvider(firstText(request.provider(), connector.getProvider()));
		}
		else if (StringUtils.hasText(request.provider())) {
			template.setProvider(request.provider().trim().toUpperCase());
		}
		template.setMessageType(firstText(request.messageType(), template.getMessageType(), "text"));
		if (request.titleTemplate() != null) {
			template.setTitleTemplate(request.titleTemplate());
		}
		if (request.contentTemplate() != null) {
			template.setContentTemplate(request.contentTemplate());
		}
		if (request.variableSchema() != null) {
			template.setVariableSchema(jsonSupport.writeJson(request.variableSchema()));
		}
		template.setPlatformTemplateId(firstText(request.platformTemplateId(), template.getPlatformTemplateId()));
		template.setRiskLevel(firstText(request.riskLevel(), template.getRiskLevel(), "LOW").toUpperCase());
		template.setConfirmRequired(request.confirmRequired() == null
				? create || Boolean.TRUE.equals(template.getConfirmRequired()) : request.confirmRequired());
		template.setStatus(firstText(request.status(), template.getStatus(), "enabled"));
		template.setDisplayOrder(request.displayOrder() == null ? template.getDisplayOrder() : request.displayOrder());
	}

	private NotificationTemplateDTO toDTO(AgentNotificationTemplate template) {
		return new NotificationTemplateDTO(template.getId(), template.getTemplateCode(), template.getTemplateName(),
				template.getConnectorCode(), template.getProvider(), template.getMessageType(), template.getTitleTemplate(),
				template.getContentTemplate(), jsonSupport.readMap(template.getVariableSchema()),
				template.getPlatformTemplateId(), template.getRiskLevel(), template.getConfirmRequired(),
				template.getStatus(), template.getDisplayOrder());
	}

	@SuppressWarnings("unchecked")
	private void validateVariables(AgentNotificationTemplate template, Map<String, Object> variables) {
		Map<String, Object> schema = jsonSupport.readMap(template.getVariableSchema());
		Object requiredValue = schema.get("required");
		if (!(requiredValue instanceof Collection<?> requiredFields)) {
			return;
		}
		for (Object requiredField : requiredFields) {
			String field = requiredField == null ? null : String.valueOf(requiredField);
			if (!StringUtils.hasText(field)) {
				continue;
			}
			Object value = variables.get(field);
			if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
				throw CheckedException.badRequest("Template variable is required: " + field);
			}
		}
	}

	private String renderText(String template, Map<String, Object> variables) {
		if (!StringUtils.hasText(template)) {
			return "";
		}
		String result = template;
		for (Map.Entry<String, Object> entry : variables.entrySet()) {
			String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
			result = result.replace("${" + entry.getKey() + "}", value).replace("{{" + entry.getKey() + "}}", value);
		}
		return result;
	}

	private String summarize(String title, String content) {
		String joined = firstText(title, "") + (StringUtils.hasText(title) ? "\n" : "") + firstText(content, "");
		String masked = jsonSupport.maskText(joined.trim());
		return masked.length() > 500 ? masked.substring(0, 500) : masked;
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

	private String requireCurrentTenantId() {
		return platformScopePermissionService.requireCurrentTenantId("通知模板");
	}

}
