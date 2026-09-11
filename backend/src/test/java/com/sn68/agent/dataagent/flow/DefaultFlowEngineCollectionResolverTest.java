/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.capability.CapabilityExecutor;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DefaultFlowEngineCollectionResolverTest {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final FlowInstanceService instanceService = mock(FlowInstanceService.class);

	private final FlowEventService eventService = mock(FlowEventService.class);

	private final FlowFieldExtractor fieldExtractor = mock(FlowFieldExtractor.class);

	private final ToolInvoker toolInvoker = mock(ToolInvoker.class);

	private final CapabilityGateway capabilityGateway = passthroughGateway();

	private final AgentExecutionResourceVersionMapper resourceVersionMapper =
			mock(AgentExecutionResourceVersionMapper.class);

	@Test
	void singleCandidateNormalizesEveryItemAndEmitsFinishedProgress() throws Exception {
		DataAgentFlowInstance instance = instance("""
				{"input":{"twoProjectId":"P-1","productList":[
				 {"productName":"商品A","num":2},{"productName":"商品B","num":3}]},
				 "runtime":{"contextRevision":1}}
				""");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenAnswer(invocation -> {
			String keyword = String.valueOf(invocation.<com.sn68.agent.dataagent.tool.ToolInvocationContext>getArgument(0)
					.arguments().get("keyword"));
			return Map.of("items", List.of(Map.of("productId", "商品A".equals(keyword) ? "A-1" : "B-1",
					"productNo", keyword + "-NO", "productName", keyword)));
		});
		stubAdvance();
		AgentRuntimeProgressService progress = mock(AgentRuntimeProgressService.class);
		DefaultFlowEngine engine = engine(progress);

		FlowExecutionResult result = engine.execute(request(), skill(), version(), null);

		Map<String, Object> context = objectMapper.readValue(result.instance().getContextData(), MAP_TYPE);
		assertEquals("A-1", new FlowContextMapper().get(context, "/input/productList/0/productId"));
		assertEquals(2, new FlowContextMapper().get(context, "/input/productList/0/num"));
		assertEquals("B-1", new FlowContextMapper().get(context, "/input/productList/1/productId"));
		assertTrue(((Map<?, ?>) context.get("runtime")).containsKey("collectionItems"));
		verify(progress, times(2)).emitFlow(any(), eq("20"), eq("resolve-products"), any(),
				eq("FLOW_RESOLVER_FINISHED"), eq(AgentRuntimeProgressService.STATUS_SUCCESS), any(), any());
		verify(capabilityGateway, times(2)).invoke(any(InvocationRequest.class), any());
	}

	@Test
	void zeroCandidateBlocksFinalReview() {
		DataAgentFlowInstance instance = instance("""
				{"input":{"twoProjectId":"P-1","productList":[{"productName":"不存在商品","num":2}]},
				 "runtime":{"contextRevision":1}}
				""");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenReturn(Map.of("items", List.of()));
		stubAdvance();

		FlowExecutionResult result = engine(null).execute(request(), skill(), version(), null);

		assertEquals(FlowInstanceStatus.WAITING.name(), result.instance().getStatus());
		assertEquals("REVIEW", result.message().payload().action());
		assertTrue(Boolean.TRUE.equals(result.message().payload().values().get("blocking")));
	}

	@Test
	void textSearchOverridesOnlyCurrentItemAndKeepsBusinessRevision() throws Exception {
		DataAgentFlowInstance instance = instance("""
				{"input":{"twoProjectId":"P-1","productList":[{"productName":"旧商品","num":2}]},
				 "runtime":{"contextRevision":5}}
				""");
		instance.setStatus(FlowInstanceStatus.WAITING.name());
		instance.setWaitingPayload("""
				{"action":"SELECT","targetPath":"/input/productList/0","options":[],"textSearch":{
				 "enabled":true,"resolverNode":"resolve-products","argumentName":"keyword",
				 "matchPaths":["/productName","/productNo"],"notFoundText":"未找到商品"}}
				""");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenAnswer(invocation -> {
			com.sn68.agent.dataagent.tool.ToolInvocationContext toolContext = invocation.getArgument(0);
			assertEquals("OF330", toolContext.arguments().get("keyword"));
			assertEquals("P-1", toolContext.arguments().get("twoProjectId"));
			return Map.of("items", List.of(
					Map.of("productId", "A-1", "productNo", "OF330-A", "productName", "商品A"),
					Map.of("productId", "B-1", "productNo", "OF330-B", "productName", "商品B")));
		});
		stubAdvance();
		AgentRequest request = request();
		request.setQuery("OF330");
		request.setFlowTextSearchIntent(new FlowTextSearchIntent("resolve-products", "resolve-products",
				"/input/productList/0", "OF330", "keyword", false));

		FlowExecutionResult result = engine(null).execute(request, skill(), textSearchVersion(), null);

		Map<String, Object> context = objectMapper.readValue(result.instance().getContextData(), MAP_TYPE);
		assertEquals("SELECT", result.message().payload().action());
		assertEquals(2, result.message().payload().options().size());
		assertFalse(result.message().payload().values().containsKey("textSearch"));
		assertFalse(result.message().payload().options().get(0).containsKey("rawData"));
		assertEquals("旧商品", new FlowContextMapper().get(context, "/input/productList/0/productName"));
		assertEquals(5L, ((Number) new FlowContextMapper().get(context, "/runtime/contextRevision")).longValue());
	}

	@Test
	void uniqueSiteCopiesMasterDataAddressUnlessUserProvidedOne() throws Exception {
		DataAgentFlowInstance instance = instance("""
				{"input":{"companyId":"C-1","addressList":[
				 {"type":"0","siteName":"杭州前进仓"},
				 {"type":"1","siteName":"杭州萧山仓","address":"用户指定地址"}]},
				 "slotMeta":{"/input/addressList/1/address":{"source":"USER","status":"PROVIDED"}},
				 "runtime":{"contextRevision":1}}
				""");
		instance.setCurrentNodeId("resolve-sites");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		when(resourceVersionMapper.findPublished(10L)).thenReturn(readTool());
		when(toolInvoker.invoke(any())).thenAnswer(invocation -> {
			String keyword = String.valueOf(invocation.<com.sn68.agent.dataagent.tool.ToolInvocationContext>getArgument(0)
					.arguments().get("keyword"));
			boolean from = keyword.contains("前进");
			return Map.of("items", List.of(Map.of("siteId", from ? "S-1" : "S-2", "siteName", keyword,
					"fullAddress", from ? "杭州市余杭区前进路1号" : "杭州市萧山区机场路1号",
					"address", from ? "前进路1号" : "机场路1号")));
		});
		stubAdvance();

		FlowExecutionResult result = engine(null).execute(request(), skill(), siteVersion(), null);

		FlowContextMapper mapper = new FlowContextMapper();
		Map<String, Object> context = objectMapper.readValue(result.instance().getContextData(), MAP_TYPE);
		assertEquals("前进路1号", mapper.get(context, "/input/addressList/0/address"));
		assertEquals("用户指定地址", mapper.get(context, "/input/addressList/1/address"));
		assertEquals("S-1", mapper.get(context, "/input/addressList/0/siteId"));
	}

	@Test
	void emptyCollectionClearsStaleDeferredIssues() throws Exception {
		DataAgentFlowInstance instance = instance("""
				{"input":{"twoProjectId":"P-1","productList":[]},
				 "runtime":{"contextRevision":1,"resolverIssues":{"products":["旧问题"]}}}
				""");
		when(instanceService.loadOrCreate(any(), any(), any(), any())).thenReturn(instance);
		stubAdvance();

		FlowExecutionResult result = engine(null).execute(request(), skill(), deferredVersion(), null);

		Map<String, Object> context = objectMapper.readValue(result.instance().getContextData(), MAP_TYPE);
		assertEquals(List.of(), new FlowContextMapper().get(context, "/runtime/resolverIssues/products"));
		verify(toolInvoker, never()).invoke(any());
	}

	@Test
	void sparseCollectionPatchKeepsIdForQuantityAndClearsIdForRenamedEntity() {
		DefaultFlowEngine engine = engine(null);
		Map<String, Object> context = read("""
				{"input":{"productList":[{"productId":"A-1","productNo":"A","productName":"商品A","num":2}]},
				 "flowPolicyConfig":{"collectionMergePolicies":[
				  {"path":"/input/productList","matchFields":["productId","productNo","productName"],
				   "clearIdWhenChanged":["productNo","productName"],"idFields":["productId"]}]},
				 "runtime":{"contextRevision":2}}
				""");

		ReflectionTestUtils.invokeMethod(engine, "applyExtractionPatch", context, "/input",
				Map.of("productList", List.of(Map.of("productName", "商品A", "num", 5))));
		assertEquals("A-1", new FlowContextMapper().get(context, "/input/productList/0/productId"));
		assertEquals(5, new FlowContextMapper().get(context, "/input/productList/0/num"));

		ReflectionTestUtils.invokeMethod(engine, "applyExtractionPatch", context, "/input",
				Map.of("productList", List.of(Map.of("productName", "商品B"))));
		assertNull(new FlowContextMapper().get(context, "/input/productList/0/productId"));
		assertEquals("商品B", new FlowContextMapper().get(context, "/input/productList/0/productName"));
	}

	@Test
	void customerChangeClearsDependentIdsButKeepsEditableDetails() {
		DefaultFlowEngine engine = engine(null);
		Map<String, Object> context = read("""
				{"input":{"companyId":"C-1","companyName":"旧客户","oneProjectId":"P-1","oneProjectName":"一级",
				 "twoProjectId":"P-2","twoProjectName":"二级",
				 "productList":[{"productId":"A-1","productName":"商品A","num":2}],
				 "addressList":[{"siteId":"S-1","siteName":"网点A","contact":"张三"}]},
				 "flowPolicyConfig":{"inputInvalidationRules":[
				  {"whenAny":["/input/companyId","/input/companyName"],
				   "clearUnlessProvided":["/input/companyId","/input/companyName","/input/oneProjectId",
				    "/input/oneProjectName","/input/twoProjectId","/input/twoProjectName"],
				   "clearCollectionFields":{"/input/productList":["productId"],"/input/addressList":["siteId"]}}]},
				 "runtime":{"contextRevision":3}}
				""");

		ReflectionTestUtils.invokeMethod(engine, "applyExtractionPatch", context, "/input",
				Map.of("companyName", "新客户"));

		FlowContextMapper mapper = new FlowContextMapper();
		assertNull(mapper.get(context, "/input/companyId"));
		assertNull(mapper.get(context, "/input/twoProjectId"));
		assertNull(mapper.get(context, "/input/productList/0/productId"));
		assertEquals(2, mapper.get(context, "/input/productList/0/num"));
		assertNull(mapper.get(context, "/input/addressList/0/siteId"));
		assertEquals("张三", mapper.get(context, "/input/addressList/0/contact"));
		assertFalse(Boolean.TRUE.equals(mapper.get(context, "/runtime/confirmed")));
	}

	@Test
	void clearReferenceAlsoRemovesMasterDataNormalizedFromHistory() {
		DefaultFlowEngine engine = engine(null);
		Map<String, Object> context = read("""
				{"input":{"productList":[{"productId":"A-1","productName":"商品A","num":2}]},
				 "slotMeta":{"/input/productList":{"source":"HISTORY","status":"PROVIDED"},
				  "/input/productList/0/productId":{"source":"HISTORY","status":"PROVIDED"}},
				 "runtime":{"contextRevision":2}}
				""");

		ReflectionTestUtils.invokeMethod(engine, "markResolvedCandidateSlots", context, "/input/productList/0",
				Map.of("productId", "A-1", "productName", "商品A"), Map.of("preserveUserFields", List.of("num")));
		assertEquals("HISTORY", new FlowContextMapper().get(context,
				"/slotMeta/~1input~1productList~10~1productId/origin"));

		ReflectionTestUtils.invokeMethod(engine, "clearHistoryReference", context);
		assertNull(new FlowContextMapper().get(context, "/input/productList"));
	}

	@Test
	void clearReferenceCompactsHistoryItemsAndRemapsUserMetadata() {
		DefaultFlowEngine engine = engine(null);
		Map<String, Object> context = read("""
				{"input":{"productList":[
				 {"productId":"H-1","productName":"历史商品"},
				 {"productName":"用户商品","num":3}]},
				 "slotMeta":{
				  "/input/productList":{"source":"HISTORY","status":"PROVIDED"},
				  "/input/productList/0":{"source":"HISTORY","status":"PROVIDED"},
				  "/input/productList/0/productId":{"source":"HISTORY","status":"PROVIDED"},
				  "/input/productList/0/productName":{"source":"HISTORY","status":"PROVIDED"},
				  "/input/productList/1":{"source":"USER","status":"PROVIDED"},
				  "/input/productList/1/productName":{"source":"USER","status":"PROVIDED"},
				  "/input/productList/1/num":{"source":"USER","status":"PROVIDED"}},
				 "runtime":{"contextRevision":2}}
				""");

		ReflectionTestUtils.invokeMethod(engine, "clearHistoryReference", context);

		FlowContextMapper mapper = new FlowContextMapper();
		List<?> products = (List<?>) mapper.get(context, "/input/productList");
		assertEquals(1, products.size());
		assertEquals("用户商品", mapper.get(context, "/input/productList/0/productName"));
		assertEquals(3, mapper.get(context, "/input/productList/0/num"));
		assertEquals("USER", mapper.get(context, "/slotMeta/~1input~1productList~10~1productName/source"));
		assertNull(mapper.get(context, "/slotMeta/~1input~1productList~11~1productName"));
		assertFalse(ReflectionTestUtils.<Boolean>invokeMethod(engine, "hasHistoryReference", context));
	}

	@Test
	void referenceInvalidationClearsHistoricalDraftWithoutOverridingCurrentDecision() throws Exception {
		DefaultFlowEngine engine = engine(null);
		Map<String, Object> context = read("""
				{"input":{"type":"2","productList":[{"productId":"H-1","productName":"历史商品"}]},
				 "slotMeta":{"/input/productList":{"source":"HISTORY","status":"PROVIDED"}},
				 "runtime":{"contextRevision":3,"lastChangedPaths":["/input/type"],
				  "referenceDecision":"FORCE_QUERY"}}
				""");
		FlowNode historyResolver = objectMapper.readValue("""
				{"id":"resolve-history","type":"resolve","config":{
				 "invalidateOn":["/input/type"],"invalidates":["/resolved/history"],
				 "clearReferenceOnInvalidate":true}}
				""", FlowNode.class);

		ReflectionTestUtils.invokeMethod(engine, "invalidateDependentResolvers", context,
				Map.of(historyResolver.id(), historyResolver));

		FlowContextMapper mapper = new FlowContextMapper();
		assertNull(mapper.get(context, "/input/productList"));
		assertEquals("FORCE_QUERY", mapper.get(context, "/runtime/referenceDecision"));
		assertEquals("INVALIDATED", mapper.get(context, "/resolverState/resolve-history/status"));
	}

	private DefaultFlowEngine engine(AgentRuntimeProgressService progress) {
		FlowContextMapper contextMapper = new FlowContextMapper();
		return new DefaultFlowEngine(new FlowDefinitionValidator(objectMapper), new FlowNodeExecutorRegistry(),
				instanceService, eventService, contextMapper, new FlowConditionEvaluator(contextMapper),
				new FlowSchemaValidator(), fieldExtractor, toolInvoker, resourceVersionMapper, objectMapper, Runnable::run,
				progress, new DataAgentProperties(), capabilityGateway);
	}

	private static CapabilityGateway passthroughGateway() {
		CapabilityGateway gateway = mock(CapabilityGateway.class);
		when(gateway.invoke(any(InvocationRequest.class), any())).thenAnswer(invocation -> {
			CapabilityExecutor executor = invocation.getArgument(1);
			return ResultEnvelope.builder()
				.status(ResultEnvelope.STATUS_SUCCESS)
				.schemaVersion(ResultEnvelope.SCHEMA_VERSION_V1)
				.data(executor.execute())
				.build();
		});
		return gateway;
	}

	private DataAgentSkill skill() {
		return DataAgentSkill.builder().id(1L).skillCode("demand-create").build();
	}

	private DataAgentSkillVersion siteVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"resolve-sites","nodes":[
				 {"id":"resolve-sites","type":"resolve","next":"end","config":{"resourceVersionId":10,
				  "forEach":"/input/addressList","candidatesPath":"/items",
				  "identityPaths":["/siteId","/siteCode","/siteName"],
				  "argumentMappings":{"companyId":"/input/companyId"},
				  "itemArgumentMappings":{"siteId":"/siteId","siteCode":"/siteCode","keyword":"/siteName"},
				  "labelPath":"/siteName","valuePath":"/siteId",
				  "preserveUserFields":["type","address","contact","tel"]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(5L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion version() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"resolve-products","nodes":[
				 {"id":"resolve-products","type":"resolve","next":"end","config":{"resourceVersionId":10,
				  "forEach":"/input/productList","candidatesPath":"/items","identityPaths":["/productId","/productNo","/productName"],
				  "argumentMappings":{"twoProjectId":"/input/twoProjectId"},
				  "itemArgumentMappings":{"productId":"/productId","productNo":"/productNo","keyword":"/productName"},
				  "labelPath":"/productName","valuePath":"/productId","preserveUserFields":["num"]}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(2L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion deferredVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"resolve-products","nodes":[
				 {"id":"resolve-products","type":"resolve","next":"end","config":{"resourceVersionId":10,
				  "forEach":"/input/productList","deferUnresolved":true,
				  "issuesPath":"/runtime/resolverIssues/products"}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(3L).skillId(1L).flowDefinition(definition).build();
	}

	private DataAgentSkillVersion textSearchVersion() {
		String definition = """
				{"schemaVersion":"skill-flow/v1","startNode":"resolve-products","nodes":[
				 {"id":"resolve-products","type":"resolve","next":"end","config":{"resourceVersionId":10,
				  "forEach":"/input/productList","candidatesPath":"/items",
				  "identityPaths":["/productId","/productNo","/productName"],
				  "argumentMappings":{"twoProjectId":"/input/twoProjectId"},
				  "itemArgumentMappings":{"productId":"/productId","productNo":"/productNo","keyword":"/productName"},
				  "labelPath":"/productName","valuePath":"/productId","preserveUserFields":["num"],
				  "textSearch":{"enabled":true,"resolverNode":"resolve-products","argumentName":"keyword",
				   "matchPaths":["/productName","/productNo"],"notFoundText":"未找到商品"}}},
				 {"id":"end","type":"end"}]}
				""";
		return DataAgentSkillVersion.builder().id(4L).skillId(1L).flowDefinition(definition).build();
	}

	private AgentRequest request() {
		return AgentRequest.builder().agentId("1").threadId("thread-1").runtimeRequestId("runtime-1")
				.tenantIdSnapshot("tenant-1").userIdSnapshot("user-1").build();
	}

	private DataAgentFlowInstance instance(String context) {
		return DataAgentFlowInstance.builder().id(20L).tenantId("tenant-1").agentId(1L).skillVersionId(2L)
				.skillCode("demand-create").threadId("thread-1").userId("user-1")
				.status(FlowInstanceStatus.RUNNING.name()).currentNodeId("resolve-products")
				.contextData(context).waitingPayload("{}").lockVersion(0).build();
	}

	private AgentExecutionResourceVersion readTool() {
		return AgentExecutionResourceVersion.builder().id(10L).resourceKey("products").versionNo(1)
				.status("PUBLISHED").accessMode("READ").exposureMode("FLOW_ONLY").build();
	}

	private void stubAdvance() {
		when(instanceService.advance(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> advance(invocation.getArgument(0), invocation.getArgument(1),
						invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)));
	}

	private DataAgentFlowInstance advance(DataAgentFlowInstance instance, FlowInstanceStatus status, String nodeId,
			Map<String, Object> context, Map<String, Object> waitingPayload) throws Exception {
		instance.setStatus(status.name());
		instance.setCurrentNodeId(nodeId);
		instance.setContextData(objectMapper.writeValueAsString(new LinkedHashMap<>(context)));
		instance.setWaitingPayload(objectMapper.writeValueAsString(new LinkedHashMap<>(waitingPayload)));
		return instance;
	}

	private Map<String, Object> read(String json) {
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
