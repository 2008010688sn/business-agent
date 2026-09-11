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
package com.sn68.agent.dataagent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.temporal.TemporalKind;
import com.sn68.agent.dataagent.temporal.TemporalResolution;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
/**
 * MCP 工具入参规范化器：按工具 inputSchema 对模型生成的调用参数做类型矫正与裁剪，降低远端调用因参数形态不符而失败的概率。
 */
@Component
@RequiredArgsConstructor
public class McpToolArgumentNormalizer {

	private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {
	};

	private static final String EXT_CONFIG_INPUT_SCHEMA = "inputSchema";

	private static final String EXT_CONFIG_ARGUMENT_WRAPPER = "mcpArgumentWrapper";

	private static final String EXT_CONFIG_TEMPORAL_FIELDS = "mcpTemporalFields";

	private static final String EXT_CONFIG_TEMPORAL_POLICIES = "mcpTemporalPolicies";

	private static final String EXT_CONFIG_RUNTIME_PARAM_MAPPINGS = "runtimeParamMappings";

	private static final Set<String> DEFAULT_INSTANT_FIELD_KEYS = Set.of("arrivalTime");

	private static final Set<String> RUNTIME_ARGUMENT_KEYS = Set.of("resourceKey", "agentId", "sessionId",
			"runtimeRequestId", "skillCode", "skillId", "skillVersionId", "flowInstanceId", "interactionMode",
			"_interactionMode", "_userQuery", "_agentId", "_skillVersionId", "_resourceVersionId", "confirmed");

	private final ObjectMapper objectMapper;

	private final AgentTemporalService agentTemporalService;

	public Map<String, Object> normalize(AgentExecutionResource resource, Map<String, Object> arguments) {
		Map<String, Object> safeArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		Map<String, Object> extConfig = readJsonObject(resource == null ? null : resource.getExtConfig());
		Map<String, TemporalFieldPolicy> temporalPolicies = temporalPolicies(extConfig);
		AgentTemporalContext temporalContext = AgentRequestSnapshotSupport.temporalContext(safeArguments);
		Map<String, Object> runtimeParamMappings = asMap(extConfig.get(EXT_CONFIG_RUNTIME_PARAM_MAPPINGS));
		String wrapperKey = firstText(stringValue(extConfig.get(EXT_CONFIG_ARGUMENT_WRAPPER)),
				inferWrapperKey(asMap(extConfig.get(EXT_CONFIG_INPUT_SCHEMA))));
		if (!StringUtils.hasText(wrapperKey)) {
			Map<String, Object> normalizedArguments = businessArguments(safeArguments, runtimeParamMappings);
			applyRuntimeParamMappings(normalizedArguments, safeArguments, runtimeParamMappings);
			normalizedArguments = normalizeTemporalFields(normalizedArguments, temporalPolicies, temporalContext);
			log.debug("MCP tool arguments kept flat. resourceKey={}, toolName={}, inputKeys={}",
					resource == null ? null : resource.getResourceKey(), resource == null ? null : resource.getToolName(),
					safeArguments.keySet());
			return normalizedArguments;
		}
		if (safeArguments.containsKey(wrapperKey)) {
			Map<String, Object> normalizedArguments = businessArguments(safeArguments, runtimeParamMappings);
			applyRuntimeParamMappings(normalizedArguments, safeArguments, runtimeParamMappings);
			normalizedArguments = normalizeTemporalFields(normalizedArguments, temporalPolicies, temporalContext);
			log.debug("MCP tool arguments already wrapped. resourceKey={}, toolName={}, wrapperKey={}, inputKeys={}",
					resource == null ? null : resource.getResourceKey(), resource == null ? null : resource.getToolName(),
					wrapperKey, safeArguments.keySet());
			return normalizedArguments;
		}
		Map<String, Object> businessArguments = businessArguments(safeArguments, runtimeParamMappings);
		List<String> droppedKeys = safeArguments.keySet().stream().filter(this::isRuntimeArgument).toList();
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put(wrapperKey, businessArguments);
		applyRuntimeParamMappings(normalized, safeArguments, runtimeParamMappings);
		normalized = normalizeTemporalFields(normalized, temporalPolicies, temporalContext);
		log.debug(
				"MCP tool arguments wrapped. resourceKey={}, toolName={}, wrapperKey={}, wrapped=true, inputKeys={}, businessKeys={}, droppedKeys={}",
				resource == null ? null : resource.getResourceKey(), resource == null ? null : resource.getToolName(),
				wrapperKey, safeArguments.keySet(), businessArguments.keySet(), droppedKeys);
		return normalized;
	}

