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

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.notification.dto.NotificationAuthorizationDTO;
import com.sn68.agent.dataagent.notification.dto.NotificationAuthorizationDecision;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationAuthorization;
import com.sn68.agent.dataagent.notification.enums.NotificationConfirmPolicy;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationAuthorizationMapper;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 通知授权组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationAuthorizationService {

	public static final String RESOURCE_KEY = "notification.send";

	private final AgentNotificationAuthorizationMapper authorizationMapper;

	private final DataAgentSkillToolRefMapper skillToolRefMapper;

	private final SkillCatalogService skillCatalogService;

	private final AuthenticationContext authenticationContext;

	/**
	 * 查询通知授权。
	 */
	public List<NotificationAuthorizationDTO> list() {
		return authorizationMapper.findAllOrdered(requireCurrentTenantId()).stream().map(this::toDTO).toList();
	}

	/**
	 * 处理通知授权。
	 */
	public NotificationAuthorizationDecision decide(NotificationSendRequest request, String authorizationTargetAlias,
			String templateCode) {
		return decide(request, authorizationTargetAlias, templateCode, false);
	}

	/**
	 * 处理通知授权。
	 */
	public NotificationAuthorizationDecision decideForHook(NotificationSendRequest request, String authorizationTargetAlias,
			String templateCode) {
		return decide(request, authorizationTargetAlias, templateCode, true);
	}

	private NotificationAuthorizationDecision decide(NotificationSendRequest request, String authorizationTargetAlias,
			String templateCode, boolean hookAction) {
		if (request == null) {
			return denied("Notification request is required.");
		}
		if (!hookAction && !isResourceBound(request)) {
			return denied("The pinned Skill version has not referenced notification.send.");
		}
		List<AgentNotificationAuthorization> matches = authorizationMapper.findActive(request.agentId(),
				request.skillCode(), request.skillVersionId(), request.resourceKey(), authorizationTargetAlias, templateCode);
		if (matches.isEmpty()) {
			return denied("Notification target or template is not authorized.");
		}
		String policy = matches.stream()
			.map(AgentNotificationAuthorization::getConfirmPolicy)
			.max(Comparator.comparingInt(this::policyWeight))
			.orElse(NotificationConfirmPolicy.ALWAYS.name());
		return new NotificationAuthorizationDecision(true, policy, "OK");
	}

	/**
	 * 创建通知授权。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationAuthorizationDTO create(NotificationAuthorizationDTO request) {
		if (request == null) {
			throw CheckedException.badRequest("Notification authorization config is required.");
		}
		AgentNotificationAuthorization authorization = new AgentNotificationAuthorization();
		apply(authorization, request);
		authorizationMapper.insert(authorization);
		return toDTO(authorization);
	}

	/**
	 * 保存通知授权。
	 */
	@Transactional(rollbackFor = Exception.class)
	public NotificationAuthorizationDTO update(Long id, NotificationAuthorizationDTO request) {
		if (id == null) {
			throw CheckedException.badRequest("Authorization ID is required.");
		}
		AgentNotificationAuthorization authorization = authorizationMapper.selectById(id);
		if (authorization == null) {
			throw CheckedException.notFound("Notification authorization does not exist.");
		}
		apply(authorization, request);
		authorizationMapper.updateById(authorization);
		return toDTO(authorization);
	}

	/**
	 * 清理通知授权。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id != null) {
			authorizationMapper.deleteById(id);
		}
	}

	private boolean isResourceBound(NotificationSendRequest request) {
		if (!StringUtils.hasText(request.skillCode()) || request.skillVersionId() == null
				|| !RESOURCE_KEY.equalsIgnoreCase(request.resourceKey())) {
			return false;
		}
		DataAgentSkill skill = skillCatalogService.findVisible(request.skillCode().trim(), currentTenantId());
		if (skill == null || !request.skillVersionId().equals(skill.getPublishedVersionId())) {
			return false;
		}
		return skillToolRefMapper.findBySkillVersionId(request.skillVersionId()).stream()
			.filter(ref -> !"disabled".equalsIgnoreCase(ref.getStatus()))
			.map(DataAgentSkillToolRef::getResourceKey)
			.anyMatch(RESOURCE_KEY::equalsIgnoreCase);
	}

	private void apply(AgentNotificationAuthorization authorization, NotificationAuthorizationDTO request) {
		authorization.setAgentId(request.agentId());
		authorization.setSkillCode(trimToNull(request.skillCode()));
		authorization.setSkillVersionId(request.skillVersionId());
		authorization.setResourceKey(trimToNull(request.resourceKey()));
		authorization.setTargetAlias(trimToNull(request.targetAlias()));
		authorization.setTemplateCode(trimToNull(request.templateCode()));
		authorization.setConfirmPolicy(firstText(request.confirmPolicy(), NotificationConfirmPolicy.ALWAYS.name())
			.toUpperCase());
		authorization.setExpireTime(request.expireTime());
		authorization.setStatus(firstText(request.status(), "enabled"));
		authorization.setDisplayOrder(request.displayOrder() == null ? 0 : request.displayOrder());
	}

	private NotificationAuthorizationDTO toDTO(AgentNotificationAuthorization authorization) {
		return new NotificationAuthorizationDTO(authorization.getId(), authorization.getAgentId(),
				authorization.getSkillCode(), authorization.getSkillVersionId(), authorization.getResourceKey(),
				authorization.getTargetAlias(),
				authorization.getTemplateCode(), authorization.getConfirmPolicy(), authorization.getExpireTime(),
				authorization.getStatus(), authorization.getDisplayOrder());
	}

	private NotificationAuthorizationDecision denied(String message) {
		return new NotificationAuthorizationDecision(false, NotificationConfirmPolicy.ALWAYS.name(), message);
	}

	private int policyWeight(String policy) {
		if (NotificationConfirmPolicy.ALWAYS.name().equalsIgnoreCase(policy)) {
			return 30;
		}
		if (NotificationConfirmPolicy.SESSION_ONCE.name().equalsIgnoreCase(policy)) {
			return 20;
		}
		if (NotificationConfirmPolicy.NONE.name().equalsIgnoreCase(policy)) {
			return 10;
		}
		return 30;
	}

	private String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private String currentTenantId() {
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析通知授权租户上下文失败, 技能可见性查询将不带租户过滤", ex);
			return null;
		}
	}

	private String requireCurrentTenantId() {
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("当前登录信息缺少租户上下文，无法查询通知授权");
		}
		return tenantId.trim();
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
