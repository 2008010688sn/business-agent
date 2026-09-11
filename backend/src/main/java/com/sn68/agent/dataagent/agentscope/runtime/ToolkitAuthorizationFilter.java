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
package com.sn68.agent.dataagent.agentscope.runtime;

import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pep.ShadowRecorder;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 工具列表部分授权过滤器（PR-4 接线点：AgentScopeToolkitFactory 组装后）。
 *
 * <p>v1.2 清单 PR-4 表格的工具列表线：{@code resolveRuntimeToolCallbacks} 产出工具回调集合后，
 * 按 PDP 决策做<b>部分授权</b>——deny 的工具不注入模型工具箱（未授权工具对模型不可见，
 * 验收 11.2 的 2.14），allow 的工具保留；全部 deny 时返回空集合（模型可继续纯文本对话）。
 * 执行期兜底不放松：工具真正调用仍经 CapabilityGateway Hook 线判定（审批不能补权限）。</p>
 *
 * <p>SHADOW（默认）：装配期<b>不过滤、不记录</b>——未授权工具保持模型可见，授权差异由
 * 执行期 Hook 线的影子比对承担（装配期逐工具预评估会成倍放大影子日志量且与执行期判定
 * 重复），现网行为零变化。ENFORCE：空主体（无租户/无用户）不再放行全部工具——fail-closed
 * 抛 {@link CheckedException}（与 {@code InvocationSubjectGuard} 平台硬基线同口径）。</p>
 *
 * <p>被移除工具的 deny 决策落影子日志（original=FALSE：装配期拦截即最终结论，MATCHED
 * 口径不虚增差异率），供 PR-10 差异报告与审计回溯。</p>
 *
 * @author Leo (PR-4 遗留接线：工具列表部分授权)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolkitAuthorizationFilter {

	/**
	 * 影子日志场景标识：工具箱装配期部分授权。
	 */
	public static final String SCENE_TOOLKIT_PARTIAL = "TOOLKIT_PARTIAL";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator;

	private final ShadowRecorder shadowRecorder;

	private final PepAuthorizationProperties pepProperties;

	private final AuthenticationContext authenticationContext;

	/**
	 * 工具回调集合部分授权（v1.2 PR-4：ToolkitAuthorizationFilter.partiallyAuthorize）。
	 *
	 * @param request       运行请求（owner/主体/租户快照来源）
	 * @param toolCallbacks 组装完成的工具回调集合
	 * @return 授权后的工具回调集合（SHADOW 原样返回；ENFORCE 移除 deny 工具，可能为空集）
	 */
	public Map<String, ToolCallback> partiallyAuthorize(AgentRequest request, Map<String, ToolCallback> toolCallbacks) {
		if (request == null || toolCallbacks == null || toolCallbacks.isEmpty()) {
			return toolCallbacks == null ? Map.of() : toolCallbacks;
		}
		String tenantId = resolveTenantId(request);
		PepAuthorizationMode mode = pepProperties.resolveMode(tenantId);
		if (!mode.enforce()) {
			// SHADOW 红线：装配期零行为变化，未授权工具对模型保持可见（差异观测移交执行期 Hook 线）
			return toolCallbacks;
		}
		String subjectId = resolveRequiredSubjectId(request);
		AuthorizationOwnerType ownerType = resolveOwnerType(request);
		Long ownerId = resolveOwnerId(request, ownerType);
		SubjectKind subjectKind = ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE
				? SubjectKind.DIGITAL_EMPLOYEE : SubjectKind.CALLER;
		Map<String, ToolCallback> authorized = new LinkedHashMap<>();
		for (Map.Entry<String, ToolCallback> entry : toolCallbacks.entrySet()) {
			if (authorizeTool(request, tenantId, ownerType, ownerId, subjectKind, subjectId, entry.getKey())) {
				authorized.put(entry.getKey(), entry.getValue());
			}
		}
		if (authorized.size() < toolCallbacks.size()) {
			log.info("工具箱部分授权完成(ENFORCE). agentId={}, tenantId={}, total={}, authorized={}, removed={}",
					request.getAgentId(), tenantId, toolCallbacks.size(), authorized.size(),
					toolCallbacks.size() - authorized.size());
		}
		return authorized;
	}

	/**
	 * 单工具 EXECUTE 授权：PDP deny → 落影子（original=FALSE）并移除；allow → 保留。
	 * ENFORCE 下评估异常失败关闭上抛（无法证明安全的工具不放行）。
	 */
	private boolean authorizeTool(AgentRequest request, String tenantId, AuthorizationOwnerType ownerType,
			Long ownerId, SubjectKind subjectKind, String subjectId, String toolName) {
		PepDecisionContext context = PepDecisionContext.builder()
			.tenantId(tenantId)
			.ownerType(ownerType)
			.ownerId(ownerId)
			.subjectKind(subjectKind)
			.subjectId(subjectId)
			.capabilityCode(toolName)
			.action(AuthorizationAction.EXECUTE)
			.build();
		PepDecisionResult result = runtimePolicyEvaluator.evaluate(context);
		if (result.allowed()) {
			return true;
		}
		shadowRecorder.recordShadowDecision(result, SCENE_TOOLKIT_PARTIAL, Boolean.FALSE, parseTenantId(tenantId),
				null, null);
		log.info("未授权工具已对模型隐藏(ENFORCE). toolName={}, reasonCode={}, decisionId={}, agentId={}",
				toolName, result.reasonCode(), result.getDecisionId(), request.getAgentId());
		return false;
	}

	/**
	 * ENFORCE 空主体硬拒绝：无用户（Holder 为空）不再放行全部工具（v1.2 PR-4 原文口径），
	 * 快照优先、授权上下文回退，两者都解析不出即抛。
	 */
	private String resolveRequiredSubjectId(AgentRequest request) {
		String userId = resolveUserId(request);
		if (StringUtils.hasText(userId)) {
			return userId.trim();
		}
		throw CheckedException.fail("PEP 空主体拒绝（BUSINESS_DENIED）：ENFORCE 租户的工具列表授权要求"
				+ "租户与请求主体完备（Holder 为空不再放行全部工具）, agentId=" + request.getAgentId()
				+ ", tenantIdSnapshot=" + request.getTenantIdSnapshot());
	}

	/**
	 * owner 类型解析：请求显式携带优先；未携带按普通智能体对话处理（DATA_AGENT 域）。
	 */
	private AuthorizationOwnerType resolveOwnerType(AgentRequest request) {
		AuthorizationOwnerType ownerType = AuthorizationOwnerType.fromCode(request.getOwnerType());
		return ownerType == null ? AuthorizationOwnerType.DATA_AGENT : ownerType;
	}

	/**
	 * owner ID 解析：数字员工链路取请求 ownerId；其余按 agentId（DATA_AGENT 域）。
	 */
	private Long resolveOwnerId(AgentRequest request, AuthorizationOwnerType ownerType) {
		if (ownerType == AuthorizationOwnerType.DIGITAL_EMPLOYEE && request.getOwnerId() != null) {
			return request.getOwnerId();
		}
		if (!StringUtils.hasText(request.getAgentId())) {
			return null;
		}
		try {
			return Long.valueOf(request.getAgentId().trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 真人用户ID解析：请求快照优先（Web 当前登录人 / IM 委托真人），授权上下文回退。
	 */
	private String resolveUserId(AgentRequest request) {
		if (StringUtils.hasText(request.getUserIdSnapshot())) {
			return request.getUserIdSnapshot().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.userId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("工具列表授权读取授权上下文用户失败. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * 租户解析：请求快照优先，授权上下文回退（异步链路），解析失败返回 null（ENFORCE 全局
	 * 模式下由空主体校验失败关闭；默认 SHADOW 直接原样返回不过滤）。
	 */
	private String resolveTenantId(AgentRequest request) {
		if (StringUtils.hasText(request.getTenantIdSnapshot())) {
			return request.getTenantIdSnapshot().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.tenantId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("工具列表授权读取授权上下文租户失败. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * 租户ID转 Long（事件表数字口径；解析失败返回 null 由 ShadowRecorder 落 0）。
	 */
	private Long parseTenantId(String tenantId) {
		if (StrUtil.isBlank(tenantId)) {
			return null;
		}
		try {
			return Long.valueOf(tenantId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
