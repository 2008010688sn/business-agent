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
package com.sn68.agent.dataagent.market;

/**
 * 能力市场常量。
 */
public final class SkillMarketConstant {

	/**
	 * 平台审核权限码：市场条目的审核（通过/驳回）与撤销只对其开放。
	 */
	public static final String PERMISSION_LISTING_REVIEW = "ai-agent:skill-market:review";

	/**
	 * 市场条目维护权限码：发布者侧分页/详情/创建/修改/提交审核/删除。
	 * 当前市场消费入口沿用此权限码，避免现有技能市场管理角色因新增查询码而看不到可安装能力。
	 * 服务层把查询固定收敛为当前登录租户发布的条目；可安装列表再叠加已审核、未撤销。
	 */
	public static final String PERMISSION_LISTING_MANAGE = "ai-agent:skill-market:manage";

	/**
	 * 撤销通知的通知目标基础别名。通知栈为平台级配置，按动态别名
	 * {@code tenant-admin:{tenantId}} 逐租户投递，由目标配置中的 ${_targetValue}
	 * 解析到该租户管理员的实际接收通道。
	 */
	public static final String REVOKE_NOTIFY_TARGET_ALIAS = "tenant-admin";

	/**
	 * 撤销通知的通知模板编码（平台级模板，变量：listingId/listingName/tenantId/revokeTime）。
	 */
	public static final String REVOKE_NOTIFY_TEMPLATE_CODE = "skill-market-listing-revoked";

	private SkillMarketConstant() {

	}

}
