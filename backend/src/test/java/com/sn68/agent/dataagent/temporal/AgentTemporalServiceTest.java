/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.entity.DataAgent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTemporalServiceTest {

	private static final Instant REFERENCE = Instant.parse("2026-07-19T06:00:00Z");

	private final AgentTemporalService service = new AgentTemporalService(
			Clock.fixed(REFERENCE, ZoneId.of("UTC")));

	@Test
	void resolvesRelativeDateAndPreservesExplicitTime() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder()
			.temporalPolicy(Map.of("zoneId", "Asia/Shanghai", "locale", "zh-CN", "weekStartsOn", "MONDAY",
					"ambiguityStrategy", "ASK"))
			.build());

		TemporalResolution today = service.resolvePoint("今天", TemporalKind.DATE_OR_DATE_TIME, context);
		TemporalResolution explicitTime = service.resolvePoint("2026-07-19 15:30", TemporalKind.DATE_OR_DATE_TIME,
				context);

		assertEquals("2026-07-19", today.normalizedValue());
		assertEquals("2026-07-19T15:30:00+08:00", explicitTime.normalizedValue());
		assertEquals("2026-07-19T07:30:00Z", service.toInstantValue(explicitTime, context));
	}

	@Test
	void dateConvertsToConfiguredZoneStartOfDayAtMcpBoundary() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder().build());
		TemporalResolution date = service.resolvePoint("今天", TemporalKind.DATE_OR_DATE_TIME, context);

		assertEquals("2026-07-18T16:00:00Z", service.toInstantValue(date, context));
	}

	@Test
	void rejectsAmbiguousExpressionsWithoutGuessing() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder().build());

		assertEquals(TemporalResolution.Status.AMBIGUOUS,
				service.resolvePoint("周五", TemporalKind.DATE_OR_DATE_TIME, context).status());
		assertEquals(TemporalResolution.Status.AMBIGUOUS,
				service.resolvePoint("只有 15:00", TemporalKind.DATE_OR_DATE_TIME, context).status());
	}

	@Test
	void calendarAndRollingIntervalsUseDifferentBoundaries() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder().build());
		TemporalInterval week = service.resolveInterval("本周", context).orElseThrow();
		TemporalInterval hours = service.resolveInterval("过去 168 小时", context).orElseThrow();

		assertEquals("2026-07-13", week.startDateInclusive().toString());
		assertEquals("2026-07-20", week.endDateExclusive().toString());
		assertFalse(week.rolling());
		assertEquals(REFERENCE.minusSeconds(168L * 3600L), hours.startInclusive());
		assertTrue(hours.rolling());
	}

	@Test
	void noYearDateUsesReferenceYearWithoutRollingForward() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder().build());

		assertEquals("2026-01-02",
				service.resolvePoint("1月2日", TemporalKind.DATE_OR_DATE_TIME, context).normalizedValue());
	}

	@Test
	void eachTurnUsesItsOwnReferenceDateAcrossShanghaiMidnight() {
		AgentTemporalService beforeMidnight = new AgentTemporalService(
				Clock.fixed(Instant.parse("2026-07-18T15:59:59Z"), ZoneId.of("UTC")));
		AgentTemporalService afterMidnight = new AgentTemporalService(
				Clock.fixed(Instant.parse("2026-07-18T16:00:00Z"), ZoneId.of("UTC")));

		assertEquals("2026-07-18", beforeMidnight.initialize(new AgentRequest(), DataAgent.builder().build())
				.localDate().toString());
		assertEquals("2026-07-19", afterMidnight.initialize(new AgentRequest(), DataAgent.builder().build())
				.localDate().toString());
	}

	@Test
	void rejectsDstGapAndOverlapButPreservesExplicitOffset() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder()
				.temporalPolicy(Map.of("zoneId", "America/New_York", "locale", "en-US", "weekStartsOn", "SUNDAY",
						"ambiguityStrategy", "ASK"))
				.build());

		assertEquals(TemporalResolution.Status.INVALID,
				service.resolvePoint("2026-03-08 02:30", TemporalKind.DATE_OR_DATE_TIME, context).status());
		assertEquals(TemporalResolution.Status.AMBIGUOUS,
				service.resolvePoint("2026-11-01 01:30", TemporalKind.DATE_OR_DATE_TIME, context).status());
		TemporalResolution explicitOffset = service.resolvePoint("2026-11-01T01:30:00-04:00",
				TemporalKind.DATE_OR_DATE_TIME, context);
		assertEquals("2026-11-01T01:30:00-04:00", explicitOffset.normalizedValue());
		assertEquals("2026-11-01T05:30:00Z", explicitOffset.instant().toString());
	}

	@Test
	void resolvesCalendarMonthYearAndNearDayIntervals() {
		AgentTemporalContext context = service.initialize(new AgentRequest(), DataAgent.builder().build());

		for (String alias : List.of("本月", "这个月", "这月")) {
			TemporalInterval month = service.resolveInterval(alias, context).orElseThrow();
			assertEquals("2026-07-01", month.startDateInclusive().toString());
			assertEquals("2026-08-01", month.endDateExclusive().toString());
			assertEquals("2026-06-30T16:00:00Z", month.startInclusive().toString());
			assertEquals("2026-07-31T16:00:00Z", month.endExclusive().toString());
		}
		assertEquals("2027-01-01", service.resolveInterval("今年", context).orElseThrow()
				.endDateExclusive().toString());
		TemporalInterval recent = service.resolveInterval("近 7 天", context).orElseThrow();
		assertEquals("2026-07-13", recent.startDateInclusive().toString());
		assertEquals("2026-07-20", recent.endDateExclusive().toString());
	}

	@Test
	void initializeIntervalResolvesDayAliasInsideALongerUtterance() {
		AgentRequest request = new AgentRequest();
		request.setQuery("下单 送箱 趟租 客户是箱箱 到货时间今天");
		service.initialize(request, DataAgent.builder().build());
		service.initializeInterval(request, Map.of());

		TemporalInterval interval = request.getTemporalInterval();
		assertEquals("2026-07-19", interval.startDateInclusive().toString());
		assertEquals("2026-07-20", interval.endDateExclusive().toString());
	}

	@Test
	void rejectsInvalidAgentTemporalPolicy() {
		assertThrows(RuntimeException.class,
				() -> service.normalizePolicy(Map.of("zoneId", "Mars/Colony")));
		assertThrows(RuntimeException.class,
				() -> service.normalizePolicy(Map.of("weekStartsOn", "FUNDAY")));
	}

}
