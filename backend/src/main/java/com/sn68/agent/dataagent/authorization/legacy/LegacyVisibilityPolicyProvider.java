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
package com.sn68.agent.dataagent.authorization.legacy;

import com.sn68.agent.dataagent.authorization.dto.AuthorizationSubjectSnapshot;
import com.sn68.agent.dataagent.constant.AgentStatusConstant;
import com.sn68.agent.dataagent.constant.AgentVisibilityConstant;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityGrant;
import com.sn68.agent.dataagent.entity.DataAgentVisibilityPolicy;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityGrantMapper;
import com.sn68.agent.dataagent.repository.DataAgentVisibilityPolicyMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Legacy 可见性策略适配器（PR-3b）。
 *
 * <p>把存量 data_agent_visibility_policy / data_agent_visibility_grant 两表的判定语义
 * （等价复现 {@code DataAgentVisibilityServiceImpl#canVisibleInUserWorkbench} 与
 * {@code #canListInCatalog}）映射为授权中心的 USE / DISCOVER 视图：</p>
 *
 * <ul>
 * <li>canUse ≡ canVisibleInUserWorkbench：发布状态 → 管理员放行 → 策略 ENABLED →
 * 主体匹配授权放行 → conversationScope（TENANT 放行 / TEAM / PERMISSION / 其余默认拒）。</li>
 * <li>canDiscover ≡ canListInCatalog：可见即目录可见；否则策略 ENABLED 时按 catalogScope
 * （TENANT 放行 / TEAM / PERMISSION / HIDDEN 默认拒）。</li>
 * </ul>
 *
 * <p>只读适配：不双写、不删表、不改变现网读写路径；判定入参为主体快照，供
 * {@code OwnerAuthorizationQueryService} 与 PEP（PR-4）接线消费。</p>
 *
 * @author Jay (PR-3b PAP + Legacy 适配)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyVisibilityPolicyProvider {

	private final DataAgentVisibilityPolicyMapper legacyPolicyMapper;

	private final DataAgentVisibilityGrantMapper legacyGrantMapper;

	/** PR-4 写路径冻结开关载体（freezeLegacyVisibilityWrites，默认 false 不影响现网旧接口）。 */
	private final DataAgentProperties dataAgentProperties;

	/**
	 * USE 授权判定（等价现网工作台可见性六步判定）。
	 *
	 * @param agentId Agent ID；空直接拒绝
	 * @param agentStatus Agent 状态；非 published 拒绝
	 * @param subject 判定主体快照
	 * @return 是否允许使用
	 */
	public boolean canUse(Long agentId, String agentStatus, AuthorizationSubjectSnapshot subject) {
		if (agentId == null || !AgentStatusConstant.PUBLISHED.equals(agentStatus) || subject == null) {
			return false;
		}
		// 步骤2：管理员放行（等价 isCurrentAdmin）
		if (subject.isAdmin()) {
			return true;
		}
		DataAgentVisibilityPolicy policy = resolvePolicy(agentId);
		// 步骤4：策略停用即整体拒绝
		if (!AgentVisibilityConstant.POLICY_STATUS_ENABLED.equals(policy.getStatus())) {
			return false;
		}
		// 步骤5：主体匹配的生效授权放行（USER/TEAM/PERMISSION/TENANT 四类主体）
		if (hasMatchedActiveGrant(agentId, subject)) {
			return true;
		}
		// 步骤6：按对话可见范围兜底
		return switch (policy.getConversationScope()) {
			case AgentVisibilityConstant.CONVERSATION_SCOPE_TENANT -> true;
			case AgentVisibilityConstant.CONVERSATION_SCOPE_TEAM -> hasMatchedTeamGrant(agentId, subject);
			case AgentVisibilityConstant.CONVERSATION_SCOPE_PERMISSION -> hasMatchedPermissionGrant(agentId, subject);
			default -> false;
		};
	}

	/**
	 * DISCOVER 授权判定（等价现网目录可见性 canListInCatalog 语义）。
	 *
	 * @param agentId Agent ID；空直接拒绝
	 * @param agentStatus Agent 状态；非 published 拒绝
	 * @param subject 判定主体快照
	 * @return 是否允许在目录中发现
	 */
	public boolean canDiscover(Long agentId, String agentStatus, AuthorizationSubjectSnapshot subject) {
		if (agentId == null || !AgentStatusConstant.PUBLISHED.equals(agentStatus) || subject == null) {
			return false;
		}
		// 可见（可使用）必然目录可见
		if (canUse(agentId, agentStatus, subject)) {
			return true;
		}
		DataAgentVisibilityPolicy policy = resolvePolicy(agentId);
		if (!AgentVisibilityConstant.POLICY_STATUS_ENABLED.equals(policy.getStatus())) {
			return false;
		}
		return switch (policy.getCatalogScope()) {
			case AgentVisibilityConstant.CATALOG_SCOPE_TENANT -> true;
			case AgentVisibilityConstant.CATALOG_SCOPE_TEAM -> hasMatchedTeamGrant(agentId, subject);
			case AgentVisibilityConstant.CATALOG_SCOPE_PERMISSION -> hasMatchedPermissionGrant(agentId, subject);
			default -> false;
		};
	}

	/**
	 * PR-4 旧写路径冻结开关消费（v1.2 freezeLegacyVisibilityWrites）：开关 true 时冻结 Legacy
	 * 可见性写——策略/授权写操作已收敛至授权中心 PAP，旧写接口调用方应先行调用本断言拒写；
	 * 读判定（canUse/canDiscover 适配）不受影响。默认 false 现网旧接口零变化。
	 */
	public void assertWritesNotFrozen() {
		DataAgentProperties.Authorization authorization = dataAgentProperties.getAuthorization();
		if (authorization != null && authorization.isFreezeLegacyVisibilityWrites()) {
			throw CheckedException.fail("Legacy 可见性写接口已冻结（freezeLegacyVisibilityWrites=true）："
					+ "可见性策略与授权写操作请通过授权中心 PAP 接口管理；读判定不受影响。");
		}
	}

	/**
	 * 解析 Legacy 可见性策略：无记录时返回默认策略（等价现网 getPolicy + defaultPolicy 语义）。
	 */
	private DataAgentVisibilityPolicy resolvePolicy(Long agentId) {
		DataAgentVisibilityPolicy existing = legacyPolicyMapper.findByAgentId(agentId);
		if (existing != null) {
			normalizePolicy(existing);
			return existing;
		}
		return DataAgentVisibilityPolicy.builder()
			.agentId(agentId)
			.conversationScope(AgentVisibilityConstant.CONVERSATION_SCOPE_GRANT_ONLY)
			.catalogScope(AgentVisibilityConstant.CATALOG_SCOPE_TENANT)
			.applyMode(AgentVisibilityConstant.APPLY_MODE_APPROVAL_REQUIRED)
			.approvalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL)
			.workflowFlowCode(AgentVisibilityConstant.DEFAULT_WORKFLOW_FLOW_CODE)
			.riskLevel("NORMAL")
			.status(AgentVisibilityConstant.POLICY_STATUS_ENABLED)
			.build();
	}

	/**
	 * 存量记录空字段兜底（等价现网 normalizePolicy）。
	 */
	private void normalizePolicy(DataAgentVisibilityPolicy policy) {
		if (!StringUtils.hasText(policy.getConversationScope())) {
			policy.setConversationScope(AgentVisibilityConstant.CONVERSATION_SCOPE_GRANT_ONLY);
		}
		if (!StringUtils.hasText(policy.getCatalogScope())) {
			policy.setCatalogScope(AgentVisibilityConstant.CATALOG_SCOPE_TENANT);
		}
		if (!StringUtils.hasText(policy.getApplyMode())) {
			policy.setApplyMode(AgentVisibilityConstant.APPLY_MODE_APPROVAL_REQUIRED);
		}
		if (!StringUtils.hasText(policy.getApprovalMode())) {
			policy.setApprovalMode(AgentVisibilityConstant.APPROVAL_MODE_LOCAL);
		}
		if (!StringUtils.hasText(policy.getWorkflowFlowCode())) {
			policy.setWorkflowFlowCode(AgentVisibilityConstant.DEFAULT_WORKFLOW_FLOW_CODE);
		}
		if (!StringUtils.hasText(policy.getStatus())) {
			policy.setStatus(AgentVisibilityConstant.POLICY_STATUS_ENABLED);
		}
	}

	/**
	 * 主体匹配的生效授权（等价 hasMatchedActiveGrant + matchesCurrentUser）。
	 */
	private boolean hasMatchedActiveGrant(Long agentId, AuthorizationSubjectSnapshot subject) {
		if (!StringUtils.hasText(subject.getUserId())) {
			return false;
		}
		return legacyGrantMapper.listActiveByAgentId(agentId)
			.stream()
			.anyMatch(grant -> matchesSnapshot(grant, subject));
	}

	/**
	 * 团队维度生效授权（等价 hasMatchedTeamGrant）。
	 */
	private boolean hasMatchedTeamGrant(Long agentId, AuthorizationSubjectSnapshot subject) {
		List<String> teamIds = subject.getTeamIds() == null ? List.of() : subject.getTeamIds();
		if (teamIds.isEmpty()) {
			return false;
		}
		return legacyGrantMapper.listActiveByAgentId(agentId)
			.stream()
			.anyMatch(grant -> AgentVisibilityConstant.SUBJECT_TYPE_TEAM.equals(grant.getSubjectType())
					&& teamIds.contains(grant.getSubjectId()));
	}

	/**
	 * 功能权限维度生效授权（等价 hasMatchedPermissionGrant）。
	 */
	private boolean hasMatchedPermissionGrant(Long agentId, AuthorizationSubjectSnapshot subject) {
		List<String> permissions = subject.getFuncPermissions() == null ? List.of() : subject.getFuncPermissions();
		if (permissions.isEmpty()) {
			return false;
		}
		return legacyGrantMapper.listActiveByAgentId(agentId)
			.stream()
			.anyMatch(grant -> AgentVisibilityConstant.SUBJECT_TYPE_PERMISSION.equals(grant.getSubjectType())
					&& permissions.contains(grant.getSubjectId()));
	}

	/**
	 * 授权主体匹配（等价 matchesCurrentUser）：USER/TENANT 精确等值，TEAM/PERMISSION 列表包含。
	 */
	private boolean matchesSnapshot(DataAgentVisibilityGrant grant, AuthorizationSubjectSnapshot subject) {
		if (grant == null || !AgentVisibilityConstant.GRANT_STATUS_ACTIVE.equals(grant.getStatus())) {
			return false;
		}
		return switch (grant.getSubjectType()) {
			case AgentVisibilityConstant.SUBJECT_TYPE_USER -> Objects.equals(grant.getSubjectId(),
					subject.getUserId());
			case AgentVisibilityConstant.SUBJECT_TYPE_TENANT -> Objects.equals(grant.getSubjectId(),
					subject.getTenantId());
			case AgentVisibilityConstant.SUBJECT_TYPE_TEAM -> subject.getTeamIds() != null
					&& subject.getTeamIds().contains(grant.getSubjectId());
			case AgentVisibilityConstant.SUBJECT_TYPE_PERMISSION -> subject.getFuncPermissions() != null
					&& subject.getFuncPermissions().contains(grant.getSubjectId());
			default -> false;
		};
	}

}
