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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentOrchestrationPolicyMapper;
import com.sn68.agent.dataagent.temporal.TemporalSemantic;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Agent 编排策略管理实现：维护协作编排策略的配置与查询。
 */
@Service
@AllArgsConstructor
public class AgentOrchestrationPolicyServiceImpl implements AgentOrchestrationPolicyService {

	private static final List<String> CLARIFICATION_CONFIG_KEYS = List.of("explicitMetricAliases",
			"ambiguousMetricAliases", "orderingMetricAliases");

	private static final int MAX_ALIASES_PER_GROUP = 100;

	private static final int MAX_ALIAS_LENGTH = 64;

	private final AgentOrchestrationPolicyMapper policyMapper;

	private final DataAgentService agentService;

	private final DataAgentProperties dataAgentProperties;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentOrchestrationPolicy getOrCreate(Long agentId) {
		validateOrchestrator(agentId);
		AgentOrchestrationPolicy policy = policyMapper.findByAgentId(agentId);
		if (policy != null) {
			return policy;
		}
		AgentOrchestrationPolicy defaultPolicy = defaultPolicy(agentId);
		policyMapper.insert(defaultPolicy);
		return policyMapper.selectById(defaultPolicy.getId());
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public AgentOrchestrationPolicy update(Long agentId, AgentOrchestrationPolicy policy) {
		validateOrchestrator(agentId);
		validatePolicy(policy);
		AgentOrchestrationPolicy existing = policyMapper.findByAgentId(agentId);
		if (existing == null) {
			existing = defaultPolicy(agentId);
			policyMapper.insert(existing);
		}
		existing.setMaxCollaboratorsPerRun(policy.getMaxCollaboratorsPerRun());
		existing.setFailureStrategy(policy.getFailureStrategy().trim().toLowerCase());
		existing.setExposeTrace(Boolean.TRUE.equals(policy.getExposeTrace()));
		existing.setEnabled(Boolean.TRUE.equals(policy.getEnabled()));
		if (policy.getClarificationConfig() != null) {
			existing.setClarificationConfig(normalizeClarificationConfig(policy.getClarificationConfig()));
		}
		existing.setLastModifyTime(Instant.now());
		policyMapper.updateById(existing);
		return policyMapper.selectById(existing.getId());
	}

	private void validateOrchestrator(Long agentId) {
		DataAgent dataAgent = agentService.requireAgent(agentId);
		if (!AgentTypeConstant.isOrchestrator(dataAgent.getAgentType())) {
			throw CheckedException.badRequest("Only orchestrator Agent can configure orchestration policy");
		}
	}

	private AgentOrchestrationPolicy defaultPolicy(Long agentId) {
		DataAgentProperties.Orchestration defaults = dataAgentProperties.getOrchestration();
		if (defaults == null) {
			throw new IllegalStateException("缺少编排默认配置");
		}
		return AgentOrchestrationPolicy.builder()
			.agentId(agentId)
			.maxCollaboratorsPerRun(requiredPositive(defaults.getDefaultMaxCollaboratorsPerRun(),
					"defaultMaxCollaboratorsPerRun"))
			.failureStrategy(requiredText(defaults.getDefaultFailureStrategy(), "defaultFailureStrategy"))
			.exposeTrace(defaults.isDefaultExposeTrace())
			.enabled(defaults.isDefaultEnabled())
			.clarificationConfig(defaultClarificationConfig())
			.createTime(Instant.now())
			.lastModifyTime(Instant.now())
			.deleted(false)
			.build();
	}

	private void validatePolicy(AgentOrchestrationPolicy policy) {
		if (policy == null) {
			throw CheckedException.badRequest("编排策略不能为空");
		}
		if (policy.getMaxCollaboratorsPerRun() == null || policy.getMaxCollaboratorsPerRun() < 1) {
			throw CheckedException.badRequest("maxCollaboratorsPerRun必须大于0");
		}
		String failureStrategy = defaultText(policy.getFailureStrategy(), "").toLowerCase();
		if (!List.of("continue", "fail_fast").contains(failureStrategy)) {
			throw CheckedException.badRequest("failureStrategy必须是continue或fail_fast");
		}
	}

	private Map<String, Object> normalizeClarificationConfig(Map<String, Object> config) {
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("temporalAliases", normalizeTemporalAliases(config.get("temporalAliases"),
				config.get("timeAliases")));
		for (String key : CLARIFICATION_CONFIG_KEYS) {
			normalized.put(key, normalizeAliases(key, config.get(key)));
		}
		Set<String> explicitMetrics = normalizedAliases(normalized.get("explicitMetricAliases"));
		Set<String> ambiguousMetrics = normalizedAliases(normalized.get("ambiguousMetricAliases"));
		explicitMetrics.retainAll(ambiguousMetrics);
		if (!explicitMetrics.isEmpty()) {
			throw CheckedException.badRequest("明确指标和需要澄清的指标不能包含相同词汇: " + explicitMetrics.iterator().next());
		}
		return normalized;
	}

	private List<String> normalizeAliases(String key, Object value) {
		if (value == null) {
			return List.of();
		}
		if (!(value instanceof List<?> aliases)) {
			throw CheckedException.badRequest(key + "必须是词汇数组");
		}
		if (aliases.size() > MAX_ALIASES_PER_GROUP) {
			throw CheckedException.badRequest(key + "最多配置" + MAX_ALIASES_PER_GROUP + "个词汇");
		}
		Map<String, String> distinct = new LinkedHashMap<>();
		for (Object alias : aliases) {
			if (!(alias instanceof String text)) {
				throw CheckedException.badRequest(key + "只能包含文本词汇");
			}
			String normalized = text.replace('\u3000', ' ').trim();
			if (!StringUtils.hasText(normalized)) {
				continue;
			}
			if (normalized.length() > MAX_ALIAS_LENGTH) {
				throw CheckedException.badRequest(key + "中的单个词汇不能超过" + MAX_ALIAS_LENGTH + "个字符");
			}
			distinct.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
		}
		return List.copyOf(distinct.values());
	}

	private Set<String> normalizedAliases(Object value) {
		if (!(value instanceof List<?> aliases)) {
			return Set.of();
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (Object alias : aliases) {
			if (alias instanceof String text) {
				normalized.add(text.toLowerCase(Locale.ROOT));
			}
		}
		return normalized;
	}

	private Map<String, String> defaultTemporalAliases() {
		Map<String, String> aliases = new LinkedHashMap<>();
		aliases.put("本月", TemporalSemantic.CURRENT_MONTH.name());
		aliases.put("这个月", TemporalSemantic.CURRENT_MONTH.name());
		aliases.put("这月", TemporalSemantic.CURRENT_MONTH.name());
		return Map.copyOf(aliases);
	}

	private Map<String, Object> defaultClarificationConfig() {
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("temporalAliases", defaultTemporalAliases());
		for (String key : CLARIFICATION_CONFIG_KEYS) {
			config.put(key, List.of());
		}
		return Map.copyOf(config);
	}

	private Map<String, String> normalizeTemporalAliases(Object value, Object legacyValue) {
		Object source = value == null ? legacyValue : value;
		if (source == null) {
			return defaultTemporalAliases();
		}
		Map<?, ?> rawAliases;
		if (source instanceof Map<?, ?> aliases) {
			rawAliases = aliases;
		}
		else if (source instanceof List<?> legacyAliases) {
			if (legacyAliases.size() > MAX_ALIASES_PER_GROUP) {
				throw CheckedException.badRequest("temporalAliases\u4e2d\u522b\u540d\u6570\u91cf\u4e0d\u80fd\u8d85\u8fc7" + MAX_ALIASES_PER_GROUP);
			}
			Map<String, String> migrated = new LinkedHashMap<>();
			for (Object legacyAlias : legacyAliases) {
				if (legacyAlias instanceof String text) {
					migrated.put(text, TemporalSemantic.CURRENT_MONTH.name());
				}
			}
			rawAliases = migrated;
		}
		else {
			throw CheckedException.badRequest("temporalAliases必须是别名到时间语义的对象");
		}
		if (rawAliases.size() > MAX_ALIASES_PER_GROUP) {
			throw CheckedException.badRequest("temporalAliases最多配置" + MAX_ALIASES_PER_GROUP + "个别名");
		}
		Map<String, String> normalized = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : rawAliases.entrySet()) {
			if (!(entry.getKey() instanceof String alias) || entry.getValue() == null) {
				throw CheckedException.badRequest("temporalAliases只能包含文本别名和时间语义");
			}
			String text = alias.replace('\u3000', ' ').trim();
			if (!StringUtils.hasText(text)) {
				continue;
			}
			if (text.length() > MAX_ALIAS_LENGTH) {
				throw CheckedException.badRequest("temporalAliases中的单个别名不能超过" + MAX_ALIAS_LENGTH + "个字符");
			}
			try {
				TemporalSemantic semantic = TemporalSemantic.valueOf(String.valueOf(entry.getValue()).trim()
						.toUpperCase(Locale.ROOT));
				normalized.putIfAbsent(text.toLowerCase(Locale.ROOT), semantic.name());
			}
			catch (IllegalArgumentException ex) {
				throw CheckedException.badRequest("temporalAliases包含不支持的时间语义: " + entry.getValue());
			}
		}
		return Map.copyOf(normalized);
	}

	private String defaultText(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
	}

	private String requiredText(String value, String propertyName) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException("缺少编排配置: " + propertyName);
		}
		return value.trim().toLowerCase();
	}

	private int requiredPositive(int value, String propertyName) {
		if (value < 1) {
			throw new IllegalStateException("编排配置必须大于0: " + propertyName);
		}
		return value;
	}

}
