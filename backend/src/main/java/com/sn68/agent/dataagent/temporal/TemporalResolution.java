/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.temporal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 确定性时间解析结果；非 RESOLVED 结果不得写入结构化业务字段。
 */
public record TemporalResolution(Status status, Precision precision, String normalizedValue, LocalDate localDate,
		OffsetDateTime dateTime, Instant instant, String reasonCode) {

	public enum Status {
		RESOLVED,
		AMBIGUOUS,
		INVALID,
		NOT_TEMPORAL
	}

	public enum Precision {
		DATE,
		DATE_TIME
	}

	public boolean resolved() {
		return status == Status.RESOLVED;
	}

	public static TemporalResolution date(LocalDate value) {
		return new TemporalResolution(Status.RESOLVED, Precision.DATE, value.toString(), value, null, null, null);
	}

	public static TemporalResolution dateTime(OffsetDateTime value) {
		String normalized = value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"));
		return new TemporalResolution(Status.RESOLVED, Precision.DATE_TIME, normalized, value.toLocalDate(), value,
				value.toInstant(), null);
	}

	public static TemporalResolution unresolved(Status status, String reasonCode) {
		return new TemporalResolution(status, null, null, null, null, null, reasonCode);
	}

}
