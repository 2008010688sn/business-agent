/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.temporal;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;

/**
 * Agent 公共时间内核。只解释时间语义，不提供业务事实或实时数据。
 */
@Service
public class AgentTemporalService {

	public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

	public static final Locale DEFAULT_LOCALE = Locale.forLanguageTag("zh-CN");

	public static final DayOfWeek DEFAULT_WEEK_STARTS_ON = DayOfWeek.MONDAY;

	private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?$");

	private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("^(\\d{1,2})月(\\d{1,2})日?$");

	private static final Pattern DATE_TIME_PATTERN = Pattern.compile(
			"^(.*?)(?:[ T]|日\\s*)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");

	private static final Pattern NEAR_DAYS_PATTERN = Pattern.compile("^近\\s*(\\d+)\\s*天$");

	private static final Pattern PAST_HOURS_PATTERN = Pattern.compile("^过去\\s*(\\d+)\\s*小时$");

	private static final Pattern TIME_ONLY_PATTERN = Pattern.compile("^(?:只有|仅)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?$");

	private static final Pattern WEEKDAY_PATTERN = Pattern.compile("^(?:本|下|上)?周[一二三四五六日天]$");

	private final Clock clock;

	@Value("${agent.temporal.zone-id:Asia/Shanghai}")
	private String applicationZoneId = DEFAULT_ZONE.getId();

	@Value("${agent.temporal.locale:zh-CN}")
	private String applicationLocale = DEFAULT_LOCALE.toLanguageTag();

	@Value("${agent.temporal.week-starts-on:MONDAY}")
	private String applicationWeekStartsOn = DEFAULT_WEEK_STARTS_ON.name();

	@Value("${agent.temporal.ambiguity-strategy:ASK}")
	private String applicationAmbiguityStrategy = TemporalAmbiguityStrategy.ASK.name();

	public AgentTemporalService() {
		this(Clock.systemUTC());
	}

	AgentTemporalService(Clock clock) {
		this.clock = clock == null ? Clock.systemUTC() : clock;
	}

	public AgentTemporalContext initialize(AgentRequest request, DataAgent agent) {
		if (request != null && request.getTemporalContext() != null) {
			return request.getTemporalContext();
		}
		Map<String, Object> policy = normalizePolicy(agent == null ? null : agent.getTemporalPolicy());
		ZoneId zoneId = ZoneId.of(String.valueOf(policy.get("zoneId")));
		Instant referenceInstant = clock.instant();
		AgentTemporalContext context = new AgentTemporalContext(referenceInstant, zoneId,
				Locale.forLanguageTag(String.valueOf(policy.get("locale"))), referenceInstant.atZone(zoneId).toLocalDate(),
				DayOfWeek.valueOf(String.valueOf(policy.get("weekStartsOn"))),
				TemporalAmbiguityStrategy.valueOf(String.valueOf(policy.get("ambiguityStrategy"))));
		if (request != null) {
			request.setTemporalContext(context);
		}
		return context;
	}

	/**
	 * Resolves the request-level interval once from persisted temporal aliases.
	 */
	public void initializeInterval(AgentRequest request, Map<String, Object> clarificationConfig) {
		if (request == null || request.getTemporalContext() == null) {
			return;
		}
		request.setTemporalInterval(extractInterval(request.getQuery(), request.getTemporalContext(), clarificationConfig)
			.orElse(null));
	}

	public Optional<TemporalInterval> extractInterval(String query, AgentTemporalContext context,
			Map<String, Object> clarificationConfig) {
		if (!StringUtils.hasText(query)) {
			return Optional.empty();
		}
		String normalizedQuery = query.replace('\u3000', ' ').trim().toLowerCase(Locale.ROOT);
		return temporalAliases(clarificationConfig).entrySet().stream()
			.filter(entry -> normalizedQuery.contains(entry.getKey()))
			.sorted(Comparator.<Map.Entry<String, TemporalSemantic>>comparingInt(entry -> normalizedQuery.indexOf(entry.getKey()))
				.thenComparing((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length())))
			.map(Map.Entry::getValue)
			.findFirst()
			.flatMap(semantic -> resolveInterval(semantic, context));
	}

