/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.skill.execution.SkillBusinessContext;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.framework.commons.security.DataPermission;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 请求上已有的租户/数据权限快照，原样放入 2.0 RuntimeContext，禁止从请求头再推导。
 *
 * <p>{@code agentId}/{@code ownerType}/{@code ownerId}/{@code durableRunId} 供 v2 PEP 绑策略，
 * 与 1.0 {@code ToolkitAuthorizationFilter} 同一口径，不从请求头重算。
 *
 * <p>P0 规则通道字段（FLOW 实例、表白名单、分析工作区句柄）同样只从请求快照读取，不走向量检索。
 *
 * <p>{@code hitlEnabled} 控制写工具 ASK：Web {@code streamSearch} 与员工 {@code converseStream}
 * 为 true；DRY_RUN / 协作者子调用 / 带 parent / 非 streamSearch（含 {@code executeAgentOnce}）
 * 为 false，写工具走 DENY observation，不发 ASK、不落 PENDING。
 */
public record V2RuntimeSnapshot(String tenantId, String tenantCode, String userId, String userNickName,
		DataPermission dataPermission, String clientId, List<String> teamIds, String sessionId,
		String runtimeRequestId, String agentId, String ownerType, Long ownerId, Long durableRunId,
		String flowInstanceId, List<String> tableWhitelist, List<String> analysisWorkspaceHandles,
		boolean hitlEnabled) {

	public V2RuntimeSnapshot {
		teamIds = teamIds == null ? List.of() : List.copyOf(teamIds);
		tableWhitelist = tableWhitelist == null ? List.of() : copyPresent(tableWhitelist);
		analysisWorkspaceHandles = analysisWorkspaceHandles == null ? List.of()
				: copyPresent(analysisWorkspaceHandles);
	}

	public V2RuntimeSnapshot(String tenantId, String tenantCode, String userId, String userNickName,
			DataPermission dataPermission, String clientId, List<String> teamIds, String sessionId,
			String runtimeRequestId) {
		this(tenantId, tenantCode, userId, userNickName, dataPermission, clientId, teamIds, sessionId,
				runtimeRequestId, null, null, null, null, null, List.of(), List.of(), true);
	}

	public V2RuntimeSnapshot(String tenantId, String tenantCode, String userId, String userNickName,
			DataPermission dataPermission, String clientId, List<String> teamIds, String sessionId,
			String runtimeRequestId, String agentId, String ownerType, Long ownerId, Long durableRunId) {
		this(tenantId, tenantCode, userId, userNickName, dataPermission, clientId, teamIds, sessionId,
				runtimeRequestId, agentId, ownerType, ownerId, durableRunId, null, List.of(), List.of(), true);
	}

	public V2RuntimeSnapshot(String tenantId, String tenantCode, String userId, String userNickName,
			DataPermission dataPermission, String clientId, List<String> teamIds, String sessionId,
			String runtimeRequestId, String flowInstanceId, List<String> tableWhitelist,
			List<String> analysisWorkspaceHandles) {
		this(tenantId, tenantCode, userId, userNickName, dataPermission, clientId, teamIds, sessionId,
				runtimeRequestId, null, null, null, null, flowInstanceId, tableWhitelist, analysisWorkspaceHandles,
				true);
	}

	public static V2RuntimeSnapshot from(AgentRequest request) {
		if (request == null) {
			return new V2RuntimeSnapshot(null, null, null, null, null, null, List.of(), null, null, null, null, null,
					null, null, List.of(), List.of(), true);
		}
		List<String> teamIds = request.getTeamIdsSnapshot() == null ? List.of()
				: List.copyOf(request.getTeamIdsSnapshot());
		return new V2RuntimeSnapshot(request.getTenantIdSnapshot(), request.getTenantCodeSnapshot(),
				request.getUserIdSnapshot(), request.getUserNickNameSnapshot(), request.getDataPermissionSnapshot(),
				request.getClientIdSnapshot(), teamIds, request.getThreadId(), request.getRuntimeRequestId(),
				request.getAgentId(), request.getOwnerType(), request.getOwnerId(), request.getDurableRunId(),
				request.getFlowInstanceId(), tableWhitelist(request), List.of(), hitlEnabled(request));
	}

	static boolean hitlEnabled(AgentRequest request) {
		if (request == null) {
			return true;
		}
		if (ExecutionIntentContext.INTENT_DRY_RUN.equalsIgnoreCase(request.getExecutionIntent())) {
			return false;
		}
		return request.isStreamSearchRuntime() && !request.isCollaboratorChild()
				&& !StringUtils.hasText(request.getParentRuntimeRequestId());
	}

	private static List<String> tableWhitelist(AgentRequest request) {
		SkillBusinessContext business = request.getSkillBusinessContext();
		if (business == null || business.allowedTables().isEmpty()) {
			return List.of();
		}
		List<String> tables = new ArrayList<>();
		for (SkillVersionResources.TableScope table : business.allowedTables()) {
			if (table != null && StringUtils.hasText(table.table())) {
				tables.add(table.table().trim());
			}
		}
		return List.copyOf(tables);
	}

	public V2RuntimeSnapshot withAnalysisWorkspaceHandles(List<String> handles) {
		return new V2RuntimeSnapshot(tenantId, tenantCode, userId, userNickName, dataPermission, clientId, teamIds,
				sessionId, runtimeRequestId, agentId, ownerType, ownerId, durableRunId, flowInstanceId, tableWhitelist,
				handles, hitlEnabled);
	}

	public V2RuntimeSnapshot withHitlEnabled(boolean hitlEnabled) {
		return new V2RuntimeSnapshot(tenantId, tenantCode, userId, userNickName, dataPermission, clientId, teamIds,
				sessionId, runtimeRequestId, agentId, ownerType, ownerId, durableRunId, flowInstanceId, tableWhitelist,
				analysisWorkspaceHandles, hitlEnabled);
	}

	private static List<String> copyPresent(List<String> values) {
		List<String> copied = new ArrayList<>();
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				copied.add(value.trim());
			}
		}
		return List.copyOf(copied);
	}

}
