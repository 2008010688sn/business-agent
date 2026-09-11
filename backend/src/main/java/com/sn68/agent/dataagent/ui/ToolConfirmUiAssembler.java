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
package com.sn68.agent.dataagent.ui;

import cn.hutool.crypto.SecureUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Packs a write-tool ASK into {@code kind=tool-confirm}. Confirm binds toolCallId + param
 * fingerprint; TTL and one-time consume stay on AgentApproval (PG), not local memory.
 */
public final class ToolConfirmUiAssembler {

	public static final Duration CONFIRM_TTL = Duration.ofMinutes(5);

	public static final String ACTION_ASK = "ASK";

	public static final String ACTION_APPROVE = "APPROVE";

	public static final String ACTION_DENY = "DENY";

	public static final String STAGE_TOOL_ASK = "TOOL_ASK";

	public static final String SOURCE_TOOL_CONFIRM = "TOOL_CONFIRM";

	static final int FINGERPRINT_LENGTH = 16;

	private static final int PREVIEW_MAX = 240;

	private static final int PREVIEW_VALUE_MAX = 40;

	private static final Pattern SECRET_KEY = Pattern.compile("password|passwd|token|secret|credential|authorization",
			Pattern.CASE_INSENSITIVE);

	private static final ObjectMapper CANONICAL = new ObjectMapper()
		.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

	private ToolConfirmUiAssembler() {
	}

	public static AgentUiMessage fromToolCall(String runtimeRequestId, String replyId, String toolCallId,
			String toolName, Map<String, Object> arguments, Instant expiresAt) {
		String fingerprint = fingerprint(arguments);
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("toolCallId", toolCallId);
		values.put("toolName", toolName);
		values.put("paramFingerprint", fingerprint);
		values.put("replyId", replyId);
		if (expiresAt != null) {
			values.put("expiresAt", expiresAt.toString());
		}
		values.put("argsPreview", preview(arguments));
		List<AgentUiMessage.Action> actions = List.of(
				new AgentUiMessage.Action("approve", ACTION_APPROVE, "确认执行", true, binding(toolCallId, fingerprint,
						replyId, toolName)),
				new AgentUiMessage.Action("deny", ACTION_DENY, "拒绝", false, binding(toolCallId, fingerprint, replyId,
						toolName)));
		String label = StringUtils.hasText(toolName) ? toolName.trim() : "写工具";
		return new AgentUiMessage(AgentUiMessage.SCHEMA_VERSION, AgentUiMessage.KIND_TOOL_CONFIRM, runtimeRequestId,
				new AgentUiMessage.Source(null, label, null, null),
				new AgentUiMessage.Content("markdown", "写操作需要确认后才会执行：" + label),
				new AgentUiMessage.Payload(ACTION_ASK, values, List.of()), actions,
				new AgentUiMessage.Timing(STAGE_TOOL_ASK, null));
	}

	public static Map<String, Object> toMetadata(AgentUiMessage message) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("uiSchemaVersion", AgentUiMessage.SCHEMA_VERSION);
		metadata.put("uiSource", "tool-confirm");
		metadata.put("messageType", AgentSessionConstant.MESSAGE_TYPE_TOOL_CONFIRM);
		metadata.put("agentUi", message);
		return metadata;
	}

	public static String fingerprint(Map<String, Object> arguments) {
		try {
			String json = CANONICAL.writeValueAsString(canonicalize(arguments == null ? Map.of() : arguments));
			String digest = SecureUtil.sha256(json);
			return digest.substring(0, Math.min(FINGERPRINT_LENGTH, digest.length()));
		}
		catch (Exception ex) {
			String digest = SecureUtil.sha256("");
			return digest.substring(0, Math.min(FINGERPRINT_LENGTH, digest.length()));
		}
	}

	public static Instant expiresAt(Instant now) {
		Instant base = now == null ? Instant.now() : now;
		return base.plus(CONFIRM_TTL);
	}

	private static Map<String, Object> binding(String toolCallId, String fingerprint, String replyId, String toolName) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("toolCallId", toolCallId);
		payload.put("paramFingerprint", fingerprint);
		payload.put("replyId", replyId);
		payload.put("toolName", toolName);
		return payload;
	}

	private static Object canonicalize(Object value) {
		if (value instanceof Map<?, ?> map) {
			TreeMap<String, Object> sorted = new TreeMap<>();
			map.forEach((key, nested) -> {
				if (key != null) {
					sorted.put(String.valueOf(key).trim(), canonicalize(nested));
				}
			});
			return sorted;
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			for (Object item : list) {
				copy.add(canonicalize(item));
			}
			return copy;
		}
		if (value instanceof String text) {
			return text.trim();
		}
		return value;
	}

	private static String preview(Map<String, Object> arguments) {
		if (arguments == null || arguments.isEmpty()) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		arguments.forEach((key, value) -> {
			if (!StringUtils.hasText(key) || SECRET_KEY.matcher(key).find()) {
				return;
			}
			if (text.length() > 0) {
				text.append("，");
			}
			String rendered = value == null ? "" : String.valueOf(value);
			if (rendered.length() > PREVIEW_VALUE_MAX) {
				rendered = rendered.substring(0, PREVIEW_VALUE_MAX) + "…";
			}
			text.append(key).append("=").append(rendered);
		});
		if (text.length() > PREVIEW_MAX) {
			return text.substring(0, PREVIEW_MAX) + "…";
		}
		return text.toString();
	}

}