	public Optional<TemporalInterval> resolveInterval(TemporalSemantic semantic, AgentTemporalContext context) {
		if (semantic == null) {
			return Optional.empty();
		}
		AgentTemporalContext safeContext = requireContext(context);
		LocalDate start = switch (semantic) {
			case CURRENT_WEEK -> safeContext.localDate().minusDays(Math.floorMod(
					safeContext.localDate().getDayOfWeek().getValue() - safeContext.weekStartsOn().getValue(), 7));
			case CURRENT_MONTH -> safeContext.localDate().withDayOfMonth(1);
			case CURRENT_YEAR -> safeContext.localDate().with(TemporalAdjusters.firstDayOfYear());
			case TODAY -> safeContext.localDate();
			case TOMORROW -> safeContext.localDate().plusDays(1);
			case DAY_AFTER_TOMORROW -> safeContext.localDate().plusDays(2);
			case YESTERDAY -> safeContext.localDate().minusDays(1);
			case DAY_BEFORE_YESTERDAY -> safeContext.localDate().minusDays(2);
		};
		LocalDate end = switch (semantic) {
			case CURRENT_WEEK -> start.plusWeeks(1);
			case CURRENT_MONTH -> start.plusMonths(1);
			case CURRENT_YEAR -> start.plusYears(1);
			case TODAY, TOMORROW, DAY_AFTER_TOMORROW, YESTERDAY, DAY_BEFORE_YESTERDAY -> start.plusDays(1);
		};
		return Optional.of(calendarInterval(start, end, safeContext));
	}

