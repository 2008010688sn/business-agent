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
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookActionResult;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.optimization.service.DataAgentEvolutionShadowService;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHookLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 运行时钩子Dispatcher组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
public class RuntimeHookDispatcher {

	public static final String EVENT_AFTER_RESOURCE_SUCCESS = "AFTER_RESOURCE_SUCCESS";

	public static final String EVENT_AFTER_SKILL_SUCCESS = "AFTER_SKILL_SUCCESS";

	public static final String EVENT_AFTER_SKILL_FAILED = "AFTER_SKILL_FAILED";

	public static final String EVENT_AFTER_AGENT_SUCCESS = "AFTER_AGENT_SUCCESS";

	public static final String EVENT_AFTER_AGENT_FAILED = "AFTER_AGENT_FAILED";

	public static final String EVENT_AFTER_TOOL_SUCCESS = "AFTER_TOOL_SUCCESS";

	public static final String EVENT_AFTER_TOOL_FAILED = "AFTER_TOOL_FAILED";

	private static final String STATUS_SUCCESS = "success";

	private static final String STATUS_FAILED = "failed";

	private final RuntimeHookService hookService;

	private final RuntimeHookActionExecutor actionExecutor;

	private final ObjectMapper objectMapper;

	private final ExecutorService dbOperationExecutor;

	private final ObjectProvider<DataAgentEvolutionShadowService> shadowProvider;

