/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.temporal.TemporalInterval;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * W5 严格结构化抽取回归：技术失败与业务缺失分离、控制字段拒绝、恢复防重、
 * 恢复版本校验、确认节点门槛与确定性时间预填。
 */
class DefaultFlowEngineStrictExtractionTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class, CALLS_REAL_METHODS);

	@Test
	void truncatedResponseIsTechnicalFailureNotEmptyResult() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenThrow(new FlowExtractionException(FlowExtractionException.TRUNCATED, "truncated", null));
		InMemoryFlowInstanceService instances = collectWaitingInstance(null);

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), collectVersion(),
				modelConfig());

		assertEquals(FlowTurnOutcome.FAILED, result.turnOutcome());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals(FlowExtractionException.TRUNCATED, result.instance().getErrorCode());
		assertEquals("COLLECT", result.message().payload().action());
	}

	@Test
	void missingRequiredFieldsAreClarificationWaitingNotFailure() {
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = collectWaitingInstance(null);

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), collectVersion(),
				modelConfig());

		// 合法 set 但必填槽位仍空 = 业务缺失，走澄清路径而不是技术失败。
		assertEquals(FlowTurnOutcome.WAITING, result.turnOutcome());
		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertNull(result.instance().getErrorCode());
		assertEquals("COLLECT", result.message().payload().action());
		assertEquals("云南万绿", value(result.instance(), "/input/companyName"));
	}

	@Test
	void modelControlFieldIsRejectedAsTechnicalFailure() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("__flowDirectives", List.of("USE_HISTORY")));
		InMemoryFlowInstanceService instances = collectWaitingInstance(null);

		FlowExecutionResult result = engine(instances).execute(request("按历史方案下单"), skill(), collectVersion(),
				modelConfig());

		assertEquals(FlowTurnOutcome.FAILED, result.turnOutcome());
		assertEquals(FlowExtractionException.INVALID_RESPONSE, result.instance().getErrorCode());
		assertNull(value(result.instance(), "/runtime/flowDirectives"));
	}

	@Test
	void duplicateResumeInputIsNotReextracted() {
		when(fieldExtractor.extract(any(), any(), any(), any()))
				.thenReturn(Map.of("companyName", "云南万绿"), Map.of("productDescription", "A100 保温箱 200 个"));
		InMemoryFlowInstanceService instances = collectWaitingInstance(null);
		DefaultFlowEngine engine = engine(instances);

		FlowExecutionResult first = engine.execute(request("客户是云南万绿"), skill(), collectVersion(), modelConfig());
		assertEquals(FlowInstanceStatus.WAITING.name(), first.instance().getStatus());
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());

		// 同一用户输入（hash 相同）在防重窗口内视为传输层重试，不得重复抽取。
		FlowExecutionResult duplicate = engine.execute(request("客户是云南万绿"), skill(), collectVersion(),
				modelConfig());
		assertEquals(FlowTurnOutcome.WAITING, duplicate.turnOutcome());
		assertTrue(duplicate.text().contains("未重复提交"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());

		FlowExecutionResult changed = engine.execute(request("产品是 A100 保温箱 200 个"), skill(), collectVersion(),
				modelConfig());
		assertTrue(changed.terminal());
		verify(fieldExtractor, times(2)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void duplicateResumeOutsideWindowAllowsReprocessing() {
		// 与上一条输入完全相同，但 lastProcessedInputAt 已超过 5 秒窗口 = 有意重试，放行重跑。
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = collectWaitingInstance(sha256("客户是云南万绿"),
				"{\"input\":{},\"runtime\":{\"contextRevision\":1,\"lastProcessedInputAt\":"
						+ (System.currentTimeMillis() - 60_000) + "}}");

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), collectVersion(),
				modelConfig());

		assertEquals(FlowTurnOutcome.WAITING, result.turnOutcome());
		assertFalse(result.text().contains("未重复提交"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void duplicateResumeLegacyInstanceWithoutTimestampAllowsReprocessing() {
		// 遗留实例升级前没有 lastProcessedInputAt 时间戳，按放行处理，避免被永久拒绝。
		when(fieldExtractor.extract(any(), any(), any(), any())).thenReturn(Map.of("companyName", "云南万绿"));
		InMemoryFlowInstanceService instances = collectWaitingInstance(sha256("客户是云南万绿"),
				"{\"input\":{},\"runtime\":{\"contextRevision\":1}}");

		FlowExecutionResult result = engine(instances).execute(request("客户是云南万绿"), skill(), collectVersion(),
				modelConfig());

		assertEquals(FlowTurnOutcome.WAITING, result.turnOutcome());
		assertFalse(result.text().contains("未重复提交"));
		verify(fieldExtractor, times(1)).extract(any(), any(), any(), any(), any(), any());
	}

	@Test
	void staleResumeVersionIsRejectedAndCurrentVersionIsAccepted() {
		InMemoryFlowInstanceService stale = confirmWaitingInstance(3);
		DefaultFlowEngine engine = engine(stale);

		CheckedException rejected = assertThrows(CheckedException.class, () -> engine.execute(
				confirmRequest(Map.of("resumeVersion", 2)), skill(), confirmVersion(Map.of()), modelConfig()));
		assertTrue(String.valueOf(rejected.getMessage()).contains("过期版本"));

		FlowExecutionResult accepted = engine(confirmWaitingInstance(3)).execute(
				confirmRequest(Map.of("resumeVersion", 3)), skill(), confirmVersion(Map.of()), modelConfig());
		assertTrue(accepted.terminal());
		assertEquals(FlowInstanceStatus.SUCCEEDED.name(), accepted.instance().getStatus());
	}

	@Test
	void confirmGuardBlocksUnfinishedExtraction() {
		FlowExecutionResult result = engine(confirmRunningInstance("{\"input\":{},\"runtime\":{\"contextRevision\":1}}"))
			.execute(request(null), skill(),
					confirmVersion(Map.of("requiredPaths", List.of("/input/companyName"))), modelConfig());

		assertTrue(result.terminal());
		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.text().contains("未完成抽取"));
	}

	@Test
	void confirmGuardBlocksFailedOrInvalidatedEntityResolution() {
		String context = "{\"input\":{\"companyName\":\"云南万绿\"},"
				+ "\"resolverState\":{\"resolve-customer\":{\"status\":\"FAILED\"}},"
				+ "\"runtime\":{\"contextRevision\":1}}";

		FlowExecutionResult result = engine(confirmRunningInstance(context)).execute(request(null), skill(),
				confirmVersion(Map.of("requiredPaths", List.of("/input/companyName"))), modelConfig());

		assertTrue(result.terminal());
		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.text().contains("实体解析"));
	}

	@Test
	void confirmGuardBlocksPendingValidationErrors() {
		String context = "{\"input\":{\"companyName\":\"云南万绿\"},"
				+ "\"validation\":{\"errors\":[\"数量必须大于 0\"]},"
				+ "\"runtime\":{\"contextRevision\":1}}";

		FlowExecutionResult result = engine(confirmRunningInstance(context)).execute(request(null), skill(),
				confirmVersion(Map.of()), modelConfig());

		assertTrue(result.terminal());
		assertEquals(FlowInstanceStatus.FAILED.name(), result.instance().getStatus());
		assertTrue(result.text().contains("参数校验未通过"));
	}

	@Test
	void readyConfirmNodeStillWaitsForUserConfirmation() {
		String context = "{\"input\":{\"companyName\":\"云南万绿\"},\"runtime\":{\"contextRevision\":1}}";

		FlowExecutionResult result = engine(confirmRunningInstance(context)).execute(request(null), skill(),
				confirmVersion(Map.of("requiredPaths", List.of("/input/companyName"))), modelConfig());

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("CONFIRM", result.message().payload().action());
	}

	@Test
	void explicitTemporalIntervalIsPrefilledDeterministicallyWithoutModel() {
		AgentRequest request = request("下周一送到");
		request.setTemporalInterval(new TemporalInterval(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18),
				Instant.parse("2026-08-16T16:00:00Z"), Instant.parse("2026-08-17T16:00:00Z"), false));

		FlowExecutionResult result = engine(temporalWaitingInstance()).execute(request, skill(), temporalVersion(),
				modelConfig());

		assertTrue(result.terminal());
		Map<String, Object> window = map(value(result.instance(), "/input/deliveryWindow"));
		assertEquals("2026-08-17", window.get("startDateInclusive"));
		assertEquals("2026-08-18", window.get("endDateExclusive"));
		verifyNoInteractions(fieldExtractor);
	}

	private DefaultFlowEngine engine(FlowInstanceService instances) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				instances, mock(FlowEventService.class), contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, mock(ToolInvoker.class),
				mock(AgentExecutionResourceVersionMapper.class), objectMapper, mock(CapabilityGateway.class));
	}

	private InMemoryFlowInstanceService collectWaitingInstance(String inputHash) {
		return collectWaitingInstance(inputHash, "{\"input\":{},\"runtime\":{\"contextRevision\":1}}");
	}

	private InMemoryFlowInstanceService collectWaitingInstance(String inputHash, String contextData) {
		String waiting = """
				{"action":"COLLECT",
				 "requiredPaths":["/input/companyName","/input/productDescription"],
				 "schema":{"type":"object","properties":{"companyName":{"type":"string"},
				  "productDescription":{"type":"string"}}},
				 "instruction":"Extract customer and product."}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-input")
				.contextData(contextData)
				.waitingPayload(waiting).lockVersion(0).inputHash(inputHash).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private DataAgentSkillVersion collectVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-input","nodes":[
				 {"id":"collect-input","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/companyName","/input/productDescription"],
				  "prompt":"请补充客户与商品信息。",
				  "schema":{"type":"object","properties":{"companyName":{"type":"string"},
				   "productDescription":{"type":"string"}}},
				  "instruction":"Extract customer and product."}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService confirmWaitingInstance(Integer resumeVersion) {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("confirm-submit")
				.contextData("{\"input\":{\"companyName\":\"云南万绿\"},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload("{\"action\":\"CONFIRM\"}").lockVersion(0).resumeVersion(resumeVersion).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private InMemoryFlowInstanceService confirmRunningInstance(String contextData) {
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("confirm-submit")
				.contextData(contextData).waitingPayload("{}").lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private DataAgentSkillVersion confirmVersion(Map<String, Object> guardConfig) {
		Map<String, Object> config = new LinkedHashMap<>(guardConfig);
		config.put("summary", "请确认提交需求单。");
		String configJson;
		try {
			configJson = objectMapper.writeValueAsString(config);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"confirm-submit","nodes":[
				 {"id":"confirm-submit","type":"confirm","next":"end","config":%s},
				 {"id":"end","type":"end"}]}
				""".formatted(configJson);
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private InMemoryFlowInstanceService temporalWaitingInstance() {
		String waiting = """
				{"action":"COLLECT","requiredPaths":["/input/deliveryWindow"],
				 "schema":{"type":"object","properties":{"deliveryWindow":{"type":"object","x-temporal":"INTERVAL",
				  "properties":{"startDateInclusive":{"type":"string"},"endDateExclusive":{"type":"string"},
				   "startInclusive":{"type":"string"},"endExclusive":{"type":"string"},
                   "rolling":{"type":"boolean"}}}}},
				 "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED"},
				 "instruction":"Extract delivery window."}
				""";
		DataAgentFlowInstance instance = DataAgentFlowInstance.builder().id(1L).tenantId("tenant-1").agentId(1L)
				.skillVersionId(2L).skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.WAITING.name()).currentNodeId("collect-window")
				.contextData("{\"input\":{},\"runtime\":{\"contextRevision\":1}}")
				.waitingPayload(waiting).lockVersion(0).build();
		return new InMemoryFlowInstanceService(instance);
	}

	private DataAgentSkillVersion temporalVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"collect-window","nodes":[
				 {"id":"collect-window","type":"collect","next":"end","config":{
				  "requiredPaths":["/input/deliveryWindow"],
				  "prompt":"请补充送达时间。",
				  "schema":{"type":"object","properties":{"deliveryWindow":{"type":"object","x-temporal":"INTERVAL",
				   "properties":{"startDateInclusive":{"type":"string"},"endDateExclusive":{"type":"string"},
				    "startInclusive":{"type":"string"},"endExclusive":{"type":"string"},
				    "rolling":{"type":"boolean"}}}}},
				  "extraction":{"modelPolicy":"IF_UNRESOLVED","schemaMode":"UNRESOLVED_REQUIRED"},
				  "instruction":"Extract delivery window."}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private AgentRequest request(String query) {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").flowInstanceId("1").query(query).build();
	}

	private AgentRequest confirmRequest(Map<String, Object> actionPayload) {
		AgentRequest request = request(null);
		request.setFlowAction(new FlowAction("confirm-1", "CONFIRM", true, actionPayload));
		return request;
	}

	private ModelConfigDTO modelConfig() {
		return ModelConfigDTO.builder().id(1L).modelName("test-model").maxTokens(1024L).build();
	}

	private String sha256(String query) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private Object value(DataAgentFlowInstance instance, String path) {
		try {
			return new FlowContextMapper().get(objectMapper.readValue(instance.getContextData(), MAP_TYPE), path);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private final class InMemoryFlowInstanceService implements FlowInstanceService {

		private final DataAgentFlowInstance instance;

		private InMemoryFlowInstanceService(DataAgentFlowInstance instance) {
			this.instance = instance;
		}

		@Override
		public DataAgentFlowInstance loadOrCreate(AgentRequest request, DataAgentSkill skill,
				DataAgentSkillVersion version, String startNode) {
			return instance;
		}

		@Override
		public DataAgentFlowInstance findActive(AgentRequest request) {
			return instance;
		}

		@Override
		public DataAgentFlowInstance advance(DataAgentFlowInstance current, FlowInstanceStatus status, String nodeId,
				Map<String, Object> context, Map<String, Object> waitingPayload, String idempotencyKey,
				String runtimeRequestId, String errorCode, String errorMessage, Instant finishedAt) {
			try {
				current.setStatus(status.name());
				current.setCurrentNodeId(nodeId);
				current.setContextData(objectMapper.writeValueAsString(new LinkedHashMap<>(context)));
				current.setWaitingPayload(objectMapper.writeValueAsString(new LinkedHashMap<>(waitingPayload)));
				current.setLockVersion(current.getLockVersion() + 1);
				current.setFinishedAt(finishedAt);
				current.setErrorCode(errorCode);
				current.setErrorMessage(errorMessage);
				if (status == FlowInstanceStatus.WAITING) {
					current.setResumeVersion(current.getResumeVersion() == null ? 1 : current.getResumeVersion() + 1);
				}
				return current;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

}