	public Map<String, Object> intervalPayload(TemporalInterval interval) {
		if (interval == null) {
			return Map.of();
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("startDateInclusive", interval.startDateInclusive().toString());
		payload.put("endDateExclusive", interval.endDateExclusive().toString());
		payload.put("startInclusive", interval.startInclusive().toString());
		payload.put("endExclusive", interval.endExclusive().toString());
		payload.put("rolling", interval.rolling());
		return Map.copyOf(payload);
	}

	public Map<String, Object> normalizePolicy(Map<String, Object> rawPolicy) {
		Map<String, Object> source = rawPolicy == null ? Map.of() : rawPolicy;
		String zoneValue = textOrDefault(source.get("zoneId"), applicationZoneId);
		String localeValue = textOrDefault(source.get("locale"), applicationLocale);
		String weekValue = textOrDefault(source.get("weekStartsOn"), applicationWeekStartsOn);
		String ambiguityValue = textOrDefault(source.get("ambiguityStrategy"), applicationAmbiguityStrategy);
		ZoneId zoneId;
		Locale locale;
		DayOfWeek weekStartsOn;
		TemporalAmbiguityStrategy ambiguityStrategy;
		try {
			zoneId = ZoneId.of(zoneValue);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("temporalPolicy.zoneId 必须是合法的 IANA 时区");
		}
		locale = Locale.forLanguageTag(localeValue);
		if (!StringUtils.hasText(locale.getLanguage()) || "und".equalsIgnoreCase(locale.toLanguageTag())) {
			throw CheckedException.badRequest("temporalPolicy.locale 必须是合法的 BCP 47 语言标签");
		}
		try {
			weekStartsOn = DayOfWeek.valueOf(weekValue.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw CheckedException.badRequest("temporalPolicy.weekStartsOn 必须是合法的星期枚举");
		}
		try {
			ambiguityStrategy = TemporalAmbiguityStrategy.valueOf(ambiguityValue.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw CheckedException.badRequest("temporalPolicy.ambiguityStrategy 当前仅支持 ASK");
		}
		Map<String, Object> normalized = new LinkedHashMap<>();
		normalized.put("zoneId", zoneId.getId());
		normalized.put("locale", locale.toLanguageTag());
		normalized.put("weekStartsOn", weekStartsOn.name());
		normalized.put("ambiguityStrategy", ambiguityStrategy.name());
		return normalized;
	}

	public TemporalResolution resolvePoint(Object rawValue, TemporalKind kind, AgentTemporalContext context) {
		AgentTemporalContext safeContext = requireContext(context);
		if (rawValue == null) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_VALUE_EMPTY");
		}
		if (rawValue instanceof LocalDate date) {
			return acceptDate(date, kind);
		}
		if (rawValue instanceof LocalDateTime dateTime) {
			return resolveLocalDateTime(dateTime, kind, safeContext);
		}
		if (rawValue instanceof OffsetDateTime dateTime) {
			return acceptDateTime(dateTime, kind);
		}
		if (rawValue instanceof Instant instant) {
			return acceptDateTime(instant.atZone(safeContext.zoneId()).toOffsetDateTime(), kind);
		}
		if (rawValue instanceof Number number) {
			return acceptDateTime(epochInstant(number.toString()).atZone(safeContext.zoneId()).toOffsetDateTime(), kind);
		}
		String value = String.valueOf(rawValue).trim();
		if (!StringUtils.hasText(value)) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_VALUE_EMPTY");
		}
		if (isAmbiguous(value)) {
			return TemporalResolution.unresolved(TemporalResolution.Status.AMBIGUOUS, "TEMPORAL_VALUE_AMBIGUOUS");
		}
		if (value.matches("^-?\\d+(?:\\.0+)?$")) {
			try {
				return acceptDateTime(epochInstant(value).atZone(safeContext.zoneId()).toOffsetDateTime(), kind);
			}
			catch (RuntimeException ex) {
				return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_EPOCH_INVALID");
			}
		}
		TemporalResolution standard = parseStandard(value, kind, safeContext);
		if (standard != null) {
			return standard;
		}
		Matcher dateTimeMatcher = DATE_TIME_PATTERN.matcher(value);
		if (dateTimeMatcher.matches()) {
			TemporalResolution date = parseDate(dateTimeMatcher.group(1).trim(), TemporalKind.DATE_OR_DATE_TIME, safeContext);
			if (!date.resolved()) {
				return date;
			}
			try {
				int hour = Integer.parseInt(dateTimeMatcher.group(2));
				int minute = Integer.parseInt(dateTimeMatcher.group(3));
				int second = dateTimeMatcher.group(4) == null ? 0 : Integer.parseInt(dateTimeMatcher.group(4));
				return resolveLocalDateTime(date.localDate().atTime(hour, minute, second), kind, safeContext);
			}
			catch (RuntimeException ex) {
				return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_TIME_INVALID");
			}
		}
		return parseDate(value, kind, safeContext);
	}

	public Optional<TemporalInterval> resolveInterval(String expression, AgentTemporalContext context) {
		if (!StringUtils.hasText(expression)) {
			return Optional.empty();
		}
		AgentTemporalContext safeContext = requireContext(context);
		String value = expression.trim();
		TemporalSemantic semantic = temporalAliases(Map.of()).get(value.toLowerCase(Locale.ROOT));
		if (semantic != null) {
			return resolveInterval(semantic, safeContext);
		}
		LocalDate start;
		LocalDate end;
		if ("本周".equals(value)) {
			int delta = Math.floorMod(safeContext.localDate().getDayOfWeek().getValue()
					- safeContext.weekStartsOn().getValue(), 7);
			start = safeContext.localDate().minusDays(delta);
			end = start.plusWeeks(1);
		}
		else if ("本月".equals(value) || "这个月".equals(value) || "这月".equals(value)) {
			start = safeContext.localDate().withDayOfMonth(1);
			end = start.plusMonths(1);
		}
		else if ("今年".equals(value)) {
			start = safeContext.localDate().with(TemporalAdjusters.firstDayOfYear());
			end = start.plusYears(1);
		}
		else {
			Matcher nearDays = NEAR_DAYS_PATTERN.matcher(value);
			if (nearDays.matches()) {
				long days = Long.parseLong(nearDays.group(1));
				if (days < 1 || days > 36600) {
					return Optional.empty();
				}
				end = safeContext.localDate().plusDays(1);
				start = end.minusDays(days);
			}
			else {
				Matcher pastHours = PAST_HOURS_PATTERN.matcher(value);
				if (!pastHours.matches()) {
					return Optional.empty();
				}
				long hours = Long.parseLong(pastHours.group(1));
				if (hours < 1 || hours > 876000) {
					return Optional.empty();
				}
				Instant endInstant = safeContext.referenceInstant();
				Instant startInstant = endInstant.minusSeconds(Math.multiplyExact(hours, 3600L));
				return Optional.of(new TemporalInterval(startInstant.atZone(safeContext.zoneId()).toLocalDate(),
						endInstant.atZone(safeContext.zoneId()).toLocalDate(), startInstant, endInstant, true));
			}
		}
		return Optional.of(calendarInterval(start, end, safeContext));
	}

	public String toInstantValue(TemporalResolution resolution, AgentTemporalContext context) {
		if (resolution == null || !resolution.resolved()) {
			throw CheckedException.badRequest("TEMPORAL_VALUE_INVALID", "时间字段无法归一化");
		}
		if (resolution.precision() == TemporalResolution.Precision.DATE) {
			return resolution.localDate().atStartOfDay(requireContext(context).zoneId()).toInstant().toString();
		}
		return resolution.instant().toString();
	}

	public String promptBlock(AgentTemporalContext context) {
		AgentTemporalContext safeContext = requireContext(context);
		return "【时间上下文】referenceInstant=" + safeContext.referenceInstant() + ", zoneId="
				+ safeContext.zoneId().getId() + ", localDate=" + safeContext.localDate() + ", locale="
				+ safeContext.locale().toLanguageTag() + ", weekStartsOn=" + safeContext.weekStartsOn()
				+ "。仅用于解释时间表达，不得替代知识证据、实时状态或业务查询结果。";
	}

	public String promptBlock(AgentTemporalContext context, TemporalInterval interval) {
		String prompt = promptBlock(context);
		if (interval == null) {
			return prompt;
		}
		return prompt + ", intervalStartInclusive=" + interval.startInclusive() + ", intervalEndExclusive="
				+ interval.endExclusive();
	}

	private TemporalInterval calendarInterval(LocalDate start, LocalDate end, AgentTemporalContext context) {
		return new TemporalInterval(start, end, start.atStartOfDay(context.zoneId()).toInstant(),
				end.atStartOfDay(context.zoneId()).toInstant(), false);
	}

	private Map<String, TemporalSemantic> temporalAliases(Map<String, Object> clarificationConfig) {
		Map<String, TemporalSemantic> aliases = new LinkedHashMap<>();
		aliases.put("本周", TemporalSemantic.CURRENT_WEEK);
		aliases.put("本月", TemporalSemantic.CURRENT_MONTH);
		aliases.put("这个月", TemporalSemantic.CURRENT_MONTH);
		aliases.put("这月", TemporalSemantic.CURRENT_MONTH);
		aliases.put("今年", TemporalSemantic.CURRENT_YEAR);
		aliases.put("今天", TemporalSemantic.TODAY);
		aliases.put("明天", TemporalSemantic.TOMORROW);
		aliases.put("后天", TemporalSemantic.DAY_AFTER_TOMORROW);
		aliases.put("昨天", TemporalSemantic.YESTERDAY);
		aliases.put("前天", TemporalSemantic.DAY_BEFORE_YESTERDAY);
		if (clarificationConfig == null || clarificationConfig.isEmpty()) {
			return Map.copyOf(aliases);
		}
		Object configured = clarificationConfig.get("temporalAliases");
		if (configured instanceof Map<?, ?> configuredAliases) {
			configuredAliases.forEach((rawAlias, rawSemantic) -> {
				if (rawAlias == null || rawSemantic == null) {
					return;
				}
				String alias = String.valueOf(rawAlias).replace('\u3000', ' ').trim().toLowerCase(Locale.ROOT);
				if (!StringUtils.hasText(alias)) {
					return;
				}
				try {
					aliases.put(alias, TemporalSemantic.valueOf(String.valueOf(rawSemantic).trim().toUpperCase(Locale.ROOT)));
				}
				catch (IllegalArgumentException ignored) {
					// Persisted policy validation prevents this; ignore legacy corrupt values at runtime.
				}
			});
		}
		else if (clarificationConfig.get("timeAliases") instanceof List<?> legacyAliases) {
			for (Object legacyAlias : legacyAliases) {
				if (legacyAlias instanceof String text && StringUtils.hasText(text)) {
					aliases.put(text.replace('\u3000', ' ').trim().toLowerCase(Locale.ROOT),
							TemporalSemantic.CURRENT_MONTH);
				}
			}
		}
		return Map.copyOf(aliases);
	}

	private TemporalResolution parseStandard(String value, TemporalKind kind, AgentTemporalContext context) {
		try {
			return acceptDateTime(OffsetDateTime.parse(value), kind);
		}
		catch (DateTimeParseException ignored) {
			// 继续尝试其他明确格式。
		}
		try {
			return acceptDateTime(Instant.parse(value).atZone(context.zoneId()).toOffsetDateTime(), kind);
		}
		catch (DateTimeParseException ignored) {
			// 继续尝试本地日期时间。
		}
		for (DateTimeFormatter formatter : new DateTimeFormatter[] { DateTimeFormatter.ISO_LOCAL_DATE_TIME,
				DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }) {
			try {
				return resolveLocalDateTime(LocalDateTime.parse(value, formatter), kind, context);
			}
			catch (DateTimeParseException ignored) {
				// 继续尝试下一格式。
			}
		}
		try {
			return acceptDate(LocalDate.parse(value), kind);
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private TemporalResolution parseDate(String value, TemporalKind kind, AgentTemporalContext context) {
		LocalDate date = switch (value) {
			case "今天" -> context.localDate();
			case "明天" -> context.localDate().plusDays(1);
			case "后天" -> context.localDate().plusDays(2);
			case "昨天" -> context.localDate().minusDays(1);
			case "前天" -> context.localDate().minusDays(2);
			default -> null;
		};
		if (date != null) {
			return acceptDate(date, kind);
		}
		Matcher fullDate = DATE_PATTERN.matcher(value);
		if (fullDate.matches()) {
			return buildDate(fullDate.group(1), fullDate.group(2), fullDate.group(3), kind);
		}
		Matcher monthDay = MONTH_DAY_PATTERN.matcher(value);
		if (monthDay.matches()) {
			return buildDate(String.valueOf(context.localDate().getYear()), monthDay.group(1), monthDay.group(2), kind);
		}
		return TemporalResolution.unresolved(TemporalResolution.Status.NOT_TEMPORAL, "TEMPORAL_VALUE_UNRECOGNIZED");
	}

	private TemporalResolution buildDate(String year, String month, String day, TemporalKind kind) {
		try {
			return acceptDate(LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day)), kind);
		}
		catch (RuntimeException ex) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_DATE_INVALID");
		}
	}

