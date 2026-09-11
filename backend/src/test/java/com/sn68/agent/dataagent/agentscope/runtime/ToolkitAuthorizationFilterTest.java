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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationMode;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionResult;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.authorization.pep.ShadowRecorder;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import com.sn68.agent.dataagent.authorization.pdp.DecisionReasonCode;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具列表部分授权过滤器测试（PR-4 接线点 3）。
 *
 * <p>SHADOW 红线：装配期零行为零记录（未授权工具保持模型可见，差异观测移交执行期 Hook 线）；
 * ENFORCE：deny 工具对模型不可见（deny 决策落影子 original=FALSE），全部 deny 返回空集，
 * 空主体（Holder 为空）不再放行全部工具——fail-closed。</p>
 *
 * @author Leo (PR-4 遗留接线：工具列表部分授权)
 */
class ToolkitAuthorizationFilterTest {

	/** ENFORCE 灰度白名单租户。 */
	private static final String ENFORCE_TENANT = "1001";

	/** 非白名单租户（SHADOW）。 */
	private static final String SHADOW_TENANT = "2002";

	private final RuntimePolicyEvaluator runtimePolicyEvaluator = mock(RuntimePolicyEvaluator.class);

	private final ShadowRecorder shadowRecorder = mock(ShadowRecorder.class);

	private final PepAuthorizationProperties pepProperties = new PepAuthorizationProperties();

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final ToolkitAuthorizationFilter filter = new ToolkitAuthorizationFilter(runtimePolicyEvaluator,
			shadowRecorder, pepProperties, authenticationContext);

	@BeforeEach
	void setUp() {
		pepProperties.setEnforceTenantIds(List.of(ENFORCE_TENANT));
	}

	@Test
	void shadowModeReturnsCallbacksUntouched() {
		// PR-4 SHADOW 红线：不过滤、不评估、不记录，现网行为零变化
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		callbacks.put("toolA", mock(ToolCallback.class));
		callbacks.put("toolB", mock(ToolCallback.class));

		Map<String, ToolCallback> result = filter.partiallyAuthorize(request(SHADOW_TENANT), callbacks);

		assertEquals(callbacks, result);
		verify(runtimePolicyEvaluator, never()).evaluate(any());
		verify(shadowRecorder, never()).recordShadowDecision(any(), anyString(), any(), any(), any(), any());
	}

	@Test
	void enforceModeRemovesDeniedTool() {
		ToolCallback allowed = mock(ToolCallback.class);
		ToolCallback denied = mock(ToolCallback.class);
		Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
		callbacks.put("toolA", allowed);
		callbacks.put("toolB", denied);
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class))).thenAnswer(invocation -> {
			PepDecisionContext context = invocation.getArgument(0);
			return "toolA".equals(context.getCapabilityCode())
					? decision(true, PepAuthorizationMode.ENFORCE)
					: decision(false, PepAuthorizationMode.ENFORCE);
		});

		Map<String, ToolCallback> result = filter.partiallyAuthorize(request(ENFORCE_TENANT), callbacks);

		assertEquals(1, result.size());
		assertSame(allowed, result.get("toolA"));
		// deny 决策落影子（original=FALSE：装配期拦截即最终结论）
		verify(shadowRecorder).recordShadowDecision(any(PepDecisionResult.class),
				eq(ToolkitAuthorizationFilter.SCENE_TOOLKIT_PARTIAL), eq(Boolean.FALSE), eq(1001L), isNull(), isNull());
	}

	@Test
	void enforceModeReturnsEmptySetWhenAllToolsDenied() {
		when(runtimePolicyEvaluator.evaluate(any(PepDecisionContext.class)))
			.thenReturn(decision(false, PepAuthorizationMode.ENFORCE));
		Map<String, ToolCallback> callbacks = Map.of("toolA", mock(ToolCallback.class));

		Map<String, ToolCallback> result = filter.partiallyAuthorize(request(ENFORCE_TENANT), callbacks);

		// 全部 deny 返回空集：模型可继续纯文本对话，不抛异常
		assertTrue(result.isEmpty());
	}

	@Test
	void enforceModeRejectsMissingSubject() {
		AgentRequest request = request(ENFORCE_TENANT);
		request.setUserIdSnapshot(null);

		// ENFORCE 空主体硬拒绝：无用户不再放行全部工具（v1.2 PR-4 原文口径）
		assertThrows(CheckedException.class,
				() -> filter.partiallyAuthorize(request, Map.of("toolA", mock(ToolCallback.class))));
		verify(runtimePolicyEvaluator, never()).evaluate(any());
	}

	private AgentRequest request(String tenantId) {
		AgentRequest request = new AgentRequest();
		request.setAgentId("1");
		request.setUserIdSnapshot("u1");
		request.setTenantIdSnapshot(tenantId);
		return request;
	}

	private PepDecisionResult decision(boolean allowed, PepAuthorizationMode mode) {
		return PepDecisionResult.builder()
			.decisionId("dec-" + System.nanoTime())
			.subjectKind(SubjectKind.CALLER)
			.decision(AuthorizationDecision.builder()
				.allowed(allowed)
				.reasonCode(allowed ? DecisionReasonCode.POLICY_ALLOWED : DecisionReasonCode.POLICY_DENIED)
				.obligations(List.of())
				.maskFields(List.of())
				.evaluatedAt(Instant.now())
				.build())
			.effectiveMode(mode)
			.evaluatedAt(Instant.now())
			.build();
	}

}
