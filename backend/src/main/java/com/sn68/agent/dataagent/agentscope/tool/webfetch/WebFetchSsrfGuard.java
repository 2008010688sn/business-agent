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

import com.sn68.agent.dataagent.linking.AppOriginMatcher;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * web_fetch SSRF 硬底线：协议、云元数据、链路本地、DNS 解析后复验，并选出可 pin 的出网 IP。
 */
@Slf4j
@Component
public class WebFetchSsrfGuard {

	static final String KEY_ONLY_MESSAGE = "本系统链接默认只抽键，不抓取页面";

	static final String REASON_KEY_ONLY = "KEY_ONLY";

	static final String REASON_SCHEME = "SCHEME";

	static final String REASON_METADATA = "METADATA";

	static final String REASON_LINK_LOCAL = "LINK_LOCAL";

	static final String REASON_PRIVATE = "PRIVATE";

	static final String REASON_LOOPBACK = "LOOPBACK";

	static final String REASON_UNSPECIFIED = "UNSPECIFIED";

	static final String REASON_DNS = "DNS";

	static final String REASON_INVALID_URL = "INVALID_URL";

	static final String REASON_MULTICAST = "MULTICAST";

	private static final Set<String> METADATA_HOSTS = Set.of("localhost", "localhost.localdomain", "ip6-localhost",
			"ip6-loopback", "metadata", "metadata.google.internal", "metadata.goog", "metadata.tencentyun.com",
			"instance-data", "instance-data.ec2.internal");

	private final DataAgentProperties dataAgentProperties;

	private final DnsResolver dnsResolver;

	@Autowired
	public WebFetchSsrfGuard(DataAgentProperties dataAgentProperties) {
		this(dataAgentProperties, InetAddress::getAllByName);
	}

	WebFetchSsrfGuard(DataAgentProperties dataAgentProperties, DnsResolver dnsResolver) {
		this.dataAgentProperties = dataAgentProperties;
		this.dnsResolver = dnsResolver == null ? InetAddress::getAllByName : dnsResolver;
	}

	public PreparedTarget prepare(String rawUrl) {
		URI uri = sanitizeUri(rawUrl);
		boolean appOrigin = AppOriginMatcher.matches(uri, appOrigins());
		List<InetAddress> resolved = resolveAll(uri.getHost());
		InetAddress connectIp = selectConnectIp(uri, resolved, appOrigin);
		return new PreparedTarget(uri, uri.getHost(), effectivePort(uri), appOrigin, resolved, connectIp);
	}

	CheckedException reject(String reasonCode, String message, String url) {
		log.warn("web_fetch rejected. reasonCode={}, url={}", reasonCode, AppOriginMatcher.truncateForLog(url));
		return CheckedException.forbidden(message);
	}

