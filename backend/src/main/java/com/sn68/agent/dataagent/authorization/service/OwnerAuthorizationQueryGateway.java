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
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * owner 维度授权查询组合门面（PR-3b，供 PR-3c PEP 内核 / PR-6 任务改造注入消费）。
 *
 * <p>按 owner 类型路由到具体 {@link OwnerAuthorizationQueryService} 实现；
 * 未知类型或无可用实现时默认拒绝（fail-closed）。本类不实现查询接口，避免集合自引用。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Component
@RequiredArgsConstructor
public class OwnerAuthorizationQueryGateway {

	private final List<OwnerAuthorizationQueryService> delegates;

	/**
	 * DISCOVER 判定：主体能否在目录中发现该 owner；未知类型默认拒绝。
	 */
	public boolean canDiscover(AuthorizationOwnerType ownerType, Long ownerId, AuthorizationSubjectSnapshot subject) {
		if (ownerType == null || ownerId == null || subject == null) {
			return false;
		}
		return delegates.stream()
			.filter(delegate -> delegate.supports(ownerType))
			.findFirst()
			.map(delegate -> delegate.canDiscover(ownerId, subject))
			.orElse(false);
	}

	/**
	 * USE 判定：主体能否使用该 owner；未知类型默认拒绝。
	 */
	public boolean canUse(AuthorizationOwnerType ownerType, Long ownerId, AuthorizationSubjectSnapshot subject) {
		if (ownerType == null || ownerId == null || subject == null) {
			return false;
		}
		return delegates.stream()
			.filter(delegate -> delegate.supports(ownerType))
			.findFirst()
			.map(delegate -> delegate.canUse(ownerId, subject))
			.orElse(false);
	}

}
