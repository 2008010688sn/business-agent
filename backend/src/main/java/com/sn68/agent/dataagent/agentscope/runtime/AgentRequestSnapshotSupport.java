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

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.TemporalAmbiguityStrategy;
import com.sn68.agent.dataagent.temporal.TemporalInterval;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * AgentRequest 执行快照透传工具。
 */
@Slf4j
public final class AgentRequestSnapshotSupport {

	public static final String USER_ID = "_agentRequestUserIdSnapshot";

	public static final String USER_NICK_NAME = "_agentRequestUserNickNameSnapshot";

	public static final String DATA_PERMISSION = "_agentRequestDataPermissionSnapshot";

	public static final String TENANT_ID = "_agentRequestTenantIdSnapshot";

	public static final String TENANT_CODE = "_agentRequestTenantCodeSnapshot";

	public static final String CLIENT_ID = "_agentRequestClientIdSnapshot";

	public static final String TEAM_IDS = "_agentRequestTeamIdsSnapshot";

	public static final String ROOT_RUNTIME_REQUEST_ID = "_agentRequestRootRuntimeRequestId";

	public static final String PARENT_RUNTIME_REQUEST_ID = "_agentRequestParentRuntimeRequestId";

	public static final String ORCHESTRATION_RUN_ID = "_agentRequestOrchestrationRunId";

	public static final String ORCHESTRATION_STEP_ID = "_agentRequestOrchestrationStepId";

	public static final String ORCHESTRATION_DEPENDENCY_INPUTS = "_agentRequestOrchestrationDependencies";

	public static final String TEMPORAL_REFERENCE_INSTANT = "_agentRequestTemporalReferenceInstant";

	public static final String TEMPORAL_ZONE_ID = "_agentRequestTemporalZoneId";

	public static final String TEMPORAL_LOCALE = "_agentRequestTemporalLocale";

	public static final String TEMPORAL_WEEK_STARTS_ON = "_agentRequestTemporalWeekStartsOn";

	public static final String TEMPORAL_AMBIGUITY_STRATEGY = "_agentRequestTemporalAmbiguityStrategy";

	public static final String TEMPORAL_INTERVAL_START_DATE = "_agentRequestTemporalIntervalStartDate";

	public static final String TEMPORAL_INTERVAL_END_DATE = "_agentRequestTemporalIntervalEndDate";

	public static final String TEMPORAL_INTERVAL_START = "_agentRequestTemporalIntervalStart";

	public static final String TEMPORAL_INTERVAL_END = "_agentRequestTemporalIntervalEnd";

	public static final String TEMPORAL_INTERVAL_ROLLING = "_agentRequestTemporalIntervalRolling";

	private AgentRequestSnapshotSupport() {
	}

	public static Map<String, Object> enrichArguments(Map<String, Object> arguments, AgentRequest request) {
		Map<String, Object> enriched = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		if (request == null) {
			return enriched;
		}
		putIfAbsent(enriched, USER_ID, request.getUserIdSnapshot());
		putIfAbsent(enriched, USER_NICK_NAME, request.getUserNickNameSnapshot());
		putIfAbsent(enriched, DATA_PERMISSION, request.getDataPermissionSnapshot());
		putIfAbsent(enriched, TENANT_ID, request.getTenantIdSnapshot());
		putIfAbsent(enriched, TENANT_CODE, request.getTenantCodeSnapshot());
		putIfAbsent(enriched, CLIENT_ID, request.getClientIdSnapshot());
		putIfAbsent(enriched, TEAM_IDS, request.getTeamIdsSnapshot());
		putIfAbsent(enriched, ROOT_RUNTIME_REQUEST_ID, request.getRootRuntimeRequestId());
		putIfAbsent(enriched, PARENT_RUNTIME_REQUEST_ID, request.getParentRuntimeRequestId());
		putIfAbsent(enriched, ORCHESTRATION_RUN_ID, request.getOrchestrationRunId());
		putIfAbsent(enriched, ORCHESTRATION_STEP_ID, request.getOrchestrationStepId());
		if (request.getOrchestrationDependencyInputs() != null
				&& !request.getOrchestrationDependencyInputs().isEmpty()) {
			enriched.put(ORCHESTRATION_DEPENDENCY_INPUTS, request.getOrchestrationDependencyInputs());
		}
		if (request.getTemporalContext() != null) {
			enriched.put(TEMPORAL_REFERENCE_INSTANT, request.getTemporalContext().referenceInstant().toString());
			enriched.put(TEMPORAL_ZONE_ID, request.getTemporalContext().zoneId().getId());
			enriched.put(TEMPORAL_LOCALE, request.getTemporalContext().locale().toLanguageTag());
			enriched.put(TEMPORAL_WEEK_STARTS_ON, request.getTemporalContext().weekStartsOn().name());
			enriched.put(TEMPORAL_AMBIGUITY_STRATEGY, request.getTemporalContext().ambiguityStrategy().name());
		}
		if (request.getTemporalInterval() != null) {
			TemporalInterval interval = request.getTemporalInterval();
			enriched.put(TEMPORAL_INTERVAL_START_DATE, interval.startDateInclusive().toString());
			enriched.put(TEMPORAL_INTERVAL_END_DATE, interval.endDateExclusive().toString());
			enriched.put(TEMPORAL_INTERVAL_START, interval.startInclusive().toString());
			enriched.put(TEMPORAL_INTERVAL_END, interval.endExclusive().toString());
			enriched.put(TEMPORAL_INTERVAL_ROLLING, interval.rolling());
		}
		return enriched;
	}

