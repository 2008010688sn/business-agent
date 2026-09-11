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
package com.sn68.agent.dataagent.authorization.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 授权判定主体快照（PR-3b）。
 *
 * <p>把 AuthenticationContext 的判定相关字段（用户/租户/团队/功能权限/管理员标记）固化为可传递值对象：
 * Legacy 适配与 owner 维度查询服务均以快照为入参，不直接依赖请求上下文，保证可单测、可被 PEP（PR-4）复用。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorizationSubjectSnapshot {

	/**
	 * 当前用户ID；空表示匿名（用户维度授权不匹配）。
	 */
	private String userId;

	/**
	 * 当前租户ID；租户维度授权匹配键。
	 */
	private String tenantId;

	/**
	 * 当前用户所属团队ID列表；空列表表示无团队维度授权。
	 */
	@Builder.Default
	private List<String> teamIds = List.of();

	/**
	 * 当前用户功能权限码列表；权限维度授权匹配键。
	 */
	@Builder.Default
	private List<String> funcPermissions = List.of();

	/**
	 * 是否管理员（PLATFORM_ADMIN/TENANT_ADMIN）；管理员在 Legacy 判定中等价于全可见。
	 */
	private boolean admin;

}
