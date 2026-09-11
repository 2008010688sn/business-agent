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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationBindingUpsertReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationDecisionSimulateDbReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationGrantCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyCreateReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyDetailResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyModifyReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyPageQueryReq;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationPolicyVersionResp;
import com.sn68.agent.dataagent.authorization.dto.pap.AuthorizationTemplateResp;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationBinding;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationGrant;
import com.sn68.agent.dataagent.authorization.entity.AgentAuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationEnvironment;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import java.util.List;

/**
 * Agent 授权策略管理点（PAP）服务契约（PR-3b）。
 *
 * <p>tenantId 由调用方（Controller 从认证上下文）显式传入并贯穿全部读写，
 * 不依赖租户插件拦截器（模块白名单默认空）。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
public interface AgentAuthorizationPapService {

	/**
	 * 列出全部预设模板及其默认策略 JSON。
	 */
	List<AuthorizationTemplateResp> listTemplates();

	/**
	 * 创建策略：主档 DRAFT + 首个草稿版本（versionNo=1）。
	 */
	AuthorizationPolicyDetailResp createPolicy(AuthorizationPolicyCreateReq request, String tenantId);

	/**
	 * 修改策略（仅 DRAFT 主档）：名称可选更新，草稿 JSON 经严格校验后覆盖草稿版本。
	 */
	AuthorizationPolicyDetailResp modifyPolicy(Long id, AuthorizationPolicyModifyReq request, String tenantId);

	/**
	 * 分页查询当前租户的策略主档。
	 */
	IPage<AgentAuthorizationPolicy> pagePolicies(AuthorizationPolicyPageQueryReq request, String tenantId);

	/**
	 * 策略详情：主档 + 当前发布版本内容 + 最新草稿内容。
	 */
	AuthorizationPolicyDetailResp getPolicyDetail(Long id, String tenantId);

	/**
	 * 删除策略（逻辑删，级联删除版本行；被绑定引用的版本存在时拒绝）。
	 */
	void deletePolicy(Long id, String tenantId);

	/**
	 * 发布最新草稿版本：校验 JSON → CAS 翻转 published → 主档指针切换为 PUBLISHED。
	 */
	AuthorizationPolicyDetailResp publishPolicy(Long id, String tenantId);

	/**
	 * 从当前发布版本复制开新草稿（PUBLISHED → DRAFT），已发布版本保持不变。
	 */
	AuthorizationPolicyDetailResp draftFromCurrent(Long id, String tenantId);

	/**
	 * 停用策略（PUBLISHED → RETIRED）；存量绑定不受影响（运行时以绑定指向的版本为准）。
	 */
	void retirePolicy(Long id, String tenantId);

	/**
	 * 重新启用策略（RETIRED → PUBLISHED，要求存在已发布版本）。
	 */
	void enablePolicy(Long id, String tenantId);

	/**
	 * 策略版本列表（按版本号倒序，含草稿）。
	 */
	List<AuthorizationPolicyVersionResp> listPolicyVersions(Long policyId, String tenantId);

	/**
	 * 绑定 upsert：无绑定则创建（bindRevision=0）；有绑定按 expectedBindRevision 做 CAS 更新。
	 */
	AgentAuthorizationBinding upsertBinding(AuthorizationBindingUpsertReq request, String tenantId);

	/**
	 * 查询 owner 在指定环境下的绑定；无绑定返回 null。
	 */
	AgentAuthorizationBinding getBinding(AuthorizationOwnerType ownerType, Long ownerId,
			AuthorizationEnvironment environment, String tenantId);

	/**
	 * 创建授权（幂等：同主体同权限已存在生效记录时直接返回既有记录）。
	 */
	AgentAuthorizationGrant createGrant(AuthorizationGrantCreateReq request, String tenantId);

	/**
	 * 查询 owner 的授权记录列表（含已撤销，按创建时间倒序）。
	 */
	List<AgentAuthorizationGrant> listGrants(AuthorizationOwnerType ownerType, Long ownerId, String tenantId);

	/**
	 * 删除授权（逻辑删）。
	 */
	void deleteGrant(Long id, String tenantId);

	/**
	 * DB 决策模拟：按绑定读取已发布策略版本，复用 PR-3a 冻结求值器求值；无绑定时按 MISSING_POLICY 求值。
	 */
	AuthorizationDecisionResp simulateFromDb(AuthorizationDecisionSimulateDbReq request, String tenantId);

}