	private Map<String, Object> businessArguments(Map<String, Object> arguments, Map<String, Object> mappings) {
		Map<String, Object> businessArguments = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		if (mappings != null) {
			mappings.keySet().forEach(businessArguments::remove);
		}
		businessArguments.keySet().removeIf(this::isRuntimeArgument);
		return businessArguments;
	}

	private boolean isRuntimeArgument(String key) {
		return RUNTIME_ARGUMENT_KEYS.contains(key) || (key != null && key.startsWith("_agentRequest"));
	}

	private void applyRuntimeParamMappings(Map<String, Object> target, Map<String, Object> source,
			Map<String, Object> mappings) {
		mappings.forEach((sourceKey, targetPathValue) -> {
			String targetPath = stringValue(targetPathValue);
			if (!StringUtils.hasText(sourceKey) || !StringUtils.hasText(targetPath) || !source.containsKey(sourceKey)) {
				return;
			}
			putPath(target, targetPath.trim(), source.get(sourceKey));
		});
	}

	@SuppressWarnings("unchecked")
	private void putPath(Map<String, Object> target, String path, Object value) {
		String[] segments = path.split("\\.");
		Map<String, Object> current = target;
		for (int index = 0; index < segments.length - 1; index++) {
			String segment = segments[index];
			Object nested = current.get(segment);
			if (nested instanceof Map<?, ?> nestedMap) {
				Map<String, Object> mutable = new LinkedHashMap<>((Map<String, Object>) nestedMap);
				current.put(segment, mutable);
				current = mutable;
			}
			else {
				Map<String, Object> created = new LinkedHashMap<>();
				current.put(segment, created);
				current = created;
			}
		}
		current.put(segments[segments.length - 1], value);
	}