	private TemporalResolution acceptDate(LocalDate date, TemporalKind kind) {
		if (kind == TemporalKind.DATE_TIME) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_TIME_REQUIRED");
		}
		return TemporalResolution.date(date);
	}

	private TemporalResolution acceptDateTime(OffsetDateTime dateTime, TemporalKind kind) {
		if (kind == TemporalKind.DATE) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_DATE_REQUIRED");
		}
		return TemporalResolution.dateTime(dateTime);
	}

	private TemporalResolution resolveLocalDateTime(LocalDateTime dateTime, TemporalKind kind,
			AgentTemporalContext context) {
		List<ZoneOffset> validOffsets = context.zoneId().getRules().getValidOffsets(dateTime);
		if (validOffsets.isEmpty()) {
			return TemporalResolution.unresolved(TemporalResolution.Status.INVALID, "TEMPORAL_TIME_INVALID");
		}
		if (validOffsets.size() > 1) {
			return TemporalResolution.unresolved(TemporalResolution.Status.AMBIGUOUS, "TEMPORAL_TIME_AMBIGUOUS");
		}
		return acceptDateTime(dateTime.atOffset(validOffsets.get(0)), kind);
	}

	private boolean isAmbiguous(String value) {
		return WEEKDAY_PATTERN.matcher(value).matches() || TIME_ONLY_PATTERN.matcher(value).matches()
				|| "月底".equals(value) || "尽快".equals(value) || "近期".equals(value);
	}

	private Instant epochInstant(String value) {
		long epochValue = new BigDecimal(value).stripTrailingZeros().longValueExact();
		return Math.abs(epochValue) >= 100_000_000_000L ? Instant.ofEpochMilli(epochValue)
				: Instant.ofEpochSecond(epochValue);
	}

	private AgentTemporalContext requireContext(AgentTemporalContext context) {
		if (context != null) {
			return context;
		}
		Instant reference = clock.instant();
		return new AgentTemporalContext(reference, DEFAULT_ZONE, DEFAULT_LOCALE,
				reference.atZone(DEFAULT_ZONE).toLocalDate(), DEFAULT_WEEK_STARTS_ON, TemporalAmbiguityStrategy.ASK);
	}

	private String textOrDefault(Object value, String defaultValue) {
		return value == null || !StringUtils.hasText(String.valueOf(value)) ? defaultValue : String.valueOf(value).trim();
	}

}