	private URI sanitizeUri(String rawUrl) {
		if (!StringUtils.hasText(rawUrl)) {
			throw reject(REASON_INVALID_URL, "网页读取需要 url 参数", rawUrl);
		}
		URI parsed;
		try {
			parsed = URI.create(rawUrl.trim());
		}
		catch (IllegalArgumentException ex) {
			throw reject(REASON_INVALID_URL, "网页读取拒绝：URL 无法解析", rawUrl);
		}
		String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw reject(REASON_SCHEME, "拒绝抓取：只允许 http/https 协议", rawUrl);
		}
		String host = extractHost(parsed);
		if (!StringUtils.hasText(host)) {
			throw reject(REASON_INVALID_URL, "网页读取拒绝：URL 缺少主机名", rawUrl);
		}
		rejectMetadataHostname(host, rawUrl);
		InetAddress literal = WebFetchIpAddresses.parseLiteral(host);
		String requestHost = parsed.getHost() != null ? parsed.getHost()
				: (literal == null ? host : literal.getHostAddress());
		try {
			return new URI(scheme, null, requestHost, parsed.getPort(), parsed.getPath(), parsed.getQuery(), null);
		}
		catch (URISyntaxException ex) {
			throw reject(REASON_INVALID_URL, "网页读取拒绝：URL 无法规范化", rawUrl);
		}
	}

	/**
	 * Java {@link URI#getHost()} 对 {@code 127.1} 这类 inet_aton 变体返回 null，回退 authority。
	 */
	static String extractHost(URI parsed) {
		if (parsed == null) {
			return null;
		}
		if (StringUtils.hasText(parsed.getHost())) {
			return parsed.getHost();
		}
		String authority = parsed.getRawAuthority();
		if (!StringUtils.hasText(authority)) {
			return null;
		}
		int at = authority.lastIndexOf('@');
		if (at >= 0) {
			authority = authority.substring(at + 1);
		}
		if (authority.startsWith("[")) {
			int end = authority.indexOf(']');
			return end > 1 ? authority.substring(1, end) : null;
		}
		int colon = authority.lastIndexOf(':');
		if (colon > 0 && authority.indexOf(':') == colon) {
			return authority.substring(0, colon);
		}
		return authority;
	}

	private void rejectMetadataHostname(String host, String rawUrl) {
		String normalized = normalizeHostname(host);
		if (!StringUtils.hasText(normalized)) {
			return;
		}
		if (METADATA_HOSTS.contains(normalized) || normalized.endsWith(".localhost")) {
			throw reject(REASON_METADATA, "拒绝抓取：目标属于本机或云元数据主机", rawUrl);
		}
	}

	private static String normalizeHostname(String host) {
		if (!StringUtils.hasText(host)) {
			return host;
		}
		String trimmed = host.trim();
		if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
			trimmed = trimmed.substring(1, trimmed.length() - 1);
		}
		return trimmed.toLowerCase(Locale.ROOT);
	}

	private List<InetAddress> resolveAll(String host) {
		InetAddress literal = WebFetchIpAddresses.parseLiteral(host);
		if (literal != null) {
			return List.of(WebFetchIpAddresses.unwrapMappedIpv4(literal));
		}
		try {
			String ascii = toAsciiHost(host);
			InetAddress[] resolved = dnsResolver.resolve(ascii);
			if (resolved == null || resolved.length == 0) {
				throw reject(REASON_DNS, "拒绝抓取：主机名无法解析", host);
			}
			List<InetAddress> addresses = new ArrayList<>();
			for (InetAddress address : resolved) {
				if (address != null) {
					addresses.add(WebFetchIpAddresses.unwrapMappedIpv4(address));
				}
			}
			if (addresses.isEmpty()) {
				throw reject(REASON_DNS, "拒绝抓取：主机名无法解析", host);
			}
			return addresses;
		}
		catch (UnknownHostException ex) {
			throw reject(REASON_DNS, "拒绝抓取：主机名无法解析", host);
		}
	}

	private InetAddress selectConnectIp(URI uri, List<InetAddress> resolved, boolean appOrigin) {
		InetAddress pin = null;
		String firstDeny = null;
		for (InetAddress address : resolved) {
			String reason = classify(address, appOrigin);
			if (reason == null) {
				if (pin == null) {
					pin = address;
				}
			}
			else if (firstDeny == null) {
				firstDeny = reason;
			}
		}
		if (pin != null) {
			return pin;
		}
		String reason = firstDeny == null ? REASON_DNS : firstDeny;
		throw reject(reason, rejectMessage(reason), uri.toString());
	}

	private String classify(InetAddress address, boolean appOrigin) {
		if (address == null) {
			return REASON_DNS;
		}
		if (WebFetchIpAddresses.isUnspecified(address)) {
			return REASON_UNSPECIFIED;
		}
		if (WebFetchIpAddresses.isCloudMetadata(address) || WebFetchIpAddresses.isIpv4MappedLoopback(address)) {
			return REASON_METADATA;
		}
		if (WebFetchIpAddresses.isLinkLocal(address)) {
			return REASON_LINK_LOCAL;
		}
		if (WebFetchIpAddresses.isMulticast(address)) {
			return REASON_MULTICAST;
		}
		if (WebFetchIpAddresses.isLoopback(address)) {
			return appOrigin ? null : REASON_LOOPBACK;
		}
		if (WebFetchIpAddresses.isRfc1918OrUla(address)) {
			return appOrigin ? null : REASON_PRIVATE;
		}
		return null;
	}

	private String rejectMessage(String reason) {
		return switch (reason) {
			case REASON_METADATA -> "拒绝抓取：目标属于云元数据或被禁止的地址变体";
			case REASON_LINK_LOCAL -> "拒绝抓取：目标属于链路本地地址";
			case REASON_PRIVATE -> "拒绝抓取：未声明的内网地址";
			case REASON_LOOPBACK -> "拒绝抓取：未声明的本机地址";
			case REASON_UNSPECIFIED -> "拒绝抓取：未指定地址";
			case REASON_MULTICAST -> "拒绝抓取：组播地址";
			case REASON_DNS -> "拒绝抓取：没有可连接的已校验地址";
			default -> "拒绝抓取：目标地址不安全";
		};
	}

	private List<String> appOrigins() {
		return webEvidence().getAppOrigins();
	}

	private DataAgentProperties.WebEvidence webEvidence() {
		return dataAgentProperties.getWebEvidence();
	}

	static int effectivePort(URI uri) {
		if (uri.getPort() > 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private static String toAsciiHost(String host) {
		if (!StringUtils.hasText(host)) {
			return host;
		}
		String trimmed = host.trim();
		if (trimmed.chars().noneMatch(ch -> ch > 127)) {
			return trimmed;
		}
		try {
			return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
		}
		catch (IllegalArgumentException ex) {
			return trimmed;
		}
	}

	@FunctionalInterface
	interface DnsResolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	record PreparedTarget(URI requestUri, String host, int port, boolean appOrigin, List<InetAddress> resolved,
			InetAddress connectIp) {
	}

}
