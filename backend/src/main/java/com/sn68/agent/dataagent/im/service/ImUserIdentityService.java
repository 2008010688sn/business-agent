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

import com.sn68.agent.dataagent.im.dto.ImUserIdentityDTO;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImUserIdentityMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * IM 用户映射管理服务（管理端，全部操作限定在当前登录租户内）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImUserIdentityService {

	private final AgentImUserIdentityMapper identityMapper;

	private final AuthenticationContext authenticationContext;

	/**
	 * 查询ImUserIdentity。
	 */
	public List<ImUserIdentityDTO> list() {
		return identityMapper.findAllOrdered(requireCurrentTenantId()).stream().map(this::toDTO).toList();
	}

	/**
	 * 创建ImUserIdentity。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImUserIdentityDTO create(ImUserIdentityDTO request) {
		AgentImUserIdentity identity = new AgentImUserIdentity();
		identity.setTenantId(requireCurrentTenantId());
		apply(identity, request, true);
		identityMapper.insert(identity);
		return toDTO(identity);
	}

	/**
	 * 保存ImUserIdentity。
	 */
	@Transactional(rollbackFor = Exception.class)
	public ImUserIdentityDTO update(Long id, ImUserIdentityDTO request) {
		AgentImUserIdentity identity = requireOwnedById(id);
		apply(identity, request, false);
		identityMapper.updateById(identity);
		return toDTO(identity);
	}

	/**
	 * 清理ImUserIdentity。
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long id) {
		if (id == null) {
			return;
		}
		requireOwnedById(id);
		identityMapper.deleteById(id);
	}

	/**
	 * 按主键读取并校验归属租户，跨租户访问一律按不存在处理。
	 */
	private AgentImUserIdentity requireOwnedById(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("IM 用户绑定 ID 不能为空");
		}
		AgentImUserIdentity identity = identityMapper.selectById(id);
		if (identity == null || !requireCurrentTenantId().equals(identity.getTenantId())) {
			throw CheckedException.notFound("IM 用户绑定不存在");
		}
		return identity;
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次 IM 用户绑定操作", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法操作 IM 用户绑定");
		}
		return tenantId;
	}

	private void apply(AgentImUserIdentity identity, ImUserIdentityDTO request, boolean create) {
		if (request == null) {
			throw CheckedException.badRequest("IM 用户绑定配置不能为空");
		}
		if (create && (!StringUtils.hasText(request.provider()) || !StringUtils.hasText(request.externalUserId())
				|| !StringUtils.hasText(request.userId()))) {
			throw CheckedException.badRequest("IM 用户绑定缺少必要字段");
		}
		if (StringUtils.hasText(request.provider())) {
			identity.setProvider(request.provider().trim().toUpperCase());
		}
		if (StringUtils.hasText(request.connectorCode())) {
			identity.setConnectorCode(request.connectorCode().trim());
		}
		if (StringUtils.hasText(request.externalUserId())) {
			identity.setExternalUserId(request.externalUserId().trim());
		}
		if (StringUtils.hasText(request.unionId())) {
			identity.setUnionId(request.unionId().trim());
		}
		if (StringUtils.hasText(request.contact())) {
			identity.setContact(request.contact().trim());
		}
		if (StringUtils.hasText(request.userId())) {
			identity.setUserId(request.userId().trim());
		}
		if (StringUtils.hasText(request.username())) {
			identity.setUsername(request.username().trim());
		}
		if (StringUtils.hasText(request.nickName())) {
			identity.setNickName(request.nickName().trim());
		}
		identity.setBindStatus(firstText(request.bindStatus(), identity.getBindStatus(), ImConstants.STATUS_ENABLED));
		identity.setBindSource(firstText(request.bindSource(), identity.getBindSource(), ImConstants.BIND_SOURCE_MANUAL));
	}

	private ImUserIdentityDTO toDTO(AgentImUserIdentity identity) {
		return new ImUserIdentityDTO(identity.getId(), identity.getProvider(), identity.getConnectorCode(),
				identity.getExternalUserId(), identity.getUnionId(), identity.getContact(), identity.getUserId(),
				identity.getUsername(), identity.getNickName(), identity.getBindStatus(), identity.getBindSource());
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
