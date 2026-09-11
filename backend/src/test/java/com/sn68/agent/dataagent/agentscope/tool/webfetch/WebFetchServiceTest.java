/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.redis.plus.RedisLimitHelper;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebFetchServiceTest {

	private DataAgentProperties properties;

	private WebFetchSsrfGuard ssrfGuard;

	private WebFetchRateLimiter rateLimiter;

	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() throws Exception {
		properties = new DataAgentProperties();
		properties.getWebEvidence().setFetchEnabled(true);
		properties.getWebEvidence().setAppOrigins(List.of("http://10.0.0.1:31770"));
		InetAddress publicIp = InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 });
		ssrfGuard = new WebFetchSsrfGuard(properties, host -> new InetAddress[] { publicIp });
		@SuppressWarnings("unchecked")
		ObjectProvider<RedisLimitHelper> redis = mock(ObjectProvider.class);
		rateLimiter = new WebFetchRateLimiter(redis);
		objectMapper = new ObjectMapper();
	}

	@Test
	void rejectsLoopback() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service(nullHttp()).fetch("http://127.0.0.1/", null));
		assertTrue(ex.getMessage().contains("本机") || ex.getMessage().contains("拒绝抓取"));
	}

	@Test
	void rejectsLoopbackIpVariants() {
		assertThrows(CheckedException.class, () -> ssrfGuard.prepare("http://127.1/"));
		assertThrows(CheckedException.class, () -> ssrfGuard.prepare("http://2130706433/"));
		assertThrows(CheckedException.class, () -> ssrfGuard.prepare("http://0177.0.0.1/"));
		assertThrows(CheckedException.class, () -> ssrfGuard.prepare("http://[::1]/"));
	}

	@Test
	void rejectsMetadataLinkLocal() {
		assertThrows(CheckedException.class, () -> service(nullHttp()).fetch("http://169.254.169.254/latest", null));
	}

	@Test
	void rejectsFileScheme() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service(nullHttp()).fetch("file:///etc/passwd", null));
		assertTrue(ex.getMessage().contains("http/https"));
	}

	@Test
	void rejectsUndeclaredRfc1918() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service(nullHttp()).fetch("http://10.1.2.3/internal", null));
		assertTrue(ex.getMessage().contains("内网"));
	}

	@Test
	void rejectsAppOriginWhenKeyOnly() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service(nullHttp()).fetch("http://10.0.0.1:31770/dual-effect/detail?id=1", null));
		assertEquals(WebFetchSsrfGuard.KEY_ONLY_MESSAGE, ex.getMessage());
	}

	@Test
	void publicHttpUrlReturnsTruncatedBodyFromMockWebClient() throws Exception {
		String body = "a".repeat(80);
		WebFetchHttpClient httpClient = new WebFetchHttpClient(WebClient.builder(),
				request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
					.body(body)
					.build()));
		WebFetchService service = service(httpClient);

		String json = service.fetch("http://example.com/page", null);

		JsonNode node = objectMapper.readTree(json);
		assertTrue(node.path("ok").asBoolean());
		assertFalse(node.path("truncated").asBoolean());
		assertEquals(80, node.path("body").asText().getBytes(StandardCharsets.UTF_8).length);
		assertEquals("http://example.com/page", node.path("citation").path("url").asText());
	}

	@Test
	void publicRedirectToAppOriginBecomesKeyOnly() {
		WebFetchHttpClient httpClient = new WebFetchHttpClient(WebClient.builder(), request -> {
			URI uri = request.url();
			if ("example.com".equals(uri.getHost())) {
				return Mono.just(ClientResponse.create(HttpStatus.FOUND)
					.header(HttpHeaders.LOCATION, "http://10.0.0.1:31770/detail?id=1")
					.body("")
					.build());
			}
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, "text/html")
				.body("<html>should not fetch app origin</html>")
				.build());
		});
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service(httpClient).fetch("http://example.com/short", null));
		assertEquals(WebFetchSsrfGuard.KEY_ONLY_MESSAGE, ex.getMessage());
	}

	private WebFetchService service(WebFetchHttpClient httpClient) {
		return new WebFetchService(ssrfGuard, httpClient, rateLimiter, objectMapper);
	}

	private WebFetchHttpClient nullHttp() {
		return new WebFetchHttpClient(WebClient.builder(),
				request -> Mono.error(new IllegalStateException("HTTP should not be called")));
	}

}