	public RuntimeHookDispatcher(RuntimeHookService hookService, RuntimeHookActionExecutor actionExecutor,
			ObjectMapper objectMapper, @Qualifier("dbOperationExecutor") ExecutorService dbOperationExecutor,
			ObjectProvider<DataAgentEvolutionShadowService> shadowProvider) {
		this.hookService = hookService;
		this.actionExecutor = actionExecutor;
		this.objectMapper = objectMapper;
		this.dbOperationExecutor = dbOperationExecutor;
		this.shadowProvider = shadowProvider;
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterResourceSuccess(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String sessionId, String runtimeRequestId, String idempotencyKey, Map<String, Object> input,
			Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_RESOURCE_SUCCESS, agentId, skillCode, skillVersionId, resourceKey,
				sessionId, runtimeRequestId, idempotencyKey, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterSkillSuccess(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String sessionId,
			String runtimeRequestId, String idempotencyKey, Map<String, Object> input, Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_SKILL_SUCCESS, agentId, skillCode, skillVersionId, resourceKey, sessionId,
				runtimeRequestId, idempotencyKey, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterSkillFailed(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String sessionId,
			String runtimeRequestId, String idempotencyKey, Map<String, Object> input, Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_SKILL_FAILED, agentId, skillCode, skillVersionId, resourceKey, sessionId,
				runtimeRequestId, idempotencyKey, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterAgentSuccess(Long agentId, String sessionId, String runtimeRequestId,
			Map<String, Object> input, Map<String, Object> output) {
		RuntimeHookEvent event = new RuntimeHookEvent(EVENT_AFTER_AGENT_SUCCESS, agentId, null, null, null, sessionId,
				runtimeRequestId, runtimeRequestId, safeMap(input), safeMap(output));
		dispatch(event);
		DataAgentEvolutionShadowService shadow = shadowProvider == null ? null : shadowProvider.getIfAvailable();
		if (shadow != null) {
			shadow.scheduleIfEligible(event);
		}
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterAgentFailed(Long agentId, String sessionId, String runtimeRequestId,
			Map<String, Object> input, Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_AGENT_FAILED, agentId, null, null, null, sessionId, runtimeRequestId,
				runtimeRequestId, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterToolSuccess(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String sessionId, String runtimeRequestId, Map<String, Object> input, Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_TOOL_SUCCESS, agentId, skillCode, skillVersionId, resourceKey, sessionId,
				runtimeRequestId, runtimeRequestId, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatchAfterToolFailed(Long agentId, String skillCode, Long skillVersionId, String resourceKey,
			String sessionId, String runtimeRequestId, Map<String, Object> input, Map<String, Object> output) {
		dispatch(new RuntimeHookEvent(EVENT_AFTER_TOOL_FAILED, agentId, skillCode, skillVersionId, resourceKey, sessionId,
				runtimeRequestId, runtimeRequestId, safeMap(input), safeMap(output)));
	}

	/**
	 * 处理运行时钩子Dispatcher。
	 */
	public void dispatch(RuntimeHookEvent event) {
		List<AgentRuntimeHook> hooks;
		try {
			hooks = hookService.findMatching(event);
		}
		catch (Exception ex) {
			log.warn("Failed to load runtime hooks. eventType={}, agentId={}, skillCode={}, skillVersionId={}, resourceKey={}",
					event == null ? null : event.eventType(), event == null ? null : event.agentId(),
					event == null ? null : event.skillCode(), event == null ? null : event.skillVersionId(),
					event == null ? null : event.resourceKey(), ex);
			return;
		}
		if (hooks.isEmpty()) {
			return;
		}
		for (AgentRuntimeHook hook : hooks) {
			try {
				if (Boolean.FALSE.equals(hook.getAsyncEnabled())) {
					runHook(hook, event);
				}
				else {
					dbOperationExecutor.execute(() -> runHook(hook, event));
				}
			}
			catch (Exception ex) {
				log.warn("Failed to schedule runtime hook. hookCode={}", hook == null ? null : hook.getHookCode(), ex);
			}
		}
	}

	private void runHook(AgentRuntimeHook hook, RuntimeHookEvent event) {
		long startedAt = System.currentTimeMillis();
		AgentRuntimeHookLog logEntry = baseLog(hook, event);
		try {
			RuntimeHookActionResult result = actionExecutor.execute(hook, event);
			logEntry.setStatus(result.success() ? STATUS_SUCCESS : STATUS_FAILED);
			logEntry.setToolKey(result.toolKey());
			logEntry.setIdempotencyKey(firstText(result.idempotencyKey(), event.idempotencyKey()));
			logEntry.setResponseSummary(summary(result.response()));
			logEntry.setErrorMessage(result.success() ? null : result.message());
		}
		catch (Exception ex) {
			logEntry.setStatus(STATUS_FAILED);
			logEntry.setErrorMessage(firstText(ex.getMessage(), "Runtime hook execution failed."));
			log.debug("Runtime hook execution failed. hookCode={}", hook.getHookCode(), ex);
		}
		finally {
			logEntry.setElapsedMs(System.currentTimeMillis() - startedAt);
			try {
				hookService.writeLog(logEntry);
			}
			catch (Exception logEx) {
				log.warn("Failed to write runtime hook log. hookCode={}", hook.getHookCode(), logEx);
			}
		}
	}

	private AgentRuntimeHookLog baseLog(AgentRuntimeHook hook, RuntimeHookEvent event) {
		AgentRuntimeHookLog logEntry = new AgentRuntimeHookLog();
		logEntry.setHookCode(hook.getHookCode());
		logEntry.setEventType(event.eventType());
		logEntry.setStatus(STATUS_FAILED);
		logEntry.setActionType(hook.getActionType());
		logEntry.setAgentId(event.agentId());
		logEntry.setSkillCode(event.skillCode());
		logEntry.setSkillVersionId(event.skillVersionId());
		logEntry.setResourceKey(event.resourceKey());
		logEntry.setSessionId(event.sessionId());
		logEntry.setRuntimeRequestId(event.runtimeRequestId());
		logEntry.setIdempotencyKey(event.idempotencyKey());
		logEntry.setRequestSummary(summary(Map.of("input", safeMap(event.input()), "output", safeMap(event.output()))));
		return logEntry;
	}

	private Map<String, Object> safeMap(Map<String, Object> value) {
		return value == null ? Map.of() : new LinkedHashMap<>(value);
	}

	private String summary(Object value) {
		try {
			String json = objectMapper.writeValueAsString(value == null ? Map.of() : value);
			return json.length() <= 4000 ? json : json.substring(0, 4000);
		}
		catch (Exception ex) {
			return value == null ? null : abbreviate(String.valueOf(value));
		}
	}

	private String abbreviate(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		return value.length() <= 4000 ? value : value.substring(0, 4000);
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

}
