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
package com.sn68.agent.dataagent.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 出站请求上下文：为对外 HTTP 调用提供当前请求头快照的读取与透传。
 */
public final class DataAgentOutboundContext {

	public static final List<String> HEADER_NAMES = List.of("V4-Authorization", HttpHeaders.AUTHORIZATION,
			HttpHeaders.ACCEPT_LANGUAGE, "x-request-id", "TraceId", "SpanId", "x-time-zone", "x-tenant-id",
			"x-mock-application");

	private static final TransmittableThreadLocal<Snapshot> CONTEXT = new TransmittableThreadLocal<>();

	private DataAgentOutboundContext() {
	}

	public static Snapshot captureFromRequest() {
		if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
			return Snapshot.empty();
		}
		return captureFrom(attributes.getRequest());
	}

	public static Snapshot captureFrom(HttpServletRequest request) {
		if (request == null) {
			return Snapshot.empty();
		}
		Map<String, String> headers = new LinkedHashMap<>();
		for (String headerName : HEADER_NAMES) {
			String value = request.getHeader(headerName);
			if (StringUtils.hasText(value)) {
				headers.put(headerName, value);
			}
		}
		return new Snapshot(headers);
	}

	public static Snapshot get() {
		Snapshot snapshot = CONTEXT.get();
		return snapshot == null ? Snapshot.empty() : snapshot;
	}

	/**
	 * 用 Principal token 覆盖出站鉴权头，保留租户/追踪等其余允许透传的头。
	 * 数字员工委托执行时 Feign 必须带员工身份，不能继续透传真人 V4-Authorization。
	 */
	public static Snapshot withPrincipalToken(Snapshot base, String tokenValue) {
		return withPrincipalToken(base, tokenValue, null);
	}

	public static Snapshot withPrincipalToken(Snapshot base, String tokenValue, String tenantId) {
		Map<String, String> headers = new LinkedHashMap<>();
		if (base != null && !base.headers().isEmpty()) {
			headers.putAll(base.headers());
		}
		if (StringUtils.hasText(tokenValue)) {
			headers.put("V4-Authorization", tokenValue);
			headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue);
		}
		if (StringUtils.hasText(tenantId) && !hasHeader(headers, "x-tenant-id")) {
			headers.put("x-tenant-id", tenantId);
		}
		return new Snapshot(headers, true);
	}

	private static boolean hasHeader(Map<String, String> headers, String headerName) {
		return headers.keySet().stream().anyMatch(name -> headerName.equalsIgnoreCase(name));
	}

	public static void set(Snapshot snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			clear();
			return;
		}
		CONTEXT.set(snapshot);
	}

	public static void clear() {
		CONTEXT.remove();
	}

	public static Runnable wrap(Runnable runnable) {
		Snapshot snapshot = get();
		return () -> runWith(snapshot, runnable);
	}

	public static <T> Supplier<T> wrap(Supplier<T> supplier) {
		Snapshot snapshot = get();
		return () -> supplyWith(snapshot, supplier);
	}

	public static void runWith(Snapshot snapshot, Runnable runnable) {
		Snapshot previous = CONTEXT.get();
		try {
			set(snapshot);
			runnable.run();
		}
		finally {
			restore(previous);
		}
	}

	public static <T> T supplyWith(Snapshot snapshot, Supplier<T> supplier) {
		Snapshot previous = CONTEXT.get();
		try {
			set(snapshot);
			return supplier.get();
		}
		finally {
			restore(previous);
		}
	}

	private static void restore(Snapshot snapshot) {
		if (snapshot == null || snapshot.isEmpty()) {
			clear();
			return;
		}
		CONTEXT.set(snapshot);
	}

	public record Snapshot(Map<String, String> headers, boolean forceHttpAuthorization) {

		public Snapshot(Map<String, String> headers) {
			this(headers, false);
		}

		public Snapshot {
			if (headers == null || headers.isEmpty()) {
				headers = Map.of();
			}
			else {
				Map<String, String> allowedHeaders = new LinkedHashMap<>();
				for (String headerName : HEADER_NAMES) {
					String value = headers.get(headerName);
					if (StringUtils.hasText(value)) {
						allowedHeaders.put(headerName, value);
					}
				}
				headers = allowedHeaders.isEmpty() ? Map.of() : Map.copyOf(allowedHeaders);
			}
		}

		public static Snapshot empty() {
			return new Snapshot(Map.of());
		}

		public boolean isEmpty() {
			return headers.isEmpty();
		}

	}

}
