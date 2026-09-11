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
package com.sn68.agent.dataagent.agentscope.tool.webfetch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.linking.AppOriginMatcher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * web_fetch 编排：KEY_ONLY、逐跳 SSRF、体积/类型限制。HTTP 正文按 maxBytes 截断；
 * 回给模型的字符预算由 {@code SpringToolCallbackAgentAdapter} 的 ToolResultBudgetSanitizer 处理。
 */
@Slf4j
@Service
public class WebFetchService {

	static final String REASON_CONTENT_TYPE = "CONTENT_TYPE";

	static final String REASON_REDIRECT = "REDIRECT";

	static final String REASON_EMPTY = "EMPTY_BODY";

	private static final int MAX_REDIRECTS = 5;

	private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of("text/html", "text/plain", "application/json",
			"application/xml", "text/xml");

	private final WebFetchSsrfGuard ssrfGuard;

	private final WebFetchHttpClient httpClient;

	private final WebFetchRateLimiter rateLimiter;

	private final ObjectMapper objectMapper;

	public WebFetchService(WebFetchSsrfGuard ssrfGuard, WebFetchHttpClient httpClient,
			WebFetchRateLimiter rateLimiter, ObjectMapper objectMapper) {
		this.ssrfGuard = ssrfGuard;
		this.httpClient = httpClient;
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	public String fetch(String rawUrl, AgentRequest agentRequest) {
		if (!rateLimiter.tryAcquire(tenantId(agentRequest))) {
			log.warn("web_fetch rejected. reasonCode={}, url={}", WebFetchRateLimiter.REASON_QPS,
					AppOriginMatcher.truncateForLog(rawUrl));
			throw CheckedException.fail("网页读取超过租户 QPS 限制");
		}
		Duration timeout = resolveTimeout(agentRequest);
		long maxBytes = WebFetchLimits.MAX_BYTES;
		WebFetchSsrfGuard.PreparedTarget target = ssrfGuard.prepare(rawUrl);
		rejectKeyOnlyIfNeeded(target, rawUrl);
		WebFetchHttpClient.Hop hop = null;
		URI current = target.requestUri();
		long startedAt = System.nanoTime();
		for (int hopIndex = 0; hopIndex <= MAX_REDIRECTS; hopIndex++) {
			log.info("web_fetch start. host={}, hop={}", current.getHost(), hopIndex);
			hop = httpClient.get(current, target.connectIp(), timeout, maxBytes);
			if (!hop.redirect()) {
				break;
			}
			if (hopIndex == MAX_REDIRECTS) {
				throw ssrfGuard.reject(REASON_REDIRECT, "拒绝抓取：重定向次数过多", current.toString());
			}
			current = resolveRedirect(current, hop.location());
			target = ssrfGuard.prepare(current.toString());
			rejectKeyOnlyIfNeeded(target, current.toString());
		}
		return successPayload(rawUrl, current, hop, startedAt);
	}

	private void rejectKeyOnlyIfNeeded(WebFetchSsrfGuard.PreparedTarget target, String url) {
		if (target.appOrigin()) {
			throw ssrfGuard.reject(WebFetchSsrfGuard.REASON_KEY_ONLY, WebFetchSsrfGuard.KEY_ONLY_MESSAGE, url);
		}
	}

	private URI resolveRedirect(URI current, String location) {
		if (!StringUtils.hasText(location)) {
			throw ssrfGuard.reject(REASON_REDIRECT, "拒绝抓取：重定向缺少 Location", current.toString());
		}
		URI resolved = current.resolve(location.trim());
		if (resolved.getScheme() == null || resolved.getHost() == null) {
			throw ssrfGuard.reject(REASON_REDIRECT, "拒绝抓取：重定向地址无法解析", current.toString());
		}
		return resolved;
	}

	private String successPayload(String originalUrl, URI finalUri, WebFetchHttpClient.Hop hop, long startedAt) {
		if (hop == null) {
			throw CheckedException.fail("网页读取失败：空响应");
		}
		MediaType mediaType = parseMediaType(hop.contentType(), finalUri.toString());
		byte[] body = hop.body() == null ? new byte[0] : hop.body();
		String text = decodeBody(body, mediaType);
		if (!StringUtils.hasText(text) || (!hop.truncated() && looksLikeEmptyShell(text))) {
			throw ssrfGuard.reject(REASON_EMPTY, "页面内容为空或为登录空壳，材料不足", finalUri.toString());
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("ok", true);
		payload.put("url", AppOriginMatcher.redactUserInfo(originalUrl));
		payload.put("finalUrl", AppOriginMatcher.redactUserInfo(finalUri.toString()));
		payload.put("status", hop.status());
		payload.put("contentType", mediaType.toString());
		payload.put("byteCount", body.length);
		payload.put("truncated", hop.truncated());
		payload.put("body", text);
		payload.put("citation", Map.of("url", AppOriginMatcher.redactUserInfo(finalUri.toString()), "contentType",
				mediaType.toString()));
		log.info("web_fetch finished. host={}, status={}, bytes={}, durationMs={}", finalUri.getHost(), hop.status(),
				body.length, (System.nanoTime() - startedAt) / 1_000_000L);
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException ex) {
			throw CheckedException.fail("网页读取结果序列化失败");
		}
	}

	private MediaType parseMediaType(String contentType, String url) {
		if (!StringUtils.hasText(contentType)) {
			throw ssrfGuard.reject(REASON_CONTENT_TYPE, "拒绝抓取：响应缺少允许的 Content-Type", url);
		}
		MediaType mediaType;
		try {
			mediaType = MediaType.parseMediaType(contentType);
		}
		catch (RuntimeException ex) {
			throw ssrfGuard.reject(REASON_CONTENT_TYPE, "拒绝抓取：Content-Type 无法解析", url);
		}
		String type = mediaType.getType() + "/" + mediaType.getSubtype();
		if (!ALLOWED_MEDIA_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
			throw ssrfGuard.reject(REASON_CONTENT_TYPE, "拒绝抓取：不支持的 Content-Type，PDF/图片请走附件", url);
		}
		return mediaType;
	}

	private String decodeBody(byte[] body, MediaType mediaType) {
		Charset charset = mediaType.getCharset() == null ? StandardCharsets.UTF_8 : mediaType.getCharset();
		return new String(body, charset);
	}

	private boolean looksLikeEmptyShell(String text) {
		String compact = text.replaceAll("\\s+", " ").trim();
		if (compact.length() < 40) {
			return true;
		}
		String lower = compact.toLowerCase(Locale.ROOT);
		boolean loginTitle = lower.contains("<title>") && (lower.contains("登录") || lower.contains("login")
				|| lower.contains("sign in") || lower.contains("登陸"));
		return loginTitle && compact.length() < 4000 && lower.contains("password");
	}

	private Duration resolveTimeout(AgentRequest agentRequest) {
		Duration timeout = Duration.ofMillis(WebFetchLimits.TIMEOUT_MS);
		if (agentRequest == null || agentRequest.getRuntimeDeadline() == null) {
			return timeout;
		}
		Duration remaining = agentRequest.getRuntimeDeadline()
			.timeoutFor(timeout, agentRequest.getRuntimeFinishBuffer());
		if (remaining.isZero() || remaining.isNegative()) {
			throw CheckedException.fail("本轮运行时间已用尽，未启动网页读取。");
		}
		return remaining;
	}

	private String tenantId(AgentRequest agentRequest) {
		if (agentRequest == null || !StringUtils.hasText(agentRequest.getTenantIdSnapshot())) {
			return "anonymous";
		}
		return agentRequest.getTenantIdSnapshot().trim();
	}

}
