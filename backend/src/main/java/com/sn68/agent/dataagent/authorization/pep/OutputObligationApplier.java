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
package com.sn68.agent.dataagent.authorization.pep;

import cn.hutool.core.collection.CollUtil;
import com.sn68.agent.dataagent.authorization.model.AuthorizationObligation;
import com.sn68.agent.dataagent.authorization.pdp.AuthorizationDecision;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 输出义务执行器（PR-3c 交付物 5：MASK_FIELDS/FILTER_FIELDS 接现有脱敏口径）。
 *
 * <p>消费 PDP 决策携带的义务：MASK_FIELDS 对输出 payload 的指定字段递归替换 "****"
 * （与 {@code ToolResourceServiceImpl#maskSensitive} 同一口径），字段列表为空时遵循能力契约
 * sensitiveFields 默认集；FILTER_FIELDS 直接移除字段。APPROVAL 义务不在本类执行
 * （审批链路既有实现承担，本类只透传记录）。</p>
 *
 * <p>SHADOW 语义由调用方控制：SHADOW 用 {@link #shadowPreview} 只输出"若 ENFORCE 将处理的字段清单"，
 * 不修改真实 payload。</p>
 *
 * @author James (PR-3c PEP 内核扩展)
 */
@Component
public class OutputObligationApplier {

	/**
	 * 脱敏替换值（与现有 ToolResourceServiceImpl.maskSensitive 一致）。
	 */
	private static final String MASK_PLACEHOLDER = "****";

	/**
	 * ENFORCE 应用输出义务：按决策 obligations 对 payload 副本执行脱敏/过滤，原 payload 不被修改。
	 *
	 * @param decision                PDP 决策（null 或无义务时原样返回）
	 * @param defaultSensitiveFields  能力契约默认敏感字段集（maskFields 为空时兜底）
	 * @param payload                 输出 payload（null 原样返回）
	 * @return 处理后的新 Map（输入为不可变 Map 时也能安全返回新实例）
	 */
	public Map<String, Object> applyObligations(AuthorizationDecision decision, Set<String> defaultSensitiveFields,
			Map<String, Object> payload) {
		if (decision == null || payload == null || payload.isEmpty()) {
			return payload;
		}
		Set<String> effectiveFields = effectiveFields(decision, defaultSensitiveFields);
		Map<String, Object> result = new LinkedHashMap<>(payload);
		if (hasObligation(decision, AuthorizationObligation.FILTER_FIELDS) && !effectiveFields.isEmpty()) {
			removeFields(result, effectiveFields);
			return result;
		}
		if (hasObligation(decision, AuthorizationObligation.MASK_FIELDS) && !effectiveFields.isEmpty()) {
			return maskValue(result, effectiveFields);
		}
		return result;
	}

	/**
	 * SHADOW 预览：不修改 payload，仅计算"若 ENFORCE 将脱敏/过滤的字段清单"供影子日志记录。
	 *
	 * @param decision               PDP 决策
	 * @param defaultSensitiveFields 能力契约默认敏感字段集
	 * @return 将处理的字段清单（无义务时为空集）
	 */
	public Set<String> shadowPreview(AuthorizationDecision decision, Set<String> defaultSensitiveFields) {
		if (decision == null || (!hasObligation(decision, AuthorizationObligation.MASK_FIELDS)
				&& !hasObligation(decision, AuthorizationObligation.FILTER_FIELDS))) {
			return Set.of();
		}
		return effectiveFields(decision, defaultSensitiveFields);
	}

	/**
	 * 生效字段集：决策 maskFields 优先；为空时回退能力契约默认 sensitiveFields。
	 */
	private Set<String> effectiveFields(AuthorizationDecision decision, Set<String> defaultSensitiveFields) {
		List<String> maskFields = decision.getMaskFields();
		if (CollUtil.isNotEmpty(maskFields)) {
			return new LinkedHashSet<>(maskFields);
		}
		return defaultSensitiveFields == null ? Set.of() : defaultSensitiveFields;
	}

	private boolean hasObligation(AuthorizationDecision decision, AuthorizationObligation obligation) {
		return decision.getObligations() != null && decision.getObligations().contains(obligation);
	}

	/**
	 * 递归移除指定字段（含嵌套 Map 与 List 内元素）。
	 */
	private void removeFields(Map<String, Object> value, Set<String> fields) {
		fields.forEach(value::remove);
		for (Map.Entry<String, Object> entry : value.entrySet()) {
			entry.setValue(removeValue(entry.getValue(), fields));
		}
	}

	private Object removeValue(Object value, Set<String> fields) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> nested = new LinkedHashMap<>();
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (entry.getKey() instanceof String key && fields.contains(key)) {
					continue;
				}
				nested.put(String.valueOf(entry.getKey()), removeValue(entry.getValue(), fields));
			}
			return nested;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> removeValue(item, fields)).toList();
		}
		return value;
	}

	/**
	 * 递归脱敏：字段名命中即替换占位符（口径对齐 ToolResourceServiceImpl.maskValue）。
	 */
	private Map<String, Object> maskValue(Map<String, Object> value, Set<String> fields) {
		Map<String, Object> masked = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : value.entrySet()) {
			if (fields.contains(entry.getKey())) {
				masked.put(entry.getKey(), MASK_PLACEHOLDER);
				continue;
			}
			masked.put(entry.getKey(), maskValue(entry.getValue(), fields));
		}
		return masked;
	}

	private Object maskValue(Object value, Set<String> fields) {
		if (value instanceof Map<?, ?> map) {
			return maskValue(stringKeyMap(map), fields);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> maskValue(item, fields)).toList();
		}
		return value;
	}

	private Map<String, Object> stringKeyMap(Map<?, ?> map) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			result.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return result;
	}

}
