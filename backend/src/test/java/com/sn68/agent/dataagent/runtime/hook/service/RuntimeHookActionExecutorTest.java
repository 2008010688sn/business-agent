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
package com.sn68.agent.dataagent.runtime.hook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationSendResponse;
import com.sn68.agent.dataagent.notification.service.NotificationFacadeService;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookActionResult;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeHookActionExecutorTest {

	private final NotificationFacadeService notificationFacadeService = mock(NotificationFacadeService.class);

	private final CapabilityGateway capabilityGateway = mock(CapabilityGateway.class);

	private final RuntimeHookActionExecutor executor = new RuntimeHookActionExecutor(notificationFacadeService,
			capabilityGateway, new ObjectMapper());

	@Test
	void notificationHookActionUsesHookNotificationEntry() {
		when(notificationFacadeService.sendFromHook(any(NotificationSendRequest.class)))
			.thenReturn(new NotificationSendResponse("SENT", "delivery-1", "OK", null));

		RuntimeHookActionResult result = executor.execute(notificationHook(), event());

		assertTrue(result.success());
		assertTrue(result.idempotencyKey().startsWith("hook:AFTER_SKILL_SUCCESS:runtime-1:resource:notification.send"));
		verify(notificationFacadeService).sendFromHook(argThat(request -> request != null
				&& "skill-a".equals(request.skillCode()) && Long.valueOf(11L).equals(request.skillVersionId())
				&& "notification.send".equals(request.resourceKey())
				&& "target-a".equals(request.targetAlias()) && "template-a".equals(request.templateCode())
				&& request.idempotencyKey().startsWith("hook:AFTER_SKILL_SUCCESS:runtime-1:resource:notification.send")));
		verify(notificationFacadeService, never()).send(any(NotificationSendRequest.class));
	}

	@Test
	void notificationHookRendersCustomIdempotencyKeyWithTargetAlias() {
		when(notificationFacadeService.sendFromHook(any(NotificationSendRequest.class)))
			.thenReturn(new NotificationSendResponse("SENT", "delivery-1", "OK", null));

		RuntimeHookActionResult result = executor.execute(notificationHook(
				"hook:${eventType}:${runtimeRequestId}:${resourceKey}:notification.send:${targetAlias}"), event());

		assertTrue(result.success());
		verify(notificationFacadeService).sendFromHook(argThat(request -> request != null
				&& "hook:AFTER_SKILL_SUCCESS:runtime-1:resource:notification.send:target-a"
					.equals(request.idempotencyKey())));
	}

	@Test
	void notificationHookRendersDemandDetailUrlFromOutputData() {
		when(notificationFacadeService.sendFromHook(any(NotificationSendRequest.class)))
			.thenReturn(new NotificationSendResponse("SENT", "delivery-1", "OK", null));

		RuntimeHookActionResult result = executor.execute(notificationHookWithDemandUrl(), eventWithDemandOutput());

		assertTrue(result.success());
		verify(notificationFacadeService).sendFromHook(argThat(request -> request != null
				&& "demand-1".equals(request.variables().get("demandId"))
				&& "XQ202607090001".equals(request.variables().get("demandNo"))
				&& "https://platform.example.com/order/detail?id=demand-1".equals(request.variables().get("url"))
				&& "https://platform.example.com/order/detail?id=demand-1".equals(request.variables().get("detailUrl"))));
	}

	private AgentRuntimeHook notificationHook() {
		return notificationHook(null);
	}

	private AgentRuntimeHook notificationHook(String idempotencyKey) {
		AgentRuntimeHook hook = new AgentRuntimeHook();
		hook.setActionType("TOOL_CALL");
		String idempotencyKeyConfig = idempotencyKey == null ? "" : ", \"idempotencyKey\": \"" + idempotencyKey + "\"";
		hook.setActionConfig("""
				{"toolKey":"notification.send","targetAlias":"target-a","templateCode":"template-a","confirmed":true%s}
				""".formatted(idempotencyKeyConfig));
		return hook;
	}

	private AgentRuntimeHook notificationHookWithDemandUrl() {
		AgentRuntimeHook hook = new AgentRuntimeHook();
		hook.setActionType("TOOL_CALL");
		hook.setActionConfig("""
				{"toolKey":"notification.send","targetAlias":"target-a","templateCode":"template-a","confirmed":true,
				"variables":{"demandId":"${output.data.demandId}","demandNo":"${output.data.demandNo}",
				"url":"https://platform.example.com/order/detail?id=${output.data.demandId}",
				"detailUrl":"https://platform.example.com/order/detail?id=${output.data.demandId}"}}
				""");
		return hook;
	}

	private RuntimeHookEvent event() {
		return new RuntimeHookEvent("AFTER_SKILL_SUCCESS", 7L, "skill-a", 11L, "resource", "session-1",
				"runtime-1", "idem-1", Map.of(), Map.of());
	}

	private RuntimeHookEvent eventWithDemandOutput() {
		return new RuntimeHookEvent("AFTER_SKILL_SUCCESS", 7L, "skill-a", 11L,
				"demo.echo.execute", "session-1", "runtime-1", "idem-1", Map.of(),
				Map.of("data", Map.of("demandId", "demand-1", "demandNo", "XQ202607090001")));
	}

}
