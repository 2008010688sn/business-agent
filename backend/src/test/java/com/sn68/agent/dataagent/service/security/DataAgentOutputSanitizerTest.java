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
package com.sn68.agent.dataagent.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.SemanticHitView;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.ToolStepView;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentOutputSanitizerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final DataAgentOutputSanitizer sanitizer = new DataAgentOutputSanitizer(objectMapper);

	@Test
	void sanitizeTextHidesSqlDatasourceAndPhysicalFields() {
		String text = """
				```sql
				select amount from bill_cost where id = 1
				```
				internal_ds 中 bill_cost.amount 异常，semantic_model.search 已命中。
				""";

		String sanitized = sanitizer.sanitizeText(text, explain());

		assertFalse(sanitized.toLowerCase().contains("select"));
		assertFalse(sanitized.contains("bill_cost"));
		assertFalse(sanitized.contains("internal_ds"));
		assertFalse(sanitized.contains("semantic_model.search"));
		assertTrue(sanitized.contains("账单金额") || sanitized.contains("业务指标") || sanitized.contains("金额"));
	}

	@Test
	void sanitizeTextReplacesKnownPhysicalColumnsAndKeepsCustomerCodes() {
		String text = "company_name 为 API_TEST_11_24，total_amount 为 9956。\n```json\n{\"company_name\":\"x\"}\n```";

		String sanitized = sanitizer.sanitizeText(text, explain());

		assertTrue(sanitized.contains("API_TEST_11_24"));
		assertTrue(sanitized.contains("客户名称"));
		assertTrue(sanitized.contains("总金额"));
		assertFalse(sanitized.contains("company_name"));
		assertFalse(sanitized.contains("total_amount"));
		assertFalse(sanitized.contains("```json"));
	}

	@Test
	void sanitizeTextPreservingEchartsKeepsFromDimensionInChartJson() {
		String text = """
				## 可视化图表
				```echarts
				{"xAxis":{"data":["from","join"]},"series":[{"type":"bar","data":[10,8]}]}
				```
				""";

		String sanitized = sanitizer.sanitizeTextPreservingEcharts(text, explain());

		assertTrue(sanitized.contains("```echarts"));
		assertTrue(sanitized.contains("\"from\""));
		assertTrue(sanitized.contains("\"join\""));
	}

	@Test
	void sanitizeResultSetJsonRenamesColumnsAndRows() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["amount", "created_at", "customer_no"],
				    "data": [
				      {"amount": "12.30", "created_at": "2026-06-01", "customer_no": "C001"}
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, explain());
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		assertEquals("账单金额", root.path("column").get(0).asText());
		assertFalse(root.path("column").toString().contains("created_at"));
		assertFalse(root.path("column").toString().contains("customer_no"));
		assertTrue(root.path("data").get(0).has("账单金额"));
		assertFalse(root.path("data").get(0).has("amount"));
	}

	@Test
	void sanitizeResultSetJsonUsesBusinessAliasesAndColumnComments() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["project_id", "项目名称", "bill_amount", "approval_status"],
				    "data": [
				      {
				        "project_id": "P001",
				        "项目名称": "测试项目",
				        "bill_amount": "33700.00",
				        "approval_status": "待项目组审批"
				      }
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, explainWithColumnComments());
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		assertEquals("项目编号", root.path("column").get(0).asText());
		assertEquals("项目名称", root.path("column").get(1).asText());
		assertEquals("账单金额", root.path("column").get(2).asText());
		assertEquals("审批状态", root.path("column").get(3).asText());
		assertTrue(root.path("data").get(0).has("项目编号"));
		assertTrue(root.path("data").get(0).has("项目名称"));
		assertTrue(root.path("data").get(0).has("账单金额"));
		assertTrue(root.path("data").get(0).has("审批状态"));
		assertFalse(sanitized.contains("project_id"));
		assertFalse(sanitized.contains("bill_amount"));
		assertFalse(sanitized.contains("approval_status"));
	}

	@Test
	void sanitizeTextKeepsBusinessWordsWhenHidingPhysicalNames() {
		String text = "2026年6月共有3个项目，project_id 对应项目编号，bill_cost 表不展示。";

		String sanitized = sanitizer.sanitizeText(text, explainWithColumnComments());

		assertTrue(sanitized.contains("3个项目"));
		assertTrue(sanitized.contains("项目编号"));
		assertFalse(sanitized.contains("project_id"));
		assertFalse(sanitized.contains("bill_cost"));
	}

	@Test
	void sanitizeTextHidesPhysicalTableNamesEvenWithoutExplain() {
		String sanitized = sanitizer.sanitizeText("已确认 `bill_cost` 表结构并编写好查询逻辑", null);

		assertFalse(sanitized.contains("bill_cost"));
		assertTrue(sanitized.contains("业务对象"));
	}

	@Test
	void sanitizeResultSetJsonHidesEnglishAliases() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["name", "metric"],
				    "data": [
				      {"name": "客户A", "metric": "500"}
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, null);
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		assertEquals("客户名称", root.path("column").get(0).asText());
		assertEquals("指标", root.path("column").get(1).asText());
		assertFalse(sanitized.contains("\"name\""));
		assertFalse(sanitized.contains("\"metric\""));
	}

	@Test
	void sanitizeResultSetJsonRewritesPlaceholderColumnsUsingSql() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["指标1", "指标2"],
				    "data": [
				      {"指标1": "10", "指标2": "20"}
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, AnswerTraceExplainView.builder()
			.sql("SELECT AVG(stay_days) AS m1, SUM(qty) AS m2 FROM stock")
			.semanticHits(List.of(
					SemanticHitView.builder().tableName("stock").columnName("stay_days").businessName("在库天数").build(),
					SemanticHitView.builder().tableName("stock").columnName("qty").businessName("数量").build()))
			.build());
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		assertEquals("平均在库天数", root.path("column").get(0).asText());
		assertEquals("总数量", root.path("column").get(1).asText());
		assertFalse(sanitized.contains("指标1"));
		assertFalse(sanitized.contains("指标2"));
	}

	@Test
	void sanitizeResultSetJsonResolvesMixedChineseAndPhysicalColumns() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["客户名称", "sign_total"],
				    "data": [
				      {"客户名称": "客户A", "sign_total": 3973}
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, null);
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		assertEquals("客户名称", root.path("column").get(0).asText());
		assertEquals("总签收数", root.path("column").get(1).asText());
		assertTrue(root.path("data").get(0).has("总签收数"));
		assertEquals(3973, root.path("data").get(0).path("总签收数").asInt());
		assertFalse(sanitized.contains("sign_total"));
	}

	@Test
	void sanitizeResultSetJsonKeepsFallbackColumnNamesUnique() throws Exception {
		String json = """
				{
				  "resultSet": {
				    "column": ["zz_metric_a", "zz_metric_b"],
				    "data": [
				      {"zz_metric_a": "1", "zz_metric_b": "2"}
				    ]
				  }
				}
				""";

		String sanitized = sanitizer.sanitizeResultSetJson(json, null);
		JsonNode root = objectMapper.readTree(sanitized).path("resultSet");

		String first = root.path("column").get(0).asText();
		String second = root.path("column").get(1).asText();
		assertEquals("分类", first);
		assertEquals("分类2", second);
		assertTrue(root.path("data").get(0).has(first));
		assertTrue(root.path("data").get(0).has(second));
		assertFalse(sanitized.contains("zz_metric_a"));
		assertFalse(sanitized.contains("zz_metric_b"));
	}

	@Test
	void sanitizeMetadataKeepsOnlySafeLongTermMemoryFields() {
		Map<String, Object> metadata = Map.of("contentFormat", "markdown", "unsafeField", "discard-me",
				"longTermMemory", Map.of("referencedCount", 1, "estimatedTokens", 20, "hits",
						List.of(Map.of("type", "answer", "summary", "bill_cost.amount from internal_ds",
								"similarity", 0.91, "injected", true))));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		String text = sanitized.toString();
		assertFalse(text.contains("bill_cost"));
		assertFalse(text.contains("internal_ds"));
		assertFalse(text.contains("unsafeField"));
		assertEquals("markdown", sanitized.get("contentFormat"));
		assertTrue(text.contains("账单金额") || text.contains("业务指标") || text.contains("金额"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataKeepsSafeSuggestedRepliesAndHidesSensitiveText() {
		Map<String, Object> metadata = Map.of("suggestedReplies",
				suggestedReplies("query-clarify", List.of(suggestedReplyGroup("group-1", 1,
						List.of(suggestedReplyOption("option-1", "select amount from bill_cost", "bill_cost.amount from internal_ds"))))));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		Map<String, Object> replies = (Map<String, Object>) sanitized.get("suggestedReplies");
		assertEquals("suggested-replies/v1", replies.get("schemaVersion"));
		assertEquals("query-clarify", replies.get("source"));
		List<Map<String, Object>> groups = (List<Map<String, Object>>) replies.get("groups");
		List<Map<String, Object>> options = (List<Map<String, Object>>) groups.get(0).get("options");
		String text = options.get(0).toString();
		assertFalse(text.toLowerCase().contains("select"));
		assertFalse(text.contains("bill_cost"));
		assertFalse(text.contains("internal_ds"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeAgentResponsePreservesBusinessClarificationSafeShape() {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("id", "o1");
		option.put("label", "按月查看");
		option.put("description", "查看本月账单");
		option.put("targetId", "internal-option-target");
		option.put("selection", Map.of("routeArtifactId", "internal-option-artifact"));
		option.put("value", "internal-option-value");
		option.put("arbitraryNested", Map.of("executionRefId", "internal-option-execution"));
		Map<String, Object> clarification = new LinkedHashMap<>();
		clarification.put("schemaVersion", "business-clarify/v1");
		clarification.put("clarificationId", "clarify-token");
		clarification.put("title", "需要补充信息");
		clarification.put("prompt", "请选择账单查询范围");
		clarification.put("options", List.of(option));
		clarification.put("allowFreeText", true);
		clarification.put("riskLevel", "MEDIUM");
		clarification.put("expiresAt", "2026-08-01T12:00:00Z");
		clarification.put("targetId", "internal-target");
		clarification.put("targetVersionId", "internal-version");
		clarification.put("routeArtifactId", "internal-artifact");
		clarification.put("routeProfileId", "internal-profile");
		clarification.put("executionRefId", "internal-execution");
		clarification.put("selection", Map.of("targetId", "internal-selection"));
		clarification.put("value", "internal-value");
		clarification.put("arbitraryNested", Map.of("targetId", "internal-nested-target"));
		AgentResponse response = AgentResponse.builder()
			.agentId("1")
			.threadId("100")
			.nodeName("AgentScopeRuntime")
			.textType(TextType.TEXT)
			.text("请选择账单查询范围")
			.metadata(Map.of("contentFormat", "markdown", "businessClarification", clarification,
					"unsafeField", "discard-me"))
			.build();

		AgentResponse sanitized = sanitizer.sanitizeAgentResponse(response, explain());

		assertTrue(sanitized.getMetadata().containsKey("businessClarification"));
		Map<String, Object> safeClarification =
				(Map<String, Object>) sanitized.getMetadata().get("businessClarification");
		assertEquals(Set.of("schemaVersion", "clarificationId", "title", "prompt", "options", "allowFreeText",
				"expiresAt"), safeClarification.keySet());
		Map<String, Object> safeOption = (Map<String, Object>) ((List<?>) safeClarification.get("options")).get(0);
		assertEquals(Map.of("id", "o1", "label", "按月查看", "description", "查看本月账单"), safeOption);
		assertEquals("markdown", sanitized.getMetadata().get("contentFormat"));
		assertFalse(sanitized.getMetadata().containsKey("unsafeField"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeAgentResponsePreservesConfirmationSafeShape() {
		Map<String, Object> confirmation = new LinkedHashMap<>();
		confirmation.put("schemaVersion", "confirm/v1");
		confirmation.put("clarificationId", "confirm-token");
		confirmation.put("title", "确认执行");
		confirmation.put("prompt", "是否继续执行？");
		confirmation.put("options", List.of(Map.of("id", "confirm", "label", "确认", "description", "继续执行",
				"selection", "internal-selection", "value", "internal-value")));
		confirmation.put("allowFreeText", false);
		confirmation.put("riskLevel", "HIGH");
		confirmation.put("expiresAt", "2026-08-01T12:00:00Z");
		confirmation.put("executionRefId", "internal-execution");
		confirmation.put("selection", "internal-selection");
		confirmation.put("value", "internal-value");
		AgentResponse response = AgentResponse.builder()
			.agentId("1")
			.threadId("100")
			.nodeName("AgentScopeRuntime")
			.textType(TextType.TEXT)
			.text("是否继续执行？")
			.metadata(Map.of("confirmation", confirmation))
			.build();

		AgentResponse sanitized = sanitizer.sanitizeAgentResponse(response, explain());

		assertNotNull(sanitized.getMetadata());
		assertTrue(sanitized.getMetadata().containsKey("confirmation"));
		Map<String, Object> safeConfirmation = (Map<String, Object>) sanitized.getMetadata().get("confirmation");
		assertEquals(Set.of("schemaVersion", "clarificationId", "title", "prompt", "options", "allowFreeText",
				"riskLevel", "expiresAt"), safeConfirmation.keySet());
		Map<String, Object> safeOption = (Map<String, Object>) ((List<?>) safeConfirmation.get("options")).get(0);
		assertEquals(Map.of("id", "confirm", "label", "确认", "description", "继续执行"), safeOption);
	}

	@Test
	void sanitizeMetadataSuppressesLegacyClarifyFieldsWhenBusinessClarificationExists() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("businessClarification", interactionWrapper("business-clarify/v1", "clarify-coexist"));
		metadata.put("clarifyRequired", true);
		metadata.put("riskLevel", "HIGH");
		metadata.put("missingDimensions", List.of("时间范围"));
		metadata.put("followUpQuestions", List.of("要查看哪个月？"));
		metadata.put("suggestedAssumptions", List.of("默认查看本月"));
		metadata.put("summary", "需要补充时间范围");
		metadata.put("suggestedReplies", suggestedReplies("query-clarify", List.of(suggestedReplyGroup("legacy", 1,
				List.of(suggestedReplyOption("legacy-option", "旧版选项", "legacy-value"))))));
		metadata.put("contentFormat", "markdown");

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		assertEquals(Set.of("businessClarification", "contentFormat"), sanitized.keySet());
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataAllowsOnlyVisibleConfirmationPlanFields() {
		Map<String, Object> confirmation = interactionWrapper("confirm/v1", "confirm-plan-token");
		confirmation.put("summary", "先查询客户，再更新状态。");
		confirmation.put("planSteps", List.of(Map.of("name", "步骤 1", "description", "查询客户", "riskLevel", "READ_ONLY",
				"routeProfileId", "internal-profile", "selection", "internal-selection")));
		confirmation.put("continuation", "server-only");
		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(Map.of("confirmation", confirmation), explain());

		Map<String, Object> safeConfirmation = (Map<String, Object>) sanitized.get("confirmation");
		assertEquals("先查询客户，再更新状态。", safeConfirmation.get("summary"));
		assertEquals(List.of(Map.of("name", "步骤 1", "description", "查询客户", "riskLevel", "READ_ONLY")),
				safeConfirmation.get("planSteps"));
		assertFalse(safeConfirmation.toString().contains("internal-profile"));
		assertFalse(safeConfirmation.toString().contains("server-only"));
	}

	@Test
	void sanitizeMetadataDropsBothInteractionWrappersWhenTheyCoexist() {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("businessClarification", interactionWrapper("business-clarify/v1", "clarify-token"));
		metadata.put("confirmation", interactionWrapper("confirm/v1", "confirm-token"));
		metadata.put("contentFormat", "markdown");
		metadata.put("unsafeField", "discard-me");

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		assertEquals(Set.of("contentFormat"), sanitized.keySet());
	}

	@Test
	void sanitizeMetadataDropsLegacyRouteClarificationTargetPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetType", "SKILL");
		payload.put("targetId", "2082710652612034561");
		payload.put("targetVersionId", "2082763316574044161");
		payload.put("routeArtifactId", "2082763316574044162");
		payload.put("routeProfileId", "2082034760929153025");
		Map<String, Object> option = suggestedReplyOption("route-1", "财务数据", "财务数据");
		option.put("payload", payload);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("originalQuery", "这个月各项目的账单情况");
		metadata.put("suggestedReplies", suggestedReplies("route-clarify",
				List.of(suggestedReplyGroup("route-target", 1, List.of(option)))));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		assertEquals("这个月各项目的账单情况", sanitized.get("originalQuery"));
		assertFalse(sanitized.containsKey("suggestedReplies"));
		assertFalse(sanitized.toString().contains("2082710652612034561"));
	}

	@Test
	void sanitizeMetadataDropsInvalidSuggestedReplies() {
		Map<String, Object> metadata = Map.of("suggestedReplies",
				suggestedReplies("frontend-guess", List.of(suggestedReplyGroup("group-1", 2,
						List.of(suggestedReplyOption("option-1", "A", "A"))))));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		assertNull(sanitized);
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataSanitizesSkillFlowRecordPayload() {
		AgentUiMessage uiMessage = new AgentUiMessage("agent-ui/v2", "skill-flow", "runtime-1",
				new AgentUiMessage.Source("1", "demand-create", "10", "confirm"),
				new AgentUiMessage.Content("markdown", "select amount from bill_cost"),
				new AgentUiMessage.Payload("CONFIRM",
						Map.of("data", Map.of("amount", "12.30", "companyId", "C-1")), List.of()),
				List.of(new AgentUiMessage.Action("select-option", "SELECT", "选择项目", "P-1",
						Map.of("rawData", Map.of("twoProjectId", "P-1", "twoProjectName", "云南万绿")))),
				new AgentUiMessage.Timing("FLOW_WAITING", 10L));
		Map<String, Object> metadata = Map.of("uiSchemaVersion", "agent-ui/v2", "messageType", "skill-flow",
				"agentUi", uiMessage);

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		Map<String, Object> safeUi = (Map<String, Object>) sanitized.get("agentUi");
		Map<String, Object> content = (Map<String, Object>) safeUi.get("content");
		assertFalse(String.valueOf(content.get("text")).toLowerCase().contains("select"));
		assertFalse(String.valueOf(content.get("text")).contains("bill_cost"));
		assertTrue(safeUi.toString().contains("amount"));
		assertEquals("runtime-1", safeUi.get("runtimeRequestId"));
		Map<String, Object> source = (Map<String, Object>) safeUi.get("source");
		assertEquals(Map.of("flowInstanceId", "10"), source);
		assertFalse(source.containsKey("agentId"));
		assertFalse(source.containsKey("skillCode"));
		assertFalse(source.containsKey("nodeId"));
		Map<String, Object> action = (Map<String, Object>) ((List<?>) safeUi.get("actions")).get(0);
		assertEquals("SELECT", action.get("type"));
		assertEquals("select-option", action.get("actionId"));
		assertFalse(action.containsKey("value"));
		assertFalse(safeUi.toString().contains("P-1"));
		assertFalse(safeUi.toString().contains("C-1"));
		assertFalse(safeUi.toString().contains("twoProjectId"));
		assertFalse(safeUi.toString().contains("rawData"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataPreservesReviewAndCancellationControlActions() {
		List<String> actionTypes = List.of("REVIEW", "SUBMIT", "EDIT", "CLEAR_REFERENCE", "CANCEL",
				"CONFIRM_CANCEL", "KEEP_FLOW", "CONFIRM");
		List<AgentUiMessage.Action> actions = actionTypes.stream()
				.map(type -> new AgentUiMessage.Action(type.toLowerCase(), type, "操作", true, Map.of()))
				.toList();
		AgentUiMessage uiMessage = new AgentUiMessage("agent-ui/v2", "skill-flow", "runtime-review",
				new AgentUiMessage.Source("1", "demand-create", "20", "review-history"),
				new AgentUiMessage.Content("markdown", "请复核下单草稿"),
				new AgentUiMessage.Payload("REVIEW", Map.of("reviewedRevision", 3), List.of()), actions,
				new AgentUiMessage.Timing("FLOW_WAITING", 20L));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(Map.of("uiSchemaVersion", "agent-ui/v2",
				"messageType", "skill-flow", "agentUi", uiMessage), explain());

		Map<String, Object> safeUi = (Map<String, Object>) sanitized.get("agentUi");
		List<Map<String, Object>> safeActions = (List<Map<String, Object>>) safeUi.get("actions");
		assertEquals(actionTypes, safeActions.stream().map(action -> String.valueOf(action.get("type"))).toList());
		assertEquals("REVIEW", ((Map<String, Object>) safeUi.get("payload")).get("action"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataSanitizesSkillFlowStepsLabelsAndKeepsStructure() {
		AgentUiMessage uiMessage = new AgentUiMessage("agent-ui/v2", "skill-flow", "runtime-steps",
				new AgentUiMessage.Source("1", "demand-create", "30", "confirm"),
				new AgentUiMessage.Content("markdown", "请确认下单信息"),
				new AgentUiMessage.Payload("CONFIRM", Map.of(), List.of()), List.of(),
				new AgentUiMessage.Timing("FLOW_WAITING", 30L),
				List.of(
						new AgentUiMessage.Step("tool", "查询 select amount from bill_cost 明细", "success", 12L),
						new AgentUiMessage.Step("node", "字段校验", "success", 8L),
						new AgentUiMessage.Step("state", "CONFIRM", "waiting", null)));
		Map<String, Object> metadata = Map.of("uiSchemaVersion", "agent-ui/v2", "messageType", "skill-flow",
				"agentUi", uiMessage);

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		Map<String, Object> safeUi = (Map<String, Object>) sanitized.get("agentUi");
		List<Map<String, Object>> safeSteps = (List<Map<String, Object>>) safeUi.get("steps");
		// steps 结构保留：条目数与 kind/status/durationMs 控制值原样透传，不被业务 ID 剥离或丢弃
		assertEquals(3, safeSteps.size());
		Map<String, Object> toolStep = safeSteps.get(0);
		assertEquals("tool", toolStep.get("kind"));
		assertEquals("success", toolStep.get("status"));
		assertEquals(12L, toolStep.get("durationMs"));
		// label 走文本清洗：SQL 片段与物理表名不允许外露
		String toolLabel = String.valueOf(toolStep.get("label"));
		assertFalse(toolLabel.toLowerCase().contains("select"));
		assertFalse(toolLabel.contains("bill_cost"));
		assertTrue(toolLabel.contains("内部查询"));
		assertEquals("node", safeSteps.get(1).get("kind"));
		assertEquals("字段校验", safeSteps.get(1).get("label"));
		Map<String, Object> stateStep = safeSteps.get(2);
		assertEquals("state", stateStep.get("kind"));
		assertEquals("CONFIRM", stateStep.get("label"));
		assertNull(stateStep.get("durationMs"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataLimitsSuggestedReplyGroupsAndOptions() {
		List<Map<String, Object>> groups = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			List<Map<String, Object>> options = new ArrayList<>();
			for (int j = 0; j < 25; j++) {
				options.add(suggestedReplyOption("option-" + i + "-" + j, "选项" + j, "选项" + j));
			}
			groups.add(suggestedReplyGroup("group-" + i, 1, options));
		}
		Map<String, Object> metadata = Map.of("suggestedReplies", suggestedReplies("agent-runtime", groups));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		Map<String, Object> replies = (Map<String, Object>) sanitized.get("suggestedReplies");
		List<Map<String, Object>> safeGroups = (List<Map<String, Object>>) replies.get("groups");
		assertEquals(4, safeGroups.size());
		assertEquals(20, ((List<?>) safeGroups.get(0).get("options")).size());
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataKeepsSuggestedReplyDisplayHints() {
		Map<String, Object> group = suggestedReplyGroup("candidate-records", 1,
				List.of(suggestedReplyOption("candidate-1", "D-001", "我选择：D-001")));
		group.put("displayMode", "table");
		group.put("selectionMode", "single");
		group.put("candidateCount", 4);
		group.put("rowFields", List.of(Map.of("fieldCode", "record", "label", "记录"),
				Map.of("fieldCode", "field1", "label", "select amount from bill_cost")));
		Map<String, Object> replies = suggestedReplies("agent-runtime", List.of(group));
		replies.put("displayMode", "table");
		Map<String, Object> metadata = Map.of("suggestedReplies", replies);

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(metadata, explain());

		Map<String, Object> safeReplies = (Map<String, Object>) sanitized.get("suggestedReplies");
		assertEquals("table", safeReplies.get("displayMode"));
		List<Map<String, Object>> safeGroups = (List<Map<String, Object>>) safeReplies.get("groups");
		assertEquals("table", safeGroups.get(0).get("displayMode"));
		assertEquals("single", safeGroups.get(0).get("selectionMode"));
		assertEquals(4, safeGroups.get(0).get("candidateCount"));
		assertFalse(safeGroups.get(0).toString().contains("bill_cost"));
		assertFalse(safeGroups.get(0).toString().toLowerCase().contains("select amount"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataKeepsNarrowRequiredSuggestedRepliesWithoutOptions() {
		Map<String, Object> group = suggestedReplyGroup("candidate-records", 1, List.of());
		group.put("displayMode", "narrow_required");
		group.put("candidateCount", 12);
		Map<String, Object> replies = suggestedReplies("agent-runtime", List.of(group));
		replies.put("displayMode", "narrow_required");

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(Map.of("suggestedReplies", replies), explain());

		Map<String, Object> safeReplies = (Map<String, Object>) sanitized.get("suggestedReplies");
		List<Map<String, Object>> safeGroups = (List<Map<String, Object>>) safeReplies.get("groups");
		assertEquals("narrow_required", safeGroups.get(0).get("displayMode"));
		assertTrue(((List<?>) safeGroups.get(0).get("options")).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	void sanitizeMetadataKeepsSelectionTokenAndDropsInternalCandidatePayload() {
		Map<String, Object> option = suggestedReplyOption("sel_token", "客户A", "我选择：客户A");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("action", "candidate_select");
		payload.put("selectionId", "sel_token");
		payload.put("selectionToken", "sel_token");
		payload.put("companyId", "C-001");
		payload.put("twoProjectId", "P-002");
		payload.put("resourceKey", "demo.echo.customerOptions");
		payload.put("rawData", Map.of("companyId", "C-001"));
		payload.put("fillParameters", Map.of("companyId", "C-001"));
		payload.put("currentParameters", Map.of("companyName", "客户A"));
		payload.put("recordValue", "C-001");
		payload.put("displayText", "我选择：客户A");
		option.put("payload", payload);
		Map<String, Object> group = suggestedReplyGroup("candidate-records", 1, List.of(option));
		group.put("resourceKey", "demo.echo.customerOptions");
		Map<String, Object> replies = suggestedReplies("agent-runtime", List.of(group));

		Map<String, Object> sanitized = sanitizer.sanitizeMetadata(Map.of("suggestedReplies", replies), explain());

		Map<String, Object> safeReplies = (Map<String, Object>) sanitized.get("suggestedReplies");
		List<Map<String, Object>> safeGroups = (List<Map<String, Object>>) safeReplies.get("groups");
		assertFalse(safeGroups.get(0).containsKey("resourceKey"));
		Map<String, Object> safeOption = (Map<String, Object>) ((List<?>) safeGroups.get(0).get("options")).get(0);
		Map<String, Object> safePayload = (Map<String, Object>) safeOption.get("payload");
		assertEquals("sel_token", safePayload.get("selectionId"));
		assertEquals("sel_token", safePayload.get("selectionToken"));
		assertFalse(safePayload.containsKey("companyId"));
		assertFalse(safePayload.containsKey("twoProjectId"));
		assertFalse(safePayload.containsKey("resourceKey"));
		assertFalse(safePayload.containsKey("rawData"));
		assertFalse(safePayload.containsKey("fillParameters"));
		assertFalse(safePayload.containsKey("currentParameters"));
		assertFalse(safePayload.containsKey("recordValue"));
	}

	@Test
	void sanitizeAgentResponsePreservesOnlySupportedContentFormat() {
		AgentResponse response = AgentResponse.builder()
			.agentId("1")
			.threadId("100")
			.nodeName("AgentScopeRuntime")
			.textType(TextType.TEXT)
			.text("**确认信息**")
			.metadata(Map.of("contentFormat", "markdown", "unsafeField", "discard-me"))
			.build();

		AgentResponse sanitized = sanitizer.sanitizeAgentResponse(response, explain());

		assertEquals("markdown", sanitized.getMetadata().get("contentFormat"));
		assertFalse(sanitized.getMetadata().containsKey("unsafeField"));
	}

	@Test
	void sanitizeAgentResponseDropsUnsupportedContentFormat() {
		AgentResponse response = AgentResponse.builder()
			.agentId("1")
			.threadId("100")
			.nodeName("AgentScopeRuntime")
			.textType(TextType.TEXT)
			.text("<strong>untrusted</strong>")
			.metadata(Map.of("contentFormat", "legacy-html"))
			.build();

		AgentResponse sanitized = sanitizer.sanitizeAgentResponse(response, explain());

		assertNull(sanitized.getMetadata());
	}

	@Test
	void sanitizeAgentResponseHandlesResultSetTextType() {
		AgentResponse response = AgentResponse.builder()
			.agentId("1")
			.threadId("100")
			.nodeName("DatasourceExplorerNode")
			.textType(TextType.RESULT_SET)
			.text("{\"columns\":[{\"name\":\"amount\"}],\"rows\":[{\"amount\":\"9\"}],\"sql\":\"select amount from bill_cost\"}")
			.build();

		AgentResponse sanitized = sanitizer.sanitizeAgentResponse(response, explain());

		assertFalse(sanitized.getText().contains("bill_cost"));
		assertFalse(sanitized.getText().contains("select"));
		assertFalse(sanitized.getText().contains("amount"));
		assertTrue(sanitized.getText().contains("账单金额"));
	}

	@Test
	void sanitizeTextKeepsBusinessFailureMessage() {
		String message = "创建需求单失败：下单信息不完整或格式不正确 missingFields=[\"arrivalTime\"] invalidFields=[\"arrivalTime\"]";

		String sanitized = sanitizer.sanitizeText(message, businessFailureExplain(message));

		assertTrue(sanitized.contains("创建需求单失败"));
		assertTrue(sanitized.contains("arrivalTime"));
		assertFalse(sanitized.contains("内部处理细节"));
		assertFalse(sanitized.contains("内部处理异常"));
	}

	@Test
	void redactForThinkingReplacesInternalToolNamesAndPhysicalFields() {
		String text = "调用 datasource_skill_search 查询 dis_order_product 的 freight_fee，并核对 settlement_time 与 total_incl_tax";

		String redacted = sanitizer.redactForThinking(text, thinkingExplain());

		assertFalse(redacted.contains("datasource_skill_search"));
		assertTrue(redacted.contains("查询数据"));
		assertFalse(redacted.contains("dis_order_product"));
		assertTrue(redacted.contains("业务对象"));
		assertFalse(redacted.contains("freight_fee"));
		assertTrue(redacted.contains("运费"));
		assertFalse(redacted.contains("settlement_time"));
		assertFalse(redacted.contains("total_incl_tax"));
	}

	@Test
	void redactForThinkingKeepsOrdinaryWordsWithoutForbiddenTermSweep() {
		String text = "先做 sql 校验，再核对 schema 后汇总";

		String redacted = sanitizer.redactForThinking(text, thinkingExplain());

		// 与答案级 sanitizeText 的差异：思考快照不做 FORBIDDEN_TERMS 全量替换，普通表述不会被换成"内部处理"。
		assertEquals(text, redacted);
	}

	private AnswerTraceExplainView thinkingExplain() {
		return AnswerTraceExplainView.builder()
			.datasource("internal_ds")
			.sql("select freight_fee from dis_order_product")
			.usedTables(List.of("dis_order_product"))
			.usedColumns(List.of("freight_fee", "settlement_time", "total_incl_tax", "sign_num"))
			.semanticHits(List.of(SemanticHitView.builder()
				.tableName("dis_order_product")
				.columnName("freight_fee")
				.businessName("运费")
				.build()))
			.build();
	}

	private AnswerTraceExplainView explain() {
		return AnswerTraceExplainView.builder()
			.datasource("internal_ds")
			.sql("select amount from bill_cost where id = 1")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("amount", "created_at", "customer_no"))
			.semanticHits(List.of(SemanticHitView.builder()
				.tableName("bill_cost")
				.columnName("amount")
				.businessName("账单金额")
				.build()))
			.toolSteps(List.of(ToolStepView.builder()
				.toolName("semantic_model.search")
				.detail("select amount from bill_cost")
				.datasource("internal_ds")
				.build()))
			.build();
	}

	private Map<String, Object> suggestedReplies(String source, List<Map<String, Object>> groups) {
		Map<String, Object> replies = new LinkedHashMap<>();
		replies.put("schemaVersion", "suggested-replies/v1");
		replies.put("source", source);
		replies.put("submitMode", "confirm");
		replies.put("groups", groups);
		return replies;
	}

	private Map<String, Object> suggestedReplyGroup(String groupId, int maxSelect,
			List<Map<String, Object>> options) {
		Map<String, Object> group = new LinkedHashMap<>();
		group.put("groupId", groupId);
		group.put("title", "候选组");
		group.put("required", false);
		group.put("maxSelect", maxSelect);
		group.put("options", options);
		return group;
	}

	private Map<String, Object> suggestedReplyOption(String optionId, String label, String value) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("optionId", optionId);
		option.put("label", label);
		option.put("value", value);
		option.put("summary", label);
		option.put("payload", Map.of("raw", value));
		return option;
	}

	private Map<String, Object> interactionWrapper(String schemaVersion, String clarificationId) {
		Map<String, Object> interaction = new LinkedHashMap<>();
		interaction.put("schemaVersion", schemaVersion);
		interaction.put("clarificationId", clarificationId);
		interaction.put("title", "需要用户确认");
		interaction.put("prompt", "请选择下一步操作");
		interaction.put("options", List.of(Map.of("id", "o1", "label", "继续", "description", "继续执行")));
		interaction.put("allowFreeText", false);
		interaction.put("riskLevel", "HIGH");
		interaction.put("expiresAt", "2026-08-01T12:00:00Z");
		return interaction;
	}

	private AnswerTraceExplainView explainWithColumnComments() {
		return AnswerTraceExplainView.builder()
			.datasource("internal_ds")
			.sql("select project_id, bill_amount, approval_status from bill_cost")
			.usedTables(List.of("bill_cost"))
			.usedColumns(List.of("project_id", "项目名称", "bill_amount", "approval_status"))
			.semanticHits(List.of(
					SemanticHitView.builder()
						.tableName("bill_cost")
						.columnName("project_id")
						.columnComment("项目编号")
						.build(),
					SemanticHitView.builder()
						.tableName("bill_cost")
						.columnName("bill_amount")
						.businessName("账单金额")
						.build(),
					SemanticHitView.builder()
						.tableName("bill_cost")
						.columnName("approval_status")
						.columnComment("审批状态")
						.build()))
			.build();
	}

	private AnswerTraceExplainView businessFailureExplain(String message) {
		return AnswerTraceExplainView.builder()
			.toolSteps(List.of(ToolStepView.builder()
				.toolName("skill.demand_create.execute")
				.detail(message)
				.errorCode("BUSINESS_FAILED")
				.errorMessage(message)
				.build()))
			.build();
	}

}
