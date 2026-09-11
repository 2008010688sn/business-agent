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

import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolConfirmUiAssemblerTest {

	@Test
	void packsToolConfirmKindWithFingerprintAndAskActions() {
		Instant expiresAt = Instant.parse("2026-09-04T12:00:00Z");
		AgentUiMessage ui = ToolConfirmUiAssembler.fromToolCall("runtime-1", "reply-1", "call-9",
				"demand_create_execute", Map.of("projectName", "太阳食品"), expiresAt);

		assertEquals(AgentUiMessage.SCHEMA_VERSION, ui.schemaVersion());
		assertEquals(AgentUiMessage.KIND_TOOL_CONFIRM, ui.kind());
		assertEquals("ASK", ui.payload().action());
		assertEquals("call-9", ui.payload().values().get("toolCallId"));
		assertEquals("demand_create_execute", ui.payload().values().get("toolName"));
		assertEquals(ToolConfirmUiAssembler.fingerprint(Map.of("projectName", "太阳食品")),
				ui.payload().values().get("paramFingerprint"));
		assertEquals("2026-09-04T12:00:00Z", ui.payload().values().get("expiresAt"));
		assertTrue(String.valueOf(ui.payload().values().get("argsPreview")).contains("projectName"));
		assertTrue(ui.actions().stream().anyMatch(action -> "APPROVE".equals(action.type())));
		assertTrue(ui.actions().stream().anyMatch(action -> "DENY".equals(action.type())));
		assertEquals("TOOL_ASK", ui.timing().stageCode());
	}

	@Test
	void fingerprintIsStableAcrossKeyOrderAndWhitespace() {
		Map<String, Object> left = new LinkedHashMap<>();
		left.put("b", " 2 ");
		left.put("a", "1");
		Map<String, Object> right = new LinkedHashMap<>();
		right.put("a", "1");
		right.put("b", "2");

		assertEquals(ToolConfirmUiAssembler.fingerprint(left), ToolConfirmUiAssembler.fingerprint(right));
		assertNotEquals(ToolConfirmUiAssembler.fingerprint(Map.of("a", "1")),
				ToolConfirmUiAssembler.fingerprint(Map.of("a", "2")));
	}

	@Test
	void previewOmitsSecretKeys() {
		AgentUiMessage ui = ToolConfirmUiAssembler.fromToolCall("runtime-1", "reply-1", "call-9", "write_tool",
				Map.of("token", "secret-value", "siteName", "上海仓"), Instant.now());

		String preview = String.valueOf(ui.payload().values().get("argsPreview"));
		assertTrue(preview.contains("siteName"));
		assertFalse(preview.contains("secret-value"));
		assertFalse(preview.contains("token="));
	}

	@Test
	void metadataUsesToolConfirmMessageType() {
		AgentUiMessage ui = ToolConfirmUiAssembler.fromToolCall("runtime-1", "reply-1", "call-9", "write_tool",
				Map.of(), Instant.now());
		Map<String, Object> metadata = ToolConfirmUiAssembler.toMetadata(ui);

		assertEquals(AgentUiMessage.SCHEMA_VERSION, metadata.get("uiSchemaVersion"));
		assertEquals(AgentSessionConstant.MESSAGE_TYPE_TOOL_CONFIRM, metadata.get("messageType"));
		assertEquals(ui, metadata.get("agentUi"));
	}

}
