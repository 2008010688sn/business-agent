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
package com.sn68.agent.dataagent.service.interaction;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestedReplyProjectionServiceTest {

	private final SuggestedReplyProjectionService service = new SuggestedReplyProjectionService();

	@Test
	@SuppressWarnings("unchecked")
	void normalizeCandidateRecordsSupportsRecordsAndNestedDataRecords() {
		List<Map<String, Object>> records = service.normalizeCandidateRecords(Map.of("data",
				Map.of("records", List.of(Map.of("companyName", "云南万绿", "companyId", "C-001")))));

		assertEquals(1, records.size());
		assertEquals("云南万绿", records.get(0).get("label"));
		Map<String, Object> rawData = (Map<String, Object>) records.get(0).get("rawData");
		assertEquals("C-001", rawData.get("companyId"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void normalizeCandidateRecordsParsesMcpContentTextJsonArrayAndHidesInternalIdsFromDisplay() {
		Map<String, Object> rawResult = Map.of("mcpSuccess", true, "content",
				List.of(Map.of("type", "text", "text", """
						[
						  {
						    "companyName": "云南万绿",
						    "companyId": "C-001",
						    "oneProjectName": "项目A",
						    "oneProjectId": "P-001",
						    "displayFields": [
						      {"fieldCode":"companyId","label":"客户ID","value":"C-001"},
						      {"fieldCode":"companyName","label":"客户","value":"云南万绿"},
						      {"fieldCode":"oneProjectId","label":"项目ID","value":"P-001"},
						      {"fieldCode":"oneProjectName","label":"一级项目","value":"项目A"}
						    ]
						  }
						]
						""")));

		List<Map<String, Object>> records = service.normalizeCandidateRecords(rawResult);
		Map<String, Object> record = records.get(0);
		Map<String, Object> rawData = (Map<String, Object>) record.get("rawData");
		List<Map<String, Object>> displayFields = (List<Map<String, Object>>) record.get("displayFields");

		assertEquals("云南万绿", record.get("label"));
		assertEquals("C-001", rawData.get("companyId"));
		assertEquals("P-001", rawData.get("oneProjectId"));
		assertEquals(2, displayFields.size());
		assertFalse(displayFields.toString().contains("客户ID"));
		assertFalse(displayFields.toString().contains("项目ID"));
		assertFalse(displayFields.toString().contains("C-001"));
		assertFalse(displayFields.toString().contains("P-001"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void normalizeCandidateRecordsParsesTopLevelTextJsonObject() {
		List<Map<String, Object>> records = service.normalizeCandidateRecords(Map.of("mcpSuccess", true, "text",
				"""
						{"companyName":"华东客户","companyId":"C-002","twoProjectName":"二级项目"}
						"""));

		assertEquals(1, records.size());
		assertEquals("华东客户", records.get(0).get("label"));
		Map<String, Object> rawData = (Map<String, Object>) records.get(0).get("rawData");
		assertEquals("C-002", rawData.get("companyId"));
	}

	@Test
	void normalizeCandidateRecordsParsesTopLevelMessageJsonObject() {
		List<Map<String, Object>> records = service.normalizeCandidateRecords(Map.of("message",
				"""
						{"records":[{"demandNo":"D-001","companyName":"云南万绿"}]}
						"""));

		assertEquals(1, records.size());
		assertEquals("D-001", records.get(0).get("label"));
	}

	@Test
	void normalizeCandidateRecordsFallsBackForNonJsonText() {
		List<Map<String, Object>> records = service
			.normalizeCandidateRecords(Map.of("mcpSuccess", true, "content", List.of(Map.of("text", "纯文本结果"))));

		assertEquals(1, records.size());
		assertNotNull(records.get(0).get("rawData"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void buildCandidateRepliesKeepsOnlySelectionTokenInPublicPayload() {
		List<Map<String, Object>> records = service
			.normalizeCandidateRecords(Map.of("records", List.of(Map.of("companyName", "云南万绿", "companyId", "C-001"))));

		Map<String, Object> replies = service.buildCandidateReplies(
				SuggestedReplyProjectionService.SOURCE_AGENT_RUNTIME, "group-1", "客户候选",
				"demo.echo.customerOptions", records, Map.of("companyName", "云南万绿"),
				Map.of("agentId", "9", "sessionId", "100", "skillCode", "demand-create", "skillVersionId", 11L,
						"resourceKey", "demo.echo.customerOptions"));
		List<Map<String, Object>> groups = (List<Map<String, Object>>) replies.get("groups");
		List<Map<String, Object>> options = (List<Map<String, Object>>) groups.get(0).get("options");
		Map<String, Object> payload = (Map<String, Object>) options.get(0).get("payload");

		assertTrue(String.valueOf(payload.get("selectionToken")).startsWith("sel_"));
		assertFalse(payload.containsKey("rawData"));
		assertFalse(payload.containsKey("resourceKey"));
		assertFalse(payload.toString().contains("companyId"));
		assertFalse(payload.toString().contains("C-001"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void selectionTokenKeepsServerRawDataAndRejectsDifferentResource() {
		List<Map<String, Object>> records = service
			.normalizeCandidateRecords(Map.of("records", List.of(Map.of("companyName", "云南万绿", "companyId", "C-001"))));
		Map<String, Object> tokenContext = Map.of("agentId", "9", "sessionId", "100", "skillCode",
				"demand-create", "skillVersionId", 11L, "resourceKey", "demo.echo.customerOptions");
		Map<String, Object> replies = service.buildCandidateReplies(
				SuggestedReplyProjectionService.SOURCE_AGENT_RUNTIME, "group-1", "客户候选",
				"demo.echo.customerOptions", records, Map.of("companyName", "云南万绿"), tokenContext);
		List<Map<String, Object>> groups = (List<Map<String, Object>>) replies.get("groups");
		List<Map<String, Object>> options = (List<Map<String, Object>>) groups.get(0).get("options");
		Map<String, Object> payload = (Map<String, Object>) options.get(0).get("payload");
		String token = String.valueOf(payload.get("selectionToken"));

		Map<String, Object> rawData = service.resolveSelectionToken(token, tokenContext).orElseThrow();

		assertEquals("C-001", rawData.get("companyId"));
		assertTrue(service.resolveSelectionToken(token,
				Map.of("agentId", "9", "sessionId", "100", "skillCode", "demand-create", "skillVersionId", 11L)).isPresent());
		assertTrue(service.resolveSelectionToken(token,
				Map.of("agentId", "9", "sessionId", "100", "skillCode", "demand-create", "skillVersionId", 11L, "resourceKey",
						"demo.echo.latest"))
			.isEmpty());
		assertTrue(service.resolveSelectionToken(token,
				Map.of("agentId", "9", "sessionId", "100", "skillCode", "demand-create", "skillVersionId", 12L,
						"resourceKey", "demo.echo.customerOptions"))
			.isEmpty());
	}

}
