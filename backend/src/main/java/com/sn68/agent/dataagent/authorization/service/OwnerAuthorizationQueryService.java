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
package com.sn68.agent.dataagent.authorization.service;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;

/**
 * owner 维度授权查询服务（PR-3b，供 PR-3c PEP 内核 / PR-6 任务改造消费）。
 *
 * <p>以"owner（数据员工/数字员工）+ 主体快照"为入参回答两类问题：能否发现（目录可见）、能否使用。
 * 每个实现负责一种 owner 类型（{@link #supports}），组合门面
 * {@link OwnerAuthorizationQueryGateway} 按 owner 类型路由；未知类型默认拒绝。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public interface OwnerAuthorizationQueryService {

	/**
	 * 是否支持该 owner 类型。
	 *
	 * @param ownerType owner 类型
	 * @return true 表示本实现负责该类型
	 */
	boolean supports(AuthorizationOwnerType ownerType);

	/**
	 * DISCOVER 判定：主体能否在目录中发现该 owner。
	 *
	 * @param ownerId owner ID
	 * @param subject 判定主体快照
	 * @return true 允许发现
	 */
	boolean canDiscover(Long ownerId, AuthorizationSubjectSnapshot subject);

	/**
	 * USE 判定：主体能否使用该 owner。
	 *
	 * @param ownerId owner ID
	 * @param subject 判定主体快照
	 * @return true 允许使用
	 */
	boolean canUse(Long ownerId, AuthorizationSubjectSnapshot subject);

}