	public static void inheritFromArguments(AgentRequest request, Map<String, Object> arguments, ObjectMapper objectMapper) {
		if (request == null || arguments == null || arguments.isEmpty()) {
			return;
		}
		request.setUserIdSnapshot(firstText(request.getUserIdSnapshot(), text(arguments.get(USER_ID))));
		request.setUserNickNameSnapshot(firstText(request.getUserNickNameSnapshot(), text(arguments.get(USER_NICK_NAME))));
		request.setTenantIdSnapshot(firstText(request.getTenantIdSnapshot(), text(arguments.get(TENANT_ID))));
		request.setTenantCodeSnapshot(firstText(request.getTenantCodeSnapshot(), text(arguments.get(TENANT_CODE))));
		request.setClientIdSnapshot(firstText(request.getClientIdSnapshot(), text(arguments.get(CLIENT_ID))));
		request.setRootRuntimeRequestId(firstText(request.getRootRuntimeRequestId(), text(arguments.get(ROOT_RUNTIME_REQUEST_ID))));
		request.setParentRuntimeRequestId(firstText(request.getParentRuntimeRequestId(),
				text(arguments.get(PARENT_RUNTIME_REQUEST_ID))));
		request.setOrchestrationRunId(firstLong(request.getOrchestrationRunId(), arguments.get(ORCHESTRATION_RUN_ID)));
		request.setOrchestrationStepId(firstLong(request.getOrchestrationStepId(), arguments.get(ORCHESTRATION_STEP_ID)));
		if (request.getDataPermissionSnapshot() == null) {
			request.setDataPermissionSnapshot(dataPermission(arguments.get(DATA_PERMISSION), objectMapper));
		}
		if (request.getTeamIdsSnapshot() == null) {
			request.setTeamIdsSnapshot(teamIds(arguments.get(TEAM_IDS), objectMapper));
		}
		if (request.getTemporalContext() == null) {
			request.setTemporalContext(temporalContext(arguments));
		}
		if (request.getTemporalInterval() == null) {
			request.setTemporalInterval(temporalInterval(arguments));
		}
	}

	public static AgentTemporalContext temporalContext(Map<String, Object> arguments) {
		if (arguments == null) {
			return null;
		}
		String referenceValue = text(arguments.get(TEMPORAL_REFERENCE_INSTANT));
		String zoneValue = text(arguments.get(TEMPORAL_ZONE_ID));
		if (!StringUtils.hasText(referenceValue) || !StringUtils.hasText(zoneValue)) {
			return null;
		}
		try {
			Instant reference = Instant.parse(referenceValue);
			ZoneId zoneId = ZoneId.of(zoneValue);
			Locale locale = Locale.forLanguageTag(firstText(text(arguments.get(TEMPORAL_LOCALE)), "zh-CN"));
			DayOfWeek weekStartsOn = DayOfWeek.valueOf(firstText(text(arguments.get(TEMPORAL_WEEK_STARTS_ON)),
					DayOfWeek.MONDAY.name()));
			TemporalAmbiguityStrategy ambiguityStrategy = TemporalAmbiguityStrategy.valueOf(firstText(
					text(arguments.get(TEMPORAL_AMBIGUITY_STRATEGY)), TemporalAmbiguityStrategy.ASK.name()));
			return new AgentTemporalContext(reference, zoneId, locale, reference.atZone(zoneId).toLocalDate(),
					weekStartsOn, ambiguityStrategy);
		}
		catch (RuntimeException ex) {
			// Dropping the temporal context makes the agent answer date-relative questions against no reference date.
			log.warn("Failed to rebuild the temporal context from tool arguments, it will be ignored. reference={}, zone={}",
					referenceValue, zoneValue, ex);
			return null;
		}
	}

