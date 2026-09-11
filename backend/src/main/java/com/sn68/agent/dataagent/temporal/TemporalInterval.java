/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.temporal;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 左闭右开的日历或滚动时间区间。
 */
public record TemporalInterval(LocalDate startDateInclusive, LocalDate endDateExclusive, Instant startInclusive,
		Instant endExclusive, boolean rolling) {
}
