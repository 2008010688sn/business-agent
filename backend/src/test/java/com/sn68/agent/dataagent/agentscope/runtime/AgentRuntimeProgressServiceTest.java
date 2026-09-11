/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.multimodal.ExtractCard;
import com.sn68.agent.dataagent.multimodal.FusionBlock;
import com.sn68.agent.dataagent.multimodal.FusionEvent;
import com.sn68.agent.dataagent.multimodal.ModalityType;
import com.sn68.agent.dataagent.multimodal.TurnArtifact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Sinks;

class AgentRuntimeProgressServiceTest {

	@Test
	void flowProgressIncludesFlowNodeAndResolverIdentity() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		service.emitFlow(request, "9", "resolve-project", "project", "FLOW_RESOLVER_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, "查询项目");

		assertEquals(1, events.size());
		ServerSentEvent<AgentResponse> event = events.get(0);
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, event.event());
		assertEquals("9", event.data().getMetadata().get("flowInstanceId"));
		assertEquals("resolve-project", event.data().getMetadata().get("nodeId"));
		assertEquals("project", event.data().getMetadata().get("resolverId"));
		assertEquals("FLOW_RESOLVER_RUNNING", event.data().getMetadata().get("stageCode"));
		service.unregister(request);
	}

	@Test
	void orchestrationChildProgressIsBridgedToParentStream() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest parent = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		String opaqueChildRuntimeRequestId = UUID.randomUUID().toString();
		AgentRequest child = AgentRequest.builder().agentId("4").threadId("child-thread")
			.runtimeRequestId(opaqueChildRuntimeRequestId).build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(parent, sink);
		service.registerOrchestrationChild(parent, child, 100L, 101L, "账单分析智能体", "财务分析助手");

		service.emitToolRunning(child, "datasource.query", "查询财务数据");

		assertEquals(1, events.size());
		AgentResponse response = events.get(0).data();
		assertEquals("1", response.getAgentId());
		assertEquals("runtime-1", response.getMetadata().get("runtimeRequestId"));
		assertEquals(opaqueChildRuntimeRequestId, response.getMetadata().get("childRuntimeRequestId"));
		assertFalse(response.getMetadata().containsKey("collaboratorAgentId"));
		assertEquals("财务分析助手", response.getMetadata().get("collaboratorRole"));
		assertEquals("TOOL_RUNNING", response.getMetadata().get("stageCode"));
		service.unregister(child);
		service.unregister(parent);
	}

	@Test
	void failedToolProgressIncludesStableErrorCode() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		service.emitToolFinished(request, "datasource.query", "查询数据",
				AgentRuntimeProgressService.STATUS_FAILED, 25L, "TOOL_TIMEOUT");

		assertEquals(1, events.size());
		assertEquals("TOOL_TIMEOUT", events.get(0).data().getMetadata().get("errorCode"));
		service.unregister(request);
	}

	@Test
	void slowToolFinishedEmitsExtraHintProgress() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		service.emitToolFinished(request, "datasource.query", "查询数据",
				AgentRuntimeProgressService.STATUS_SUCCESS, 5100L);

		assertEquals(2, events.size());
		Map<String, Object> hintMetadata = events.get(1).data().getMetadata();
		assertEquals("HINT", hintMetadata.get("stageCode"));
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, hintMetadata.get("status"));
		assertEquals(5100L, hintMetadata.get("durationMs"));
		assertEquals("工具执行耗时 5.1 秒", hintMetadata.get("displayName"));
		// HINT 不携带 toolName，不参与 TOOL_RUNNING/TOOL_FINISHED 的 toolExecutionSeq 配对。
		assertFalse(hintMetadata.containsKey("toolExecutionSeq"));
		service.unregister(request);
	}

	@Test
	void emitHintDoesNotCarryToolName() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		service.emitHint(request, "已从链接抽出键", 3L, Map.of("keyNames", List.of("id")));

		assertEquals(1, events.size());
		Map<String, Object> hintMetadata = events.get(0).data().getMetadata();
		assertEquals("HINT", hintMetadata.get("stageCode"));
		assertEquals("已从链接抽出键", hintMetadata.get("displayName"));
		assertFalse(hintMetadata.containsKey("toolExecutionSeq"));
		service.unregister(request);
	}

	@Test
	void skillToolProgressDoesNotExposeRawToolNameInSerializedMetadata() throws Exception {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);
		String rawToolName = "skill.sales_analysis.__forbidden_raw_tool_name__";

		service.emitToolRunning(request, rawToolName, "查询经营数据");
		service.emitToolFinished(request, rawToolName, "查询经营数据",
				AgentRuntimeProgressService.STATUS_SUCCESS, 37L);

		assertEquals(2, events.size());
		Map<String, Object> runningMetadata = events.get(0).data().getMetadata();
		Map<String, Object> finishedMetadata = events.get(1).data().getMetadata();
		ObjectMapper objectMapper = new ObjectMapper();
		assertFalse(objectMapper.writeValueAsString(runningMetadata).contains(rawToolName));
		assertFalse(objectMapper.writeValueAsString(finishedMetadata).contains(rawToolName));
		assertFalse(runningMetadata.containsKey("toolName"));
		assertFalse(finishedMetadata.containsKey("toolName"));
		assertEquals("查询经营数据", runningMetadata.get("displayName"));
		assertEquals(runningMetadata.get("toolExecutionSeq"), finishedMetadata.get("toolExecutionSeq"));
		assertEquals(AgentRuntimeProgressService.STATUS_RUNNING, runningMetadata.get("status"));
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, finishedMetadata.get("status"));
		assertEquals(37L, finishedMetadata.get("durationMs"));
		service.unregister(request);
	}

	@Test
	void flowStepsKeepFinishedDropRunningAndKeepLastTerminal() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		// 同一 Resolver 两次 RUNNING+FINISHED（重试历史全保留）
		service.emitFlow(request, "9", "resolve-customer", "customer", "FLOW_RESOLVER_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, "查询下单客户候选");
		service.emitFlow(request, "9", "resolve-customer", "customer", "FLOW_RESOLVER_FINISHED",
				AgentRuntimeProgressService.STATUS_SUCCESS, 120L, "查询下单客户候选");
		service.emitFlow(request, "9", "resolve-customer", "customer", "FLOW_RESOLVER_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, "查询下单客户候选");
		service.emitFlow(request, "9", "resolve-customer", "customer", "FLOW_RESOLVER_FINISHED",
				AgentRuntimeProgressService.STATUS_SUCCESS, 90L, "查询下单客户候选");
		// 节点完成 + 一个没有后续 FINISHED 的 RUNNING（应被丢弃，由终态表达结局）
		service.emitFlow(request, "9", "extract-order", null, "FLOW_NODE_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, "提取信息");
		service.emitFlow(request, "9", "extract-order", null, "FLOW_NODE_FINISHED",
				AgentRuntimeProgressService.STATUS_SUCCESS, 300L, "提取信息");
		service.emitFlow(request, "9", "lonely-node", null, "FLOW_NODE_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, "收集信息");
		service.emitFlow(request, "9", "confirm", null, "FLOW_WAITING",
				AgentRuntimeProgressService.STATUS_WAITING, null, "CONFIRM");

		List<AgentRuntimeProgressService.FlowStepView> steps = service.flowSteps(request);

		assertEquals(4, steps.size());
		assertEquals("tool", steps.get(0).kind());
		assertEquals("查询下单客户候选", steps.get(0).label());
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, steps.get(0).status());
		assertEquals(120L, steps.get(0).durationMs());
		assertEquals("tool", steps.get(1).kind());
		assertEquals(90L, steps.get(1).durationMs());
		assertEquals("node", steps.get(2).kind());
		assertEquals("提取信息", steps.get(2).label());
		assertEquals(300L, steps.get(2).durationMs());
		// state 恒为最后一条；RUNNING 全部被吸收或丢弃
		AgentRuntimeProgressService.FlowStepView state = steps.get(3);
		assertEquals("state", state.kind());
		assertEquals("CONFIRM", state.label());
		assertEquals(AgentRuntimeProgressService.STATUS_WAITING, state.status());
		assertNull(state.durationMs());
		assertTrue(steps.stream().noneMatch(step -> "收集信息".equals(step.label())));

		service.unregister(request);
		assertTrue(service.flowSteps(request).isEmpty());
	}

	@Test
	void flowStepsFallbackLabelToStageCodeWhenDisplayNameBlank() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		sink.asFlux().subscribe(event -> {
		});
		service.register(request, sink);

		service.emitFlow(request, "9", "end", null, "FLOW_FINISHED",
				AgentRuntimeProgressService.STATUS_SUCCESS, 15L, null);

		List<AgentRuntimeProgressService.FlowStepView> steps = service.flowSteps(request);
		assertEquals(1, steps.size());
		assertEquals("state", steps.get(0).kind());
		assertEquals("FLOW_FINISHED", steps.get(0).label());
		service.unregister(request);
	}

	@Test
	void durableRecordMethodsAreNoopWithoutRunLease() {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").query("你好").build();
		service.recordAcceptedUserMessage(request);
		service.recordAssistantDelta(request, "世界");
	}

	@Test
	void emitFusionTracePutsSummaryInProgressDetailsWithoutExtractedText() throws Exception {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-1").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);

		service.emitFusionTrace(request, null);
		assertEquals(0, events.size());

		String giantText = "PAGE_TEXT_" + "应收1200 ".repeat(800);
		FusionEvent fusionEvent = new FusionEvent(ModalityType.PDF, 2048, 120L, "pdf_text", Instant.parse("2026-08-25T00:00:00Z"),
				12, 3, 2, 1, "art-9");
		TurnArtifact artifact = new TurnArtifact("art-9",
				List.of(FusionBlock.text(giantText, "bill.pdf", "k"),
						FusionBlock.image(new byte[] { 1, 2, 3 }, "image/png", "chart", "k2")),
				List.of(fusionEvent), 4096, "附件：文档 1 份");

		service.emitFusionTrace(request, artifact);

		assertEquals(1, events.size());
		ServerSentEvent<AgentResponse> event = events.get(0);
		assertEquals(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS, event.event());
		AgentResponse response = event.data();
		assertEquals("", response.getText());
		Map<String, Object> metadata = response.getMetadata();
		assertEquals("FUSION_TRACE", metadata.get("stageCode"));
		assertEquals(AgentRuntimeProgressService.STATUS_SUCCESS, metadata.get("status"));
		assertEquals("fusion_trace", metadata.get("displayName"));
		assertEquals("art-9", metadata.get("artifactId"));
		assertEquals("附件：文档 1 份", metadata.get("routeSummary"));
		assertEquals(4096, metadata.get("totalTokensEstimate"));
		assertEquals(1, metadata.get("eventCount"));
		assertEquals(true, metadata.get("keepAsImage"));
		assertEquals(1, metadata.get("chartsKept"));
		assertEquals(12, metadata.get("pages"));
		assertEquals(3, metadata.get("tables"));
		assertEquals(1, metadata.get("chartsDropped"));
		assertEquals(giantText.trim().length(), metadata.get("extractedContextLength"));
		assertFalse(metadata.containsKey("extractedContext"));
		assertFalse(metadata.containsKey("blocks"));
		assertFalse(metadata.containsKey("payload"));
		assertFalse(metadata.containsKey("extractStatus"));
		assertFalse(metadata.containsKey("visionExtractMs"));
		assertFalse(metadata.containsKey("cardFields"));
		assertFalse(metadata.containsKey("unreadReason"));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> eventTraces = (List<Map<String, Object>>) metadata.get("events");
		assertEquals(1, eventTraces.size());
		assertEquals("PDF", eventTraces.get(0).get("modality"));
		assertEquals("pdf_text", eventTraces.get(0).get("method"));
		assertEquals(2048, eventTraces.get(0).get("tokensOut"));
		assertEquals(120L, eventTraces.get(0).get("processingMs"));
		String json = new ObjectMapper().writeValueAsString(metadata);
		assertFalse(json.contains("PAGE_TEXT_"));
		assertTrue(json.length() < giantText.length());
		service.unregister(request);
	}

	@Test
	void emitFusionTraceAddsExtractCardFieldsWithoutFullCardText() throws Exception {
		AgentRuntimeProgressService service = new AgentRuntimeProgressService();
		AgentRequest request = AgentRequest.builder().agentId("1").threadId("thread-1")
			.runtimeRequestId("runtime-2").build();
		Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
		List<ServerSentEvent<AgentResponse>> events = new ArrayList<>();
		sink.asFlux().subscribe(events::add);
		service.register(request, sink);
		ExtractCard card = new ExtractCard("UNIQUE_CAPTION_SHOULD_NOT_LEAK", "pod",
				List.of(new ExtractCard.VisibleField("运单号", "YD1", "high")), false, "", ExtractCard.STATUS_OK);
		TurnArtifact artifact = new TurnArtifact("art-card",
				List.of(card.toTextBlock(), FusionBlock.imagePointer("k", "image/png", "sign.png")), List.of(), 12,
				"附件：图片 1 张");

		service.emitFusionTrace(request, artifact, card, 88L);

		Map<String, Object> metadata = events.get(0).data().getMetadata();
		assertEquals(ExtractCard.STATUS_OK, metadata.get("extractStatus"));
		assertEquals(88L, metadata.get("visionExtractMs"));
		assertEquals(1, metadata.get("cardFields"));
		assertFalse(metadata.containsKey("unreadReason"));
		String json = new ObjectMapper().writeValueAsString(metadata);
		assertFalse(json.contains("UNIQUE_CAPTION_SHOULD_NOT_LEAK"));
		service.unregister(request);
	}

}
