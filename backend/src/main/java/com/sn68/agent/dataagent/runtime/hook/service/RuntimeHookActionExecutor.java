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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.CapabilityKind;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.dto.NotificationSendResponse;
import com.sn68.agent.dataagent.notification.service.NotificationAuthorizationService;
import com.sn68.agent.dataagent.notification.service.NotificationFacadeService;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookActionResult;
import com.sn68.agent.dataagent.runtime.hook.dto.RuntimeHookEvent;
import com.sn68.agent.dataagent.runtime.hook.entity.AgentRuntimeHook;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 运行时钩子ActionExecutor组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeHookActionExecutor {

	private static final String ACTION_TOOL_CALL = "TOOL_CALL";

	private static final String TOOL_NOTIFICATION_SEND = NotificationAuthorizationService.RESOURCE_KEY;

	private static final String SOURCE_RUNTIME_HOOK = "RUNTIME_HOOK";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final NotificationFacadeService notificationFacadeService;

	private final CapabilityGateway capabilityGateway;

	private final ObjectMapper objectMapper;

	/**
	 * 执行运行时钩子ActionExecutor。
	 */
	public RuntimeHookActionResult execute(AgentRuntimeHook hook, RuntimeHookEvent event) {
		if (hook == null || event == null) {
			throw CheckedException.badRequest("Runtime hook and event are required.");
		}
		if (!ACTION_TOOL_CALL.equalsIgnoreCase(hook.getActionType())) {
			throw CheckedException.badRequest("Unsupported hook action type: " + hook.getActionType());
		}
		Map<String, Object> actionConfig = renderedActionConfig(hook, event);
		String toolKey = firstText(stringValue(actionConfig.get("toolKey")), stringValue(actionConfig.get("resourceKey")));
		if (!StringUtils.hasText(toolKey)) {
			throw CheckedException.badRequest("Hook toolKey is required.");
		}
		if (TOOL_NOTIFICATION_SEND.equalsIgnoreCase(toolKey)) {
			return executeNotification(actionConfig, event);
		}
		Map<String, Object> arguments = arguments(actionConfig);
		arguments.putIfAbsent("agentId", event.agentId());
		arguments.putIfAbsent("skillCode", event.skillCode());
		arguments.putIfAbsent("skillVersionId", event.skillVersionId());
		arguments.putIfAbsent("sessionId", event.sessionId());
		arguments.putIfAbsent("runtimeRequestId", event.runtimeRequestId());
		arguments.putIfAbsent("idempotencyKey", defaultIdempotencyKey(null, toolKey, event));
		// 钩子工具动作统一走能力网关（capabilityKind=HOOK），不再直连 ToolTransportInvoker 绕过
		// 白名单/授权/风险/限流检查；检查拒绝抛 CheckedException，由 RuntimeHookDispatcher 记录失败 hook log。
		InvocationRequest invocationRequest = InvocationRequest.builder()
			.capabilityKind(CapabilityKind.HOOK)
			.capabilityCode(toolKey)
			.arguments(arguments)
			.source(SOURCE_RUNTIME_HOOK)
			.idempotencyKey(stringValue(arguments.get("idempotencyKey")))
			.stepKey(event.runtimeRequestId())
			.agentId(event.agentId())
			.build();
		ResultEnvelope envelope = capabilityGateway.invoke(invocationRequest);
		return new RuntimeHookActionResult(true, toolKey, envelope.idempotencyKey(), "OK", envelope.data());
	}

	private RuntimeHookActionResult executeNotification(Map<String, Object> actionConfig, RuntimeHookEvent event) {
		@SuppressWarnings("unchecked")
		Map<String, Object> variables = actionConfig.get("variables") instanceof Map<?, ?> map ? copyStringKeyMap(map)
				: Map.of();
		String targetAlias = firstText(stringValue(actionConfig.get("targetAlias")),
				stringValue(actionConfig.get("target")));
		String templateCode = firstText(stringValue(actionConfig.get("templateCode")),
				stringValue(actionConfig.get("template")));
		String idempotencyKey = firstText(stringValue(actionConfig.get("idempotencyKey")),
				defaultIdempotencyKey(targetAlias, TOOL_NOTIFICATION_SEND, event));
		Boolean confirmed = booleanValue(actionConfig.get("confirmed"));
		NotificationSendRequest request = new NotificationSendRequest(event.agentId(), event.skillCode(),
				event.skillVersionId(), TOOL_NOTIFICATION_SEND, event.sessionId(), event.runtimeRequestId(), targetAlias, templateCode,
				variables, idempotencyKey, confirmed);
		NotificationSendResponse response = notificationFacadeService.sendFromHook(request);
		boolean success = response != null && ("SENT".equalsIgnoreCase(response.status())
				|| "NEED_CONFIRMATION".equalsIgnoreCase(response.status()) || "PREVIEW".equalsIgnoreCase(response.status()));
		return new RuntimeHookActionResult(success, TOOL_NOTIFICATION_SEND, idempotencyKey,
				response == null ? "Notification returned empty response." : response.message(), response);
	}

	private Map<String, Object> renderedActionConfig(AgentRuntimeHook hook, RuntimeHookEvent event) {
		Map<String, Object> raw = readJsonObject(hook.getActionConfig());
		Object rendered = renderValue(raw, context(event, raw));
		return rendered instanceof Map<?, ?> map ? copyStringKeyMap(map) : Map.of();
	}

	private Map<String, Object> context(RuntimeHookEvent event, Map<String, Object> actionConfig) {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("eventType", event.eventType());
		context.put("agentId", event.agentId());
		context.put("skillCode", event.skillCode());
		context.put("skillVersionId", event.skillVersionId());
		context.put("resourceKey", event.resourceKey());
		context.put("sessionId", event.sessionId());
		context.put("runtimeRequestId", event.runtimeRequestId());
		context.put("idempotencyKey", event.idempotencyKey());
		context.put("input", event.input() == null ? Map.of() : event.input());
		context.put("output", event.output() == null ? Map.of() : event.output());
		if (actionConfig != null) {
			actionConfig.forEach((key, value) -> {
				if (key != null) {
					context.putIfAbsent(String.valueOf(key), value);
				}
			});
		}
		return context;
	}

	@SuppressWarnings("unchecked")
	private Object renderValue(Object value, Map<String, Object> context) {
		if (value instanceof String text) {
			return renderText(text, context);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> rendered = new LinkedHashMap<>();
			map.forEach((key, itemValue) -> {
				if (key != null) {
					rendered.put(String.valueOf(key), renderValue(itemValue, context));
				}
			});
			return rendered;
		}
		if (value instanceof Collection<?> collection) {
			return collection.stream().map(item -> renderValue(item, context)).toList();
		}
		return value;
	}

	private Object renderText(String text, Map<String, Object> context) {
		Matcher exact = PLACEHOLDER.matcher(text);
		if (exact.matches()) {
			Object resolved = resolvePath(exact.group(1), context);
			return resolved == null ? "" : resolved;
		}
		Matcher matcher = PLACEHOLDER.matcher(text);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find()) {
			Object resolved = resolvePath(matcher.group(1), context);
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved == null ? "" : String.valueOf(resolved)));
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private Object resolvePath(String path, Map<String, Object> context) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		String normalized = path.trim();
		if (context.containsKey(normalized)) {
			return context.get(normalized);
		}
		Object current = context;
		for (String part : normalized.split("\\.")) {
			if (!(current instanceof Map<?, ?> map)) {
				return null;
			}
			current = map.get(part);
		}
		return current;
	}

	private Map<String, Object> arguments(Map<String, Object> actionConfig) {
		Object configured = actionConfig.get("arguments");
		if (configured instanceof Map<?, ?> map) {
			return copyStringKeyMap(map);
		}
		Map<String, Object> arguments = new LinkedHashMap<>(actionConfig);
		arguments.remove("toolKey");
		arguments.remove("resourceKey");
		return arguments;
	}

	private String defaultIdempotencyKey(String targetAlias, String toolKey, RuntimeHookEvent event) {
		return String.join(":", List.of("hook", firstText(event.eventType(), "event"), firstText(event.runtimeRequestId(),
				event.sessionId(), event.idempotencyKey(), "runtime"), firstText(event.resourceKey(), "resource"),
				firstText(toolKey, "tool"), firstText(targetAlias, "target")));
	}

	private Boolean booleanValue(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			return Boolean.valueOf(text.trim());
		}
		return null;
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, MAP_TYPE);
			return map == null ? Map.of() : new LinkedHashMap<>(map);
		}
		catch (Exception ex) {
			log.debug("Failed to parse runtime hook action config", ex);
			return Map.of();
		}
	}

	private Map<String, Object> copyStringKeyMap(Map<?, ?> source) {
		Map<String, Object> target = new LinkedHashMap<>();
		source.forEach((key, value) -> {
			if (key != null) {
				target.put(String.valueOf(key), value);
			}
		});
		return target;
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
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
