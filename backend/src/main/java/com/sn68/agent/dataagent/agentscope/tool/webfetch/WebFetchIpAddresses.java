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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * IP 字面量归一与分类。十进制整数、八进制、缩写（{@code 127.1}）先展开再判定，避免 inet_aton 后门。
 */
final class WebFetchIpAddresses {

	static final String ALIYUN_METADATA_V4 = "100.100.100.200";

	static final String AWS_METADATA_V6 = "fd00:ec2::254";

	private WebFetchIpAddresses() {
	}

	static InetAddress parseLiteral(String host) {
		if (!StringUtils.hasText(host)) {
			return null;
		}
		String normalized = stripBrackets(host.trim());
		if (normalized.indexOf(':') >= 0) {
			return parseIpv6(normalized);
		}
		if (!looksNumericIpv4(normalized)) {
			return null;
		}
		return parseIpv4Variant(normalized);
	}

	static InetAddress unwrapMappedIpv4(InetAddress address) {
		if (address == null) {
			return null;
		}
		byte[] bytes = address.getAddress();
		if (bytes == null || bytes.length != 16 || !isIpv4Mapped(bytes)) {
			return address;
		}
		byte[] v4 = new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
		try {
			return InetAddress.getByAddress(v4);
		}
		catch (UnknownHostException ex) {
			return address;
		}
	}

	static boolean isUnspecified(InetAddress address) {
		return address != null && address.isAnyLocalAddress();
	}

	static boolean isLoopback(InetAddress address) {
		return address != null && address.isLoopbackAddress();
	}

	static boolean isLinkLocal(InetAddress address) {
		return address != null && address.isLinkLocalAddress();
	}

	static boolean isMulticast(InetAddress address) {
		return address != null && address.isMulticastAddress();
	}

	static boolean isRfc1918OrUla(InetAddress address) {
		if (address == null) {
			return false;
		}
		if (address.isSiteLocalAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		return bytes != null && bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
	}

	static boolean isCloudMetadata(InetAddress address) {
		if (address == null) {
			return false;
		}
		InetAddress unwrapped = unwrapMappedIpv4(address);
		String host = unwrapped.getHostAddress();
		if (ALIYUN_METADATA_V4.equals(host)) {
			return true;
		}
		return AWS_METADATA_V6.equals(unwrapped.getHostAddress().toLowerCase(Locale.ROOT));
	}

	static boolean isIpv4MappedLoopback(InetAddress address) {
		if (address == null) {
			return false;
		}
		byte[] bytes = address.getAddress();
		if (bytes == null || bytes.length != 16 || !isIpv4Mapped(bytes)) {
			return false;
		}
		return (bytes[12] & 0xff) == 127;
	}

	private static boolean isIpv4Mapped(byte[] bytes) {
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
	}

	private static String stripBrackets(String host) {
		if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
			return host.substring(1, host.length() - 1);
		}
		return host;
	}

	private static boolean looksNumericIpv4(String host) {
		for (int i = 0; i < host.length(); i++) {
			char ch = host.charAt(i);
			if (ch == '.' || (ch >= '0' && ch <= '9') || ch == 'x' || ch == 'X' || (ch >= 'a' && ch <= 'f')
					|| (ch >= 'A' && ch <= 'F')) {
				continue;
			}
			return false;
		}
		return true;
	}

	private static InetAddress parseIpv6(String host) {
		try {
			InetAddress address = InetAddress.getByName(host);
			return address.getAddress() != null && address.getAddress().length == 16 ? address : null;
		}
		catch (UnknownHostException ex) {
			return null;
		}
	}

	private static InetAddress parseIpv4Variant(String host) {
		String[] parts = host.split("\\.", -1);
		if (parts.length == 0 || parts.length > 4) {
			return null;
		}
		long[] values = new long[parts.length];
		for (int i = 0; i < parts.length; i++) {
			Long value = parseIpv4Part(parts[i]);
			if (value == null) {
				return null;
			}
			values[i] = value;
		}
		Long addr = composeIpv4(values);
		if (addr == null) {
			return null;
		}
		byte[] bytes = new byte[] { (byte) ((addr >>> 24) & 0xff), (byte) ((addr >>> 16) & 0xff),
				(byte) ((addr >>> 8) & 0xff), (byte) (addr & 0xff) };
		try {
			return InetAddress.getByAddress(bytes);
		}
		catch (UnknownHostException ex) {
			return null;
		}
	}

	private static Long composeIpv4(long[] values) {
		return switch (values.length) {
			case 1 -> values[0] <= 0xffffffffL ? values[0] : null;
			case 2 -> values[0] <= 0xff && values[1] <= 0xffffffL ? (values[0] << 24) | values[1] : null;
			case 3 -> values[0] <= 0xff && values[1] <= 0xff && values[2] <= 0xffffL
					? (values[0] << 24) | (values[1] << 16) | values[2] : null;
			case 4 -> values[0] <= 0xff && values[1] <= 0xff && values[2] <= 0xff && values[3] <= 0xff
					? (values[0] << 24) | (values[1] << 16) | (values[2] << 8) | values[3] : null;
			default -> null;
		};
	}

	private static Long parseIpv4Part(String part) {
		if (!StringUtils.hasText(part)) {
			return null;
		}
		try {
			if (part.startsWith("0x") || part.startsWith("0X")) {
				return part.length() == 2 ? null : Long.parseLong(part.substring(2), 16);
			}
			if (part.length() > 1 && part.charAt(0) == '0') {
				for (int i = 1; i < part.length(); i++) {
					char ch = part.charAt(i);
					if (ch < '0' || ch > '7') {
						return null;
					}
				}
				return Long.parseLong(part, 8);
			}
			if (part.chars().allMatch(Character::isDigit)) {
				return Long.parseLong(part, 10);
			}
			return null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