	private Map<String, Object> normalizeTemporalFields(Map<String, Object> arguments,
			Map<String, TemporalFieldPolicy> temporalPolicies, AgentTemporalContext temporalContext) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		arguments.forEach((key, value) -> normalized.put(key,
				normalizeTemporalValue(key, value, temporalPolicies, temporalContext)));
		return normalized;
	}

	@SuppressWarnings("unchecked")
	private Object normalizeTemporalValue(String key, Object value, Map<String, TemporalFieldPolicy> temporalPolicies,
			AgentTemporalContext temporalContext) {
		TemporalFieldPolicy policy = temporalPolicies.get(key);
		if (policy != null) {
			return normalizeTemporalValue(value, policy, temporalContext);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> normalized = new LinkedHashMap<>();
			map.forEach((nestedKey, nestedValue) -> {
				if (nestedKey != null) {
					normalized.put(String.valueOf(nestedKey),
							normalizeTemporalValue(String.valueOf(nestedKey), nestedValue, temporalPolicies, temporalContext));
				}
			});
			return normalized;
		}
		if (value instanceof List<?> list) {
			List<Object> normalized = new ArrayList<>(list.size());
			for (Object item : list) {
				normalized.add(normalizeTemporalValue("", item, temporalPolicies, temporalContext));
			}
			return normalized;
		}
		return value;
	}

	private Object normalizeTemporalValue(Object value, TemporalFieldPolicy policy,
			AgentTemporalContext temporalContext) {
		TemporalResolution resolution = agentTemporalService.resolvePoint(value, policy.kind(), temporalContext);
		if (!resolution.resolved()) {
			throw CheckedException.badRequest("TEMPORAL_VALUE_INVALID",
					"时间字段无法解析，reasonCode=" + resolution.reasonCode());
		}
		return switch (policy.targetType()) {
			case "DATE" -> resolution.localDate().toString();
			case "DATE_TIME" -> resolution.normalizedValue();
			case "INSTANT" -> agentTemporalService.toInstantValue(resolution, temporalContext);
			default -> throw CheckedException.badRequest("TEMPORAL_POLICY_INVALID",
					"mcpTemporalPolicies.targetType 仅支持 DATE、DATE_TIME 或 INSTANT");
		};
	}

	private Map<String, TemporalFieldPolicy> temporalPolicies(Map<String, Object> extConfig) {
		Map<String, TemporalFieldPolicy> policies = new LinkedHashMap<>();
		temporalFieldKeys(extConfig).forEach(key -> policies.put(key,
				new TemporalFieldPolicy(TemporalKind.DATE_OR_DATE_TIME, "INSTANT")));
		Map<String, Object> configuredPolicies = asMap(extConfig.get(EXT_CONFIG_TEMPORAL_POLICIES));
		configuredPolicies.forEach((key, value) -> {
			Map<String, Object> policy = asMap(value);
			try {
				TemporalKind kind = TemporalKind.valueOf(firstText(stringValue(policy.get("kind")),
						"DATE_OR_DATE_TIME").toUpperCase(java.util.Locale.ROOT));
				String targetType = firstText(stringValue(policy.get("targetType")), "INSTANT")
					.toUpperCase(java.util.Locale.ROOT);
				policies.put(key, new TemporalFieldPolicy(kind, targetType));
			}
			catch (IllegalArgumentException ex) {
				throw CheckedException.badRequest("TEMPORAL_POLICY_INVALID",
						"mcpTemporalPolicies.kind 配置非法，field=" + key);
			}
		});
		return policies;
	}

	private Set<String> temporalFieldKeys(Map<String, Object> extConfig) {
		Object configured = extConfig.get(EXT_CONFIG_TEMPORAL_FIELDS);
		Set<String> keys = new LinkedHashSet<>(DEFAULT_INSTANT_FIELD_KEYS);
		if (configured instanceof Iterable<?> values) {
			for (Object value : values) {
				addTemporalFieldKey(keys, stringValue(value));
			}
		}
		else if (configured instanceof String text) {
			for (String value : text.split(",")) {
				addTemporalFieldKey(keys, value);
			}
		}
		return keys;
	}

	private void addTemporalFieldKey(Set<String> keys, String value) {
		if (StringUtils.hasText(value)) {
			keys.add(value.trim());
		}
	}

	private String inferWrapperKey(Map<String, Object> schema) {
		if (schema.isEmpty() || !isObjectSchema(schema)) {
			return null;
		}
		Map<String, Object> properties = asMap(schema.get("properties"));
		if (properties.size() != 1) {
			return null;
		}
		Map.Entry<String, Object> entry = properties.entrySet().iterator().next();
		if (isObjectSchema(asMap(entry.getValue()))) {
			return entry.getKey();
		}
		return null;
	}

	private boolean isObjectSchema(Map<String, Object> schema) {
		if (schema.isEmpty()) {
			return false;
		}
		String type = stringValue(schema.get("type"));
		return !StringUtils.hasText(type) || "object".equalsIgnoreCase(type.trim());
	}

	private Map<String, Object> asMap(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> result = new LinkedHashMap<>();
			map.forEach((key, item) -> {
				if (key != null) {
					result.put(String.valueOf(key), item);
				}
			});
			return result;
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			try {
				Map<String, Object> map = objectMapper.readValue(text, JSON_MAP_TYPE);
				return map == null ? Map.of() : map;
			}
			catch (Exception ex) {
				log.debug("Failed to parse MCP input schema", ex);
			}
		}
		return Map.of();
	}

	private Map<String, Object> readJsonObject(String value) {
		if (!StringUtils.hasText(value)) {
			return Map.of();
		}
		try {
			Map<String, Object> map = objectMapper.readValue(value, JSON_MAP_TYPE);
			return map == null ? Map.of() : map;
		}
		catch (Exception ex) {
			log.debug("Failed to parse MCP execution resource extConfig", ex);
			return Map.of();
		}
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

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}

	private record TemporalFieldPolicy(TemporalKind kind, String targetType) {
	}

}
