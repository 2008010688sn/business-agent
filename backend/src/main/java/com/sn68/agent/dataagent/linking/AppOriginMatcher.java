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
package com.sn68.agent.dataagent.linking;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 按 scheme+host+port 匹配本系统 origin。忽略 path/query/fragment/尾 {@code /}；剥 userinfo；IPv6 括号归一。
 */
public final class AppOriginMatcher {

	private AppOriginMatcher() {
	}

	public static boolean matches(URI uri, Collection<String> origins) {
		String urlKey = originKey(uri);
		if (!StringUtils.hasText(urlKey) || origins == null || origins.isEmpty()) {
			return false;
		}
		for (String origin : origins) {
			if (urlKey.equals(originKey(parse(origin)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 本系统站点：命中 app-origins，或 host 是 localhost / RFC1918 等字面量（不 DNS）。
	 * 公网域名的内部站点仍靠 app-origins。
	 */
	public static boolean isOwnSite(URI uri, Collection<String> origins) {
		return matches(uri, origins) || isPrivateOrLocalLiteral(uri);
	}

	static boolean isPrivateOrLocalLiteral(URI uri) {
		if (uri == null || !StringUtils.hasText(uri.getHost())) {
			return false;
		}
		String host = uri.getHost().trim();
		if ("localhost".equalsIgnoreCase(host)) {
			return true;
		}
		if (!looksLikeIpLiteral(host)) {
			return false;
		}
		try {
			InetAddress address = InetAddress.getByName(stripBrackets(host));
			return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
					|| address.isAnyLocalAddress();
		}
		catch (UnknownHostException ex) {
			return false;
		}
	}

	private static boolean looksLikeIpLiteral(String host) {
		String value = stripBrackets(host);
		if (value.indexOf(':') >= 0) {
			return true;
		}
		int dots = 0;
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch == '.') {
				dots++;
			}
			else if (ch < '0' || ch > '9') {
				return false;
			}
		}
		return dots >= 1 && dots <= 3;
	}

	private static String stripBrackets(String host) {
		if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
			return host.substring(1, host.length() - 1);
		}
		return host;
	}

	public static URI parse(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.endsWith("/") && trimmed.length() > 8) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		try {
			URI uri = URI.create(trimmed);
			if (uri.getScheme() == null || uri.getHost() == null) {
				return null;
			}
			return uri;
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	public static String originKey(URI uri) {
		if (uri == null || !StringUtils.hasText(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
			return null;
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			return null;
		}
		return scheme + "://" + normalizeHost(uri.getHost()) + ":" + effectivePort(scheme, uri.getPort());
	}

	public static String redactUserInfo(String url) {
		if (!StringUtils.hasText(url)) {
			return url;
		}
		return url.replaceFirst("://[^/@\\s]+@", "://");
	}

	public static String truncateForLog(String url) {
		String redacted = redactUserInfo(url);
		if (!StringUtils.hasText(redacted) || redacted.length() <= 240) {
			return redacted;
		}
		return redacted.substring(0, 240) + "...";
	}

	static String normalizeHost(String host) {
		if (!StringUtils.hasText(host)) {
			return null;
		}
		String trimmed = host.trim();
		if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
			trimmed = trimmed.substring(1, trimmed.length() - 1);
		}
		trimmed = trimmed.toLowerCase(Locale.ROOT);
		if (trimmed.chars().anyMatch(ch -> ch > 127)) {
			try {
				return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
			}
			catch (IllegalArgumentException ex) {
				return trimmed;
			}
		}
		return trimmed;
	}

	static int effectivePort(String scheme, int port) {
		if (port > 0) {
			return port;
		}
		if ("https".equalsIgnoreCase(scheme)) {
			return 443;
		}
		if ("http".equalsIgnoreCase(scheme)) {
			return 80;
		}
		return port;
	}

}