	public static TemporalInterval temporalInterval(Map<String, Object> arguments) {
		if (arguments == null) {
			return null;
		}
		String startDate = text(arguments.get(TEMPORAL_INTERVAL_START_DATE));
		String endDate = text(arguments.get(TEMPORAL_INTERVAL_END_DATE));
		String start = text(arguments.get(TEMPORAL_INTERVAL_START));
		String end = text(arguments.get(TEMPORAL_INTERVAL_END));
		if (!StringUtils.hasText(startDate) || !StringUtils.hasText(endDate) || !StringUtils.hasText(start)
				|| !StringUtils.hasText(end)) {
			return null;
		}
		try {
			return new TemporalInterval(LocalDate.parse(startDate), LocalDate.parse(endDate), Instant.parse(start),
					Instant.parse(end), Boolean.parseBoolean(text(arguments.get(TEMPORAL_INTERVAL_ROLLING))));
		}
		catch (RuntimeException ex) {
			log.warn("Failed to rebuild the temporal interval from tool arguments, it will be ignored. startDate={}, endDate={}",
					startDate, endDate, ex);
			return null;
		}
	}

	public static String userId(Map<String, Object> arguments) {
		return arguments == null ? null : text(arguments.get(USER_ID));
	}

	public static String userNickName(Map<String, Object> arguments) {
		return arguments == null ? null : text(arguments.get(USER_NICK_NAME));
	}

	public static String tenantId(Map<String, Object> arguments) {
		return arguments == null ? null : text(arguments.get(TENANT_ID));
	}

	public static String tenantCode(Map<String, Object> arguments) {
		return arguments == null ? null : text(arguments.get(TENANT_CODE));
	}

	public static String clientId(Map<String, Object> arguments) {
		return arguments == null ? null : text(arguments.get(CLIENT_ID));
	}

	public static DataPermission dataPermission(Map<String, Object> arguments, ObjectMapper objectMapper) {
		return arguments == null ? null : dataPermission(arguments.get(DATA_PERMISSION), objectMapper);
	}

	@SuppressWarnings("unchecked")
	public static List<String> teamIds(Map<String, Object> arguments, ObjectMapper objectMapper) {
		return arguments == null ? List.of() : teamIds(arguments.get(TEAM_IDS), objectMapper);
	}

	private static void putIfAbsent(Map<String, Object> target, String key, Object value) {
		if (value == null) {
			return;
		}
		Object current = target.get(key);
		if (current == null || (current instanceof String text && !StringUtils.hasText(text))) {
			target.put(key, value);
		}
	}

	private static DataPermission dataPermission(Object value, ObjectMapper objectMapper) {
		if (value instanceof DataPermission permission) {
			return permission;
		}
		if (value instanceof Map<?, ?> map && objectMapper != null) {
			return objectMapper.convertValue(map, DataPermission.class);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static List<String> teamIds(Object value, ObjectMapper objectMapper) {
		if (value instanceof List<?> list) {
			return list.stream().map(AgentRequestSnapshotSupport::text).filter(StringUtils::hasText).toList();
		}
		if (value instanceof Object[] array) {
			return java.util.Arrays.stream(array).map(AgentRequestSnapshotSupport::text).filter(StringUtils::hasText).toList();
		}
		if (value instanceof Iterable<?> iterable) {
			return java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
				.map(AgentRequestSnapshotSupport::text)
				.filter(StringUtils::hasText)
				.toList();
		}
		if (value instanceof String text && StringUtils.hasText(text)) {
			return java.util.Arrays.stream(text.split(",")).map(String::trim).filter(StringUtils::hasText).toList();
		}
		if (value instanceof Map<?, ?> && objectMapper != null) {
			return objectMapper.convertValue(value,
					objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
		}
		return List.of();
	}

	private static String firstText(String... values) {
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

	private static Long firstLong(Long current, Object value) {
		if (current != null) {
			return current;
		}
		String text = text(value);
		if (!StringUtils.hasText(text)) {
			return null;
		}
		try {
			return Long.valueOf(text.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String text(Object value) {
		return value == null ? null : String.valueOf(value);
	}

}
