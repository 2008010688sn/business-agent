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
package com.sn68.agent.dataagent.service.permission;

import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 判定当前请求能否操作平台级（无 tenant_id 列、跨租户共享）资源。
 * <p>
 * {@code model_config}、{@code realtime_voice_config} 等表没有租户列，写入口一旦放开就会影响全部租户。
 * 在表归属定性之前，这些入口收敛到平台管理员，其余用户显式报错而不是静默改写他人数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformScopePermissionService {

	/**
	 * HTTP 403。
	 * <p>
	 * 本类的权限拒绝统一走带 code 的通用工厂 {@code CheckedException.badRequest(int, String)}，
	 * 传本常量即「403 + 自定义提示文案」。提示文案必须点明是平台级共享资源，
	 * 否则权限不足的用户会误判成登录问题、去重新登录再撞同一堵墙。
	 * 框架 {@code CheckedException} 的 403 常量为 private，故在此本地声明。
	 */
	private static final int HTTP_FORBIDDEN = 403;

	private final AuthenticationContext authenticationContext;

	/**
	 * 当前用户是否为平台管理员，解析失败按非平台管理员处理。
	 */
	public boolean isPlatformAdmin() {
		try {
			if (authenticationContext.anonymous() || authenticationContext.getContext() == null) {
				return false;
			}
			return authenticationContext.userType() == UserType.PLATFORM_ADMIN;
		}
		catch (Exception ex) {
			log.warn("解析平台管理员身份失败, 按非平台管理员处理", ex);
			return false;
		}
	}

	/**
	 * 要求当前用户为平台管理员，否则拒绝并说明该资源是平台级共享的。
	 * @param resourceName 面向用户的资源名称，用于拼装错误提示
	 */
	public void requirePlatformAdmin(String resourceName) {
		if (isPlatformAdmin()) {
			return;
		}
		log.warn("非平台管理员尝试修改平台级共享配置, 已拒绝. resource={}, tenantId={}", resourceName, currentTenantId());
		throw CheckedException.badRequest(HTTP_FORBIDDEN, resourceName + "是平台级共享配置，仅平台管理员可修改");
	}

	/**
	 * 解析当前租户，解析失败或上下文缺失时返回 null 由调用方失败关闭。
	 */
	public String currentTenantId() {
		try {
			String tenantId = authenticationContext.tenantId();
			return StringUtils.hasText(tenantId) ? tenantId.trim() : null;
		}
		catch (Exception ex) {
			log.debug("解析当前租户上下文失败, 按无租户处理: {}", ex.getLocalizedMessage());
			return null;
		}
	}

	/**
	 * 解析当前租户，缺失时直接拒绝，避免退化成不带租户谓词的全平台查询。
	 * @param resourceName 面向用户的资源名称，用于拼装错误提示
	 */
	public String requireCurrentTenantId(String resourceName) {
		String tenantId = currentTenantId();
		if (!StringUtils.hasText(tenantId)) {
			log.warn("当前租户上下文不可用, 已拒绝{}查询", resourceName);
			throw CheckedException.badRequest(HTTP_FORBIDDEN, "当前登录信息缺少租户上下文，无法查询" + resourceName);
		}
		return tenantId;
	}

}
