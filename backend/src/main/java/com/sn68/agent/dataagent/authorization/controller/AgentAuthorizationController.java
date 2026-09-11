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
package com.sn68.agent.dataagent.authorization.controller;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionResp;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionAuditResp;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationDecisionSimulateReq;
import com.sn68.agent.dataagent.authorization.dto.AuthorizationRuleReq;
import com.sn68.agent.dataagent.authorization.model.AuthorizationPolicy;
import com.sn68.agent.dataagent.authorization.model.AuthorizationRule;
import com.sn68.agent.dataagent.authorization.model.PolicyValidator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationPolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationRequest;
import com.sn68.agent.dataagent.authorization.template.AuthorizationTemplate;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeEvent;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeEventMapper;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 授权中心入口（PR-3a：仅提供决策模拟；PAP 管理端点由 PR-3b 增量扩展）。
 *
 * <p>simulate 是纯逻辑接口：不读库、不依赖运行时接线，供权限中心联调与下游 PR-3b/PR-4 对接契约验证。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Slf4j
@RestController
@RequestMapping("/agent-authorizations")
@RequiredArgsConstructor
@Tag(name = "Agent 授权中心", description = "PDP 授权决策模拟（PR-3a 最小切片，PAP 管理端点由 PR-3b 扩展）")
public class AgentAuthorizationController {

	private final AuthorizationPolicyEvaluator policyEvaluator;

	private final AgentRuntimeEventMapper runtimeEventMapper;

	private final AuthenticationContext authenticationContext;

	@Operation(summary = "授权决策模拟 - [DONE] - [Felix]", description = "输入 inline 策略 JSON 或模板代码（可覆盖规则），对指定主体/能力/动作求值并返回决策结果；不依赖任何运行时接线。")
	@AccessLog(module = "Agent 授权中心", description = "授权决策模拟")
	@PostMapping("/decisions/simulate")
	public AuthorizationDecisionResp simulate(@Valid @RequestBody AuthorizationDecisionSimulateReq req) {
		AuthorizationPolicy policy = resolvePolicy(req);
		AuthorizationRequest request = toRequest(req);
		AuthorizationDecision decision = policyEvaluator.evaluate(policy, request);
		log.info("授权决策模拟完成, templateCode={}, capabilityCode={}, action={}, allowed={}, reasonCode={}",
				req.getTemplateCode(), req.getCapabilityCode(), req.getAction(), decision.isAllowed(),
				decision.getReasonCode());
		return AuthorizationDecisionResp.builder()
				.allowed(decision.isAllowed())
				.reasonCode(decision.getReasonCode())
				.obligations(decision.getObligations())
				.maskFields(decision.getMaskFields())
				.policyHash(decision.getPolicyHash())
				.evaluatedAt(decision.getEvaluatedAt())
				.build();
	}

	@Operation(summary = "查询授权决策审计 - [DONE] - [Lee]", description = "按 decisionId 读取当前租户的 AUTHORIZATION_DECISION 事件审计列。")
	@GetMapping("/decisions/{decisionId}")
	public AuthorizationDecisionAuditResp getDecision(@PathVariable String decisionId) {
		if (!StringUtils.hasText(decisionId)) {
			throw CheckedException.badRequest("decisionId不能为空");
		}
		AgentRuntimeEvent event = runtimeEventMapper.findAuthorizationDecision(decisionId.trim(), currentTenantId());
		if (event == null) {
			throw CheckedException.notFound("授权决策不存在或不属于当前租户: " + decisionId);
		}
		return AuthorizationDecisionAuditResp.builder()
				.decisionId(event.getDecisionId())
				.runId(event.getRunId())
				.tenantId(event.getTenantId())
				.policyHash(event.getPolicyHash())
				.subjectKind(event.getSubjectKind())
				.reasonCode(event.getReasonCode())
				.occurredAt(event.getOccurredAt())
				.detailedDecisionLog(event.getDetailedDecisionLog())
				.build();
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止查询授权决策");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止查询授权决策");
		}
		return tenantId.trim();
	}

	/**
	 * 解析参与求值的策略：policyJson 与 templateCode 二选一。
	 */
	private AuthorizationPolicy resolvePolicy(AuthorizationDecisionSimulateReq req) {
		boolean hasInlinePolicy = StrUtil.isNotBlank(req.getPolicyJson());
		boolean hasTemplate = StrUtil.isNotBlank(req.getTemplateCode());
		if (hasInlinePolicy == hasTemplate) {
			throw CheckedException.badRequest("policyJson 与 templateCode 必须提供且仅提供其一");
		}
		if (hasInlinePolicy) {
			// inline 策略走严格校验（未知字段拒绝、schemaVersion=1、中文错误消息）
			return PolicyValidator.validateAndParse(req.getPolicyJson());
		}
		AuthorizationTemplate template = AuthorizationTemplate.fromCode(req.getTemplateCode());
		if (template == null) {
			throw CheckedException.badRequest("未知的模板代码: " + req.getTemplateCode());
		}
		AuthorizationPolicy defaultPolicy = template.defaultPolicy();
		if (CollUtil.isEmpty(req.getRules())) {
			return defaultPolicy;
		}
		// 覆盖规则时保留模板的策略级字段（subjectMode/iamUnavailableBehavior/allowModelOnly）
		return defaultPolicy.toBuilder()
				.rules(toRules(req.getRules()))
				.build();
	}

	/**
	 * 规则覆盖 DTO 转内核规则；空集合字段归一为空列表，保持匹配语义"空即通配"。
	 */
	private List<AuthorizationRule> toRules(List<AuthorizationRuleReq> ruleReqs) {
		return ruleReqs.stream()
				.map(ruleReq -> AuthorizationRule.builder()
						.name(ruleReq.getName())
						.effect(ruleReq.getEffect())
						.capabilityCodes(ruleReq.getCapabilityCodes() == null ? List.of() : ruleReq.getCapabilityCodes())
						.actions(ruleReq.getActions() == null ? List.of() : ruleReq.getActions())
						.obligations(ruleReq.getObligations() == null ? List.of() : ruleReq.getObligations())
						.maskFields(ruleReq.getMaskFields() == null ? List.of() : ruleReq.getMaskFields())
						.build())
				.toList();
	}

	/**
	 * 请求 DTO 转 PDP 请求；iamAvailable 缺省按 true 处理。
	 */
	private AuthorizationRequest toRequest(AuthorizationDecisionSimulateReq req) {
		return AuthorizationRequest.builder()
				.subjectKind(req.getSubjectKind())
				.capabilityCode(req.getCapabilityCode())
				.action(req.getAction())
				.capabilityVersion(req.getCapabilityVersion())
				.iamAvailable(req.getIamAvailable() == null || req.getIamAvailable())
				.build();
	}
}
