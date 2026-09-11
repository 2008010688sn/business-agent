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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.im.dto.ImConversationBindingDTO;
import com.sn68.agent.dataagent.im.entity.AgentImConversationBinding;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImConversationBindingMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 单聊和群聊绑定服务（管理端，全部操作限定在当前登录租户内）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImConversationBindingService {

	private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {
	};

	private final AgentImConversationBindingMapper bindingMapper;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	private final ObjectMapper objectMapper;

	private final AuthenticationContext authenticationContext;

	/**
	 * 查询ImConversationBinding。
	 */
	public List<ImConversationBindingDTO> list(String provider, String connectorCode) {
		return bindingMapper.findByConnector(requireCurrentTenantId(), provider, connectorCode)
			.stream()
			.map(this::toDTO)
			.toList();
	}

	/**
	 * 创建ImConversationBinding。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImConversationBindingDTO create(ImConversationBindingDTO request) {
		AgentImConversationBinding binding = new AgentImConversationBinding();
		binding.setTenantId(requireCurrentTenantId());
		apply(binding, request, true);
		bindingMapper.insert(binding);
		return toDTO(binding);
	}

	/**
	 * 保存ImConversationBinding。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImConversationBindingDTO update(Long id, ImConversationBindingDTO request) {
		AgentImConversationBinding binding = requireOwnedById(id);
		apply(binding, request, false);
		bindingMapper.updateById(binding);
		return toDTO(binding);
	}

	/**
	 * 清理ImConversationBinding。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id == null) {
			return;
		}
		requireOwnedById(id);
		bindingMapper.deleteById(id);
	}

	/**
	 * 按主键读取并校验归属租户，跨租户访问一律按不存在处理。
	 */
	private AgentImConversationBinding requireOwnedById(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("IM 会话绑定 ID 不能为空");
		}
		AgentImConversationBinding binding = bindingMapper.selectById(id);
		if (binding == null || !requireCurrentTenantId().equals(binding.getTenantId())) {
			throw CheckedException.notFound("IM 会话绑定不存在");
		}
		return binding;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 IM 会话绑定操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法操作 IM 会话绑定");
		}
		return tenantId;
	}

	private void apply(AgentImConversationBinding binding, ImConversationBindingDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("IM 会话绑定配置不能为空");
		}
		if (create && (!StringUtils.hasText(request.provider()) || !StringUtils.hasText(request.connectorCode())
				|| !StringUtils.hasText(request.conversationType())
				|| !StringUtils.hasText(request.externalConversationId()) || request.agentId() == null)) {
			throw CheckedException.badRequest("IM 会话绑定缺少必要字段");
		}
		if (StringUtils.hasText(request.provider())) {
			binding.setProvider(request.provider().trim().toUpperCase());
		}
		if (StringUtils.hasText(request.connectorCode())) {
			binding.setConnectorCode(request.connectorCode().trim());
		}
		if (StringUtils.hasText(request.conversationType())) {
			binding.setConversationType(request.conversationType().trim().toUpperCase());
		}
		if (StringUtils.hasText(request.externalConversationId())) {
			binding.setExternalConversationId(request.externalConversationId().trim());
		}
		if (StringUtils.hasText(request.conversationName())) {
			binding.setConversationName(request.conversationName().trim());
		}
		if (request.agentId() != null) {
			binding.setAgentId(request.agentId());
		}
		binding.setDigitalEmployeeId(resolveDigitalEmployeeId(request.digitalEmployeeId()));
		binding.setTriggerPolicy(firstText(request.triggerPolicy(), binding.getTriggerPolicy(),
				ImConstants.CONVERSATION_GROUP.equals(binding.getConversationType()) ? ImConstants.TRIGGER_MENTION
						: ImConstants.TRIGGER_ALWAYS));
		binding.setWakeWords(writeList(request.wakeWords()));
		binding.setSessionScope(firstText(request.sessionScope(), binding.getSessionScope(),
				ImConstants.SESSION_SCOPE_PER_USER));
		binding.setStatus(firstText(request.status(), binding.getStatus(), ImConstants.STATUS_ENABLED));
		binding.setDisplayOrder(request.displayOrder() == null ? binding.getDisplayOrder() : request.displayOrder());
	}

	private Long resolveDigitalEmployeeId(Long digitalEmployeeId) {
		if (digitalEmployeeId == null) {
			return null;
		}
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(digitalEmployeeId,
				requireCurrentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在");
		}
		return digitalEmployeeId;
	}

	private ImConversationBindingDTO toDTO(AgentImConversationBinding binding) {
		return new ImConversationBindingDTO(binding.getId(), binding.getProvider(), binding.getConnectorCode(),
				binding.getConversationType(), binding.getExternalConversationId(), binding.getConversationName(),
				binding.getAgentId(), binding.getDigitalEmployeeId(),
				binding.getTriggerPolicy(), readList(binding.getWakeWords()),
				binding.getSessionScope(), binding.getStatus(), binding.getDisplayOrder());
	}

	private String writeList(List<String> values) {
		if (values == null) {
			return null;
		}
		List<String> safeValues = values.stream().filter(StringUtils::hasText).map(String::trim).toList();
		if (safeValues.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(safeValues);
		}
		catch (Exception ex) {
			// Returning null persists "no wake words", silently widening what the bot responds to.
			log.warn("Failed to serialize IM wake words, they will be persisted as empty. count={}", safeValues.size(),
					ex);
			return null;
		}
	}

	private List<String> readList(String value) {
		if (!StringUtils.hasText(value)) {
			return List.of();
		}
		try {
			List<String> result = objectMapper.readValue(value, LIST_TYPE);
			return result == null ? List.of() : result;
		}
		catch (Exception ex) {
			log.warn("Failed to read IM wake words, falling back to no wake word filter. length={}", value.length(), ex);
			return List.of();
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

}
