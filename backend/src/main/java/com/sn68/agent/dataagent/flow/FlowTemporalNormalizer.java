/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.temporal.TemporalKind;
import com.sn68.agent.dataagent.temporal.TemporalInterval;
import com.sn68.agent.dataagent.temporal.TemporalResolution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 根据 FLOW Schema 的 x-temporal 声明归一化结构化字段。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowTemporalNormalizer {

	private final AgentTemporalService agentTemporalService;

	public NormalizationResult normalize(Map<String, Object> values, Map<String, Object> schema, AgentRequest request) {
		Map<String, Object> normalized = new LinkedHashMap<>(values == null ? Map.of() : values);
		List<String> invalidFields = new ArrayList<>();
		normalizeObject(normalized, schema == null ? Map.of() : schema, request, "", invalidFields);
		return new NormalizationResult(normalized, List.copyOf(invalidFields));
	}

	/**
	 * 请求级确定性时间区间负载。上游 AgentTemporalService 只在 query 含显式时间表达时
	 * 才解析出 temporalInterval，因此可直接用于确定性槽位预填。
	 */
	public Map<String, Object> requestIntervalPayload(AgentRequest request) {
		if (request == null || request.getTemporalInterval() == null) {
			return Map.of();
		}
		return agentTemporalService.intervalPayload(request.getTemporalInterval());
	}

	private void normalizeObject(Map<String, Object> values, Map<String, Object> schema, AgentRequest request,
			String path, List<String> invalidFields) {
		Map<String, Object> properties = map(schema.get("properties"));
		for (Map.Entry<String, Object> entry : properties.entrySet()) {
			String field = entry.getKey();
			if (!values.containsKey(field)) {
				continue;
			}
			Map<String, Object> fieldSchema = map(entry.getValue());
			Map<String, Object> temporal = temporalPolicy(fieldSchema.get("x-temporal"));
			String fieldPath = path + "/" + field;
			if (!temporal.isEmpty()) {
				if ("INTERVAL".equalsIgnoreCase(String.valueOf(temporal.get("kind")))) {
					TemporalInterval interval = resolveInterval(values.get(field), request);
					if (interval != null) {
						values.put(field, agentTemporalService.intervalPayload(interval));
					}
					else {
						values.remove(field);
						invalidFields.add(fieldPath + ":TEMPORAL_INTERVAL_UNRECOGNIZED");
					}
					continue;
				}
				TemporalKind kind = temporalKind(temporal.get("kind"));
				TemporalResolution resolution = agentTemporalService.resolvePoint(values.get(field), kind,
						request == null ? null : request.getTemporalContext());
				if (resolution.resolved()) {
					values.put(field, resolution.normalizedValue());
				}
				else {
					values.remove(field);
					invalidFields.add(fieldPath + ":" + resolution.reasonCode());
				}
				continue;
			}
			Object value = values.get(field);
			if (value instanceof Map<?, ?> nested) {
				Map<String, Object> mutable = map(nested);
				normalizeObject(mutable, fieldSchema, request, fieldPath, invalidFields);
				values.put(field, mutable);
			}
			else if (value instanceof List<?> list && !map(fieldSchema.get("items")).isEmpty()) {
				List<Object> normalizedItems = new ArrayList<>(list.size());
				for (int index = 0; index < list.size(); index++) {
					Object item = list.get(index);
					if (item instanceof Map<?, ?> itemMap) {
						Map<String, Object> mutable = map(itemMap);
						normalizeObject(mutable, map(fieldSchema.get("items")), request,
								fieldPath + "/" + index, invalidFields);
						normalizedItems.add(mutable);
					}
					else {
						normalizedItems.add(item);
					}
				}
				values.put(field, normalizedItems);
			}
		}
	}

	private TemporalInterval resolveInterval(Object value, AgentRequest request) {
		if (value instanceof TemporalInterval interval) {
			return interval;
		}
		if (value instanceof String expression && StringUtils.hasText(expression)) {
			return agentTemporalService.resolveInterval(expression,
					request == null ? null : request.getTemporalContext()).orElse(null);
		}
		return request == null ? null : request.getTemporalInterval();
	}

	private Map<String, Object> temporalPolicy(Object value) {
		if (value instanceof String kind && StringUtils.hasText(kind)) {
			return Map.of("kind", kind);
		}
		return map(value);
	}

	private TemporalKind temporalKind(Object value) {
		String kind = value == null ? TemporalKind.DATE_OR_DATE_TIME.name() : String.valueOf(value).trim();
		try {
			return TemporalKind.valueOf(kind.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Unknown FLOW temporal kind, falling back to DATE_OR_DATE_TIME. kind={}", kind);
			return TemporalKind.DATE_OR_DATE_TIME;
		}
	}

	private Map<String, Object> map(Object value) {
		if (!(value instanceof Map<?, ?> source)) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		source.forEach((key, item) -> {
			if (key != null) {
				result.put(String.valueOf(key), item);
			}
		});
		return result;
	}

	public record NormalizationResult(Map<String, Object> values, List<String> invalidFields) {
	}

}
