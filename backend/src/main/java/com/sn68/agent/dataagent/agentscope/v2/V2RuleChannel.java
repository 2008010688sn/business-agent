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
package com.sn68.agent.dataagent.agentscope.v2;

import com.sn68.agent.framework.commons.security.DataPermission;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * Independent rule channel for tenant, redline and table whitelist.
 *
 * <p>These facts are taken from {@link V2RuntimeSnapshot} and injected via
 * {@code onSystemPrompt}. They must not be recovered from vector retrieval or
 * compacted conversation summaries.
 */
public final class V2RuleChannel {

	static final String MARKER = "[v2-tenant-guard]";

	private V2RuleChannel() {
	}

	static String mergeIntoPrompt(String currentPrompt, V2RuntimeSnapshot snapshot) {
		String prompt = currentPrompt == null ? "" : currentPrompt;
		String block = block(snapshot);
		if (!StringUtils.hasText(block)) {
			return prompt;
		}
		if (prompt.contains(MARKER) && retainsP0(prompt, snapshot)) {
			return prompt;
		}
		if (!StringUtils.hasText(prompt)) {
			return block;
		}
		return prompt + System.lineSeparator() + System.lineSeparator() + block;
	}

	static String block(V2RuntimeSnapshot snapshot) {
		if (snapshot == null) {
			return "";
		}
		StringBuilder block = new StringBuilder(MARKER).append(System.lineSeparator());
		appendLine(block, "tenantId", snapshot.tenantId());
		appendLine(block, "tenantCode", snapshot.tenantCode());
		appendLine(block, "userId", snapshot.userId());
		appendLine(block, "sessionId", snapshot.sessionId());
		appendLine(block, "flowInstanceId", snapshot.flowInstanceId());
		DataPermission permission = snapshot.dataPermission();
		if (permission != null && permission.getScopeType() != null) {
			appendLine(block, "dataScope", permission.getScopeType().name());
		}
		appendCsv(block, "tableWhitelist", snapshot.tableWhitelist());
		for (String handle : snapshot.analysisWorkspaceHandles()) {
			appendLine(block, "analysisWorkspaceHandle", handle);
		}
		block.append("Rules: Stay inside this tenant. Do not cross tenant or data-permission redlines. ")
			.append("Never invent tenant from HTTP headers. ")
			.append("Never load tenant, redlines, or table whitelist from vector retrieval.");
		return block.toString();
	}

	static boolean retainsP0(String text, V2RuntimeSnapshot snapshot) {
		if (!StringUtils.hasText(text) || snapshot == null) {
			return false;
		}
		for (String token : p0Tokens(snapshot)) {
			if (!text.contains(token)) {
				return false;
			}
		}
		return true;
	}

	static List<String> p0Tokens(V2RuntimeSnapshot snapshot) {
		List<String> tokens = new ArrayList<>();
		if (snapshot == null) {
			return List.of();
		}
		addToken(tokens, snapshot.tenantId());
		addToken(tokens, snapshot.tenantCode());
		addToken(tokens, snapshot.flowInstanceId());
		DataPermission permission = snapshot.dataPermission();
		if (permission != null && permission.getScopeType() != null) {
			addToken(tokens, permission.getScopeType().name());
		}
		for (String table : snapshot.tableWhitelist()) {
			addToken(tokens, table);
		}
		for (String handle : snapshot.analysisWorkspaceHandles()) {
			addToken(tokens, handle);
		}
		return List.copyOf(tokens);
	}

	private static void addToken(List<String> tokens, String value) {
		if (StringUtils.hasText(value)) {
			tokens.add(value);
		}
	}

	private static void appendLine(StringBuilder block, String name, String value) {
		if (!StringUtils.hasText(value)) {
			return;
		}
		block.append(name).append('=').append(value).append(System.lineSeparator());
	}

	private static void appendCsv(StringBuilder block, String name, List<String> values) {
		if (values == null || values.isEmpty()) {
			return;
		}
		List<String> present = new ArrayList<>();
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				present.add(value.trim());
			}
		}
		if (present.isEmpty()) {
			return;
		}
		block.append(name).append('=').append(String.join(",", present)).append(System.lineSeparator());
	}

}
