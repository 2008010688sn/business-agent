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

import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * {@code [link-keys …]} 本轮 ephemeral 标记。create pending 时剥离，每轮由抽键重建。
 */
public final class LinkKeysMarker {

	public static final String PREFIX = "[link-keys";

	private static final Pattern MARKER = Pattern.compile("\\s*\\[link-keys\\b.*?\\]", Pattern.DOTALL);

	private LinkKeysMarker() {
	}

	public static String strip(String text) {
		if (!StringUtils.hasText(text)) {
			return text;
		}
		String stripped = MARKER.matcher(text).replaceAll("");
		return stripped == null ? "" : stripped.trim();
	}

	public static String wrapMarker(String wrappedPayload) {
		if (!StringUtils.hasText(wrappedPayload)) {
			return "";
		}
		return PREFIX + " " + wrappedPayload.strip() + "]";
	}

	public static String replace(String routingQuery, String marker) {
		String base = strip(routingQuery);
		if (!StringUtils.hasText(marker)) {
			return base;
		}
		if (!StringUtils.hasText(base)) {
			return marker;
		}
		return base + "\n" + marker;
	}

}
