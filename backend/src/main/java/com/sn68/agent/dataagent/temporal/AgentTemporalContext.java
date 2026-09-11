/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.temporal;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

/**
 * 单个 Agent Turn 内固定的时间解释上下文。
 */
public record AgentTemporalContext(Instant referenceInstant, ZoneId zoneId, Locale locale, LocalDate localDate,
		DayOfWeek weekStartsOn, TemporalAmbiguityStrategy ambiguityStrategy) {
}
