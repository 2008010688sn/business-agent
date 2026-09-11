/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRequestSnapshotSupport;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolDisplayNameResolver;
import com.sn68.agent.dataagent.capability.CapabilityApprovalRequiredException;
import com.sn68.agent.dataagent.capability.CapabilityGateway;
import com.sn68.agent.dataagent.capability.CapabilityKind;
import com.sn68.agent.dataagent.capability.InvocationRequest;
import com.sn68.agent.dataagent.capability.ResultEnvelope;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.entity.AgentExecutionResourceVersion;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.flow.definition.FlowBranch;
import com.sn68.agent.dataagent.flow.definition.FlowDefinition;
import com.sn68.agent.dataagent.flow.definition.FlowNode;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.tool.FlowInvocationReference;
import com.sn68.agent.dataagent.tool.ToolInvocationContext;
import com.sn68.agent.dataagent.tool.ToolInvocationException;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.dataagent.skill.SkillManagerApproval;
import com.sn68.agent.dataagent.tool.ToolInvoker;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.TemporalAmbiguityStrategy;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Generic, configuration-driven FLOW engine. No business field or Skill code is hard-coded here.
 */
@Slf4j
@Service
public class DefaultFlowEngine implements FlowEngine {

	private static final int MAX_NODES_PER_TURN = 64;

	/** 同一节点在单轮内连续推进的上限，避免 emptyNext 空转跑满 MAX_NODES_PER_TURN。 */
	private static final int MAX_SAME_NODE_VISITS_PER_TURN = 3;

	private static final String EXTRACTION_INVOCATIONS_PATH = "/runtime/extractionInvocations";

	/**
	 * 本回合主抽取（extract 节点 / 其 EXTRACT 恢复路径）已消费整句用户输入。
	 * 语义是"主抽取已跑过"，不是"本回合发生过任何抽取"：等待态用户输入触发的抽取（COLLECT/VALIDATION/REVIEW
	 * 等等待卡恢复）不得置位，否则会误杀同句里其余字段的后续 collect 抽取。
	 */
	private static final String PRIMARY_EXTRACT_RAN_PATH = "/runtime/primaryExtractRan";

	/** 死路回退原因通知路径：回退后随目标卡片提示前置展示，回合开始清空。 */
	private static final String FLOW_NOTICE_PATH = "/runtime/flowNotice";

	/**
	 * 死路回退已执行标记：首次自动回退后置位并随实例持久化，之后同一死路一律落回死路卡兜底，
	 * 防止多个空候选 select 节点之间循环弹跳；手动 FALLBACK 重选不受该标记限制。
	 */
	private static final String FALLBACK_APPLIED_PATH = "/runtime/fallbackApplied";

	/** 上下文修订号路径：用户每轮有效输入自增，用于失效并发 Resolver 的旧结果。 */
	private static final String CONTEXT_REVISION_PATH = "/runtime/contextRevision";

	/** 下一节点覆盖路径：交互处理器写入后，本轮推进起点改为该节点（读取后即清空）。 */
	private static final String NEXT_NODE_OVERRIDE_PATH = "/runtime/nextNodeOverride";

	/** 确认页直改文本暂存路径：用户在 CONFIRM 态直接输入修改内容时随跳转带回编辑节点。 */
	private static final String PENDING_EDIT_QUERY_PATH = "/runtime/pendingEditQuery";

	/** 最近一次抽取变更的字段路径列表：用于 REVIEW 刷新路由匹配。 */
	private static final String LAST_CHANGED_PATHS_PATH = "/runtime/lastChangedPaths";

	/** 运行时限流配置在 FLOW 上下文中的键名。 */
	private static final String FLOW_RUNTIME_CONFIG_KEY = "flowRuntimeConfig";

	/** 用户结构化输入在 FLOW 上下文中的根路径。 */
	private static final String INPUT_ROOT_PATH = "/input";

	/** 单个用户回合内允许的抽取模型调用次数。一回合可能串联多个 extract/collect 节点，不设上限即按节点数放大 LLM 成本。 */
	private static final long MAX_EXTRACTION_INVOCATIONS_PER_TURN = 1L;

	/** 连续 REASK 达到该次数后不再重述，改为提示用户切换模型。 */
	private static final long MAX_REASK_FAILURES_BEFORE_MODEL_CHANGE = 2L;

	private static final String UI_MODE_CHAT = "CHAT";

	private static final String UI_MODE_FORM = "FORM";

	private static final String UI_MODE_SUMMARY = "SUMMARY";

	private static final String MODEL_POLICY_ALWAYS = "ALWAYS";

	private static final String MODEL_POLICY_IF_UNRESOLVED = "IF_UNRESOLVED";

	private static final String SCHEMA_MODE_FULL = "FULL";

	private static final String SCHEMA_MODE_CONFIGURED_PATHS = "CONFIGURED_PATHS";

	private static final String SCHEMA_MODE_UNRESOLVED_REQUIRED = "UNRESOLVED_REQUIRED";

	/** 与 CapabilityGatewayToolCallback / RuntimeHook 对齐的 FLOW 调用来源，参与幂等键。 */
	static final String SOURCE_FLOW = "FLOW";

	private static final List<String> LITERAL_NEGATIONS = List.of("不要", "不是", "不选", "不用", "不需要", "不做",
			"不下", "取消", "别", "别用", "非");

	/** 抽取补丁里的回合意图字段，写入 /input 前剥离，避免污染业务槽。 */
	private static final String TURN_ACTION_FIELD = "turnAction";

	private static final String LIST_LABEL_FIELD = "listLabel";

	private static final String TURN_ACTION_PATH = "/runtime/turnAction";

	/** 防重窗口基准路径：记录本回合输入成功受理的毫秒时间戳，供相同输入的窗口判断使用。 */
	private static final String LAST_PROCESSED_INPUT_AT_PATH = "/runtime/lastProcessedInputAt";

	/** 相同输入的去重窗口：窗口内视为传输层重试（双击重发/IM 重发/SSE 重放）直接去重；超过窗口视为有意重试放行。 */
	private static final Duration DUPLICATE_RESUME_WINDOW = Duration.ofSeconds(5);

	private static final String LIST_LABEL_PATH = "/runtime/listLabel";

	private static final String CATALOG_LABELS_PATH = "/runtime/catalogLabels";

	private static final String FILLED_THIS_TURN_PATH = "/runtime/filledThisTurn";

	private static final String LISTED_CATALOG_PATH = "/runtime/listedCatalog";

	private static final String REFERENCE_DECISION_PATH = "/runtime/referenceDecision";

	private static final List<String> TURN_ACTION_VALUES = List.of("fill", "list", "proceed", "cancel", "ask",
			"chitchat");

	private static final Set<String> TURN_ACTIONS = Set.copyOf(TURN_ACTION_VALUES);

	private static final String TURN_REPLY_FIELD = "turnReply";

	private static final String TURN_REPLY_PATH = "/runtime/turnReply";

	private static final String REMAINING_SLOT_INSTRUCTION = "Also extract any other remaining writable schema fields the user explicitly stated, in any order or phrasing. Do not guess or invent identifiers.";

	private static final Pattern LEADING_INTEGER = Pattern.compile("^\\s*([+-]?\\d+)");

	private static final Pattern USER_VISIBLE_JSON_POINTER = Pattern.compile(
			"(?i)(?:^|\\s)/[A-Za-z][A-Za-z0-9_]*(?:/[A-Za-z0-9_]+)*(?:\\s+is required)?");

	private static final Pattern USER_VISIBLE_REQUIRED_PHRASE = Pattern.compile("(?i)\\bis required\\b");

	private static final Pattern USER_VISIBLE_CAMEL_IDENT = Pattern.compile("\\b[a-z]+[A-Z][A-Za-z0-9]*\\b");

	private static final Pattern USER_VISIBLE_ASCII_FIELD_TAIL = Pattern.compile("(?:,\\s*[a-z][a-zA-Z0-9_]*)+");

	private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

	private static final DateTimeFormatter DISPLAY_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final Set<String> SUMMARY_HIDDEN_ITEM_FIELDS = Set.of("erpcode", "productno", "producttype",
			"supportscan", "sizelength", "sizewidth", "sizeheight", "expandvolume", "foldvolume", "unitweight",
			"unitvolume", "addnum", "tonnage", "cleanstatus", "sitecode", "sitetype", "sitetypename", "siteaddress",
			"lng", "lat", "longitude", "latitude", "fulladdress", "tel", "phone", "mobile", "contact", "provincename",
			"cityname", "companyname");

	private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

	private static final Pattern TRAILING_IDENTITY_PUNCT = Pattern.compile("[,;，。、]+$");

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final FlowDefinitionValidator definitionValidator;

	private final FlowNodeExecutorRegistry nodeRegistry;

	private final FlowInstanceService instanceService;

	private final FlowEventService eventService;

	private final FlowContextMapper contextMapper;

	private final FlowConditionEvaluator conditionEvaluator;

	private final FlowSchemaValidator schemaValidator;

	private final FlowFieldExtractor fieldExtractor;

	private final ToolInvoker toolInvoker;

	private final CapabilityGateway capabilityGateway;

	private final AgentExecutionResourceVersionMapper resourceVersionMapper;

	private final ObjectMapper objectMapper;

	private final Executor flowResolverExecutor;

	private final AgentRuntimeProgressService runtimeProgressService;

	private final DataAgentProperties dataAgentProperties;

	private final FlowTemporalNormalizer temporalNormalizer;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private AgentApprovalService approvalService;

	private final ConcurrentMap<Long, ResolverRun> activeResolverRuns = new ConcurrentHashMap<>();

	public DefaultFlowEngine(FlowDefinitionValidator definitionValidator, FlowNodeExecutorRegistry nodeRegistry,
			FlowInstanceService instanceService, FlowEventService eventService, FlowContextMapper contextMapper,
			FlowConditionEvaluator conditionEvaluator, FlowSchemaValidator schemaValidator,
			FlowFieldExtractor fieldExtractor, ToolInvoker toolInvoker,
			AgentExecutionResourceVersionMapper resourceVersionMapper, ObjectMapper objectMapper,
			CapabilityGateway capabilityGateway) {
		this(definitionValidator, nodeRegistry, instanceService, eventService, contextMapper, conditionEvaluator,
				schemaValidator, fieldExtractor, toolInvoker, resourceVersionMapper, objectMapper, Runnable::run,
				null, new DataAgentProperties(), new FlowTemporalNormalizer(new AgentTemporalService()),
				new DataAgentAsyncContextBridge(), capabilityGateway);
	}

	public DefaultFlowEngine(FlowDefinitionValidator definitionValidator, FlowNodeExecutorRegistry nodeRegistry,
			FlowInstanceService instanceService, FlowEventService eventService, FlowContextMapper contextMapper,
			FlowConditionEvaluator conditionEvaluator, FlowSchemaValidator schemaValidator,
			FlowFieldExtractor fieldExtractor, ToolInvoker toolInvoker,
			AgentExecutionResourceVersionMapper resourceVersionMapper, ObjectMapper objectMapper,
			Executor flowResolverExecutor, AgentRuntimeProgressService runtimeProgressService,
			DataAgentProperties dataAgentProperties, CapabilityGateway capabilityGateway) {
		this(definitionValidator, nodeRegistry, instanceService, eventService, contextMapper, conditionEvaluator,
				schemaValidator, fieldExtractor, toolInvoker, resourceVersionMapper, objectMapper, flowResolverExecutor,
				runtimeProgressService, dataAgentProperties, new FlowTemporalNormalizer(new AgentTemporalService()),
				new DataAgentAsyncContextBridge(), capabilityGateway);
	}

	@Autowired
	public DefaultFlowEngine(FlowDefinitionValidator definitionValidator, FlowNodeExecutorRegistry nodeRegistry,
			FlowInstanceService instanceService, FlowEventService eventService, FlowContextMapper contextMapper,
			FlowConditionEvaluator conditionEvaluator, FlowSchemaValidator schemaValidator,
			FlowFieldExtractor fieldExtractor, ToolInvoker toolInvoker,
			AgentExecutionResourceVersionMapper resourceVersionMapper, ObjectMapper objectMapper,
			@Qualifier("flowResolverExecutor") Executor flowResolverExecutor,
			AgentRuntimeProgressService runtimeProgressService, DataAgentProperties dataAgentProperties,
			FlowTemporalNormalizer temporalNormalizer, DataAgentAsyncContextBridge asyncContextBridge,
			CapabilityGateway capabilityGateway) {
		this.definitionValidator = definitionValidator;
		this.nodeRegistry = nodeRegistry;
		this.instanceService = instanceService;
		this.eventService = eventService;
		this.contextMapper = contextMapper;
		this.conditionEvaluator = conditionEvaluator;
		this.schemaValidator = schemaValidator;
		this.fieldExtractor = fieldExtractor;
		this.toolInvoker = toolInvoker;
		this.capabilityGateway = Objects.requireNonNull(capabilityGateway, "capabilityGateway");
		this.resourceVersionMapper = resourceVersionMapper;
		this.objectMapper = objectMapper;
		this.flowResolverExecutor = Objects.requireNonNull(flowResolverExecutor, "flowResolverExecutor");
		this.runtimeProgressService = runtimeProgressService;
		this.dataAgentProperties = dataAgentProperties == null ? new DataAgentProperties() : dataAgentProperties;
		this.temporalNormalizer = Objects.requireNonNull(temporalNormalizer, "temporalNormalizer");
		this.asyncContextBridge = Objects.requireNonNull(asyncContextBridge, "asyncContextBridge");
	}

	@Autowired(required = false)
	public void setApprovalService(AgentApprovalService approvalService) {
		this.approvalService = approvalService;
	}

	@Override
	public FlowExecutionResult execute(AgentRequest request, DataAgentSkill skill, DataAgentSkillVersion version,
			ModelConfigDTO modelConfig) {
		Map<String, Object> rawDefinition = readMap(version.getFlowDefinition());
		List<String> errors = definitionValidator.validate(rawDefinition);
		if (!errors.isEmpty()) {
			throw CheckedException.badRequest("Published FLOW definition is invalid: " + String.join("; ", errors));
		}
		FlowDefinition definition = objectMapper.convertValue(rawDefinition, FlowDefinition.class);
		Map<String, FlowNode> nodeMap = new LinkedHashMap<>();
		definition.nodes().forEach(node -> nodeMap.put(node.id(), node));
		DataAgentFlowInstance instance = instanceService.loadOrCreate(request, skill, version, definition.startNode());
		String turnInputHash = inputHash(request.getQuery());
		if (FlowInstanceStatus.PROCESSING.name().equals(instance.getStatus())) {
			// 同一输入的重复投递（客户端重试/IM 重发）不打断正在处理的回合。
			boolean duplicateDelivery = turnInputHash != null && turnInputHash.equals(instance.getInputHash());
			if (!duplicateDelivery && request.getFlowTextSearchIntent() == null
					&& (StringUtils.hasText(request.getQuery()) || request.getFlowAction() != null)) {
				Map<String, Object> processingContext = readMap(instance.getContextData());
				incrementContextRevision(processingContext);
				instance = instanceService.advance(instance, FlowInstanceStatus.RUNNING, instance.getCurrentNodeId(),
						processingContext, Map.of(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null,
						null);
			}
			else {
				return processingResponse(request, instance);
			}
		}
		FlowExecutionResult duplicateResume = rejectDuplicateResume(request, instance, turnInputHash);
		if (duplicateResume != null) {
			return duplicateResume;
		}
		emitFlowProgress(request, instance, null, null, "FLOW_STARTED", AgentRuntimeProgressService.STATUS_RUNNING,
				null, "开始执行流程");
		Map<String, Object> context = readMap(instance.getContextData());
		if (!context.containsKey(FLOW_RUNTIME_CONFIG_KEY)) {
			context.put(FLOW_RUNTIME_CONFIG_KEY, readMap(version.getFlowRuntimeConfig()));
		}
		if (!context.containsKey("flowPolicyConfig")) {
			context.put("flowPolicyConfig", readMap(version.getFlowPolicyConfig()));
		}
		contextMapper.set(context, EXTRACTION_INVOCATIONS_PATH, 0L);
		// 防跨回合泄漏：KEEP_FLOW/文本搜索等主抽取未跑的回合，标记必须为空，collect 聚焦抽取才不被误杀。
		contextMapper.set(context, PRIMARY_EXTRACT_RAN_PATH, null);
		contextMapper.set(context, TURN_ACTION_PATH, null);
		contextMapper.set(context, LIST_LABEL_PATH, null);
		contextMapper.set(context, TURN_REPLY_PATH, null);
		// 死路回退的原因说明是回合级状态：新回合清空，避免上一回合的回退提示残留。
		// fallbackApplied 同样按回合重置：用户每个回合都保有一次自动回退机会（换到客户 B 仍无项目时，
		// 下回合仍可自动回退重选 C）；同回合内至多一次，回退后的再死路落死路卡兜底，无循环风险。
		contextMapper.set(context, FLOW_NOTICE_PATH, null);
		contextMapper.set(context, FALLBACK_APPLIED_PATH, null);
		contextMapper.set(context, CATALOG_LABELS_PATH, catalogResourceLabels(version));
		contextMapper.set(context, FILLED_THIS_TURN_PATH, List.of());
		applyFlowTemporalContext(request, context);
		if (request.isFlowTextNoop() && FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			Map<String, Object> waiting = readMap(instance.getWaitingPayload());
			String action = text(waiting.get("action"));
			String message = "CANCEL_CONFIRM".equalsIgnoreCase(action)
					? firstText(text(waiting.get("warning")), "请回复“确认取消”或“继续”。")
					: "APPROVAL".equalsIgnoreCase(action)
							? firstText(text(waiting.get("message")), "已提交审批，通过后将自动创建。")
					: "SELECT".equalsIgnoreCase(action)
							? selectionWaitingText(request, context,
									"未识别到有效候选，请回复最新列表中的序号、名称或业务编码。", waiting)
							: "请按当前流程提示继续操作。";
			return response(request, instance, null, NodeResult.waiting(message, action, waiting), false, 0L);
		}
		if (request.getFlowTextSearchIntent() == null
				&& (StringUtils.hasText(request.getQuery()) || request.getFlowAction() != null)
				&& !frozenApprovalWait(instance, request)) {
			incrementContextRevision(context);
		}
		FlowExecutionResult controlResult = handleControlAction(request, instance, context, nodeMap);
		if (controlResult != null) {
			return controlResult;
		}
		FlowExecutionResult recoveryWait = prepareRecoveryWait(request, modelConfig, instance, context);
		if (recoveryWait != null) {
			return recoveryWait;
		}
		// 恢复防重：本回合确定要处理该输入后才记录 hash；技术失败路径会清空 hash 允许原文重试。
		if (turnInputHash != null) {
			instance.setInputHash(turnInputHash);
			// 防重窗口基准：记录本回合输入成功受理时间，供相同输入的窗口判断使用。
			contextMapper.set(context, LAST_PROCESSED_INPUT_AT_PATH, System.currentTimeMillis());
		}
		try {
			applyWaitingInput(request, modelConfig, instance, version, context);
		}
		catch (RuntimeException ex) {
			FlowNode waitingNode = nodeMap.get(instance.getCurrentNodeId());
			if (waitingNode != null && isInputCollectionWait(instance, waitingNode)) {
				return inputFailureWaiting(request, modelConfig, instance, waitingNode, context, ex);
			}
			if (ex instanceof FlowExtractionException
					&& "SELECT".equalsIgnoreCase(text(readMap(instance.getWaitingPayload()).get("action")))) {
				return selectInputFailureWaiting(request, instance, waitingNode, context, ex);
			}
			throw ex;
		}
		FlowExecutionResult intentControl = handleTurnControlAction(request, instance, context, nodeMap);
		if (intentControl != null) {
			return intentControl;
		}
		String currentNodeId = textSearchStartNode(request, instance, nodeMap);
		currentNodeId = firstText(text(contextMapper.get(context, NEXT_NODE_OVERRIDE_PATH)), currentNodeId);
		contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, null);
		Map<String, Integer> nodeVisits = new LinkedHashMap<>();
		for (int count = 0; count < MAX_NODES_PER_TURN; count++) {
			invalidateDependentResolvers(context, nodeMap);
			FlowNode node = nodeMap.get(currentNodeId);
			if (node == null) {
				return fail(instance, request, null, context, "FLOW_NODE_MISSING",
						"流程节点不存在: " + currentNodeId);
			}
			if (nodeVisits.merge(currentNodeId, 1, Integer::sum) >= MAX_SAME_NODE_VISITS_PER_TURN) {
				return stopRepeatedNode(request, instance, node, context);
			}
			emitFlowProgress(request, instance, node, null, "FLOW_NODE_RUNNING",
				AgentRuntimeProgressService.STATUS_RUNNING, null, nodeDisplayName(node));
			if (isParallelResolverBatch(node) && !FlowInstanceStatus.UNKNOWN.name().equals(instance.getStatus())) {
				long batchStart = System.nanoTime();
				try {
					NodeResult batch = executeParallelResolverBatch(request, instance, version, node, context);
					eventService.record(instance, request.getRuntimeRequestId(), node, "RESOLVER_BATCH_COMPLETED",
							batch.status().name(), elapsedMs(batchStart), Map.of(), batch.output(), null);
					if (batch.waiting()) {
						instance = instanceService.advanceBatch(instance, batch.status(), node.id(), context,
								batch.waitingPayload(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null,
								null);
						return response(request, instance, node, batch, false, elapsedMs(batchStart));
					}
					String nextNodeId = firstText(batch.nextNodeId(), node.next());
					instance = instanceService.advanceBatch(instance, FlowInstanceStatus.RUNNING, nextNodeId, context,
							Map.of(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null, null);
					emitFlowProgress(request, instance, node, null, "FLOW_NODE_FINISHED",
							AgentRuntimeProgressService.STATUS_SUCCESS, elapsedMs(batchStart), nodeDisplayName(node));
					currentNodeId = nextNodeId;
					continue;
				}
				catch (RuntimeException ex) {
					eventService.record(instance, request.getRuntimeRequestId(), node, "RESOLVER_BATCH_FAILED", "FAILED",
							elapsedMs(batchStart), Map.of(), Map.of(), ex);
					return fail(instance, request, node, context, ex.getClass().getSimpleName(), ex.getMessage());
				}
			}
			long start = System.nanoTime();
			boolean executeNode = "execute".equalsIgnoreCase(node.type());
			boolean unknownInstance = executeNode && FlowInstanceStatus.UNKNOWN.name().equals(instance.getStatus());
			boolean idempotentWrite = executeNode && requiresIdempotency(node);
			boolean recoverableWrite = idempotentWrite && hasResultQuery(node);
			if (unknownInstance && !recoverableWrite) {
				return fail(instance, request, node, context, "WRITE_RESULT_UNKNOWN",
						"写入结果无法自动确认，系统不会重复提交，请人工核对业务单据后再决定是否重新提交。");
			}
			boolean recoveringUnknown = unknownInstance;
			try {
				if (executeNode && !recoveringUnknown) {
					String idempotencyKey = idempotentWrite ? firstText(instance.getIdempotencyKey(),
							"flow:" + instance.getTenantId() + ":" + instance.getId() + ":" + node.id()) : null;
					instance = instanceService.advance(instance, FlowInstanceStatus.EXECUTING, node.id(), context, Map.of(),
							idempotencyKey, request.getRuntimeRequestId(), null, null, null);
				}
				NodeResult result = recoveringUnknown ? recoverUnknown(request, instance, version, node, context)
						: executeNode(request, modelConfig, instance, version, node, context);
				FlowExecutionResult nodeControl = handleTurnControlAction(request, instance, context, nodeMap);
				if (nodeControl != null) {
					return nodeControl;
				}
				boolean publishResourceSuccess = executeNode && "EXECUTED".equals(result.action())
						&& !resourceSuccessAlreadyPublished(context, node);
				if (publishResourceSuccess) {
					markResourceSuccessPublished(context, node);
				}
				eventService.record(instance, request.getRuntimeRequestId(), node, "NODE_COMPLETED", result.status().name(),
						elapsedMs(start), Map.of(), result.output(), null);
				if (result.waiting()) {
					instance = instanceService.advance(instance, result.status(), node.id(), context,
							result.waitingPayload(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null,
							null);
					return response(request, instance, node, result, false, elapsedMs(start));
				}
				if (result.terminal()) {
					instance = instanceService.advance(instance, result.status(), node.id(), context, Map.of(),
							result.idempotencyKey(), request.getRuntimeRequestId(), result.errorCode(), result.text(),
							Instant.now());
					return response(request, instance, node, result, true, elapsedMs(start));
				}
				if ("extract".equalsIgnoreCase(node.type())) {
					// 主抽取节点整回合消费了整句用户输入，后续 collect 节点不再对同一句话重复聚焦抽取。
					contextMapper.set(context, PRIMARY_EXTRACT_RAN_PATH, Boolean.TRUE);
				}
				emitFlowProgress(request, instance, node, null, "FLOW_NODE_FINISHED",
						AgentRuntimeProgressService.STATUS_SUCCESS, elapsedMs(start), nodeDisplayName(node));
				currentNodeId = StringUtils.hasText(result.nextNodeId()) ? result.nextNodeId() : node.next();
				instance = instanceService.advance(instance, FlowInstanceStatus.RUNNING, currentNodeId, context, Map.of(),
						result.idempotencyKey(), request.getRuntimeRequestId(), null, null, null);
				if (publishResourceSuccess) {
					try {
						eventService.resourceSuccess(instance, node, request.getRuntimeRequestId(), result.idempotencyKey(),
								context, result.output());
					}
					catch (RuntimeException eventError) {
						log.warn("FLOW resource success event failed, flowInstanceId={}, nodeId={}", instance.getId(),
								node.id(), eventError);
					}
				}
			}
			catch (RuntimeException ex) {
				if (executeNode && ex instanceof CapabilityApprovalRequiredException approvalEx) {
					eventService.record(instance, request.getRuntimeRequestId(), node, "NODE_WAITING_APPROVAL",
							"WAITING", elapsedMs(start), Map.of(), Map.of("approvalId", approvalEx.getApprovalId()),
							approvalEx);
					NodeResult waiting = managerApprovalWaiting(request, instance, node, version, context,
							approvalEx);
					instance = instanceService.advance(instance, FlowInstanceStatus.WAITING, node.id(), context,
							waiting.waitingPayload(), instance.getIdempotencyKey(), request.getRuntimeRequestId(),
							null, null, null);
					return response(request, instance, node, waiting, false, elapsedMs(start));
				}
				eventService.record(instance, request.getRuntimeRequestId(), node, "NODE_FAILED", "FAILED",
						elapsedMs(start), Map.of(), Map.of(), ex);
				if (isInputCollectionNode(node)) {
					return inputFailureWaiting(request, modelConfig, instance, node, context, ex);
				}
				if (recoveringUnknown || executeNode && recoverableWrite && isUncertainWriteFailure(ex)) {
					instance = instanceService.advance(instance, FlowInstanceStatus.UNKNOWN, node.id(), context,
							Map.of("action", "POLL_RESULT"),
							instance.getIdempotencyKey(), request.getRuntimeRequestId(), "WRITE_RESULT_UNKNOWN", ex.getMessage(),
							null);
					return response(request, instance, node,
							NodeResult.unknown("写入结果暂时未知，系统将先按幂等键查询结果，不会重复提交。",
									Map.of("action", "POLL_RESULT")), false, elapsedMs(start));
				}
				if (executeNode && isUncertainWriteFailure(ex)) {
					return fail(instance, request, node, context, "WRITE_RESULT_UNCERTAIN",
							"写入请求超时，结果可能已生效。系统不会自动重试，请人工核对业务单据后再决定是否重新提交。");
				}
				return fail(instance, request, node, context, ex.getClass().getSimpleName(), ex.getMessage());
			}
		}
		return fail(instance, request, null, context, "FLOW_NODE_LIMIT", "流程单轮推进超过安全上限");
	}

	private FlowExecutionResult stopRepeatedNode(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			Map<String, Object> context) {
		log.warn("FLOW repeated node visit stopped, flowInstanceId={}, nodeId={}", instance.getId(), node.id());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("blocking", true);
		payload.put("retryable", true);
		addUiConfig(payload, node.config() == null ? Map.of() : node.config(), context);
		String message = "当前步骤未能取得新结果，请换一个客户、项目或关键词后再试。";
		String action = "select".equalsIgnoreCase(node.type()) ? "SELECT" : "COLLECT";
		NodeResult waiting = NodeResult.waiting(message, action, payload);
		instance = instanceService.advance(instance, FlowInstanceStatus.WAITING, node.id(), context,
				waiting.waitingPayload(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null,
				null);
		return response(request, instance, node, waiting, false, 0L);
	}

	private void applyFlowTemporalContext(AgentRequest request, Map<String, Object> context) {
		if (request == null || request.getTemporalContext() == null) {
			return;
		}
		Map<String, Object> fixedPolicy = map(contextMapper.get(context, "/runtime/temporalPolicy"));
		AgentTemporalContext current = request.getTemporalContext();
		if (fixedPolicy.isEmpty()) {
			Map<String, Object> stored = new LinkedHashMap<>();
			stored.put("zoneId", current.zoneId().getId());
			stored.put("locale", current.locale().toLanguageTag());
			stored.put("weekStartsOn", current.weekStartsOn().name());
			stored.put("ambiguityStrategy", current.ambiguityStrategy().name());
			contextMapper.set(context, "/runtime/temporalPolicy", stored);
			applyFlowTemporalInterval(request, context);
			return;
		}
		try {
			ZoneId zoneId = ZoneId.of(firstText(text(fixedPolicy.get("zoneId")), current.zoneId().getId()));
			Locale locale = Locale.forLanguageTag(firstText(text(fixedPolicy.get("locale")),
					current.locale().toLanguageTag()));
			DayOfWeek weekStartsOn = DayOfWeek.valueOf(firstText(text(fixedPolicy.get("weekStartsOn")),
					current.weekStartsOn().name()));
			TemporalAmbiguityStrategy ambiguityStrategy = TemporalAmbiguityStrategy.valueOf(firstText(
					text(fixedPolicy.get("ambiguityStrategy")), current.ambiguityStrategy().name()));
			request.setTemporalContext(new AgentTemporalContext(current.referenceInstant(), zoneId, locale,
					current.referenceInstant().atZone(zoneId).toLocalDate(), weekStartsOn, ambiguityStrategy));
			applyFlowTemporalInterval(request, context);
		}
		catch (RuntimeException ex) {
			throw CheckedException.badRequest("FLOW temporal policy snapshot is invalid");
		}
	}

	private void applyFlowTemporalInterval(AgentRequest request, Map<String, Object> context) {
		if (request == null) {
			return;
		}
		if (request.getOrchestrationDependencyInputs() != null
				&& !request.getOrchestrationDependencyInputs().isEmpty()) {
			contextMapper.set(context, "/runtime/orchestrationDependencies",
					new LinkedHashMap<>(request.getOrchestrationDependencyInputs()));
		}
		if (request.getTemporalInterval() == null) {
			return;
		}
		var interval = request.getTemporalInterval();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("startDateInclusive", interval.startDateInclusive().toString());
		payload.put("endDateExclusive", interval.endDateExclusive().toString());
		payload.put("startInclusive", interval.startInclusive().toString());
		payload.put("endExclusive", interval.endExclusive().toString());
		payload.put("rolling", interval.rolling());
		contextMapper.set(context, "/runtime/temporalInterval", payload);
	}

	/**
	 * 恢复防重：等待恢复的实例收到与上一条已成功处理输入完全相同的文本时，不重复抽取、
	 * 不推进流程，直接按当前等待状态回应（覆盖客户端重试、IM 重发、SSE 重放等场景）。
	 */
	private FlowExecutionResult rejectDuplicateResume(AgentRequest request, DataAgentFlowInstance instance,
			String turnInputHash) {
		if (turnInputHash == null || request.getFlowAction() != null || request.getFlowTextSearchIntent() != null
				|| !FlowInstanceStatus.WAITING.name().equals(instance.getStatus())
				|| !turnInputHash.equals(instance.getInputHash())) {
			return null;
		}
		// 防重的真实目的是挡传输层重试（双击重发/IM 重发/SSE 重放，秒级）；
		// 超过窗口的同文输入视为有意重试，放行重跑。
		if (!withinDuplicateResumeWindow(instance)) {
			return null;
		}
		log.info("FLOW duplicate resume input ignored. flowInstanceId={}, runtimeRequestId={}", instance.getId(),
				request.getRuntimeRequestId());
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		String action = firstText(text(waiting.get("action")), "COLLECT");
		return response(request, instance, null, NodeResult.waiting(
				"该内容与上一条已处理的输入相同，未重复提交。请补充新的信息或按当前流程提示继续。", action, waiting),
				false, 0L);
	}

	/** 相同输入在窗口内视为传输层重试直接去重；超过窗口视为有意重试放行重跑。
	 *  遗留实例无时间戳时按放行处理，避免升级后被永久拒绝。 */
	private boolean withinDuplicateResumeWindow(DataAgentFlowInstance instance) {
		Object processedAt = contextMapper.get(readMap(instance.getContextData()), LAST_PROCESSED_INPUT_AT_PATH);
		if (!(processedAt instanceof Number number)) {
			return false;
		}
		return Duration.ofMillis(System.currentTimeMillis() - number.longValue())
			.compareTo(DUPLICATE_RESUME_WINDOW) <= 0;
	}

	/** 输入哈希只用于恢复防重比对，不回显原文，避免日志与持久层泄漏敏感输入。 */
	private String inputHash(String query) {
		if (!StringUtils.hasText(query)) {
			return null;
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(query.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	@Override
	public FlowExecutionResult cancel(AgentRequest request) {
		DataAgentFlowInstance instance = instanceService.findActive(request);
		if (instance == null) {
			return new FlowExecutionResult(null, null, "当前没有进行中的流程。", true);
		}
		Map<String, Object> context = readMap(instance.getContextData());
		// cancel 语义不含 FALLBACK：不传 nodeMap，重选动作在此入口不可用。
		FlowExecutionResult result = handleControlAction(request, instance, context, null);
		if (result != null) {
			return result;
		}
		throw CheckedException.badRequest("取消当前流程前必须进行二次确认");
	}

	private FlowExecutionResult handleControlAction(AgentRequest request, DataAgentFlowInstance instance,
			Map<String, Object> context, Map<String, FlowNode> nodeMap) {
		FlowAction action = request == null ? null : request.getFlowAction();
		String actionType = action == null ? "" : text(action.type()).toUpperCase(Locale.ROOT);
		Map<String, Object> currentWaiting = readMap(instance.getWaitingPayload());
		if (!StringUtils.hasText(actionType) && FlowInstanceStatus.WAITING.name().equals(instance.getStatus())
				&& "CANCEL_CONFIRM".equalsIgnoreCase(text(currentWaiting.get("action")))) {
			return response(request, instance, null,
					NodeResult.waiting(text(currentWaiting.get("warning")), "CANCEL_CONFIRM", currentWaiting), false, 0L);
		}
		if ("CANCEL".equals(actionType) && "APPROVAL".equalsIgnoreCase(text(currentWaiting.get("action")))) {
			return null;
		}
		if ("FALLBACK".equals(actionType) && nodeMap != null) {
			return handleReselectAction(request, instance, context, nodeMap);
		}
		if (!List.of("CANCEL", "CONFIRM_CANCEL", "KEEP_FLOW").contains(actionType)) {
			return null;
		}
		if ("CANCEL".equals(actionType) && FlowInstanceStatus.RUNNING.name().equals(instance.getStatus())) {
			return requestCancellation(request, instance, context, true);
		}
		if (!FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			if (FlowInstanceStatus.EXECUTING.name().equals(instance.getStatus())
					|| FlowInstanceStatus.UNKNOWN.name().equals(instance.getStatus())) {
				throw CheckedException.badRequest("当前流程已经开始执行，正在确认业务结果，当前不能取消");
			}
			throw CheckedException.badRequest("只有等待用户操作的流程可以取消");
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		if ("CANCEL".equals(actionType)) {
			return requestCancellation(request, instance, context, false);
		}
		if (!"CANCEL_CONFIRM".equals(text(waiting.get("action")).toUpperCase(Locale.ROOT))) {
			throw CheckedException.badRequest("当前流程没有等待取消确认");
		}
		if ("KEEP_FLOW".equals(actionType)) {
			Map<String, Object> checkpoint = map(contextMapper.get(context, "/runtime/cancelCheckpoint"));
			String nodeId = firstText(text(checkpoint.get("nodeId")), instance.getCurrentNodeId());
			Map<String, Object> restored = map(checkpoint.get("waitingPayload"));
			String restoredAction = firstText(text(restored.get("action")), "REVIEW");
			contextMapper.set(context, "/runtime/cancelCheckpoint", null);
			instance = instanceService.advance(instance, FlowInstanceStatus.WAITING, nodeId, context, restored,
					instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null, null);
			String continueText = cancelPolicyTextFromPayload(restored, "continueText", "已继续当前流程。");
			return response(request, instance, null,
					NodeResult.waiting(continueText, restoredAction, restored), false, 0L);
		}
		contextMapper.set(context, "/runtime/cancelCheckpoint", null);
		instance = instanceService.advance(instance, FlowInstanceStatus.CANCELLED, instance.getCurrentNodeId(), context,
				Map.of(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null, Instant.now());
		String successText = cancelPolicyTextFromPayload(waiting, "successText", "已取消当前流程。");
		NodeResult result = NodeResult.terminal(FlowInstanceStatus.CANCELLED, successText, "CANCELLED",
				Map.of(), null, null);
		return response(request, instance, null, result, true, 0L);
	}

	/**
	 * 重选动作：清当前选择链上声明的路径并改道到 fallbackNode，让用户重新选择。
	 * 返回 null 表示已改道、主循环接管；FALLBACK payload 允许携带 resumeVersion，
	 * 版本校验仍由 applyWaitingInput 的 validateResumeVersion 统一执行（过期版本照样拒绝）。
	 */
	private FlowExecutionResult handleReselectAction(AgentRequest request, DataAgentFlowInstance instance,
			Map<String, Object> context, Map<String, FlowNode> nodeMap) {
		if (!FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			throw CheckedException.badRequest("只有等待用户操作的流程可以重新选择");
		}
		FlowNode node = nodeMap == null ? null : nodeMap.get(instance.getCurrentNodeId());
		Map<String, Object> config = node == null || node.config() == null ? Map.of() : node.config();
		String fallbackNode = text(config.get("fallbackNode"));
		if (!StringUtils.hasText(fallbackNode) || nodeMap == null || !nodeMap.containsKey(fallbackNode)) {
			throw CheckedException.badRequest("当前流程不支持重新选择");
		}
		for (String path : strings(config.get("fallbackClears"))) {
			contextMapper.set(context, path, null);
		}
		contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, fallbackNode);
		log.info("FLOW reselect fallback applied. nodeId={}, fallbackNode={}", instance.getCurrentNodeId(),
				fallbackNode);
		return null;
	}

	private FlowExecutionResult requestCancellation(AgentRequest request, DataAgentFlowInstance instance,
			Map<String, Object> context, boolean resolverRunning) {
		Map<String, Object> checkpoint = resolverRunning
				? map(contextMapper.get(context, "/runtime/lastInteractionCheckpoint")) : Map.of();
		if (checkpoint.isEmpty()) {
			checkpoint = new LinkedHashMap<>();
			checkpoint.put("nodeId", instance.getCurrentNodeId());
			checkpoint.put("waitingPayload", readMap(instance.getWaitingPayload()));
		}
		contextMapper.set(context, "/runtime/cancelCheckpoint", checkpoint);
		if (resolverRunning) {
			cancelResolverRun(instance.getId());
		}
		Map<String, Object> sourcePayload = map(checkpoint.get("waitingPayload"));
		Map<String, Object> cancelPolicy = cancelPolicy(sourcePayload);
		String question = cancelPolicyText(cancelPolicy, "question", "取消后当前流程将结束，是否确认取消？");
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("warning", question);
		if (!cancelPolicy.isEmpty()) {
			payload.put("cancelPolicy", cancelPolicy);
		}
		NodeResult result = NodeResult.waiting(question, "CANCEL_CONFIRM", payload);
		instance = instanceService.advance(instance, FlowInstanceStatus.WAITING, instance.getCurrentNodeId(), context,
				result.waitingPayload(), instance.getIdempotencyKey(), request.getRuntimeRequestId(), null, null, null);
		return response(request, instance, null, result, false, 0L);
	}

	private NodeResult executeNode(AgentRequest request, ModelConfigDTO modelConfig, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> context) {
		String type = nodeRegistry.requireSupported(node.type());
		Map<String, Object> config = node.config() == null ? Map.of() : node.config();
		return switch (type) {
			case "extract" -> extract(request, modelConfig, instance, version, node, config, context);
			case "collect" -> collect(request, modelConfig, instance, version, node, config, context);
			case "review" -> review(request, modelConfig, instance, version, node, config, context);
			case "resolve" -> resolve(request, instance, version, node, config, context);
			case "select" -> select(request, version, node, config, context);
			case "merge" -> merge(request, node, config, context);
			case "validate" -> validate(request, instance, version, node, config, context);
			case "switch" -> switchNode(node, context);
			case "confirm" -> confirm(request, version, node, config, context);
			case "execute" -> executeTool(request, instance, version, node, config, context);
			case "present" -> present(node, config, context);
			case "handoff" -> handoff(node, config, context);
			case "end" -> end(instance, config, context);
			case "error" -> NodeResult.terminal(FlowInstanceStatus.FAILED, render(config, "text", context,
					"流程执行失败。"), "FAILED", Map.of(), text(config.get("errorCode")), instance.getIdempotencyKey());
			default -> throw CheckedException.badRequest("Unsupported FLOW node type: " + type);
		};
	}

	private String textSearchStartNode(AgentRequest request, DataAgentFlowInstance instance,
			Map<String, FlowNode> nodeMap) {
		FlowTextSearchIntent intent = request == null ? null : request.getFlowTextSearchIntent();
		if (intent == null || intent.consumed() || !StringUtils.hasText(intent.resolverNodeId())) {
			return instance.getCurrentNodeId();
		}
		if (!StringUtils.hasText(intent.waitingNodeId()) || !intent.waitingNodeId().equals(instance.getCurrentNodeId())) {
			throw CheckedException.badRequest("候选列表已更新，请使用最新列表重新选择");
		}
		FlowNode resolver = nodeMap.get(intent.resolverNodeId());
		if (resolver == null || !"resolve".equalsIgnoreCase(resolver.type())) {
			throw CheckedException.badRequest("当前文本查询未绑定有效的 READ Resolver");
		}
		return resolver.id();
	}

	private boolean isInputCollectionNode(FlowNode node) {
		if (node == null) {
			return false;
		}
		String type = node.type() == null ? "" : node.type().trim().toLowerCase(Locale.ROOT);
		return "extract".equals(type) || "collect".equals(type) || "review".equals(type)
				|| "validate".equals(type);
	}

	private boolean isInputCollectionWait(DataAgentFlowInstance instance, FlowNode node) {
		if (isInputCollectionNode(node)) {
			return true;
		}
		String action = text(readMap(instance == null ? null : instance.getWaitingPayload()).get("action"));
		return List.of("COLLECT", "VALIDATION", "REVIEW").contains(action);
	}

	private FlowExecutionResult inputFailureWaiting(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance,
			FlowNode node, Map<String, Object> context, RuntimeException error) {
		Map<String, Object> config = node.config() == null ? Map.of() : node.config();
		String type = node.type() == null ? "" : node.type().trim().toLowerCase(Locale.ROOT);
		Map<String, Object> existingPayload = readMap(instance.getWaitingPayload());
		String existingAction = text(existingPayload.get("action"));
		String action = List.of("COLLECT", "VALIDATION", "REVIEW").contains(existingAction) ? existingAction
				: "validate".equals(type) ? "VALIDATION" : "review".equals(type) ? "REVIEW" : "COLLECT";
		boolean isRecoveryExtraction = "EXTRACT".equals(existingAction)
				&& "EXTRACT".equalsIgnoreCase(text(existingPayload.get("originAction")));
		Map<String, Object> payload = (action.equals(existingAction) || isRecoveryExtraction)
				? new LinkedHashMap<>(existingPayload) : new LinkedHashMap<>();
		payload.put("schema", map(config.get("schema")));
		payload.put("instruction", text(config.get("instruction")));
		String errorCode = extractionErrorCode(error);
		payload.put("retryable", isRetryableExtractionError(errorCode));
		payload.put("errorCode", errorCode);
		putInternalExtractionConfig(payload, config);
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		addUiConfig(payload, config, context);
		String message = "validate".equals(type) ? "字段校验暂时失败，请修正或重试" : "信息抽取暂时失败，请补充字段或重试";
		String originAction = firstText(text(existingPayload.get("originAction")),
				"extract".equals(type) ? "EXTRACT" : action);
		payload.put("originNodeId", node.id());
		payload.put("originNodeType", type);
		payload.put("originAction", originAction);
		payload.put("originOutputPath", firstText(text(existingPayload.get("originOutputPath")),
				text(config.get("outputPath")), INPUT_ROOT_PATH));
		payload.put("originNextNodeId", firstText(text(existingPayload.get("originNextNodeId")), node.next()));
		payload.put("recoveryMode", firstText(text(existingPayload.get("recoveryMode")),
				"extract".equals(type) ? "EXTRACT_THEN_ADVANCE"
						: "review".equals(type) ? "REVIEW_EDIT_THEN_REFRESH" : "APPLY_THEN_REENTER"));
		long reaskFailures = number(existingPayload.get("reaskFailures"), 0L) + 1L;
		payload.put("reaskFailures", reaskFailures);
		boolean inputCapacityExceeded = FlowExtractionException.TRUNCATED.equals(errorCode)
				|| FlowExtractionException.INPUT_TOO_LARGE.equals(errorCode);
		boolean partialCollected = !strings(contextMapper.get(context, LAST_CHANGED_PATHS_PATH)).isEmpty();
		boolean schemaMismatch = FlowExtractionException.INVALID_RESPONSE.equals(errorCode);
		// 超时、限流这类错误码单次看是瞬时的，但连续 REASK 到阈值说明当前模型这一轮压根跑不通，
		// 再让用户重述也只是重复失败，此时升级为「换模型」。容量类错误和 JSON 形状不整换模型无益，仍走 COLLECT。
		String waitingAction = inputCapacityExceeded || partialCollected || schemaMismatch ? "COLLECT"
				: requiresModelChange(errorCode) || reaskFailures >= MAX_REASK_FAILURES_BEFORE_MODEL_CHANGE
						? "CHANGE_MODEL" : "REASK";
		if ("CHANGE_MODEL".equals(waitingAction)) {
			payload.put("blockedModelConfigId", modelConfig == null ? null : modelConfig.getId());
			payload.put("blockedModelLastModifyTime",
					modelConfig == null || modelConfig.getLastModifyTime() == null ? null
							: modelConfig.getLastModifyTime().toString());
			message = "当前聊天模型暂时无法完成本次信息识别，请切换可用模型后继续。";
		}
		else {
			message = extractionFailurePrompt(config, context, inputCapacityExceeded, partialCollected);
		}
		NodeResult waitingResult = NodeResult.waiting(message, waitingAction, payload);
		// 技术失败=本条输入未被成功处理：清空输入 hash 允许用户原文重试，且本回合结果按 FAILED
		// 上抛（区别于业务字段缺失的 WAITING 澄清），实例保持 WAITING 支持下一回合恢复。
		instance.setInputHash(null);
		instance = instanceService.advance(instance, FlowInstanceStatus.WAITING, node.id(), context,
				waitingResult.waitingPayload(),
				instance.getIdempotencyKey(), request.getRuntimeRequestId(), errorCode, message, null);
		return response(request, instance, node, waitingResult, false, 0L,
				FlowTurnOutcome.FAILED);
	}

	private String extractionErrorCode(RuntimeException error) {
		return error instanceof FlowExtractionException extractionError ? extractionError.errorCode()
				: FlowExtractionException.CONFIG_ERROR;
	}

	private boolean requiresModelChange(String errorCode) {
		return FlowExtractionException.CONFIG_ERROR.equals(errorCode)
				|| FlowExtractionException.AUTHENTICATION_ERROR.equals(errorCode)
				|| FlowExtractionException.PROTOCOL_INCOMPATIBLE.equals(errorCode);
	}

	private boolean isRetryableExtractionError(String errorCode) {
		return FlowExtractionException.TIMEOUT.equals(errorCode)
				|| FlowExtractionException.RATE_LIMITED.equals(errorCode)
				|| FlowExtractionException.PROVIDER_ERROR.equals(errorCode)
				|| FlowExtractionException.UPSTREAM_ERROR.equals(errorCode);
	}

	/** SELECT 等待态技术性抽取失败：保持等待并返回可重试恢复卡。仅转换 FlowExtractionException；
	 *  校验类异常（resumeVersion/动作不匹配）不经此转换，避免吞掉协议保护导致过期选点被静默丢弃。 */
	private FlowExecutionResult selectInputFailureWaiting(AgentRequest request, DataAgentFlowInstance instance,
			FlowNode node, Map<String, Object> context, RuntimeException error) {
		String errorCode = extractionErrorCode(error);
		Map<String, Object> payload = new LinkedHashMap<>(readMap(instance.getWaitingPayload()));
		payload.put("retryable", isRetryableExtractionError(errorCode));
		String message = "信息识别暂时失败，请稍后重试或换一种说法。";
		// 技术失败=本条输入未被成功处理：清空输入 hash 允许用户原文重试（与 inputFailureWaiting 同语义）。
		instance.setInputHash(null);
		instance = instanceService.advance(instance, FlowInstanceStatus.WAITING,
				node == null ? instance.getCurrentNodeId() : node.id(), context, payload,
				instance.getIdempotencyKey(), request.getRuntimeRequestId(), errorCode, message, null);
		return response(request, instance, node, NodeResult.waiting(message, "SELECT", payload), false, 0L,
				FlowTurnOutcome.FAILED);
	}

	private FlowExecutionResult prepareRecoveryWait(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, Map<String, Object> context) {
		if (!FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			return null;
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		String action = text(waiting.get("action")).toUpperCase(Locale.ROOT);
		if (!"REASK".equals(action) && !"CHANGE_MODEL".equals(action)) {
			return null;
		}
		if (!StringUtils.hasText(request.getQuery()) && request.getFlowAction() == null) {
			return response(request, instance, null,
					NodeResult.waiting("请继续说明需要办理的内容。", action, waiting), false, 0L,
					FlowTurnOutcome.WAITING);
		}
		if ("CHANGE_MODEL".equals(action) && !modelIdentityChanged(waiting, modelConfig)) {
			return response(request, instance, null,
					NodeResult.waiting("当前模型尚未变更，请切换可用模型后继续。", action, waiting), false, 0L,
					FlowTurnOutcome.WAITING);
		}
		String originAction = text(waiting.get("originAction")).toUpperCase(Locale.ROOT);
		if (!StringUtils.hasText(originAction)) {
			throw new FlowExtractionException(FlowExtractionException.CONFIG_ERROR,
					"FLOW extraction recovery metadata is incomplete", null);
		}
		Map<String, Object> recovery = new LinkedHashMap<>(waiting);
		recovery.put("action", originAction);
		recovery.remove("blockedModelConfigId");
		recovery.remove("blockedModelLastModifyTime");
		replaceWaitingPayload(instance, recovery);
		return null;
	}

	private boolean modelIdentityChanged(Map<String, Object> waiting, ModelConfigDTO modelConfig) {
		String blockedId = text(waiting.get("blockedModelConfigId"));
		String blockedRevision = text(waiting.get("blockedModelLastModifyTime"));
		String currentId = modelConfig == null || modelConfig.getId() == null ? "" : String.valueOf(modelConfig.getId());
		String currentRevision = modelConfig == null || modelConfig.getLastModifyTime() == null ? ""
				: modelConfig.getLastModifyTime().toString();
		return !Objects.equals(blockedId, currentId) || !Objects.equals(blockedRevision, currentRevision);
	}

	private void replaceWaitingPayload(DataAgentFlowInstance instance, Map<String, Object> waiting) {
		try {
			instance.setWaitingPayload(objectMapper.writeValueAsString(waiting));
		}
		catch (Exception ex) {
			throw new FlowExtractionException(FlowExtractionException.CONFIG_ERROR,
					"FLOW extraction recovery metadata is invalid", ex);
		}
	}

	private boolean isParallelResolverBatch(FlowNode node) {
		if (node == null || !"resolve".equalsIgnoreCase(node.type()) || node.config() == null) {
			return false;
		}
		Object batch = node.config().get("parallelResolvers");
		return batch instanceof Iterable<?> iterable && iterable.iterator().hasNext();
	}

	/**
	 * 并行 Resolver 批次入口：收集可执行任务后按波次并发执行，任一必填缺失/失败转等待。
	 */
	private NodeResult executeParallelResolverBatch(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> context) {
		List<Map<String, Object>> configs = mapList(node.config().get("parallelResolvers"));
		Map<String, Object> runtimeConfig = map(context.get(FLOW_RUNTIME_CONFIG_KEY));
		DataAgentProperties.Flow flowProperties = dataAgentProperties.getFlow();
		int maxParallel = effectiveLimit(runtimeConfig, "maxParallelResolvers", flowProperties.getMaxParallelResolvers(), 1, 16);
		int maxWaves = effectiveLimit(runtimeConfig, "maxResolverWaves", flowProperties.getMaxResolverWaves(), 1, 32);
		int maxItems = effectiveLimit(runtimeConfig, "maxFanOutItems", flowProperties.getMaxFanOutItems(), 1, 100);
		if (configs.size() > maxItems) {
			throw CheckedException.badRequest("Resolver batch exceeds the configured fan-out limit");
		}

		List<String> missing = new ArrayList<>();
		List<ResolverTask> tasks = collectParallelResolverTasks(request, node, configs, context, missing);
		if (!missing.isEmpty()) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("requiredPaths", missing.stream().distinct().toList());
			payload.put("resolverIds", configs.stream().map(item -> firstText(text(item.get("id")), "resolver"))
					.toList());
			return NodeResult.waiting("请先补充查询所需的信息", "COLLECT", payload);
		}
		if (tasks.isEmpty()) {
			return NodeResult.next(node.next(), Map.of("reused", true));
		}

		Map<String, Object> outputs = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();
		String invocationFailureMessage = null;
		int completedWaves = 0;
		ResolverRun resolverRun = beginResolverRun(instance.getId());
		try {
			for (int offset = 0; offset < tasks.size() && completedWaves < maxWaves; offset += maxParallel) {
				completedWaves++;
				List<ResolverTask> wave = tasks.subList(offset, Math.min(offset + maxParallel, tasks.size()));
				Map<String, Long> taskStarts = new LinkedHashMap<>();
				List<CompletableFuture<ResolverOutcome>> futures = new ArrayList<>();
				NodeResult submitFailure = submitResolverWave(request, instance, version, node, resolverRun, wave,
						taskStarts, futures);
				if (submitFailure != null) {
					return submitFailure;
				}
				invocationFailureMessage = collectResolverWaveOutcomes(request, instance, node, context, wave, futures,
						taskStarts, outputs, errors, invocationFailureMessage);
			}
			if (completedWaves >= maxWaves && tasks.size() > completedWaves * maxParallel) {
				errors.add("resolver batch wave limit exceeded");
			}
			if (!errors.isEmpty()) {
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("errors", errors);
				payload.put("completed", outputs.keySet());
				payload.put("retryable", true);
				return NodeResult.waiting(firstText(invocationFailureMessage, "部分数据查询失败，请重试查询"),
						"RESOLVER_RETRY", payload);
			}
			return NodeResult.next(node.next(), outputs);
		}
		finally {
			endResolverRun(instance.getId(), resolverRun);
		}
	}

	/**
	 * 逐条校验并行 Resolver 配置并生成待执行任务：跳过已完成/指纹未变/允许缺省的项，
	 * 必填缺失累计到 missing 由调用方转等待。
	 */
	private List<ResolverTask> collectParallelResolverTasks(AgentRequest request, FlowNode node,
			List<Map<String, Object>> configs, Map<String, Object> context, List<String> missing) {
		long revision = number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L);
		List<ResolverTask> tasks = new ArrayList<>();
		for (int index = 0; index < configs.size(); index++) {
			Map<String, Object> config = configs.get(index);
			String resolverId = firstText(text(config.get("id")), node.id() + "-" + index);
			if (!Boolean.TRUE.equals(config.get("parallelSafe"))) {
				throw CheckedException.badRequest("Parallel resolver must explicitly set parallelSafe=true: " + resolverId);
			}
			Long resourceVersionId = longValue(config.get("resourceVersionId"));
			if (resourceVersionId == null) {
				throw CheckedException.badRequest("Parallel resolver must pin resourceVersionId: " + resolverId);
			}
			AgentExecutionResourceVersion resource = resourceVersionMapper.findPublished(resourceVersionId);
			if (resource != null && "WRITE".equalsIgnoreCase(resource.getAccessMode())) {
				throw CheckedException.badRequest("WRITE tools cannot run in parallel resolver batches: " + resolverId);
			}
			Map<String, Object> arguments = AgentRequestSnapshotSupport.enrichArguments(arguments(config, context), request);
			arguments = applyTextSearchOverride(request, resolverId, null, arguments);
			String fingerprint = fingerprint(resolverId, resourceVersionId, arguments);
			if (Boolean.TRUE.equals(config.get("skipWhenComplete"))) {
				List<String> completePaths = strings(config.get("completePaths"));
				if (!completePaths.isEmpty()
						&& completePaths.stream().allMatch(path -> !contextMapper.isEmpty(contextMapper.get(context, path)))) {
					continue;
				}
			}
			Map<String, Object> state = resolverState(context, resolverId);
			if ("RESOLVED".equals(state.get("status")) && fingerprint.equals(state.get("fingerprint"))) {
				continue;
			}
			List<String> required = missingRequirements(config, context);
			if (!required.isEmpty()) {
				if (Boolean.TRUE.equals(config.get("skipIfMissing"))) {
					continue;
				}
				missing.addAll(required);
				continue;
			}
			tasks.add(new ResolverTask(resolverId, resourceVersionId, config, arguments, fingerprint, revision,
					resolvedToolDisplayName(resource)));
		}
		return tasks;
	}

	/**
	 * 提交一个波次的 Resolver 任务；执行队列拒绝或已取消时统一收尾并返回等待结果，正常提交返回 null。
	 */
	private NodeResult submitResolverWave(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, ResolverRun resolverRun, List<ResolverTask> wave,
			Map<String, Long> taskStarts, List<CompletableFuture<ResolverOutcome>> futures) {
		try {
			for (ResolverTask task : wave) {
				taskStarts.put(task.id(), System.nanoTime());
				emitFlowProgress(request, instance, node, task.id(), "FLOW_RESOLVER_RUNNING",
						AgentRuntimeProgressService.STATUS_RUNNING, null, resolverDisplayName(task));
				futures.add(submitResolver(resolverRun,
						() -> invokeResolverTask(request, instance, version, node.id(), task)));
			}
			return null;
		}
		catch (RejectedExecutionException | CancellationException ex) {
			futures.forEach(future -> future.cancel(true));
			wave.stream()
				.filter(task -> taskStarts.containsKey(task.id()))
				.forEach(task -> emitResolverFinished(request, instance, node, task, taskStarts,
						ex instanceof CancellationException ? AgentRuntimeProgressService.STATUS_CANCELLED
								: AgentRuntimeProgressService.STATUS_FAILED, ex));
			return resolverWaiting(ex instanceof CancellationException
					? "流程已进入取消确认，当前查询已停止" : "Resolver 执行队列已满，请稍后重试",
					List.of(firstText(ex.getMessage(), ex instanceof CancellationException
							? "FLOW_RESOLVER_CANCELLED" : "FLOW_RESOLVER_QUEUE_FULL")));
		}
	}

	/**
	 * 收集一个波次的执行结果：成功写回 context 与 outputs，失败/超时/取消计入 errors；
	 * 返回首个工具调用类失败消息（已有则保持不变），供批次汇总提示使用。
	 */
	private String collectResolverWaveOutcomes(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			Map<String, Object> context, List<ResolverTask> wave, List<CompletableFuture<ResolverOutcome>> futures,
			Map<String, Long> taskStarts, Map<String, Object> outputs, List<String> errors,
			String invocationFailureMessage) {
		for (int index = 0; index < futures.size(); index++) {
			ResolverTask task = wave.get(index);
			try {
				long timeoutMs = remainingResolverTimeoutMs(request);
				ResolverOutcome outcome = futures.get(index).get(timeoutMs, TimeUnit.MILLISECONDS);
				if (outcome.error() != null) {
					markResolverFailed(context, task, outcome.error());
					emitResolverFinished(request, instance, node, task, taskStarts,
							AgentRuntimeProgressService.STATUS_FAILED, outcome.error());
					errors.add(task.id() + ": " + outcome.error().getMessage());
					if (invocationFailureMessage == null && outcome.error() instanceof ToolInvocationException) {
						invocationFailureMessage = outcome.error().getMessage();
					}
					continue;
				}
				contextMapper.set(context, outputPath(task.config(), task.id()), outcome.result());
				markResolverResolved(context, task);
				emitResolverFinished(request, instance, node, task, taskStarts,
						AgentRuntimeProgressService.STATUS_SUCCESS, null);
				outputs.put(task.id(), outcome.result());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Resolver batch interrupted", ex);
			}
			catch (ExecutionException | TimeoutException ex) {
				Throwable cause = ex instanceof ExecutionException execution ? execution.getCause() : ex;
				markResolverFailed(context, task, cause);
				futures.get(index).cancel(true);
				emitResolverFinished(request, instance, node, task, taskStarts,
						AgentRuntimeProgressService.STATUS_FAILED, cause);
				errors.add(task.id() + ": " + cause.getMessage());
				if (invocationFailureMessage == null && cause instanceof ToolInvocationException) {
					invocationFailureMessage = cause.getMessage();
				}
			}
			catch (CancellationException ex) {
				emitResolverFinished(request, instance, node, task, taskStarts,
						AgentRuntimeProgressService.STATUS_CANCELLED, ex);
				errors.add(task.id() + ": cancelled");
			}
		}
		return invocationFailureMessage;
	}

	private ResolverOutcome invokeResolverTask(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, String invokingNodeId, ResolverTask task) {
		try {
			Map<String, Object> result = invokeToolViaGateway(request, instance, version, invokingNodeId,
					task.resourceVersionId(), "READ", false, null, task.arguments(), task.config());
			return new ResolverOutcome(result == null ? Map.of() : result, null);
		}
		catch (RuntimeException ex) {
			return new ResolverOutcome(Map.of(), ex);
		}
	}

	private List<Map<String, Object>> mapList(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object item : iterable) {
			if (item instanceof Map<?, ?> map) {
				result.add(map(map));
			}
		}
		return result;
	}

	private List<String> missingRequirements(Map<String, Object> config, Map<String, Object> context) {
		List<String> missing = new ArrayList<>();
		for (String path : strings(config.get("requiresAll"))) {
			if (contextMapper.isEmpty(contextMapper.get(context, path))) {
				missing.add(path);
			}
		}
		List<String> any = strings(config.get("requiresAny"));
		if (!any.isEmpty() && any.stream().noneMatch(path -> !contextMapper.isEmpty(contextMapper.get(context, path)))) {
			missing.addAll(any);
		}
		return missing;
	}

	private Map<String, Object> resolverState(Map<String, Object> context, String resolverId) {
		String path = "/resolverState/" + escapePointer(resolverId);
		Object value = contextMapper.get(context, path);
		if (value instanceof Map<?, ?> state) {
			return map(state);
		}
		Map<String, Object> created = new LinkedHashMap<>();
		contextMapper.set(context, path, created);
		return created;
	}

	private void markResolverResolved(Map<String, Object> context, ResolverTask task) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("fingerprint", task.fingerprint());
		state.put("status", "RESOLVED");
		state.put("outputRevision", task.revision());
		contextMapper.set(context, "/resolverState/" + escapePointer(task.id()), state);
	}

	private void markResolverFailed(Map<String, Object> context, ResolverTask task, Throwable error) {
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("fingerprint", task.fingerprint());
		state.put("status", "FAILED");
		state.put("outputRevision", task.revision());
		state.put("error", error == null ? "unknown" : String.valueOf(error.getMessage()));
		contextMapper.set(context, "/resolverState/" + escapePointer(task.id()), state);
	}

	private void incrementContextRevision(Map<String, Object> context) {
		long revision = number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L);
		contextMapper.set(context, CONTEXT_REVISION_PATH, revision + 1L);
	}

	private void markSlot(Map<String, Object> context, String path, String status, String source) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("status", status);
		metadata.put("source", source);
		metadata.put("revision", number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L));
		contextMapper.set(context, "/slotMeta/" + escapePointer(path), metadata);
		recordChangedPath(context, path);
	}

	private void recordChangedPath(Map<String, Object> context, String path) {
		List<String> changed = new ArrayList<>(strings(contextMapper.get(context, LAST_CHANGED_PATHS_PATH)));
		if (!changed.contains(path)) {
			changed.add(path);
		}
		contextMapper.set(context, LAST_CHANGED_PATHS_PATH, changed);
	}

	private void invalidateDependentResolvers(Map<String, Object> context, Map<String, FlowNode> nodeMap) {
		List<String> changed = strings(contextMapper.get(context, LAST_CHANGED_PATHS_PATH));
		if (changed.isEmpty()) {
			return;
		}
		for (FlowNode flowNode : nodeMap.values()) {
			if (flowNode == null || !"resolve".equalsIgnoreCase(flowNode.type()) || flowNode.config() == null) {
				continue;
			}
			List<String> triggerPaths = strings(flowNode.config().get("invalidateOn"));
			if (triggerPaths.isEmpty()) {
				triggerPaths = strings(flowNode.config().get("invalidates"));
			}
			List<String> effectiveTriggers = triggerPaths;
			boolean triggered = changed.stream().anyMatch(changedPath -> effectiveTriggers.stream().anyMatch(trigger ->
					changedPath.equals(trigger) || changedPath.startsWith(trigger + "/")
							|| trigger.startsWith(changedPath + "/")));
			if (!triggered) {
				continue;
			}
			contextMapper.set(context, "/resolverState/" + escapePointer(flowNode.id()),
					Map.of("status", "INVALIDATED"));
			if (Boolean.TRUE.equals(flowNode.config().get("clearReferenceOnInvalidate"))) {
				clearHistoryReference(context, false);
			}
			for (String invalidatedPath : strings(flowNode.config().get("invalidates"))) {
				if (changed.stream().noneMatch(invalidatedPath::equals)) {
					contextMapper.set(context, invalidatedPath, null);
				}
			}
		}
		contextMapper.set(context, LAST_CHANGED_PATHS_PATH, List.of());
	}

	private void recordMergedPaths(Map<String, Object> context, String targetPath, Object source) {
		if (source == null) {
			return;
		}
		if (source instanceof Map<?, ?> sourceMap) {
			for (Object key : sourceMap.keySet()) {
				recordChangedPath(context, targetPath + "/" + escapePointer(String.valueOf(key)));
			}
		}
		else {
			recordChangedPath(context, targetPath);
		}
	}

	private void markValueSlots(Map<String, Object> context, String path, Object value, String source) {
		markSlot(context, path, contextMapper.isEmpty(value) ? "CLEARED" : "PROVIDED", source);
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, nested) -> markValueSlots(context,
					path + "/" + escapePointer(String.valueOf(key)), nested, source));
		}
		else if (value instanceof List<?> list) {
			for (int index = 0; index < list.size(); index++) {
				markValueSlots(context, path + "/" + index, list.get(index), source);
			}
		}
	}

	private List<String> missingMergeTargets(Map<String, Object> context, String targetPath, Object source) {
		if (source == null || !StringUtils.hasText(targetPath)) {
			return List.of();
		}
		if (!(source instanceof Map<?, ?> sourceMap)) {
			return contextMapper.isEmpty(contextMapper.get(context, targetPath)) ? List.of(targetPath) : List.of();
		}
		List<String> targets = new ArrayList<>();
		for (Object key : sourceMap.keySet()) {
			String path = targetPath + "/" + escapePointer(String.valueOf(key));
			if (contextMapper.isEmpty(contextMapper.get(context, path))) {
				targets.add(path);
			}
		}
		return List.copyOf(targets);
	}

	private boolean hasHistoryReference(Map<String, Object> context) {
		return map(contextMapper.get(context, "/slotMeta")).values().stream()
			.filter(Map.class::isInstance)
			.map(this::map)
			.anyMatch(meta -> isHistoryOwned(meta)
					&& !"CLEARED".equalsIgnoreCase(text(meta.get("status"))));
	}

	private void clearHistoryReference(Map<String, Object> context) {
		clearHistoryReference(context, true);
	}

	private void clearHistoryReference(Map<String, Object> context, boolean skipForCurrentFlow) {
		Map<String, Object> slotMeta = map(contextMapper.get(context, "/slotMeta"));
		List<String> userPaths = slotMeta.entrySet().stream()
			.filter(entry -> entry.getValue() instanceof Map<?, ?>)
			.filter(entry -> "USER".equalsIgnoreCase(text(map(entry.getValue()).get("source"))))
			.map(Map.Entry::getKey)
			.toList();
		List<String> historyPaths = slotMeta.entrySet().stream()
			.filter(entry -> entry.getValue() instanceof Map<?, ?>)
			.filter(entry -> isHistoryOwned(map(entry.getValue())))
			.map(Map.Entry::getKey)
			.toList();
		List<String> clearablePaths = historyPaths.stream()
			.filter(path -> userPaths.stream().noneMatch(userPath -> userPath.equals(path)
					|| userPath.startsWith(path + "/") || path.startsWith(userPath + "/")))
			.sorted(java.util.Comparator.comparingInt(String::length).reversed())
			.toList();
		for (String path : clearablePaths) {
			contextMapper.set(context, path, null);
		}
		for (String path : historyPaths) {
			markSlot(context, path, "CLEARED", "USER");
		}
		compactClearedHistoryCollections(context, historyPaths);
		contextMapper.set(context, "/resolved/historySelection", null);
		contextMapper.set(context, "/resolved/history", null);
		if (skipForCurrentFlow) {
			contextMapper.set(context, REFERENCE_DECISION_PATH, "SKIP_CURRENT_FLOW");
		}
		invalidateApproval(context);
	}

	private void compactClearedHistoryCollections(Map<String, Object> context, List<String> historyPaths) {
		List<String> collectionPaths = historyPaths.stream()
			.filter(path -> contextMapper.get(context, path) instanceof List<?>)
			.distinct()
			.sorted(java.util.Comparator.comparingInt(String::length).reversed())
			.toList();
		for (String collectionPath : collectionPaths) {
			Object value = contextMapper.get(context, collectionPath);
			if (!(value instanceof List<?> list)) {
				continue;
			}
			List<Object> compacted = list.stream().filter(this::hasMeaningfulValue).map(item -> (Object) item).toList();
			if (compacted.size() == list.size()) {
				continue;
			}
			remapCollectionSlotMeta(context, collectionPath, list);
			contextMapper.set(context, collectionPath, compacted.isEmpty() ? null : new ArrayList<>(compacted));
			contextMapper.set(context, "/runtime/collectionItems", null);
		}
	}

	private void remapCollectionSlotMeta(Map<String, Object> context, String collectionPath, List<?> original) {
		Map<String, Object> slotMeta = map(contextMapper.get(context, "/slotMeta"));
		Map<Integer, Integer> newIndexes = new LinkedHashMap<>();
		int nextIndex = 0;
		for (int index = 0; index < original.size(); index++) {
			if (hasMeaningfulValue(original.get(index))) {
				newIndexes.put(index, nextIndex++);
			}
		}
		Map<String, Object> remapped = new LinkedHashMap<>();
		String prefix = collectionPath + "/";
		for (Map.Entry<String, Object> entry : slotMeta.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				remapped.put(entry.getKey(), entry.getValue());
				continue;
			}
			String suffix = entry.getKey().substring(prefix.length());
			int separator = suffix.indexOf('/');
			String indexValue = separator < 0 ? suffix : suffix.substring(0, separator);
			try {
				Integer newIndex = newIndexes.get(Integer.valueOf(indexValue));
				if (newIndex != null) {
					String remainder = separator < 0 ? "" : suffix.substring(separator);
					remapped.put(prefix + newIndex + remainder, entry.getValue());
				}
			}
			catch (NumberFormatException ex) {
				remapped.put(entry.getKey(), entry.getValue());
			}
		}
		contextMapper.set(context, "/slotMeta", remapped);
	}

	private boolean hasMeaningfulValue(Object value) {
		if (value == null || value instanceof String text && !StringUtils.hasText(text)) {
			return false;
		}
		if (value instanceof Map<?, ?> map) {
			return map.values().stream().anyMatch(this::hasMeaningfulValue);
		}
		if (value instanceof Iterable<?> iterable) {
			for (Object item : iterable) {
				if (hasMeaningfulValue(item)) {
					return true;
				}
			}
			return false;
		}
		return true;
	}

	private boolean isHistoryOwned(Map<String, Object> metadata) {
		return "HISTORY".equalsIgnoreCase(text(metadata.get("source")))
				|| "HISTORY".equalsIgnoreCase(text(metadata.get("origin")));
	}

	private void invalidateApproval(Map<String, Object> context) {
		contextMapper.set(context, "/runtime/reviewedRevision", null);
		contextMapper.set(context, "/runtime/confirmedRevision", null);
		contextMapper.set(context, "/runtime/confirmed", false);
	}

	private String outputPath(Map<String, Object> config, String resolverId) {
		return firstText(text(config.get("outputPath")), "/resolved/" + resolverId);
	}

	private String fingerprint(String resolverId, Long resourceVersionId, Map<String, Object> arguments) {
		try {
			return resolverId + ":" + resourceVersionId + ":" + objectMapper.writeValueAsString(arguments);
		}
		catch (Exception ex) {
			return resolverId + ":" + resourceVersionId + ":" + arguments;
		}
	}

	private long remainingResolverTimeoutMs(AgentRequest request) {
		if (request != null && request.getRuntimeDeadline() != null) {
			long remaining = request.getRuntimeDeadline().remaining().toMillis();
			return Math.max(1L, remaining);
		}
		return 15000L;
	}

	private int boundedLimit(Map<String, Object> runtimeConfig, String key, int fallback, int min, int max) {
		Map<String, Object> scheduler = runtimeConfig.get("scheduler") instanceof Map<?, ?> value ? map(value)
				: runtimeConfig;
		long configured = number(scheduler.get(key), fallback);
		return (int) Math.max(min, Math.min(max, configured));
	}

	private int effectiveLimit(Map<String, Object> runtimeConfig, String key, int platformLimit, int min, int max) {
		int platform = Math.max(min, Math.min(max, platformLimit));
		return Math.min(platform, boundedLimit(runtimeConfig, key, platform, min, max));
	}

	private String resolverDisplayName(ResolverTask task) {
		return firstText(text(task.config().get("displayName")), task.resolvedToolName(), task.id());
	}

	/**
	 * 从已发布版本快照解析工具展示名；快照缺失或解析失败时返回 null，由调用方按既有兜底链路取值。
	 * 解析方式与 ToolInvokerImpl.snapshot 一致（JSON -> AgentExecutionResource），仅取 toolName。
	 */
	private String resolvedToolDisplayName(AgentExecutionResourceVersion resource) {
		if (resource == null || !StringUtils.hasText(resource.getSnapshot())) {
			return null;
		}
		try {
			AgentExecutionResource snapshot = objectMapper.readValue(resource.getSnapshot(),
					AgentExecutionResource.class);
			return AgentRuntimeToolDisplayNameResolver.displayName(snapshot == null ? null : snapshot.getToolName());
		}
		catch (Exception ex) {
			log.debug("解析已发布工具快照标题失败. resourceVersionId={}", resource.getId(), ex);
			return null;
		}
	}

	/**
	 * 节点展示名：按节点类型映射中文标签，未知类型兜底 node.id()；
	 * execute 节点每回合仅执行一两次，优先取已发布快照的工具标题，失败时回退类型标签。
	 */
	private String nodeDisplayName(FlowNode node) {
		String type = node.type() == null ? "" : node.type().trim().toLowerCase(Locale.ROOT);
		String label = switch (type) {
			case "extract" -> "提取信息";
			case "collect" -> "收集信息";
			case "resolve" -> "查询主数据";
			case "select" -> "选择候选项";
			case "merge" -> "合并写入";
			case "validate" -> "字段校验";
			case "review" -> "信息确认";
			case "confirm" -> "最终确认";
			case "execute" -> "执行业务操作";
			case "switch" -> "条件分支";
			case "present" -> "结果展示";
			case "handoff" -> "转交处理";
			case "end" -> "流程结束";
			case "error" -> "流程异常";
			default -> node.id();
		};
		if (!"execute".equals(type)) {
			return label;
		}
		try {
			Long resourceVersionId = longValue(node.config() == null ? null : node.config().get("resourceVersionId"));
			if (resourceVersionId != null) {
				String toolLabel = resolvedToolDisplayName(resourceVersionMapper.findPublished(resourceVersionId));
				if (StringUtils.hasText(toolLabel)) {
					return toolLabel;
				}
			}
		}
		catch (RuntimeException ex) {
			log.debug("解析 execute 节点工具标题失败，回退类型标签. nodeId={}", node.id(), ex);
		}
		return label;
	}

	private NodeResult resolverWaiting(String message, List<String> errors) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("errors", errors == null ? List.of() : errors);
		payload.put("retryable", true);
		return NodeResult.waiting(message, "RESOLVER_RETRY", payload);
	}

	private void emitResolverFinished(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			ResolverTask task, Map<String, Long> taskStarts, String status, Throwable error) {
		Long startedAt = taskStarts == null ? null : taskStarts.get(task.id());
		long durationMs = startedAt == null ? 0L : elapsedMs(startedAt);
		emitFlowProgress(request, instance, node, task.id(), "FLOW_RESOLVER_FINISHED", status, durationMs,
				resolverDisplayName(task), error);
	}

	private ResolverRun beginResolverRun(Long flowInstanceId) {
		ResolverRun run = new ResolverRun();
		if (flowInstanceId != null) {
			ResolverRun previous = activeResolverRuns.put(flowInstanceId, run);
			if (previous != null) {
				previous.cancel();
			}
		}
		return run;
	}

	private <T> CompletableFuture<T> submitResolver(ResolverRun run, Supplier<T> task) {
		if (run.cancelled()) {
			throw new CancellationException("FLOW resolver run was cancelled");
		}
		DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.capture();
		CompletableFuture<T> future = CompletableFuture.supplyAsync(
				() -> asyncContextBridge.supplyWith(snapshot, task), flowResolverExecutor);
		run.register(future);
		return future;
	}

	private FlowInvocationReference flowInvocationReference(AgentRequest request, DataAgentFlowInstance instance,
			String invokingNodeId) {
		return new FlowInvocationReference(instance.getId(), instance.getLockVersion(), instance.getCurrentNodeId(),
				invokingNodeId, instance.getThreadId(), instance.getUserId(), request.getRuntimeRequestId());
	}

	/**
	 * FLOW 工具统一经 CapabilityGateway 进程内重载：审批/预算/Release 固定失败关闭，检查通过后再走
	 * ToolInvoker（固定 resourceVersionId、FLOW 引用与确认契约）。缺 runId 时仍带 capabilityCode，
	 * 高风险 WRITE 由网关按目录发布版本裁决。
	 */
	private Map<String, Object> invokeToolViaGateway(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, String invokingNodeId, Long resourceVersionId, String expectedAccessMode,
			boolean confirmed, String idempotencyKey, Map<String, Object> arguments, Map<String, Object> toolConfig) {
		return invokeToolViaGateway(request, instance, version, invokingNodeId, resourceVersionId, expectedAccessMode,
				confirmed, idempotencyKey, arguments, toolConfig, null);
	}

	private Map<String, Object> invokeToolViaGateway(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, String invokingNodeId, Long resourceVersionId, String expectedAccessMode,
			boolean confirmed, String idempotencyKey, Map<String, Object> arguments, Map<String, Object> toolConfig,
			Long resumeApprovalId) {
		String capabilityCode = requireCapabilityCode(resourceVersionId, toolConfig);
		Map<String, Object> toolArguments = arguments == null ? Map.of() : arguments;
		ToolInvocationContext context = new ToolInvocationContext(instance.getAgentId(), instance.getTenantId(),
				version.getId(), resourceVersionId, expectedAccessMode, confirmed, idempotencyKey, toolArguments,
				flowInvocationReference(request, instance, invokingNodeId));
		boolean requireManagerApproval = "WRITE".equals(expectedAccessMode)
				&& SkillManagerApproval.enabled(version.getRuntimeConfig(), objectMapper);
		InvocationRequest invocation = InvocationRequest.builder()
			.tenantId(firstText(request == null ? null : request.getTenantIdSnapshot(), instance.getTenantId()))
			.ownerType(request == null ? null : request.getOwnerType())
			.ownerId(request == null ? null : request.getOwnerId())
			.releaseId(request == null ? null : request.getReleaseId())
			.runId(request == null ? null : request.getDurableRunId())
			.stepKey(firstText(request == null ? null : request.getRuntimeRequestId(), invokingNodeId,
					instance.getThreadId()))
			.capabilityKind(CapabilityKind.FLOW)
			.capabilityCode(capabilityCode)
			.arguments(toolArguments)
			.source(SOURCE_FLOW)
			.idempotencyKey(idempotencyKey)
			.agentId(instance.getAgentId())
			.userId(firstText(request == null ? null : request.getUserIdSnapshot(), instance.getUserId()))
			.executionIntent(request == null ? null : request.getExecutionIntent())
			.executionScopeKey(request == null ? null : request.getExecutionScopeKey())
			.requireManagerApproval(requireManagerApproval)
			.confirmed(confirmed)
			.sourceRefId(instance.getId() == null ? null : String.valueOf(instance.getId()))
			.approvalId(resumeApprovalId)
			.build();
		ResultEnvelope envelope = capabilityGateway.invoke(invocation, () -> toolInvoker.invoke(context));
		return unwrapGatewayData(envelope, capabilityCode);
	}

	private String requireCapabilityCode(Long resourceVersionId, Map<String, Object> toolConfig) {
		String fromConfig = text(toolConfig == null ? null : toolConfig.get("resourceKey")).trim();
		if (StringUtils.hasText(fromConfig)) {
			return fromConfig;
		}
		if (resourceVersionId == null) {
			throw CheckedException.badRequest("能力调用请求不完整：resourceVersionId 必填");
		}
		AgentExecutionResourceVersion published = resourceVersionMapper.findPublished(resourceVersionId);
		if (published == null || !StringUtils.hasText(published.getResourceKey())) {
			throw CheckedException.badRequest(
					"能力调用请求不完整：无法解析已发布工具的 capabilityCode, resourceVersionId=" + resourceVersionId);
		}
		return published.getResourceKey();
	}

	private Map<String, Object> unwrapGatewayData(ResultEnvelope envelope, String capabilityCode) {
		if (envelope == null) {
			throw CheckedException.fail("能力执行失败：能力网关返回空结果，capabilityCode=" + capabilityCode);
		}
		if (!ResultEnvelope.STATUS_SUCCESS.equals(envelope.status())) {
			throw CheckedException.fail(
					"能力执行失败：capabilityCode=" + capabilityCode + ", status=" + envelope.status());
		}
		Object data = envelope.data();
		if (data == null) {
			return Map.of();
		}
		if (data instanceof Map<?, ?> map) {
			return map(map);
		}
		throw CheckedException.fail("能力执行失败：FLOW 工具结果必须是 JSON 对象，capabilityCode=" + capabilityCode);
	}

	private void cancelResolverRun(Long flowInstanceId) {
		ResolverRun run = flowInstanceId == null ? null : activeResolverRuns.get(flowInstanceId);
		if (run != null) {
			run.cancel();
		}
	}

	private void endResolverRun(Long flowInstanceId, ResolverRun run) {
		if (flowInstanceId != null) {
			activeResolverRuns.remove(flowInstanceId, run);
		}
	}

	private void emitFlowProgress(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			String resolverId, String stageCode, String status, Long durationMs, String displayName) {
		emitFlowProgress(request, instance, node, resolverId, stageCode, status, durationMs, displayName, null);
	}

	private void emitFlowProgress(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			String resolverId, String stageCode, String status, Long durationMs, String displayName, Throwable error) {
		if (runtimeProgressService == null || request == null) {
			return;
		}
		String flowInstanceId = instance == null || instance.getId() == null ? null : String.valueOf(instance.getId());
		String effectiveDisplayName = error == null ? displayName
				: firstText(displayName, "Resolver") + ": " + firstText(error.getMessage(), "执行失败");
		runtimeProgressService.emitFlow(request, flowInstanceId, node == null ? null : node.id(), resolverId, stageCode,
				status, durationMs, effectiveDisplayName);
	}

	private NodeResult extract(AgentRequest request, ModelConfigDTO modelConfig, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		String outputPath = firstText(text(config.get("outputPath")), INPUT_ROOT_PATH);
		List<String> requiredPaths = strings(config.get("requiredPaths"));
		List<String> requiredAnyPaths = strings(config.get("requiredAnyPaths"));
		Map<String, Object> nodeSchema = map(config.get("schema"));
		Map<String, Object> extracted;
		try {
			extracted = extractWithLiteralMappings(request, modelConfig, nodeSchema,
					text(config.get("instruction")), context, map(config.get("literalMappings")),
					requiredPaths, requiredAnyPaths, outputPath, extractionConfig(config), instance, version, true);
		}
		catch (RuntimeException ex) {
			if (requiredPathsComplete(config, context, requiredPaths)) {
				log.warn("FLOW extract model failed after literals, continuing. nodeId={}, error={}",
						node.id(), ex.getMessage());
				return NodeResult.next(node.next(), Map.of("path", outputPath, "extractDegraded", true));
			}
			throw ex;
		}
		if (!shouldSkipUtteranceApply(context)) {
			applyExtractedUtterance(request, context,
					utteranceExtractionSchema(nodeSchema, version, context, outputPath), outputPath, extracted, config,
					version);
		}
		return NodeResult.next(node.next(), Map.of("path", outputPath));
	}

	private boolean extractionBudgetExhausted(Map<String, Object> context) {
		return number(contextMapper.get(context, EXTRACTION_INVOCATIONS_PATH), 0L)
				>= MAX_EXTRACTION_INVOCATIONS_PER_TURN;
	}

	private Map<String, Object> extractFields(AgentRequest request, ModelConfigDTO modelConfig,
			Map<String, Object> schema, String instruction, Map<String, Object> context) {
		long invocations = number(contextMapper.get(context, EXTRACTION_INVOCATIONS_PATH), 0L);
		contextMapper.set(context, EXTRACTION_INVOCATIONS_PATH, invocations + 1L);
		try {
			return fieldExtractor.extract(request, modelConfig, schema, instruction, Map.of(),
					map(contextMapper.get(context, INPUT_ROOT_PATH)));
		}
		catch (RuntimeException ex) {
			contextMapper.set(context, EXTRACTION_INVOCATIONS_PATH, invocations);
			throw ex;
		}
	}

	private Map<String, Object> extractWithLiteralMappings(AgentRequest request, ModelConfigDTO modelConfig,
			Map<String, Object> schema, String instruction, Map<String, Object> context,
			Map<String, Object> literalMappings, List<String> requiredPaths, List<String> requiredAnyPaths,
			String outputPath, Map<String, Object> extraction, DataAgentFlowInstance instance,
			DataAgentSkillVersion version) {
		return extractWithLiteralMappings(request, modelConfig, schema, instruction, context, literalMappings,
				requiredPaths, requiredAnyPaths, outputPath, extraction, instance, version, true);
	}

	private Map<String, Object> extractWithLiteralMappings(AgentRequest request, ModelConfigDTO modelConfig,
			Map<String, Object> schema, String instruction, Map<String, Object> context,
			Map<String, Object> literalMappings, List<String> requiredPaths, List<String> requiredAnyPaths,
			String outputPath, Map<String, Object> extraction, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, boolean classifyTurn) {
		// 确定性只锁封闭值域（字面量枚举、已解析时间）。开放语言（网点怎么说、
		// 到货时间、商品俗称）交给模型，不按分隔符抢答，避免把「到货时间」切成假网点后锁死槽位。
		Map<String, Object> nodeSchema = schema == null ? Map.of() : schema;
		Map<String, Object> utteranceSchema = utteranceExtractionSchema(nodeSchema, version, context, outputPath);
		Map<String, Object> mergedMappings = mergeLiteralMappings(flowLiteralMappings(version), literalMappings);
		String utteranceInstruction = withRemainingSlotInstruction(instruction);
		SlotMergeCheckpoint checkpoint = captureSlotMerge(context, outputPath);
		Map<String, Object> literalValues = keepWritableThisTurn(
				extractLiteralValues(request == null ? null : request.getQuery(), mergedMappings), nodeSchema, context,
				outputPath);
		literalValues = new LinkedHashMap<>(
				temporalNormalizer.normalize(literalValues, utteranceSchema, request).values());
		Map<String, Object> deterministicValues = new LinkedHashMap<>();
		if (checkpoint != null) {
			applyExtractionPatch(context, outputPath, literalValues);
			recordFilledThisTurn(context, outputPath, literalValues);
			deterministicValues.putAll(literalValues);
			Map<String, Object> temporalValues = keepWritableThisTurn(
					deterministicTemporalValues(utteranceSchema, context, outputPath, deterministicValues, request),
					nodeSchema, context, outputPath);
			if (!temporalValues.isEmpty()) {
				applyExtractionPatch(context, outputPath, temporalValues);
				recordFilledThisTurn(context, outputPath, temporalValues);
				deterministicValues.putAll(temporalValues);
			}
			Map<String, Object> pairValues = keepWritableThisTurn(
					FlowAddressPairExtractor.extract(request == null ? null : request.getQuery(),
							flowPairExtractions(version, extraction)),
					nodeSchema, context, outputPath);
			if (!pairValues.isEmpty()) {
				applyExtractionPatch(context, outputPath, pairValues);
				recordFilledThisTurn(context, outputPath, pairValues);
				deterministicValues.putAll(pairValues);
			}
		}

		String modelPolicy = firstText(text(extraction.get("modelPolicy")), MODEL_POLICY_ALWAYS)
			.trim().toUpperCase(Locale.ROOT);
		boolean remainingUnresolved = hasEmptyUtteranceFields(utteranceSchema, context, outputPath,
				deterministicValues.keySet());
		boolean callModel = MODEL_POLICY_ALWAYS.equals(modelPolicy)
				|| MODEL_POLICY_IF_UNRESOLVED.equals(modelPolicy)
				&& (hasUnresolvedRequirements(nodeSchema, context, requiredPaths, requiredAnyPaths)
						|| remainingUnresolved);
		// 预算耗尽时退回「只用字面量映射」，与 callModel=false 同路径：后续节点的必填项仍会走
		// 等待/追问分支，由下一回合重新获得预算，而不是在同一回合里对模型连发多次抽取。
		if (!callModel || extractionBudgetExhausted(context)) {
			return Map.of();
		}

		Map<String, Object> modelSchema = extractionSchema(utteranceSchema,
				extraction == null ? Map.of() : extraction, context, outputPath, requiredPaths, requiredAnyPaths,
				deterministicValues.keySet(), classifyTurn);
		if (onlyTurnIntentProperties(map(modelSchema.get("properties")))) {
			return Map.of();
		}
		Map<String, Object> result;
		try {
			Map<String, Object> modelValues = normalizeMappedValues(
					extractFields(request, modelConfig, modelSchema, utteranceInstruction, context),
					mergedMappings);
			modelValues = takeTurnIntent(modelValues, context);
			modelValues = keepWritableThisTurn(modelValues, nodeSchema, context, outputPath);
			if (isTurnAskOrChitchat(context) || isTurnList(context) || isTurnProceed(context)
					|| isTurnCancel(context)) {
				modelValues = isTurnCancel(context) || isTurnList(context) ? Map.of()
						: filterStatedInQuery(request == null ? null : request.getQuery(), modelValues);
				if (shouldRollbackTurnIntent(context, deterministicValues, modelValues)) {
					restoreSlotMerge(context, outputPath, checkpoint);
					return Map.of();
				}
				if (modelValues.isEmpty()) {
					return Map.of();
				}
			}
			result = validateExtractionPatch(modelValues, modelSchema, outputPath);
			result = new LinkedHashMap<>(temporalNormalizer.normalize(result, modelSchema, request).values());
		}
		catch (RuntimeException ex) {
			if (requiredPathsComplete(schemaConfig(nodeSchema), context, requiredPaths)
					|| !deterministicValues.isEmpty()) {
				log.warn("FLOW extraction model failed after literals, keeping collected values. error={}",
						ex.getMessage());
				if (instance != null) {
					instance.setInputHash(null);
				}
				return Map.of();
			}
			FlowExtractionException extractionError = ex instanceof FlowExtractionException typed ? typed
					: new FlowExtractionException(FlowExtractionException.PROVIDER_ERROR,
							"FLOW extraction failed", ex);
			throw extractionError;
		}
		List<String> missing = unresolvedPaths(nodeSchema, context, requiredPaths, requiredAnyPaths, outputPath, result);
		if (!missing.isEmpty()) {
			log.warn("FLOW input extraction left required fields unresolved, runtimeRequestId={}, flowInstanceId={}, "
					+ "nodeId={}, modelName={}, missingPaths={}",
					request == null ? null : request.getRuntimeRequestId(),
					instance == null ? null : instance.getId(), instance == null ? null : instance.getCurrentNodeId(),
					modelConfig == null ? null : modelConfig.getModelName(), missing);
		}
		return coerceWritableValues(result, utteranceSchema);
	}

	private boolean hasUnresolvedRequirements(Map<String, Object> schema, Map<String, Object> context,
			List<String> requiredPaths, List<String> requiredAnyPaths) {
		Map<String, Object> schemaConfig = schemaConfig(schema);
		return requiredPaths.stream().anyMatch(path -> isIncompletePath(schemaConfig, context, path))
				|| !requiredAnyPaths.isEmpty()
				&& requiredAnyPaths.stream().noneMatch(path -> !isIncompletePath(schemaConfig, context, path));
	}

	private Map<String, Object> schemaConfig(Map<String, Object> schema) {
		return Map.of("schema", schema == null ? Map.of() : schema);
	}

	private Map<String, Object> flowInputSchema(DataAgentSkillVersion version) {
		if (version == null) {
			return Map.of("type", "object", "properties", Map.of());
		}
		Map<String, Object> fromColumn = readMap(version.getVariablesSchema());
		if (!map(fromColumn.get("properties")).isEmpty()) {
			return fromColumn;
		}
		Map<String, Object> fromDefinition = map(readMap(version.getFlowDefinition()).get("variablesSchema"));
		if (!map(fromDefinition.get("properties")).isEmpty()) {
			return fromDefinition;
		}
		Map<String, Object> properties = new LinkedHashMap<>();
		for (FlowNode node : flowNodes(version)) {
			if (!writesFlowInput(node)) {
				continue;
			}
			for (Map.Entry<String, Object> entry : map(map(node.config().get("schema")).get("properties")).entrySet()) {
				properties.putIfAbsent(entry.getKey(), entry.getValue());
			}
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		return schema;
	}

	private boolean writesFlowInput(FlowNode node) {
		if (node == null || node.config() == null || !StringUtils.hasText(node.type())) {
			return false;
		}
		String type = node.type().trim().toLowerCase(Locale.ROOT);
		if (!Set.of("extract", "collect", "validate", "review").contains(type)) {
			return false;
		}
		String outputPath = firstText(text(node.config().get("outputPath")), text(node.config().get("targetPath")),
				INPUT_ROOT_PATH);
		return INPUT_ROOT_PATH.equals(outputPath) || outputPath.startsWith(INPUT_ROOT_PATH + "/");
	}

	private Map<String, Object> flowLiteralMappings(DataAgentSkillVersion version) {
		Map<String, Object> merged = new LinkedHashMap<>();
		for (FlowNode node : flowNodes(version)) {
			if (!writesFlowInput(node)) {
				continue;
			}
			merged = mergeLiteralMappings(merged, map(node.config().get("literalMappings")));
		}
		return merged;
	}

	private Map<String, Object> mergeLiteralMappings(Map<String, Object> left, Map<String, Object> right) {
		if (left == null || left.isEmpty()) {
			return right == null ? Map.of() : new LinkedHashMap<>(right);
		}
		if (right == null || right.isEmpty()) {
			return new LinkedHashMap<>(left);
		}
		Map<String, Object> merged = new LinkedHashMap<>(left);
		for (Map.Entry<String, Object> entry : right.entrySet()) {
			Map<String, Object> existing = map(merged.get(entry.getKey()));
			Map<String, Object> incoming = map(entry.getValue());
			if (existing.isEmpty()) {
				merged.put(entry.getKey(), new LinkedHashMap<>(incoming));
				continue;
			}
			Map<String, Object> aliases = new LinkedHashMap<>(existing);
			for (Map.Entry<String, Object> alias : incoming.entrySet()) {
				aliases.putIfAbsent(alias.getKey(), alias.getValue());
			}
			merged.put(entry.getKey(), aliases);
		}
		return merged;
	}

	private List<Map<String, Object>> flowPairExtractions(DataAgentSkillVersion version,
			Map<String, Object> extraction) {
		List<Map<String, Object>> merged = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		appendPairExtractions(merged, seen, mapList(extraction == null ? null : extraction.get("pairExtractions")));
		for (FlowNode node : flowNodes(version)) {
			if (node == null || node.config() == null) {
				continue;
			}
			List<Map<String, Object>> fromNode = mapList(extractionConfig(node.config()).get("pairExtractions"));
			if (fromNode.isEmpty()) {
				fromNode = mapList(node.config().get("pairExtractions"));
			}
			appendPairExtractions(merged, seen, fromNode);
		}
		return merged;
	}

	private void appendPairExtractions(List<Map<String, Object>> merged, Set<String> seen,
			List<Map<String, Object>> pairs) {
		for (Map<String, Object> pair : pairs) {
			if (pair == null || pair.isEmpty()) {
				continue;
			}
			String field = firstText(text(pair.get("field")), text(pair.get("path")));
			if (!StringUtils.hasText(field) || !seen.add(field)) {
				continue;
			}
			merged.add(pair);
		}
	}

	private Map<String, Object> utteranceExtractionSchema(Map<String, Object> nodeSchema,
			DataAgentSkillVersion version, Map<String, Object> context, String outputPath) {
		Map<String, Object> nodeProperties = map(nodeSchema.get("properties"));
		Map<String, Object> properties = new LinkedHashMap<>(nodeProperties);
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (Map.Entry<String, Object> entry : map(flowInputSchema(version).get("properties")).entrySet()) {
			if (properties.containsKey(entry.getKey()) || isBusinessIdPath("/" + entry.getKey())) {
				continue;
			}
			Object current = contextMapper.get(context, root + "/" + escapePointer(entry.getKey()));
			if (contextMapper.isEmpty(current)) {
				properties.put(entry.getKey(), entry.getValue());
			}
		}
		Map<String, Object> schema = new LinkedHashMap<>(nodeSchema == null || nodeSchema.isEmpty()
				? Map.of("type", "object") : nodeSchema);
		schema.put("type", firstText(text(schema.get("type")), "object"));
		schema.put("properties", properties);
		schema.remove("required");
		return schema;
	}

	private Map<String, Object> keepWritableThisTurn(Map<String, Object> values, Map<String, Object> nodeSchema,
			Map<String, Object> context, String outputPath) {
		if (values == null || values.isEmpty()) {
			return values == null ? Map.of() : values;
		}
		Set<String> nodeFields = map(nodeSchema.get("properties")).keySet();
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		Map<String, Object> kept = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			if (nodeFields.contains(entry.getKey())) {
				kept.put(entry.getKey(), entry.getValue());
				continue;
			}
			Object current = contextMapper.get(context, root + "/" + escapePointer(entry.getKey()));
			if (contextMapper.isEmpty(current)) {
				kept.put(entry.getKey(), entry.getValue());
			}
		}
		return kept;
	}

	private boolean hasEmptyUtteranceFields(Map<String, Object> schema, Map<String, Object> context, String outputPath,
			Set<String> resolvedFields) {
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (String field : map(schema.get("properties")).keySet()) {
			if (resolvedFields != null && resolvedFields.contains(field)) {
				continue;
			}
			if (TURN_ACTION_FIELD.equals(field) || LIST_LABEL_FIELD.equals(field) || TURN_REPLY_FIELD.equals(field)) {
				continue;
			}
			if (contextMapper.isEmpty(contextMapper.get(context, root + "/" + escapePointer(field)))) {
				return true;
			}
		}
		return false;
	}

	private String withRemainingSlotInstruction(String instruction) {
		if (!StringUtils.hasText(instruction)) {
			return REMAINING_SLOT_INSTRUCTION;
		}
		if (instruction.contains("remaining writable schema fields")) {
			return instruction;
		}
		return instruction.trim() + " " + REMAINING_SLOT_INSTRUCTION;
	}

	private Map<String, Object> filterStatedInQuery(String query, Map<String, Object> values) {
		if (!StringUtils.hasText(query) || values == null || values.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> grounded = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			if (statedInQuery(query, entry.getValue())) {
				grounded.put(entry.getKey(), entry.getValue());
			}
		}
		return grounded;
	}

	private boolean statedInQuery(String query, Object value) {
		if (value == null) {
			return false;
		}
		if (value instanceof String text) {
			return StringUtils.hasText(text) && query.contains(text);
		}
		if (value instanceof Number number) {
			return query.contains(String.valueOf(number));
		}
		if (value instanceof Map<?, ?> nested) {
			return nested.values().stream().anyMatch(item -> statedInQuery(query, item));
		}
		if (value instanceof Iterable<?> items) {
			for (Object item : items) {
				if (statedInQuery(query, item)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean shouldRollbackTurnIntent(Map<String, Object> context, Map<String, Object> deterministicValues,
			Map<String, Object> modelValues) {
		if (isTurnCancel(context)) {
			return true;
		}
		if (!deterministicValues.isEmpty() || (modelValues != null && !modelValues.isEmpty())) {
			return false;
		}
		return isTurnAskOrChitchat(context) || isTurnProceed(context);
	}

	private boolean shouldSkipUtteranceApply(Map<String, Object> context) {
		return shouldRollbackTurnIntent(context, Map.of(), Map.of()) && !hasFilledThisTurn(context);
	}

	private boolean hasFilledThisTurn(Map<String, Object> context) {
		return !strings(contextMapper.get(context, FILLED_THIS_TURN_PATH)).isEmpty();
	}

	private boolean hasNewFillsThisTurn(Map<String, Object> context, List<String> filledBeforeExtract) {
		List<String> before = filledBeforeExtract == null ? List.of() : filledBeforeExtract;
		return strings(contextMapper.get(context, FILLED_THIS_TURN_PATH)).stream()
			.anyMatch(path -> !before.contains(path));
	}

	/**
	 * 确定性时间预填：上游已从当前消息解析出显式时间区间，且 Schema 恰有一个未解析的
	 * x-temporal 槽位时由服务端直接填充；多个候选槽位无法确定归属，仍交模型/追问。
	 */
	private Map<String, Object> deterministicTemporalValues(Map<String, Object> schema, Map<String, Object> context,
			String outputPath, Map<String, Object> resolvedValues, AgentRequest request) {
		Map<String, Object> intervalPayload = temporalNormalizer.requestIntervalPayload(request);
		if (intervalPayload.isEmpty()) {
			return Map.of();
		}
		List<String> candidates = new ArrayList<>();
		boolean pointSlot = false;
		for (Map.Entry<String, Object> property : map(schema.get("properties")).entrySet()) {
			if (!(property.getValue() instanceof Map<?, ?> fieldSchema)
					|| !isTemporalField(map(fieldSchema)) || resolvedValues.containsKey(property.getKey())) {
				continue;
			}
			String path = outputPath + (outputPath.endsWith("/") ? "" : "/") + escapePointer(property.getKey());
			if (contextMapper.isEmpty(contextMapper.get(context, path))) {
				candidates.add(property.getKey());
				pointSlot = !isIntervalTemporalField(map(fieldSchema));
			}
		}
		if (candidates.size() != 1) {
			return Map.of();
		}
		Object value = pointSlot ? firstNonEmpty(intervalPayload.get("startInclusive"),
				intervalPayload.get("startDateInclusive")) : intervalPayload;
		return value == null ? Map.of() : Map.of(candidates.get(0), value);
	}

	private boolean isTemporalField(Map<String, Object> fieldSchema) {
		Object temporal = fieldSchema.get("x-temporal");
		if (temporal instanceof Map<?, ?> policy) {
			return !map(policy).isEmpty();
		}
		return StringUtils.hasText(text(temporal));
	}

	private boolean isIntervalTemporalField(Map<String, Object> fieldSchema) {
		Object temporal = fieldSchema.get("x-temporal");
		String kind = temporal instanceof Map<?, ?> policy ? text(map(policy).get("kind")) : text(temporal);
		return "INTERVAL".equalsIgnoreCase(kind.trim());
	}

	private Object firstNonEmpty(Object first, Object second) {
		if (first != null && StringUtils.hasText(String.valueOf(first))) {
			return first;
		}
		return second;
	}

	private Map<String, Object> extractionSchema(Map<String, Object> schema, Map<String, Object> extraction,
			Map<String, Object> context, String outputPath, List<String> requiredPaths, List<String> requiredAnyPaths,
			Set<String> deterministicFields, boolean classifyTurn) {
		String schemaMode = firstText(text(extraction.get("schemaMode")), SCHEMA_MODE_FULL)
			.trim().toUpperCase(Locale.ROOT);
		Set<String> includedFields = new java.util.LinkedHashSet<>();
		if (SCHEMA_MODE_CONFIGURED_PATHS.equals(schemaMode)) {
			strings(extraction.get("paths")).stream()
				.map(path -> schemaField(path, outputPath)).filter(StringUtils::hasText).forEach(includedFields::add);
		}
		else if (SCHEMA_MODE_UNRESOLVED_REQUIRED.equals(schemaMode)) {
			Map<String, Object> schemaConfig = schemaConfig(schema);
			requiredPaths.stream().filter(path -> isIncompletePath(schemaConfig, context, path))
				.map(path -> schemaField(path, outputPath)).filter(StringUtils::hasText).forEach(includedFields::add);
			if (!requiredAnyPaths.isEmpty()
					&& requiredAnyPaths.stream().noneMatch(path -> !isIncompletePath(schemaConfig, context, path))) {
				requiredAnyPaths.stream().map(path -> schemaField(path, outputPath)).filter(StringUtils::hasText)
					.forEach(includedFields::add);
			}
		}
		Map<String, Object> projected = modelWritableSchema(schema);
		Map<String, Object> properties = new LinkedHashMap<>(map(projected.get("properties")));
		if (SCHEMA_MODE_CONFIGURED_PATHS.equals(schemaMode) || SCHEMA_MODE_UNRESOLVED_REQUIRED.equals(schemaMode)) {
			properties.entrySet().removeIf(entry -> !includedFields.contains(entry.getKey()));
		}
		properties.keySet().removeAll(deterministicFields);
		if (classifyTurn) {
			injectTurnIntentFields(properties, context);
		}
		projected.put("properties", properties);
		return projected;
	}

	private boolean requiredPathsComplete(Map<String, Object> config, Map<String, Object> context,
			List<String> requiredPaths) {
		if (requiredPaths == null || requiredPaths.isEmpty()) {
			return !strings(contextMapper.get(context, LAST_CHANGED_PATHS_PATH)).isEmpty();
		}
		return requiredPaths.stream().noneMatch(path -> isIncompletePath(config, context, path));
	}

	private Map<String, Object> modelWritableSchema(Map<String, Object> schema) {
		return projectWritableSchema(schema, true);
	}

	private Map<String, Object> projectWritableSchema(Map<String, Object> schema, boolean sparseRoot) {
		Map<String, Object> projected = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : map(schema).entrySet()) {
			if ("required".equals(entry.getKey())) {
				if (!sparseRoot) {
					List<String> required = strings(entry.getValue()).stream()
						.filter(name -> !isBusinessIdPath("/" + name)).toList();
					if (!required.isEmpty()) {
						projected.put("required", required);
					}
				}
				continue;
			}
			if ("properties".equals(entry.getKey())) {
				projected.put(entry.getKey(), modelWritableProperties(map(entry.getValue())));
			}
			else if ("items".equals(entry.getKey()) && entry.getValue() instanceof Map<?, ?> nested) {
				projected.put(entry.getKey(), projectWritableSchema(map(nested), false));
			}
			else if (entry.getValue() instanceof Map<?, ?> nested) {
				projected.put(entry.getKey(), projectWritableSchema(map(nested), sparseRoot));
			}
			else if (entry.getValue() instanceof List<?> values) {
				boolean itemSchema = "items".equals(entry.getKey());
				projected.put(entry.getKey(), values.stream().map(value -> value instanceof Map<?, ?> nested
						? projectWritableSchema(map(nested), sparseRoot && !itemSchema) : value).toList());
			}
			else {
				projected.put(entry.getKey(), entry.getValue());
			}
		}
		if (projected.containsKey("properties") || "object".equalsIgnoreCase(text(projected.get("type")))) {
			projected.put("additionalProperties", false);
		}
		return projected;
	}

	private Map<String, Object> modelWritableProperties(Map<String, Object> properties) {
		Map<String, Object> projected = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : map(properties).entrySet()) {
			if (isBusinessIdPath("/" + entry.getKey())) {
				continue;
			}
			Object value = entry.getValue();
			projected.put(entry.getKey(), value instanceof Map<?, ?> nested ? modelWritableSchema(map(nested)) : value);
		}
		return projected;
	}

	private String schemaField(String path, String outputPath) {
		if (!StringUtils.hasText(path) || !StringUtils.hasText(outputPath)) {
			return null;
		}
		String prefix = outputPath.endsWith("/") ? outputPath : outputPath + "/";
		if (!path.startsWith(prefix)) {
			return null;
		}
		String relative = path.substring(prefix.length());
		int separator = relative.indexOf('/');
		return unescapePointer(separator < 0 ? relative : relative.substring(0, separator));
	}

	private Map<String, Object> validateExtractionPatch(Map<String, Object> extracted, Map<String, Object> schema,
			String outputPath) {
		if (extracted == null || extracted.isEmpty()) {
			return Map.of();
		}
		Set<String> allowedFields = map(schema.get("properties")).keySet();
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : extracted.entrySet()) {
			if (entry.getKey() == null || entry.getKey().startsWith("__flow") || isBusinessIdPath("/" + entry.getKey())) {
				continue;
			}
			if (!allowedFields.isEmpty() && !allowedFields.contains(entry.getKey())) {
				continue;
			}
			values.put(entry.getKey(), entry.getValue());
		}
		if (values.isEmpty()) {
			throw new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE,
					"FLOW extraction response contains no writable schema fields", null);
		}
		List<String> errors = schemaValidator.validateSparse(values, schema);
		if (!errors.isEmpty()) {
			throw new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE,
					"FLOW extraction values do not match schema", null);
		}
		return values;
	}

	private Map<String, Object> extractLiteralValues(String query, Map<String, Object> literalMappings) {
		if (!StringUtils.hasText(query) || literalMappings.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> fieldEntry : literalMappings.entrySet()) {
			Map<String, Object> valueMappings = map(fieldEntry.getValue());
			Map<String, Integer> matchedLengths = new LinkedHashMap<>();
			for (Map.Entry<String, Object> valueEntry : valueMappings.entrySet()) {
				int longestAlias = 0;
				for (String alias : strings(valueEntry.getValue())) {
					if (matchesLiteral(query, alias)) {
						longestAlias = Math.max(longestAlias, normalizeLiteral(alias).length());
					}
				}
				if (longestAlias > 0) {
					matchedLengths.put(valueEntry.getKey(), longestAlias);
				}
			}
			int longestAlias = matchedLengths.values().stream().mapToInt(Integer::intValue).max().orElse(0);
			List<String> matchedValues = matchedLengths.entrySet().stream()
				.filter(entry -> entry.getValue() == longestAlias)
				.map(Map.Entry::getKey)
				.toList();
			if (longestAlias > 0 && matchedValues.size() == 1) {
				result.put(fieldEntry.getKey(), matchedValues.get(0));
			}
		}
		return result;
	}

	private Map<String, Object> normalizeMappedValues(Map<String, Object> extracted,
			Map<String, Object> literalMappings) {
		if (extracted == null || extracted.isEmpty() || literalMappings.isEmpty()) {
			return extracted == null ? Map.of() : new LinkedHashMap<>(extracted);
		}
		Map<String, Object> result = new LinkedHashMap<>(extracted);
		for (Map.Entry<String, Object> fieldEntry : literalMappings.entrySet()) {
			if (!result.containsKey(fieldEntry.getKey())) {
				continue;
			}
			String canonicalValue = canonicalMappedValue(result.get(fieldEntry.getKey()), map(fieldEntry.getValue()));
			if (canonicalValue == null) {
				result.remove(fieldEntry.getKey());
			}
			else {
				result.put(fieldEntry.getKey(), canonicalValue);
			}
		}
		return result;
	}

	private String canonicalMappedValue(Object value, Map<String, Object> valueMappings) {
		String candidate = normalizeLiteral(value);
		if (!StringUtils.hasText(candidate)) {
			return null;
		}
		for (Map.Entry<String, Object> entry : valueMappings.entrySet()) {
			if (candidate.equals(normalizeLiteral(entry.getKey()))
					|| strings(entry.getValue()).stream().map(this::normalizeLiteral).anyMatch(candidate::equals)) {
				return entry.getKey();
			}
		}
		return null;
	}

	private boolean matchesLiteral(String query, String alias) {
		String normalizedQuery = query.toLowerCase(Locale.ROOT);
		String normalizedAlias = normalizeLiteral(alias);
		if (!StringUtils.hasText(normalizedAlias)) {
			return false;
		}
		int index = normalizedQuery.indexOf(normalizedAlias);
		while (index >= 0) {
			if (hasLiteralBoundaries(normalizedQuery, normalizedAlias, index)
					&& !isNegatedLiteral(normalizedQuery, index)) {
				return true;
			}
			index = normalizedQuery.indexOf(normalizedAlias, index + normalizedAlias.length());
		}
		return false;
	}

	private boolean hasLiteralBoundaries(String query, String alias, int index) {
		if (alias.chars().noneMatch(character -> character < 128 && Character.isLetterOrDigit(character))) {
			return true;
		}
		int end = index + alias.length();
		return (index == 0 || !isAsciiTokenCharacter(query.charAt(index - 1)))
				&& (end == query.length() || !isAsciiTokenCharacter(query.charAt(end)));
	}

	private boolean isAsciiTokenCharacter(char value) {
		return value < 128 && (Character.isLetterOrDigit(value) || value == '_');
	}

	private boolean isNegatedLiteral(String query, int index) {
		String context = query.substring(Math.max(0, index - 8), index);
		int separatorIndex = -1;
		for (int current = 0; current < context.length(); current++) {
			if ("，,。.!！?？;；".indexOf(context.charAt(current)) >= 0) {
				separatorIndex = current;
			}
		}
		String prefix = compactLiteralContext(context.substring(separatorIndex + 1));
		return LITERAL_NEGATIONS.stream().anyMatch(prefix::contains);
	}

	private String compactLiteralContext(String value) {
		StringBuilder result = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (Character.isLetterOrDigit(character)) {
				result.append(character);
			}
		}
		return result.toString();
	}

	private String normalizeLiteral(Object value) {
		return value == null ? "" : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
	}

	private List<String> unresolvedPaths(Map<String, Object> schema, Map<String, Object> context,
			List<String> requiredPaths, List<String> requiredAnyPaths, String outputPath, Map<String, Object> patch) {
		List<String> missing = new ArrayList<>();
		for (String requiredPath : requiredPaths) {
			Object current = contextMapper.get(context, requiredPath);
			String field = schemaField(requiredPath, outputPath);
			Object pending = StringUtils.hasText(field) ? patch.get(field) : null;
			Object effective = !contextMapper.isEmpty(pending) ? pending : current;
			if (valueIncomplete(effective, schema, requiredPath, outputPath)) {
				missing.add(requiredPath);
			}
		}
		if (!requiredAnyPaths.isEmpty()) {
			boolean anyPresent = requiredAnyPaths.stream().anyMatch(path -> {
				Object current = contextMapper.get(context, path);
				String field = schemaField(path, outputPath);
				Object pending = StringUtils.hasText(field) ? patch.get(field) : null;
				Object effective = !contextMapper.isEmpty(pending) ? pending : current;
				return !valueIncomplete(effective, schema, path, outputPath);
			});
			if (!anyPresent) {
				missing.addAll(requiredAnyPaths);
			}
		}
		return List.copyOf(missing);
	}

	private boolean valueIncomplete(Object value, Map<String, Object> schema, String path, String outputPath) {
		if (contextMapper.isEmpty(value)) {
			return true;
		}
		String field = schemaField(path, outputPath);
		if (!StringUtils.hasText(field)) {
			return false;
		}
		return collectionIncomplete(value, map(map(schema.get("properties")).get(field)));
	}

	private Object pendingValue(Map<String, Object> context, String outputPath, String path,
			Map<String, Object> patch) {
		Object current = contextMapper.get(context, path);
		if (!contextMapper.isEmpty(current)) {
			return current;
		}
		String field = schemaField(path, outputPath);
		return StringUtils.hasText(field) ? patch.get(field) : null;
	}

	private String unescapePointer(String value) {
		return value == null ? null : value.replace("~1", "/").replace("~0", "~");
	}

	private void applyExtractionPatch(Map<String, Object> context, String outputPath,
			Map<String, Object> extracted) {
		if (extracted == null || extracted.isEmpty()) {
			return;
		}
		rejectControlFields(extracted);
		Map<String, Object> values = new LinkedHashMap<>(extracted);
		applyConfiguredInvalidations(context, outputPath, values);
		Map<String, Object> scalarValues = new LinkedHashMap<>(values);
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			String path = outputPath + "/" + escapePointer(entry.getKey());
			if (!(entry.getValue() instanceof List<?> patchItems)) {
				continue;
			}
			Object current = contextMapper.get(context, path);
			Map<String, Object> collectionPolicy = collectionMergePolicy(context, path);
			if (!collectionPolicy.isEmpty() || current instanceof List<?>) {
				mergeCollectionPatch(context, path, patchItems, collectionPolicy);
				scalarValues.remove(entry.getKey());
			}
		}
		contextMapper.mergePresent(context, outputPath, scalarValues);
		for (Map.Entry<String, Object> entry : scalarValues.entrySet()) {
			markValueSlots(context, outputPath + "/" + escapePointer(entry.getKey()), entry.getValue(), "USER");
		}
		invalidateApproval(context);
	}

	/**
	 * 旧协议允许模型通过 __flowClear/__flowDirectives 控制字段清空与历史引用决策，
	 * 已被方案明令禁止：任何来源的补丁出现 __flow 前缀字段都按抽取技术失败处理，不再消费。
	 */
	private void rejectControlFields(Map<String, Object> extracted) {
		for (String field : extracted.keySet()) {
			if (field != null && field.startsWith("__flow")) {
				log.warn("FLOW extraction patch contains the forbidden control field, rejected. field={}", field);
				throw new FlowExtractionException(FlowExtractionException.INVALID_RESPONSE,
						"FLOW extraction patch contains the forbidden control field '" + field + "'", null);
			}
		}
	}

	private void applyConfiguredInvalidations(Map<String, Object> context, String outputPath,
			Map<String, Object> values) {
		Object configured = contextMapper.get(context, "/flowPolicyConfig/inputInvalidationRules");
		if (!(configured instanceof Iterable<?> rules)) {
			return;
		}
		List<String> providedPaths = values.keySet().stream()
			.map(key -> outputPath + "/" + escapePointer(key))
			.toList();
		for (Object value : rules) {
			Map<String, Object> rule = map(value);
			if (strings(rule.get("whenAny")).stream().noneMatch(providedPaths::contains)) {
				continue;
			}
			for (String path : strings(rule.get("clearUnlessProvided"))) {
				if (providedPaths.contains(path) || contextMapper.isEmpty(contextMapper.get(context, path))) {
					continue;
				}
				contextMapper.set(context, path, null);
				markSlot(context, path, "CLEARED", "DEPENDENCY");
			}
			Map<String, Object> collectionFields = map(rule.get("clearCollectionFields"));
			for (Map.Entry<String, Object> entry : collectionFields.entrySet()) {
				if (providedPaths.contains(entry.getKey())) {
					continue;
				}
				clearCollectionFields(context, entry.getKey(), strings(entry.getValue()));
			}
		}
	}

	private void clearCollectionFields(Map<String, Object> context, String listPath, List<String> fields) {
		Object current = contextMapper.get(context, listPath);
		if (!(current instanceof List<?> items) || fields.isEmpty()) {
			return;
		}
		for (int index = 0; index < items.size(); index++) {
			if (!(items.get(index) instanceof Map<?, ?>)) {
				continue;
			}
			for (String field : fields) {
				String path = listPath + "/" + index + "/" + escapePointer(field);
				if (!contextMapper.isEmpty(contextMapper.get(context, path))) {
					contextMapper.set(context, path, null);
					markSlot(context, path, "CLEARED", "DEPENDENCY");
				}
			}
		}
	}

	private Map<String, Object> collectionMergePolicy(Map<String, Object> context, String path) {
		Object configured = contextMapper.get(context, "/flowPolicyConfig/collectionMergePolicies");
		if (!(configured instanceof Iterable<?> policies)) {
			return Map.of();
		}
		for (Object value : policies) {
			Map<String, Object> policy = map(value);
			if (path.equals(text(policy.get("path")))) {
				return policy;
			}
		}
		return Map.of();
	}

	private void mergeCollectionPatch(Map<String, Object> context, String listPath, List<?> patchItems,
			Map<String, Object> policy) {
		if (patchItems.isEmpty()) {
			contextMapper.set(context, listPath, List.of());
			markSlot(context, listPath, "CLEARED", "USER");
			return;
		}
		List<Object> merged = new ArrayList<>();
		Object current = contextMapper.get(context, listPath);
		if (current instanceof List<?> items) {
			items.forEach(item -> merged.add(item instanceof Map<?, ?> value
					? new LinkedHashMap<>(map(value)) : item));
		}
		List<String> matchFields = strings(policy.get("matchFields"));
		List<String> clearIdWhenChanged = strings(policy.get("clearIdWhenChanged"));
		List<String> idFields = strings(policy.get("idFields"));
		int patchIndex = 0;
		for (Object value : patchItems) {
			if (!(value instanceof Map<?, ?> rawPatch)) {
				merged.add(value);
				patchIndex++;
				continue;
			}
			Map<String, Object> patch = map(rawPatch);
			int index = findCollectionItem(merged, patch, matchFields);
			if (index < 0 && !matchFields.contains("type")) {
				index = findCollectionItem(merged, patch, List.of("type"));
			}
			if (index < 0 && merged.size() == 1 && patchItems.size() == 1) {
				index = 0;
			}
			if (index < 0 && merged.size() == patchItems.size() && patchIndex < merged.size()) {
				index = patchIndex;
			}
			Map<String, Object> item = index >= 0 && merged.get(index) instanceof Map<?, ?> existing
					? new LinkedHashMap<>(map(existing)) : new LinkedHashMap<>();
			boolean identityChanged = clearIdWhenChanged.stream()
				.anyMatch(field -> patch.containsKey(field) && !sameSelectionValue(item.get(field), patch.get(field)));
			if (identityChanged) {
				idFields.forEach(item::remove);
			}
			patch.forEach((key, nested) -> {
				if (nested != null) {
					item.put(key, nested);
				}
			});
			if (index < 0) {
				index = merged.size();
				merged.add(item);
			}
			else {
				merged.set(index, item);
			}
			for (Map.Entry<String, Object> entry : patch.entrySet()) {
				markValueSlots(context, listPath + "/" + index + "/" + escapePointer(entry.getKey()),
						entry.getValue(), "USER");
			}
			if (identityChanged) {
				for (String idField : idFields) {
					markSlot(context, listPath + "/" + index + "/" + escapePointer(idField), "CLEARED", "DEPENDENCY");
				}
			}
			patchIndex++;
		}
		contextMapper.set(context, listPath, merged);
	}

	private int findCollectionItem(List<Object> items, Map<String, Object> patch, List<String> matchFields) {
		for (String field : matchFields) {
			Object expected = patch.get(field);
			if (contextMapper.isEmpty(expected)) {
				continue;
			}
			for (int index = 0; index < items.size(); index++) {
				if (items.get(index) instanceof Map<?, ?> item
						&& sameSelectionValue(map(item).get(field), expected)) {
					return index;
				}
			}
		}
		return -1;
	}

	private NodeResult collect(AgentRequest request, ModelConfigDTO modelConfig, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		fillConfiguredPairRoles(context, config, INPUT_ROOT_PATH);
		NodeResult catalog = tryCatalogListing(request, instance, version, node, config, context, "COLLECT");
		if (catalog != null) {
			return catalog;
		}
		if (shouldExtractCollectUtterance(request, config, context)) {
			extractCollectUtterance(request, modelConfig, instance, version, config, context);
		}
		List<String> paths = strings(config.get("requiredPaths"));
		List<String> missing = paths.stream().filter(path -> isIncompletePath(config, context, path)).toList();
		List<String> missingAnyPaths = incompleteRequiredAnyPaths(config, context);
		if (missing.isEmpty() && missingAnyPaths.isEmpty()) {
			clearListedCatalogIfResolved(context);
			return NodeResult.next(node.next(), Map.of());
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("requiredPaths", missing);
		payload.put("requiredAnyPaths", missingAnyPaths);
		payload.put("missing", missingRequiredSlots(config, context, missing, missingAnyPaths));
		payload.put("schema", map(config.get("schema")));
		payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("instruction", text(config.get("instruction")));
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		putInternalExtractionConfig(payload, config);
		attachListedCatalog(payload, context);
		addUiConfig(payload, config, context);
		return NodeResult.waiting(collectionFollowUpPrompt(config, context, missing, missingAnyPaths), "COLLECT",
				payload);
	}

	private String collectionPrompt(Map<String, Object> config, Map<String, Object> context, List<String> missing,
			List<String> missingAnyPaths) {
		LinkedHashSet<String> missingPaths = new LinkedHashSet<>();
		if (missing != null) {
			missingPaths.addAll(missing);
		}
		if (missingAnyPaths != null) {
			missingPaths.addAll(missingAnyPaths);
		}
		Map<String, Object> fieldPrompts = map(map(config.get("collectionPresentation")).get("fieldPrompts"));
		Set<String> prompts = new java.util.LinkedHashSet<>();
		for (String path : missingPaths) {
			String nestedPrompt = remainingItemFieldPrompt(path, config, context);
			if (StringUtils.hasText(nestedPrompt)) {
				prompts.add(nestedPrompt);
				continue;
			}
			String prompt = firstText(text(fieldPrompts.get(path)), schemaFieldPrompt(path, config));
			if (!StringUtils.hasText(prompt)) {
				continue;
			}
			prompts.add(render(Map.of("value", prompt), "value", context, ""));
		}
		String body = prompts.isEmpty() ? render(config, "prompt", context, "请补充必填信息。\n")
				: String.join("\n", prompts);
		return withTurnReply(context, sanitizeUserVisibleText(withFlowNotice(context, body)));
	}

	/**
	 * 死路回退原因说明（flowNotice）前置到本态提示之前：随回合清空，只在本回合提示上展示。
	 * 正文已包含 notice（如死路卡正文本身就是回退文案）时不重复拼接。
	 */
	private String withFlowNotice(Map<String, Object> context, String body) {
		String notice = text(contextMapper.get(context, FLOW_NOTICE_PATH));
		if (!StringUtils.hasText(notice) || (StringUtils.hasText(body) && body.contains(notice))) {
			return body;
		}
		return notice + "\n" + body;
	}

	private String collectionFollowUpPrompt(Map<String, Object> config, Map<String, Object> context,
			List<String> missing, List<String> missingAnyPaths) {
		Map<String, Object> listed = map(contextMapper.get(context, LISTED_CATALOG_PATH));
		String listedPath = text(listed.get("path"));
		LinkedHashSet<String> remaining = new LinkedHashSet<>();
		if (missing != null) {
			remaining.addAll(missing);
		}
		if (missingAnyPaths != null) {
			remaining.addAll(missingAnyPaths);
		}
		String listedPrompt = "";
		if (StringUtils.hasText(listedPath) && remaining.contains(listedPath)) {
			listedPrompt = listedCatalogPrompt(listed);
			remaining.remove(listedPath);
		}
		String rest = remaining.isEmpty() ? "" : collectionPrompt(config, context, new ArrayList<>(remaining), List.of());
		if (!StringUtils.hasText(listedPrompt)) {
			return collectionPrompt(config, context, missing, missingAnyPaths);
		}
		return withTurnReply(context, joinUniqueVisibleLines(listedPrompt, rest));
	}

	private String listedCatalogPrompt(Map<String, Object> listed) {
		List<Map<String, Object>> options = mapList(listed.get("options"));
		if (options.isEmpty()) {
			return "";
		}
		String itemLabel = firstText(text(listed.get("itemLabel")), "候选项");
		StringBuilder prompt = new StringBuilder("当前可选").append(itemLabel)
			.append("如下，请直接说明要办理的项（可多项）及每项仍缺的信息：");
		int index = 1;
		for (Map<String, Object> option : options) {
			String label = firstText(text(option.get("label")), "未命名");
			prompt.append('\n').append(index++).append(". ").append(label);
		}
		return prompt.toString();
	}

	private void attachListedCatalog(Map<String, Object> payload, Map<String, Object> context) {
		Map<String, Object> listed = map(contextMapper.get(context, LISTED_CATALOG_PATH));
		String path = text(listed.get("path"));
		if (!StringUtils.hasText(path) || contextMapper.isEmpty(listed.get("options"))) {
			return;
		}
		if (!contextMapper.isEmpty(contextMapper.get(context, path))) {
			contextMapper.set(context, LISTED_CATALOG_PATH, null);
			return;
		}
		payload.put("listedOptions", listed.get("options"));
		payload.put("catalogTargetPath", path);
		if (listed.get("identityPaths") != null) {
			payload.put("identityPaths", listed.get("identityPaths"));
		}
		if (listed.get("preserveFields") != null) {
			payload.put("preserveFields", listed.get("preserveFields"));
		}
		payload.put("selectionSource", "RESOLVED");
	}

	private void storeListedCatalog(Map<String, Object> context, Map<String, Object> payload, String itemLabel) {
		Map<String, Object> stored = new LinkedHashMap<>();
		stored.put("path", payload.get("catalogTargetPath"));
		stored.put("options", payload.get("listedOptions"));
		stored.put("identityPaths", payload.get("identityPaths"));
		stored.put("preserveFields", payload.get("preserveFields"));
		stored.put("itemLabel", itemLabel);
		contextMapper.set(context, LISTED_CATALOG_PATH, stored);
	}

	private void clearListedCatalogIfResolved(Map<String, Object> context) {
		Map<String, Object> listed = map(contextMapper.get(context, LISTED_CATALOG_PATH));
		String path = text(listed.get("path"));
		if (StringUtils.hasText(path) && !contextMapper.isEmpty(contextMapper.get(context, path))) {
			contextMapper.set(context, LISTED_CATALOG_PATH, null);
		}
	}

	private boolean shouldExtractCollectUtterance(AgentRequest request, Map<String, Object> config,
			Map<String, Object> context) {
		if (Boolean.TRUE.equals(contextMapper.get(context, PRIMARY_EXTRACT_RAN_PATH))) {
			// 同回合主抽取已消费整句：聚焦节点不再重复抽取；字段仍缺时由 collect 等待卡向用户要，
			// resolver 空 keyword 查候选列表兜底（与重复抽取的最终结果等价，省一次模型调用）。
			// 注意：等待态用户输入触发的抽取（applyCollectQueryInput 等）不会置位该标记，此卫语句不影响其链路。
			return false;
		}
		if (request == null || !StringUtils.hasText(request.getQuery()) || extractionBudgetExhausted(context)) {
			return false;
		}
		List<String> requiredPaths = strings(config.get("requiredPaths"));
		return requiredPaths.stream().anyMatch(path -> isIncompletePath(config, context, path))
				|| !incompleteRequiredAnyPaths(config, context).isEmpty();
	}

	private void extractCollectUtterance(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, DataAgentSkillVersion version, Map<String, Object> config,
			Map<String, Object> context) {
		String outputPath = firstText(text(config.get("outputPath")), INPUT_ROOT_PATH);
		try {
			Map<String, Object> extracted = extractWithLiteralMappings(request, modelConfig, map(config.get("schema")),
					text(config.get("instruction")), context, map(config.get("literalMappings")),
					strings(config.get("requiredPaths")), strings(config.get("requiredAnyPaths")), outputPath,
					extractionConfig(config), instance, version);
			if (!shouldSkipUtteranceApply(context)) {
				applyExtractedUtterance(request, context,
						utteranceExtractionSchema(map(config.get("schema")), version, context, outputPath), outputPath,
						extracted, config, version);
			}
		}
		catch (RuntimeException ex) {
			log.warn("FLOW collect utterance extraction failed, waiting for remaining fields. error={}",
					ex.getMessage());
		}
	}

	private List<Map<String, Object>> missingRequiredSlots(Map<String, Object> config, Map<String, Object> context,
			List<String> missing, List<String> missingAnyPaths) {
		LinkedHashSet<String> paths = new LinkedHashSet<>();
		if (missing != null) {
			paths.addAll(missing);
		}
		if (missingAnyPaths != null) {
			paths.addAll(missingAnyPaths);
		}
		Map<String, Object> fieldPrompts = map(map(config.get("collectionPresentation")).get("fieldPrompts"));
		List<Map<String, Object>> slots = new ArrayList<>();
		for (String path : paths) {
			if (!StringUtils.hasText(path) || !isIncompletePath(config, context, path)) {
				continue;
			}
			String prompt = remainingItemFieldPrompt(path, config, context);
			if (!StringUtils.hasText(prompt)) {
				prompt = firstText(text(fieldPrompts.get(path)), schemaFieldPrompt(path, config));
			}
			if (!StringUtils.hasText(prompt)) {
				continue;
			}
			Map<String, Object> slot = new LinkedHashMap<>();
			slot.put("path", path);
			String name = schemaField(path, INPUT_ROOT_PATH);
			if (StringUtils.hasText(name)) {
				slot.put("name", name);
			}
			slot.put("prompt", prompt);
			slots.add(slot);
		}
		return List.copyOf(slots);
	}

	private String schemaFieldPrompt(String path, Map<String, Object> config) {
		String field = schemaField(path, INPUT_ROOT_PATH);
		if (!StringUtils.hasText(field) || isBusinessIdPath(path) || isBusinessIdPath("/" + field)) {
			return null;
		}
		Map<String, Object> property = map(map(map(config.get("schema")).get("properties")).get(field));
		String labeled = firstText(text(property.get("title")), text(property.get("description")));
		if (!StringUtils.hasText(labeled)) {
			return null;
		}
		if (labeled.startsWith("请")) {
			return labeled.endsWith("。") || labeled.endsWith(".") ? labeled : labeled + "。";
		}
		return "请补充" + labeled + "。";
	}

	private String withTurnReply(Map<String, Object> context, String body) {
		String reply = sanitizeUserVisibleText(text(contextMapper.get(context, TURN_REPLY_PATH)).trim());
		String suffix = sanitizeUserVisibleText(StringUtils.hasText(body) ? body : "");
		if (!StringUtils.hasText(reply)) {
			return suffix;
		}
		return joinUniqueVisibleLines(reply, suffix);
	}

	private String joinUniqueVisibleLines(String left, String right) {
		Set<String> lines = new LinkedHashSet<>();
		addVisibleLines(lines, left);
		addVisibleLines(lines, right);
		return String.join("\n", lines);
	}

	private void addVisibleLines(Set<String> lines, String text) {
		if (!StringUtils.hasText(text)) {
			return;
		}
		for (String line : text.split("\\R")) {
			String trimmed = line.trim();
			if (StringUtils.hasText(trimmed)) {
				lines.add(trimmed);
			}
		}
	}

	private String sanitizeUserVisibleText(String text) {
		if (!StringUtils.hasText(text)) {
			return text == null ? "" : text;
		}
		String result = USER_VISIBLE_JSON_POINTER.matcher(text).replaceAll(" ");
		result = USER_VISIBLE_REQUIRED_PHRASE.matcher(result).replaceAll(" ");
		result = USER_VISIBLE_CAMEL_IDENT.matcher(result).replaceAll(" ");
		result = USER_VISIBLE_ASCII_FIELD_TAIL.matcher(result).replaceAll(" ");
		result = result.replaceAll("[\\t ]{2,}", " ");
		result = result.replaceAll(" *([，,、；;])+", "$1");
		result = result.replaceAll("[，,、；;]+(?=\\s*[。.!！？?\\n]|\\s*$)", "");
		result = result.replaceAll("(?:\\n\\s*){2,}", "\n");
		return result.trim();
	}

	private String remainingItemFieldPrompt(String path, Map<String, Object> config, Map<String, Object> context) {
		Object value = contextMapper.get(context, path);
		if (!(value instanceof List<?> items) || items.isEmpty()) {
			return null;
		}
		String field = schemaField(path, INPUT_ROOT_PATH);
		if (!StringUtils.hasText(field)) {
			return null;
		}
		Map<String, Object> fieldSchema = map(map(map(config.get("schema")).get("properties")).get(field));
		Map<String, Object> itemSchema = map(fieldSchema.get("items"));
		List<String> required = strings(itemSchema.get("required")).stream()
			.filter(name -> !isBusinessIdPath("/" + name))
			.toList();
		if (required.isEmpty()) {
			return null;
		}
		Set<String> skip = pairRoleFields(config, field);
		Set<String> missingFields = new java.util.LinkedHashSet<>();
		for (Object item : items) {
			Map<String, Object> row = item instanceof Map<?, ?> map ? map(map) : Map.of();
			for (String name : required) {
				if (skip.contains(name)) {
					continue;
				}
				if (contextMapper.isEmpty(row.get(name))) {
					missingFields.add(name);
				}
			}
		}
		if (missingFields.isEmpty()) {
			return null;
		}
		List<String> prompts = new ArrayList<>();
		for (String name : missingFields) {
			String prompt = itemFieldPrompt(itemSchema, name);
			if (StringUtils.hasText(prompt)) {
				prompts.add(prompt);
			}
		}
		return prompts.isEmpty() ? null : String.join("\n", prompts);
	}

	private Set<String> pairRoleFields(Map<String, Object> config, String collectionField) {
		Set<String> fields = new java.util.LinkedHashSet<>();
		List<Map<String, Object>> pairs = mapList(extractionConfig(config).get("pairExtractions"));
		if (pairs.isEmpty()) {
			pairs = mapList(config.get("pairExtractions"));
		}
		for (Map<String, Object> pair : pairs) {
			if (collectionField.equals(text(pair.get("field")))) {
				fields.add(firstText(text(pair.get("typeField")), "type"));
			}
		}
		return fields;
	}

	private String itemFieldPrompt(Map<String, Object> itemSchema, String field) {
		Map<String, Object> property = map(map(itemSchema.get("properties")).get(field));
		String labeled = firstText(text(property.get("title")), text(property.get("description")));
		if (StringUtils.hasText(labeled)) {
			if (labeled.startsWith("请")) {
				return labeled.endsWith("。") || labeled.endsWith(".") ? labeled : labeled + "。";
			}
			return "请补充" + labeled + "。";
		}
		if ("num".equals(field)) {
			return "请补充数量。";
		}
		return null;
	}

	/**
	 * 用 pairExtractions 的 typeField/from/to 给已抽出的列表项补角色，不解析用户话里的分隔符。
	 * 两项都有名称、角色为空时：第一项 from、最后一项 to。
	 */
	private void fillConfiguredPairRoles(Map<String, Object> context, Map<String, Object> config, String outputPath) {
		if (context == null || config == null) {
			return;
		}
		List<Map<String, Object>> pairs = mapList(extractionConfig(config).get("pairExtractions"));
		if (pairs.isEmpty()) {
			pairs = mapList(config.get("pairExtractions"));
		}
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (Map<String, Object> pair : pairs) {
			String field = text(pair.get("field"));
			if (!StringUtils.hasText(field)) {
				continue;
			}
			String typeField = firstText(text(pair.get("typeField")), "type");
			String nameField = firstText(text(pair.get("nameField")), "siteName");
			String from = firstText(text(pair.get("fromValue")), "0");
			String to = firstText(text(pair.get("toValue")), "1");
			String path = root + (root.endsWith("/") ? "" : "/") + escapePointer(field);
			Object value = contextMapper.get(context, path);
			if (!(value instanceof List<?> items) || items.size() < 2) {
				continue;
			}
			List<Map<String, Object>> rows = new ArrayList<>();
			boolean named = true;
			for (Object item : items) {
				if (!(item instanceof Map<?, ?>)) {
					named = false;
					break;
				}
				Map<String, Object> row = new LinkedHashMap<>(map(item));
				if (contextMapper.isEmpty(row.get(nameField))) {
					named = false;
				}
				rows.add(row);
			}
			if (!named || rows.size() < 2) {
				continue;
			}
			if (contextMapper.isEmpty(rows.get(0).get(typeField))) {
				rows.get(0).put(typeField, from);
				markValueSlots(context, path + "/0/" + escapePointer(typeField), from, "SYSTEM");
			}
			int last = rows.size() - 1;
			if (contextMapper.isEmpty(rows.get(last).get(typeField))) {
				rows.get(last).put(typeField, to);
				markValueSlots(context, path + "/" + last + "/" + escapePointer(typeField), to, "SYSTEM");
			}
			contextMapper.set(context, path, rows);
		}
	}

	private Map<String, Object> coerceWritableValues(Map<String, Object> values, Map<String, Object> schema) {
		if (values == null || values.isEmpty()) {
			return values == null ? Map.of() : values;
		}
		Map<String, Object> properties = map(schema.get("properties"));
		if (properties.isEmpty()) {
			return values;
		}
		Map<String, Object> coerced = new LinkedHashMap<>(values);
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			Map<String, Object> fieldSchema = map(properties.get(entry.getKey()));
			Object coercedValue = coerceSchemaValue(entry.getValue(), fieldSchema);
			if (coercedValue != null) {
				coerced.put(entry.getKey(), coercedValue);
			}
		}
		return coerced;
	}

	private Object coerceSchemaValue(Object value, Map<String, Object> schema) {
		if (value == null || schema.isEmpty()) {
			return value;
		}
		String type = text(schema.get("type"));
		if (("integer".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) && value instanceof String text
				&& StringUtils.hasText(text)) {
			Long parsed = leadingInteger(text);
			if (parsed != null) {
				return parsed;
			}
			return value;
		}
		if ("string".equalsIgnoreCase(type) && value instanceof Number number) {
			return Long.toString(number.longValue());
		}
		if ("string".equalsIgnoreCase(type) && value instanceof String text) {
			return trimTrailingIdentityPunctuation(text);
		}
		if (value instanceof List<?> items && "array".equalsIgnoreCase(type)) {
			Map<String, Object> itemSchema = map(schema.get("items"));
			List<Object> coerced = new ArrayList<>(items.size());
			for (Object item : items) {
				coerced.add(item instanceof Map<?, ?> row
						? coerceWritableValues(map(row), itemSchema) : coerceSchemaValue(item, itemSchema));
			}
			return coerced;
		}
		if (value instanceof Map<?, ?> row && "object".equalsIgnoreCase(type)) {
			return coerceWritableValues(map(row), schema);
		}
		return value;
	}

	private NodeResult review(AgentRequest request, ModelConfigDTO modelConfig, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		String pendingEditQuery = text(contextMapper.get(context, PENDING_EDIT_QUERY_PATH));
		if (StringUtils.hasText(pendingEditQuery) && !isProceedWithoutEditCommand(request, config, version)
				&& !isTurnList(context)) {
			Map<String, Object> nodeSchema = map(config.get("schema"));
			Map<String, Object> corrected = extractWithLiteralMappings(request, modelConfig, nodeSchema,
					text(config.get("instruction")), context, map(config.get("literalMappings")),
					strings(config.get("requiredPaths")), strings(config.get("requiredAnyPaths")), INPUT_ROOT_PATH,
					map(config.get("extraction")), instance, version);
			if (!shouldSkipUtteranceApply(context)) {
				applyExtractedUtterance(request, context,
						utteranceExtractionSchema(nodeSchema, version, context, INPUT_ROOT_PATH), INPUT_ROOT_PATH,
						corrected, config, version);
				contextMapper.set(context, PENDING_EDIT_QUERY_PATH, null);
				String refreshNext = reviewRefreshNext(config, context);
				if (StringUtils.hasText(refreshNext)) {
					return NodeResult.next(refreshNext, Map.of("edited", true));
				}
			}
		}
		NodeResult catalog = tryCatalogListing(request, instance, version, node, config, context, "REVIEW");
		if (catalog != null) {
			return catalog;
		}
		long revision = number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L);
		long reviewedRevision = number(contextMapper.get(context, "/runtime/reviewedRevision"), -1L);
		if (request.getFlowAction() != null && "SUBMIT".equalsIgnoreCase(request.getFlowAction().type())
				&& reviewedRevision == revision) {
			return NodeResult.next(node.next(), Map.of("reviewedRevision", revision));
		}
		String refreshNext = text(config.get("refreshNext"));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schema", map(config.get("schema")));
		payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("data", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("fieldMeta", map(contextMapper.get(context, "/slotMeta")));
		payload.put("instruction", text(config.get("instruction")));
		payload.put("referenceApplied", hasHistoryReference(context));
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		putInternalExtractionConfig(payload, config);
		List<String> issuePaths = strings(config.get("issuesPaths"));
		if (issuePaths.isEmpty() && StringUtils.hasText(text(config.get("issuesPath")))) {
			issuePaths = List.of(text(config.get("issuesPath")));
		}
		List<String> issues = issuePaths.stream()
				.flatMap(path -> strings(contextMapper.get(context, path)).stream()).toList();
		if (!issuePaths.isEmpty() && issues.isEmpty()) {
			return NodeResult.next(node.next(), Map.of("issuesResolved", true));
		}
		if (!issues.isEmpty()) {
			payload.put("errors", issues);
			payload.put("blocking", true);
		}
		if (StringUtils.hasText(refreshNext)) {
			payload.put("refreshNext", refreshNext);
		}
		List<Map<String, Object>> refreshRoutes = mapList(config.get("refreshRoutes"));
		if (!refreshRoutes.isEmpty()) {
			payload.put("refreshRoutes", refreshRoutes);
		}
		addUiConfig(payload, config, context);
		String prompt = render(config, "prompt", context,
				"请核对当前流程信息。需要调整时可直接在对话中说明；信息无误后点击继续。");
		return NodeResult.waiting(withSummary(prompt, payload), "REVIEW", payload);
	}

	private NodeResult resolve(AgentRequest request, DataAgentFlowInstance instance, DataAgentSkillVersion version,
			FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		if (StringUtils.hasText(text(config.get("forEach")))) {
			return resolveEach(request, instance, version, node, config, context);
		}
		if (skipWhenComplete(node.id(), config, context)) {
			return NodeResult.next(node.next(), Map.of("skipped", true));
		}
		if (shouldSkipReferenceResolver(config, context)) {
			contextMapper.set(context, "/referenceState/" + escapePointer(firstText(
					text(config.get("referencePolicyCode")), node.id())), Map.of("status", "SKIPPED"));
			return NodeResult.next(node.next(), Map.of("skipped", true));
		}
		List<String> missing = missingRequirements(config, context);
		if (!missing.isEmpty()) {
			return NodeResult.waiting("请先补充查询所需的信息", "COLLECT", Map.of("requiredPaths", missing));
		}
		Long resourceVersionId = longValue(config.get("resourceVersionId"));
		Map<String, Object> arguments = AgentRequestSnapshotSupport.enrichArguments(arguments(config, context), request);
		arguments = applyTextSearchOverride(request, node.id(), null, arguments);
		String fingerprint = fingerprint(node.id(), resourceVersionId, arguments);
		Map<String, Object> state = resolverState(context, node.id());
		if ("RESOLVED".equals(state.get("status")) && fingerprint.equals(state.get("fingerprint"))) {
			return NodeResult.next(node.next(), Map.of("reused", true));
		}
		Map<String, Object> result = invokeToolViaGateway(request, instance, version, node.id(), resourceVersionId,
				"READ", false, null, arguments, config);
		String outputPath = firstText(text(config.get("outputPath")), "/resolved/" + node.id());
		contextMapper.set(context, outputPath, result);
		// 单路构建点此前没有 findPublished，这里补一次 PK 查询取工具标题；config 是共享解析产物，禁止写入标题。
		String resolvedToolName = resourceVersionId == null ? null
				: resolvedToolDisplayName(resourceVersionMapper.findPublished(resourceVersionId));
		markResolverResolved(context,
				new ResolverTask(node.id(), resourceVersionId, config, arguments, fingerprint,
						number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L), resolvedToolName));
		return NodeResult.next(node.next(), Map.of("outputPath", outputPath));
	}

	/**
	 * 集合 Resolver 入口：对 forEach 列表逐条并发解析主数据，按结果转入选择/复核等待或推进下一节点。
	 */
	private NodeResult resolveEach(AgentRequest request, DataAgentFlowInstance instance, DataAgentSkillVersion version,
			FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		String issuesPath = text(config.get("issuesPath"));
		if (StringUtils.hasText(issuesPath)) {
			contextMapper.set(context, issuesPath, List.of());
		}
		List<String> missing = missingRequirements(config, context);
		if (!missing.isEmpty()) {
			return eachMissingRequirementsWaiting(config, context, missing);
		}
		String listPath = text(config.get("forEach"));
		Object value = contextMapper.get(context, listPath);
		if (!(value instanceof List<?> items) || items.isEmpty()) {
			return NodeResult.next(node.next(), Map.of("count", 0));
		}
		Map<String, Object> runtimeConfig = map(context.get(FLOW_RUNTIME_CONFIG_KEY));
		int maxItems = effectiveLimit(runtimeConfig, "maxFanOutItems",
				dataAgentProperties.getFlow().getMaxFanOutItems(), 1, 100);
		if (items.size() > maxItems) {
			throw CheckedException.badRequest("待解析条目超过 Flow 允许的最大数量: " + maxItems);
		}
		Long resourceVersionId = longValue(config.get("resourceVersionId"));
		AgentExecutionResourceVersion resource = resourceVersionMapper.findPublished(resourceVersionId);
		if (resource == null || "WRITE".equalsIgnoreCase(resource.getAccessMode())) {
			throw CheckedException.badRequest("集合 Resolver 必须绑定已发布的 READ 工具版本");
		}
		String resolvedToolName = resolvedToolDisplayName(resource);
		List<String> identityPaths = strings(config.get("identityPaths"));
		List<EachResolverTask> tasks = collectEachResolverTasks(request, node, config, context, items, listPath,
				resourceVersionId, identityPaths);
		if (tasks.isEmpty()) {
			return NodeResult.next(node.next(), Map.of("reused", true));
		}
		List<CompletableFuture<ResolverOutcome>> futures = new ArrayList<>();
		Map<String, Long> taskStarts = new LinkedHashMap<>();
		ResolverRun resolverRun = beginResolverRun(instance.getId());
		try {
			NodeResult submitFailure = submitEachResolverTasks(request, instance, version, node, config,
					resourceVersionId, resolvedToolName, tasks, resolverRun, taskStarts, futures);
			if (submitFailure != null) {
				return submitFailure;
			}
			List<String> unresolved = new ArrayList<>();
			EachOutcomeSummary summary = collectEachResolverOutcomes(request, instance, node, config, context,
					resourceVersionId, resolvedToolName, identityPaths, tasks, futures, taskStarts, unresolved);
			if (StringUtils.hasText(summary.invocationFailureMessage())) {
				return resolverWaiting(summary.invocationFailureMessage(), unresolved);
			}
			if (summary.textSearchNotFound() != null) {
				return eachTextSearchNotFoundWaiting(config, context, summary.textSearchNotFound());
			}
			if (summary.pendingSelection() != null) {
				return eachPendingSelectionWaiting(request, config, context, summary.pendingSelection());
			}
			if (!unresolved.isEmpty() && Boolean.TRUE.equals(config.get("deferUnresolved"))
					&& StringUtils.hasText(issuesPath)) {
				contextMapper.set(context, issuesPath, List.copyOf(unresolved));
				return NodeResult.next(node.next(), Map.of("count", tasks.size(), "issues", unresolved.size()));
			}
			if (!unresolved.isEmpty()) {
				return eachUnresolvedReviewWaiting(config, context, unresolved);
			}
			return NodeResult.next(node.next(), Map.of("count", tasks.size()));
		}
		finally {
			endResolverRun(instance.getId(), resolverRun);
		}
	}

	/** 集合 Resolver 前置必填缺失时的 REVIEW 等待响应。 */
	private NodeResult eachMissingRequirementsWaiting(Map<String, Object> config, Map<String, Object> context,
			List<String> missing) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("errors", List.of("请先补充主数据查询所需字段: " + String.join(", ", missing)));
		payload.put("schema", map(config.get("schema")));
		payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("data", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("instruction", text(config.get("instruction")));
		payload.put("blocking", true);
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		putInternalExtractionConfig(payload, config);
		return NodeResult.waiting("请先补充主数据查询所需的信息。", "REVIEW", payload);
	}

	/**
	 * 为集合内每个条目生成解析任务：跳过非对象条目、身份路径全空的条目与指纹未变化的已解析条目。
	 */
	private List<EachResolverTask> collectEachResolverTasks(AgentRequest request, FlowNode node,
			Map<String, Object> config, Map<String, Object> context, List<?> items, String listPath,
			Long resourceVersionId, List<String> identityPaths) {
		List<EachResolverTask> tasks = new ArrayList<>();
		Map<String, Object> globalArguments = arguments(config, context);
		Map<String, Object> itemMappings = map(config.get("itemArgumentMappings"));
		long revision = number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L);
		List<Map<String, Object>> itemStates = reconcileCollectionItemStates(context, node.id(), items, identityPaths,
				revision);
		for (int index = 0; index < items.size(); index++) {
			if (!(items.get(index) instanceof Map<?, ?> rawItem)) {
				continue;
			}
			Map<String, Object> item = map(rawItem);
			if (!identityPaths.isEmpty()
					&& identityPaths.stream().allMatch(path -> contextMapper.isEmpty(contextMapper.get(item, path)))) {
				continue;
			}
			Map<String, Object> mappedArguments = new LinkedHashMap<>(globalArguments);
			itemMappings.forEach((key, path) -> {
				Object mapped = contextMapper.get(item, text(path));
				if (!contextMapper.isEmpty(mapped)) {
					mappedArguments.put(key, mapped);
				}
			});
			Map<String, Object> taskArguments = AgentRequestSnapshotSupport.enrichArguments(mappedArguments, request);
			taskArguments = applyTextSearchOverride(request, node.id(), listPath + "/" + index, taskArguments);
			String itemKey = text(itemStates.get(index).get("key"));
			String taskId = node.id() + ":" + itemKey;
			String fingerprint = fingerprint(taskId, resourceVersionId, taskArguments);
			Map<String, Object> state = resolverState(context, taskId);
			if ("RESOLVED".equals(state.get("status")) && fingerprint.equals(state.get("fingerprint"))) {
				continue;
			}
			tasks.add(new EachResolverTask(taskId, index, listPath + "/" + index, item, taskArguments,
					fingerprint, revision));
		}
		return tasks;
	}

	/**
	 * 提交全部集合解析任务；执行队列拒绝或已取消时统一收尾并返回等待结果，正常提交返回 null。
	 */
	private NodeResult submitEachResolverTasks(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> config, Long resourceVersionId,
			String resolvedToolName, List<EachResolverTask> tasks, ResolverRun resolverRun, Map<String, Long> taskStarts,
			List<CompletableFuture<ResolverOutcome>> futures) {
		try {
			for (EachResolverTask task : tasks) {
				taskStarts.put(task.id(), System.nanoTime());
				emitFlowProgress(request, instance, node, task.id(), "FLOW_RESOLVER_RUNNING",
						AgentRuntimeProgressService.STATUS_RUNNING, null,
						firstText(text(config.get("displayName")), resolvedToolName, task.id()));
				futures.add(submitResolver(resolverRun, () -> invokeEachResolverTask(request, instance, version,
						node.id(), resourceVersionId, task, config)));
			}
			return null;
		}
		catch (RejectedExecutionException | CancellationException ex) {
			futures.forEach(future -> future.cancel(true));
			tasks.stream().filter(task -> taskStarts.containsKey(task.id()))
				.forEach(task -> emitEachResolverFinished(request, instance, node, config, resolvedToolName, task,
						taskStarts,
						ex instanceof CancellationException ? AgentRuntimeProgressService.STATUS_CANCELLED
								: AgentRuntimeProgressService.STATUS_FAILED, ex));
			return resolverWaiting(ex instanceof CancellationException
					? "流程已进入取消确认，当前查询已停止" : "Resolver 执行队列已满，请稍后重试",
					List.of(firstText(ex.getMessage(), ex instanceof CancellationException
							? "FLOW_RESOLVER_CANCELLED" : "FLOW_RESOLVER_QUEUE_FULL")));
		}
	}

	/**
	 * 收集集合解析结果：唯一候选直接写回并记录状态，无候选/多候选分别累计文本搜索未命中与待选择项，
	 * 修订号已变化的结果按取消处理。unresolved 由调用方创建并继续用于汇总提示。
	 */
	private EachOutcomeSummary collectEachResolverOutcomes(AgentRequest request, DataAgentFlowInstance instance,
			FlowNode node, Map<String, Object> config, Map<String, Object> context, Long resourceVersionId,
			String resolvedToolName, List<String> identityPaths, List<EachResolverTask> tasks,
			List<CompletableFuture<ResolverOutcome>> futures, Map<String, Long> taskStarts, List<String> unresolved) {
		String invocationFailureMessage = null;
		EachSelection pendingSelection = null;
		EachResolverTask textSearchNotFound = null;
		for (int taskIndex = 0; taskIndex < tasks.size(); taskIndex++) {
			EachResolverTask task = tasks.get(taskIndex);
			ResolverOutcome outcome;
			try {
				outcome = futures.get(taskIndex).get(remainingResolverTimeoutMs(request), TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Resolver batch interrupted", ex);
			}
			catch (ExecutionException | TimeoutException ex) {
				futures.get(taskIndex).cancel(true);
				outcome = new ResolverOutcome(Map.of(),
						ex instanceof ExecutionException execution ? execution.getCause() : ex);
			}
			catch (CancellationException ex) {
				outcome = new ResolverOutcome(Map.of(), ex);
			}
			if (outcome.error() != null) {
				emitEachResolverFinished(request, instance, node, config, resolvedToolName, task, taskStarts,
						AgentRuntimeProgressService.STATUS_FAILED, outcome.error());
				unresolved.add(task.id() + ": " + outcome.error().getMessage());
				if (invocationFailureMessage == null && outcome.error() instanceof ToolInvocationException) {
					invocationFailureMessage = outcome.error().getMessage();
				}
				continue;
			}
			if (task.revision() != number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L)) {
				emitEachResolverFinished(request, instance, node, config, resolvedToolName, task, taskStarts,
						AgentRuntimeProgressService.STATUS_CANCELLED, null);
				continue;
			}
			List<Map<String, Object>> candidates = candidateItems(outcome.result(), config);
			emitEachResolverFinished(request, instance, node, config, resolvedToolName, task, taskStarts,
					AgentRuntimeProgressService.STATUS_SUCCESS, null);
			if (candidates.isEmpty()) {
				if (isTextSearchTarget(request, node.id(), task.itemPath())) {
					textSearchNotFound = task;
					continue;
				}
				unresolved.add(firstText(text(config.get("itemLabel")), "条目") + " " + (task.index() + 1)
						+ " 未找到可用主数据");
				continue;
			}
			if (candidates.size() > 1) {
				if (pendingSelection == null) {
					pendingSelection = new EachSelection(task, candidates);
				}
				continue;
			}
			Map<String, Object> normalized = normalizeCandidate(context, task, candidates.get(0), config);
			contextMapper.set(context, task.itemPath(), normalized);
			markResolvedCandidateSlots(context, task.itemPath(), candidates.get(0), config);
			updateCollectionItemState(context, node.id(), task.index(), normalized, identityPaths);
			markResolverResolved(context, new ResolverTask(task.id(), resourceVersionId, config, task.arguments(),
					task.fingerprint(), task.revision(), resolvedToolName));
		}
		return new EachOutcomeSummary(invocationFailureMessage, pendingSelection, textSearchNotFound);
	}

	/**
	 * 文本搜索未命中时的 SELECT 等待响应：空候选列表提示用户换关键词。
	 * 批量解析部分条目已成功时不提供 FALLBACK 回退动作：回退会清空声明路径、丢弃已解析进度，
	 * 损失大于收益，此处保持只引导换关键词重试。
	 */
	private NodeResult eachTextSearchNotFoundWaiting(Map<String, Object> config, Map<String, Object> context,
			EachResolverTask textSearchNotFound) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetPath", textSearchNotFound.itemPath());
		payload.put("options", List.of());
		payload.put("allowSkip", false);
		payload.put("selectionSource", "RESOLVED");
		payload.put("preserveFields", strings(config.get("preserveUserFields")));
		addUiConfig(payload, config, context);
		String notFoundText = firstText(text(map(config.get("textSearch")).get("notFoundText")),
				"未找到匹配项，请换一个名称或编码。");
		appendCancelUiAction(payload);
		return NodeResult.waiting(notFoundText, "SELECT", payload);
	}

	/** 命中多个候选时的 SELECT 等待响应：一次只处理首个待选择条目。 */
	private NodeResult eachPendingSelectionWaiting(AgentRequest request, Map<String, Object> config,
			Map<String, Object> context, EachSelection pendingSelection) {
		EachResolverTask task = pendingSelection.task();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetPath", task.itemPath());
		payload.put("options", selectionOptions(
				normalizedCandidates(context, task, pendingSelection.candidates(), config), config));
		payload.put("allowSkip", false);
		payload.put("selectionSource", "RESOLVED");
		payload.put("preserveFields", strings(config.get("preserveUserFields")));
		addUiConfig(payload, config, context);
		String prompt = "请为" + firstText(text(config.get("itemLabel")), "条目") + " " + (task.index() + 1)
				+ " 选择匹配项";
		return NodeResult.waiting(selectionWaitingText(request, context, prompt, payload), "SELECT", payload);
	}

	/** 存在未解析条目且不允许延后处理时的 REVIEW 等待响应。 */
	private NodeResult eachUnresolvedReviewWaiting(Map<String, Object> config, Map<String, Object> context,
			List<String> unresolved) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("errors", unresolved);
		payload.put("schema", map(config.get("schema")));
		payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("data", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("instruction", text(config.get("instruction")));
		payload.put("blocking", true);
		payload.put("referenceApplied", hasHistoryReference(context));
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		putInternalExtractionConfig(payload, config);
		addUiConfig(payload, config, context);
		return NodeResult.waiting("部分信息未匹配到可用主数据，请直接说明需要修改的内容。", "REVIEW", payload);
	}

	/** 集合解析一轮结果汇总：首个工具失败消息、首个待人工选择项、文本搜索未命中任务。 */
	private record EachOutcomeSummary(String invocationFailureMessage, EachSelection pendingSelection,
			EachResolverTask textSearchNotFound) {
	}

	private boolean isTextSearchTarget(AgentRequest request, String resolverNodeId, String itemPath) {
		FlowTextSearchIntent intent = request == null ? null : request.getFlowTextSearchIntent();
		return intent != null && intent.consumed() && resolverNodeId.equals(intent.resolverNodeId())
				&& (!StringUtils.hasText(intent.targetPath()) || intent.targetPath().equals(itemPath));
	}

	private List<Map<String, Object>> reconcileCollectionItemStates(Map<String, Object> context, String resolverId,
			List<?> items, List<String> identityPaths, long revision) {
		String statePath = "/runtime/collectionItems/" + escapePointer(resolverId);
		Object existingValue = contextMapper.get(context, statePath);
		List<Map<String, Object>> existing = new ArrayList<>();
		if (existingValue instanceof Iterable<?> values) {
			for (Object value : values) {
				if (value instanceof Map<?, ?> map) {
					existing.add(map(map));
				}
			}
		}
		boolean[] used = new boolean[existing.size()];
		List<Map<String, Object>> reconciled = new ArrayList<>();
		for (int index = 0; index < items.size(); index++) {
			Map<String, Object> item = items.get(index) instanceof Map<?, ?> map ? map(map) : Map.of();
			String signature = collectionItemSignature(item, identityPaths);
			String key = null;
			for (int candidate = 0; candidate < existing.size(); candidate++) {
				if (!used[candidate] && signature.equals(text(existing.get(candidate).get("signature")))) {
					key = text(existing.get(candidate).get("key"));
					used[candidate] = true;
					break;
				}
			}
			if (!StringUtils.hasText(key)) {
				key = revision + "-" + index + "-" + Integer.toUnsignedString(signature.hashCode());
			}
			reconciled.add(new LinkedHashMap<>(Map.of("key", key, "signature", signature)));
		}
		contextMapper.set(context, statePath, reconciled);
		return reconciled;
	}

	private void updateCollectionItemState(Map<String, Object> context, String resolverId, int index,
			Map<String, Object> normalized, List<String> identityPaths) {
		String statePath = "/runtime/collectionItems/" + escapePointer(resolverId) + "/" + index;
		Map<String, Object> state = new LinkedHashMap<>(map(contextMapper.get(context, statePath)));
		state.put("signature", collectionItemSignature(normalized, identityPaths));
		contextMapper.set(context, statePath, state);
	}

	private String collectionItemSignature(Map<String, Object> item, List<String> identityPaths) {
		Map<String, Object> identity = new LinkedHashMap<>();
		for (String path : identityPaths) {
			identity.put(path, contextMapper.get(item, path));
		}
		try {
			return objectMapper.writeValueAsString(identity);
		}
		catch (Exception ex) {
			return identity.toString();
		}
	}

	private void markResolvedCandidateSlots(Map<String, Object> context, String itemPath,
			Map<String, Object> candidate, Map<String, Object> config) {
		List<String> preserved = strings(config.get("preserveUserFields"));
		boolean historyOrigin = hasHistoryOrigin(context, itemPath);
		for (Map.Entry<String, Object> entry : candidate.entrySet()) {
			if (!preserved.contains(entry.getKey())) {
				String path = itemPath + "/" + escapePointer(entry.getKey());
				markValueSlots(context, path, entry.getValue(), "RESOLVED");
				if (historyOrigin) {
					markValueOrigin(context, path, entry.getValue(), "HISTORY");
				}
			}
		}
	}

	private boolean hasHistoryOrigin(Map<String, Object> context, String path) {
		return map(contextMapper.get(context, "/slotMeta")).entrySet().stream()
			.filter(entry -> entry.getValue() instanceof Map<?, ?>)
			.anyMatch(entry -> (entry.getKey().equals(path) || entry.getKey().startsWith(path + "/"))
					&& isHistoryOwned(map(entry.getValue())));
	}

	private void markValueOrigin(Map<String, Object> context, String path, Object value, String origin) {
		Map<String, Object> metadata = new LinkedHashMap<>(map(
				contextMapper.get(context, "/slotMeta/" + escapePointer(path))));
		metadata.put("origin", origin);
		contextMapper.set(context, "/slotMeta/" + escapePointer(path), metadata);
		if (value instanceof Map<?, ?> map) {
			map.forEach((key, nested) -> markValueOrigin(context,
					path + "/" + escapePointer(String.valueOf(key)), nested, origin));
		}
		else if (value instanceof List<?> list) {
			for (int index = 0; index < list.size(); index++) {
				markValueOrigin(context, path + "/" + index, list.get(index), origin);
			}
		}
	}

	private void emitEachResolverFinished(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			Map<String, Object> config, String resolvedToolName, EachResolverTask task, Map<String, Long> taskStarts,
			String status, Throwable error) {
		Long startedAt = taskStarts.get(task.id());
		long durationMs = startedAt == null ? 0L : elapsedMs(startedAt);
		String displayName = firstText(text(config.get("displayName")), resolvedToolName, task.id());
		emitFlowProgress(request, instance, node, task.id(), "FLOW_RESOLVER_FINISHED", status, durationMs,
				displayName, error);
	}

	private ResolverOutcome invokeEachResolverTask(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, String invokingNodeId, Long resourceVersionId, EachResolverTask task,
			Map<String, Object> config) {
		try {
			Map<String, Object> result = invokeToolViaGateway(request, instance, version, invokingNodeId,
					resourceVersionId, "READ", false, null, task.arguments(), config);
			return new ResolverOutcome(result == null ? Map.of() : result, null);
		}
		catch (RuntimeException ex) {
			return new ResolverOutcome(Map.of(), ex);
		}
	}

	private List<Map<String, Object>> candidateItems(Map<String, Object> result, Map<String, Object> config) {
		String candidatesPath = firstText(text(config.get("candidatesPath")), "/items");
		Object value = contextMapper.get(result, candidatesPath);
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<Map<String, Object>> candidates = new ArrayList<>();
		for (Object item : iterable) {
			if (item instanceof Map<?, ?> map) {
				candidates.add(map(map));
			}
		}
		return List.copyOf(candidates);
	}

	private List<Map<String, Object>> normalizedCandidates(Map<String, Object> context, EachResolverTask task,
			List<Map<String, Object>> candidates, Map<String, Object> config) {
		return candidates.stream().map(candidate -> normalizeCandidate(context, task, candidate, config)).toList();
	}

	private Map<String, Object> normalizeCandidate(Map<String, Object> context, EachResolverTask task,
			Map<String, Object> candidate, Map<String, Object> config) {
		Map<String, Object> normalized = new LinkedHashMap<>(task.item());
		normalized.putAll(candidate);
		for (String field : strings(config.get("preserveUserFields"))) {
			String fieldPath = task.itemPath() + "/" + escapePointer(field);
			Map<String, Object> meta = map(contextMapper.get(context, "/slotMeta/" + escapePointer(fieldPath)));
			if ("USER".equalsIgnoreCase(text(meta.get("source"))) && !contextMapper.isEmpty(task.item().get(field))) {
				normalized.put(field, task.item().get(field));
			}
		}
		if (contextMapper.isEmpty(normalized.get("address"))) {
			Object siteAddress = firstNonEmpty(candidate.get("address"),
					firstNonEmpty(candidate.get("fullAddress"), candidate.get("siteAddress")));
			if (siteAddress != null && StringUtils.hasText(String.valueOf(siteAddress))) {
				normalized.put("address", siteAddress);
			}
		}
		return normalized;
	}

	private boolean shouldSkipReferenceResolver(Map<String, Object> config, Map<String, Object> context) {
		if (config == null || (!config.containsKey("referencePolicyCode") && !Boolean.TRUE.equals(config.get("reference")))) {
			return false;
		}
		String decision = text(contextMapper.get(context, "/runtime/referenceDecision"));
		if ("SKIP_CURRENT_FLOW".equalsIgnoreCase(decision)) {
			return true;
		}
		if ("FORCE_QUERY".equalsIgnoreCase(decision)) {
			return false;
		}
		if (!Boolean.TRUE.equals(config.get("skipWhenComplete"))) {
			return false;
		}
		List<String> referenceablePaths = strings(config.get("referenceablePaths"));
		return !referenceablePaths.isEmpty()
				&& referenceablePaths.stream().allMatch(path -> !contextMapper.isEmpty(contextMapper.get(context, path)));
	}

	private boolean skipWhenComplete(String resolverId, Map<String, Object> config, Map<String, Object> context) {
		if (config == null || !Boolean.TRUE.equals(config.get("skipWhenComplete"))) {
			return false;
		}
		if ("INVALIDATED".equals(resolverState(context, resolverId).get("status"))) {
			return false;
		}
		List<String> completePaths = strings(config.get("completePaths"));
		return !completePaths.isEmpty()
				&& completePaths.stream().allMatch(path -> !contextMapper.isEmpty(contextMapper.get(context, path)));
	}

	private NodeResult select(AgentRequest request, DataAgentSkillVersion version, FlowNode node,
			Map<String, Object> config, Map<String, Object> context) {
		String optionsPath = text(config.get("optionsPath"));
		Object options = contextMapper.get(context, optionsPath);
		List<?> list = options instanceof List<?> values ? values : List.of();
		if (list.isEmpty()) {
			if (isTextSearchResponse(request, node.id())) {
				Map<String, Object> payload = selectionWaitingPayload(node, config, context, list);
				String notFoundText = firstText(text(map(config.get("textSearch")).get("notFoundText")),
						"未找到匹配项，请换一个名称或编码。");
				appendCancelUiAction(payload);
				appendFallbackUiAction(payload, config);
				return NodeResult.waiting(notFoundText, "SELECT", payload);
			}
			String skipWhenPresentPath = text(config.get("skipWhenPresentPath"));
			if (StringUtils.hasText(skipWhenPresentPath)
					&& !contextMapper.isEmpty(contextMapper.get(context, skipWhenPresentPath))) {
				return NodeResult.next(node.next(), Map.of("skipped", true));
			}
			NodeResult resolverFailure = resolverToolErrorWaiting(request, node, config, context);
			if (resolverFailure != null) {
				return resolverFailure;
			}
			String emptyNext = text(config.get("emptyNext"));
			if (StringUtils.hasText(emptyNext) && collectAlreadySatisfied(version, emptyNext, context)) {
				NodeResult fallback = fallbackBounce(node, config, context, list);
				if (fallback != null) {
					return fallback;
				}
				return emptyOptionsWaiting(request, node, config, context, list);
			}
			return NodeResult.next(firstText(emptyNext, node.next()), Map.of("count", 0));
		}
		String targetPath = firstText(text(config.get("targetPath")), "/selection/" + node.id());
		String skipPath = "/runtime/skipped/" + node.id();
		if (Boolean.TRUE.equals(contextMapper.get(context, skipPath))) {
			return NodeResult.next(node.next(), Map.of("count", list.size(), "skipped", true));
		}
		if (list.size() == 1 && !Boolean.FALSE.equals(config.get("autoSelectSingle"))) {
			contextMapper.set(context, targetPath, list.get(0));
			return NodeResult.next(node.next(), Map.of("count", 1));
		}
		if (!contextMapper.isEmpty(contextMapper.get(context, targetPath))) {
			return NodeResult.next(node.next(), Map.of("count", list.size()));
		}
		Map<String, Object> payload = selectionWaitingPayload(node, config, context, list);
		appendFallbackUiAction(payload, config);
		return NodeResult.waiting(
				selectionWaitingText(request, context, render(config, "prompt", context, "请选择一项。"), payload),
				"SELECT", payload);
	}

	/**
	 * 文本通道没有卡片，必须把候选项写进等待正文；Web 仍只展示 prompt，选项走 payload。
	 * 死路自动回退的原因说明（flowNotice）经 withFlowNotice 统一前置：它是回退原因，随回合清空，
	 * 只在本回合卡片上展示，且与正文相同/正文已包含时不重复拼接。
	 */
	private String selectionWaitingText(AgentRequest request, Map<String, Object> context, String prompt,
			Map<String, Object> payload) {
		String combined = withFlowNotice(context, prompt);
		if (request == null || !request.supportsTextCommands()) {
			return combined;
		}
		Map<String, Object> safePayload = payload == null ? Map.of() : payload;
		return FlowSelectTextSupport.format(combined, publicOptions(safePayload),
				strings(safePayload.get("skipCommands")));
	}

	private NodeResult resolverToolErrorWaiting(AgentRequest request, FlowNode node, Map<String, Object> config,
			Map<String, Object> context) {
		if (!isResolverToolError(resolverOutputForSelect(config, context))) {
			return null;
		}
		log.warn("FLOW empty select stopped because resolver output failed, nodeId={}, optionsPath={}", node.id(),
				text(config.get("optionsPath")));
		Map<String, Object> payload = selectionWaitingPayload(node, config, context, List.of());
		payload.put("blocking", true);
		payload.put("retryable", true);
		appendFallbackUiAction(payload, config);
		String message = "主数据查询失败，请换一个客户或稍后重试。";
		return NodeResult.waiting(selectionWaitingText(request, context, message, payload), "SELECT", payload);
	}

	private NodeResult emptyOptionsWaiting(AgentRequest request, FlowNode node, Map<String, Object> config,
			Map<String, Object> context, List<?> list) {
		Map<String, Object> payload = selectionWaitingPayload(node, config, context, list);
		// 该分支是"当前范围查不出任何数据"的死路：换关键词也无济于事，不再复用
		// textSearch.notFoundText（那是"关键词未命中"专用文案，保留在另一个分支），
		// 改为节点级 emptyOptionsText 可配置 + 通用缺省兜底，并保证卡片带退出动作。
		String notFoundText = firstText(text(config.get("emptyOptionsText")),
				"当前没有可选项，无法继续办理。可调整条件后重新发起，或联系管理员检查基础数据配置。");
		appendCancelUiAction(payload);
		appendFallbackUiAction(payload, config);
		return NodeResult.waiting(selectionWaitingText(request, context, notFoundText, payload), "SELECT", payload);
	}

	/**
	 * 死路自动回退：清声明的选择路径并跳回重选点，让用户直接换选；每回合至多一次，防止循环弹跳。
	 */
	private NodeResult fallbackBounce(FlowNode node, Map<String, Object> config, Map<String, Object> context,
			List<?> list) {
		String fallbackNode = text(config.get("fallbackNode"));
		if (!StringUtils.hasText(fallbackNode)
				|| Boolean.TRUE.equals(contextMapper.get(context, FALLBACK_APPLIED_PATH))) {
			return null;
		}
		for (String path : strings(config.get("fallbackClears"))) {
			// 回退只清声明的选择路径，不清用户提供的其他输入（到货时间/类型等多信息字段全保留）。
			contextMapper.set(context, path, null);
		}
		contextMapper.set(context, FLOW_NOTICE_PATH,
				firstText(text(config.get("emptyOptionsText")), "当前没有可选项，请返回上一步重新选择。"));
		contextMapper.set(context, FALLBACK_APPLIED_PATH, Boolean.TRUE);
		log.info("FLOW empty options fallback bounce. nodeId={}, fallbackNode={}", node.id(), fallbackNode);
		return NodeResult.next(fallbackNode, Map.of("fallback", true));
	}

	/** 空选项死路等待卡：确保用户有明确退出动作；uiActions 已含 CANCEL 时不重复添加。 */
	private void appendCancelUiAction(Map<String, Object> payload) {
		List<Map<String, Object>> uiActions = new ArrayList<>(mapList(payload.get("uiActions")));
		boolean hasCancel = uiActions.stream().anyMatch(item -> "CANCEL".equalsIgnoreCase(text(item.get("type"))));
		if (!hasCancel) {
			uiActions.add(Map.of("actionId", "cancel-flow", "type", "CANCEL", "label", "取消流程"));
		}
		boolean hasSelect = uiActions.stream().anyMatch(item -> "SELECT".equalsIgnoreCase(text(item.get("type"))));
		if (!hasSelect) {
			uiActions.add(new LinkedHashMap<>(Map.of("actionId", "select-option", "type", "SELECT", "label", "选择")));
		}
		payload.put("uiActions", uiActions);
	}

	/** 配置了 fallbackNode 的选择等待卡提供重选动作，用户可一键回到重选点换选。 */
	private void appendFallbackUiAction(Map<String, Object> payload, Map<String, Object> config) {
		if (!StringUtils.hasText(text(config.get("fallbackNode")))) {
			return;
		}
		List<Map<String, Object>> uiActions = new ArrayList<>(mapList(payload.get("uiActions")));
		boolean hasSelect = uiActions.stream().anyMatch(item -> "SELECT".equalsIgnoreCase(text(item.get("type"))));
		if (!hasSelect) {
			uiActions.add(new LinkedHashMap<>(Map.of("actionId", "select-option", "type", "SELECT", "label", "选择")));
		}
		boolean hasFallback = uiActions.stream().anyMatch(item -> "FALLBACK".equalsIgnoreCase(text(item.get("type"))));
		if (!hasFallback) {
			uiActions.add(new LinkedHashMap<>(Map.of("actionId", "reselect", "type", "FALLBACK",
					"label", firstText(text(config.get("fallbackLabel")), "重新选择"))));
		}
		payload.put("uiActions", uiActions);
	}

	private Map<String, Object> resolverOutputForSelect(Map<String, Object> config, Map<String, Object> context) {
		String optionsPath = text(config.get("optionsPath"));
		Object value = contextMapper.get(context, optionsPath);
		if (value instanceof Map<?, ?>) {
			return map(value);
		}
		return map(contextMapper.get(context, parentJsonPointer(optionsPath)));
	}

	private boolean isResolverToolError(Map<String, Object> output) {
		return Boolean.TRUE.equals(output.get("isError")) || Boolean.TRUE.equals(output.get("toolError"));
	}

	private String parentJsonPointer(String path) {
		if (!StringUtils.hasText(path) || "/".equals(path)) {
			return path;
		}
		int slash = path.lastIndexOf('/');
		return slash <= 0 ? "/" : path.substring(0, slash);
	}

	private boolean collectAlreadySatisfied(DataAgentSkillVersion version, String nodeId, Map<String, Object> context) {
		FlowNode target = findFlowNode(version, nodeId);
		if (target == null || !"collect".equalsIgnoreCase(target.type()) || target.config() == null) {
			return false;
		}
		Map<String, Object> targetConfig = target.config();
		if (!incompleteRequiredPaths(targetConfig, context).isEmpty()) {
			return false;
		}
		List<String> anyPaths = strings(targetConfig.get("requiredAnyPaths"));
		if (anyPaths.isEmpty()) {
			return true;
		}
		return anyPaths.stream().anyMatch(path -> !isIncompletePath(targetConfig, context, path));
	}

	private Map<String, Object> selectionWaitingPayload(FlowNode node, Map<String, Object> config,
			Map<String, Object> context, List<?> options) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("targetPath", firstText(text(config.get("targetPath")), "/selection/" + node.id()));
		payload.put("optionsPath", text(config.get("optionsPath")));
		payload.put("options", selectionOptions(options, config));
		payload.put("skipPath", "/runtime/skipped/" + node.id());
		payload.put("allowSkip", Boolean.TRUE.equals(config.get("allowSkip")));
		List<String> skipCommands = strings(config.get("skipCommands"));
		if (!skipCommands.isEmpty()) {
			payload.put("skipCommands", skipCommands);
		}
		String skipDecisionPath = text(config.get("skipDecisionPath"));
		if (StringUtils.hasText(skipDecisionPath) && config.get("skipDecisionValue") != null) {
			payload.put("skipDecisionPath", skipDecisionPath);
			payload.put("skipDecisionValue", config.get("skipDecisionValue"));
		}
		addUiConfig(payload, config, context);
		return payload;
	}

	private boolean isTextSearchResponse(AgentRequest request, String waitingNodeId) {
		FlowTextSearchIntent intent = request == null ? null : request.getFlowTextSearchIntent();
		return intent != null && intent.consumed() && waitingNodeId.equals(intent.waitingNodeId());
	}

	private NodeResult merge(AgentRequest request, FlowNode node, Map<String, Object> config,
			Map<String, Object> context) {
		Object source = contextMapper.get(context, text(config.get("sourcePath")));
		String targetPath = text(config.get("targetPath"));
		String strategy = firstText(text(config.get("strategy")), "FILL_MISSING");
		List<String> historyTargets = "HISTORY".equalsIgnoreCase(text(config.get("source")))
				? missingMergeTargets(context, targetPath, source) : List.of();
		if ("REPLACE".equalsIgnoreCase(strategy)) {
			contextMapper.set(context, targetPath, source);
		}
		else if ("OVERWRITE_PRESENT".equalsIgnoreCase(strategy)) {
			contextMapper.mergePresent(context, targetPath, source);
		}
		else {
			contextMapper.mergeMissing(context, targetPath, source);
		}
		Map<String, Object> schema = map(config.get("schema"));
		if (StringUtils.hasText(targetPath) && !schema.isEmpty()) {
			Map<String, Object> mergedValues = map(contextMapper.get(context, targetPath));
			FlowTemporalNormalizer.NormalizationResult temporal = temporalNormalizer.normalize(mergedValues, schema,
					request);
			if (!temporal.values().equals(mergedValues)) {
				contextMapper.set(context, targetPath, temporal.values());
			}
		}
		for (String path : historyTargets) {
			Object value = contextMapper.get(context, path);
			if (!contextMapper.isEmpty(value)) {
				markValueSlots(context, path, value, "HISTORY");
			}
		}
		String sourcePath = text(config.get("sourcePath"));
		if (!StringUtils.hasText(sourcePath) || !sourcePath.startsWith("/resolved/")) {
			recordMergedPaths(context, targetPath, source);
		}
		return NodeResult.next(node.next(), Map.of());
	}

	private NodeResult validate(AgentRequest request, DataAgentFlowInstance instance, DataAgentSkillVersion version,
			FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		NodeResult catalog = tryCatalogListing(request, instance, version, node, config, context, "VALIDATION");
		if (catalog != null) {
			return catalog;
		}
		String targetPath = firstText(text(config.get("targetPath")), INPUT_ROOT_PATH);
		Map<String, Object> currentValues = map(contextMapper.get(context, targetPath));
		FlowTemporalNormalizer.NormalizationResult temporal = temporalNormalizer.normalize(currentValues,
				map(config.get("schema")), request);
		if (!temporal.values().equals(currentValues)) {
			contextMapper.set(context, targetPath, temporal.values());
		}
		Map<String, Object> schema = map(config.get("schema"));
		Object coerced = coerceSchemaValue(contextMapper.get(context, targetPath), schema);
		if (coerced instanceof Map<?, ?> coercedMap) {
			contextMapper.set(context, targetPath, new LinkedHashMap<>(map(coercedMap)));
		}
		List<String> errors = schemaValidator.validate(contextMapper.get(context, targetPath), schema);
		if (errors.isEmpty()) {
			// 校验通过必须清掉历史残留错误，否则确认节点的参数校验门槛会被旧错误误伤。
			if (!contextMapper.isEmpty(contextMapper.get(context, "/validation/errors"))) {
				contextMapper.set(context, "/validation/errors", List.of());
			}
			return NodeResult.next(node.next(), Map.of());
		}
		String invalidNext = text(config.get("invalidNext"));
		if (StringUtils.hasText(invalidNext)) {
			contextMapper.set(context, "/validation/errors", errors);
			return NodeResult.next(invalidNext, Map.of("errors", errors));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("errors", errors);
		payload.put("schema", map(config.get("schema")));
		payload.put("targetPath", targetPath);
		payload.put("currentValues", map(contextMapper.get(context, targetPath)));
		payload.put("instruction", text(config.get("instruction")));
		Map<String, Object> literalMappings = map(config.get("literalMappings"));
		if (!literalMappings.isEmpty()) {
			payload.put("literalMappings", literalMappings);
		}
		putInternalExtractionConfig(payload, config);
		addUiConfig(payload, config, context);
		// VALIDATION waits on schema errors, not empty required slots; omit missing so FORM shows full schema.
		return NodeResult.waiting(displayValidationErrors(errors, config), "VALIDATION", payload);
	}

	private NodeResult switchNode(FlowNode node, Map<String, Object> context) {
		for (FlowBranch branch : node.branches() == null ? List.<FlowBranch>of() : node.branches()) {
			if (branch != null && conditionEvaluator.evaluate(branch.condition(), context)) {
				return NodeResult.next(branch.next(), Map.of());
			}
		}
		return NodeResult.next(node.next(), Map.of());
	}

	private NodeResult confirm(AgentRequest request, DataAgentSkillVersion version, FlowNode node,
			Map<String, Object> config, Map<String, Object> context) {
		assertConfirmReady(config, context, version);
		if (request.getFlowAction() != null && "CONFIRM".equalsIgnoreCase(request.getFlowAction().type())
				&& Boolean.TRUE.equals(request.getFlowAction().value())) {
			contextMapper.set(context, "/runtime/confirmed", true);
			contextMapper.set(context, "/runtime/confirmedRevision",
					number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L));
			contextMapper.set(context, "/runtime/callerSnapshot", callerSnapshot(request));
			return NodeResult.next(node.next(), Map.of());
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("summaryPath", text(config.get("summaryPath")));
		payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("data", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		payload.put("editNode", text(config.get("editNode")));
		addUiConfig(payload, config, context);
		return NodeResult.waiting(withSummary(render(config, "summary", context, "请确认是否提交。"), payload),
				"CONFIRM", payload);
	}

	/**
	 * 业务写 Flow 门槛：进入确认节点前必须满足「抽取完成 + 实体解析完成 + 参数校验通过」，
	 * 缺任何一项都不允许向用户展示确认，更不允许流转到写入节点。
	 */
	private void assertConfirmReady(Map<String, Object> config, Map<String, Object> context,
			DataAgentSkillVersion version) {
		List<String> missing = strings(config.get("requiredPaths")).stream()
			.filter(path -> contextMapper.isEmpty(contextMapper.get(context, path)))
			.toList();
		if (!missing.isEmpty()) {
			log.warn("FLOW confirm blocked by incomplete required paths: {}", missing);
			throw CheckedException.badRequest("进入确认前仍有必填信息未完成抽取，请先补充完整");
		}
		List<String> unresolved = unresolvedResolverIds(context, version);
		if (!unresolved.isEmpty()) {
			log.warn("FLOW confirm blocked by unresolved resolvers: {}", unresolved);
			throw CheckedException.badRequest("进入确认前实体解析未完成或已失效，请先完成主数据解析");
		}
		List<String> validationErrors = strings(contextMapper.get(context, "/validation/errors"));
		if (!validationErrors.isEmpty()) {
			throw CheckedException.badRequest("进入确认前参数校验未通过，请先修正后再确认");
		}
	}

	private List<String> unresolvedResolverIds(Map<String, Object> context, DataAgentSkillVersion version) {
		Map<String, Object> resolverStates = map(contextMapper.get(context, "/resolverState"));
		boolean skippedReference = "SKIP_CURRENT_FLOW".equalsIgnoreCase(
				text(contextMapper.get(context, REFERENCE_DECISION_PATH)));
		Set<String> referenceIds = referenceResolverIds(version);
		List<String> unresolved = new ArrayList<>();
		for (Map.Entry<String, Object> entry : resolverStates.entrySet()) {
			String status = text(map(entry.getValue()).get("status")).toUpperCase(Locale.ROOT);
			if ("SKIPPED".equals(status)) {
				continue;
			}
			if ("INVALIDATED".equals(status) && skippedReference && referenceIds.contains(entry.getKey())) {
				continue;
			}
			if ("FAILED".equals(status) || "INVALIDATED".equals(status)) {
				unresolved.add(entry.getKey());
			}
		}
		return unresolved;
	}

	private Set<String> referenceResolverIds(DataAgentSkillVersion version) {
		Set<String> ids = new LinkedHashSet<>();
		for (FlowNode node : flowNodes(version)) {
			if (isReferenceResolver(node)) {
				ids.add(node.id());
			}
		}
		return ids;
	}

	private boolean isReferenceResolver(FlowNode node) {
		if (node == null || node.config() == null || !"resolve".equalsIgnoreCase(node.type())) {
			return false;
		}
		return node.config().containsKey("referencePolicyCode")
				|| Boolean.TRUE.equals(node.config().get("reference"));
	}

	private NodeResult executeTool(AgentRequest request, DataAgentFlowInstance instance, DataAgentSkillVersion version,
			FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		if (!Boolean.TRUE.equals(contextMapper.get(context, "/runtime/confirmed"))) {
			throw CheckedException.badRequest("Write node cannot execute before confirmation");
		}
		long revision = number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L);
		long confirmedRevision = number(contextMapper.get(context, "/runtime/confirmedRevision"), -1L);
		if (confirmedRevision != revision) {
			throw CheckedException.badRequest("流程信息已发生变化，请重新复核并确认");
		}
		String idempotencyKey = instance.getIdempotencyKey();
		Map<String, Object> toolArguments = AgentRequestSnapshotSupport.enrichArguments(arguments(config, context), request);
		Long resumeApprovalId = longValue(contextMapper.get(context, "/runtime/resumeApprovalId"));
		Map<String, Object> result = invokeToolViaGateway(request, instance, version, node.id(),
				longValue(config.get("resourceVersionId")), "WRITE", true, idempotencyKey, toolArguments, config,
				resumeApprovalId);
		String outputPath = firstText(text(config.get("outputPath")), "/result");
		contextMapper.set(context, outputPath, result);
		Map<String, Object> successCondition = map(config.get("successCondition"));
		if (!successCondition.isEmpty() && !conditionEvaluator.evaluate(successCondition, context)) {
			String failureText = businessFailureText(result);
			return NodeResult.terminal(FlowInstanceStatus.FAILED, failureText, "FAILED", result,
					"FLOW_EXECUTION_RESULT_INVALID", idempotencyKey);
		}
		return new NodeResult(false, false, FlowInstanceStatus.RUNNING, node.next(), "", "EXECUTED",
				Map.of("outputPath", outputPath), null, idempotencyKey, result);
	}

	private String businessFailureText(Map<String, Object> result) {
		String message = firstText(text(result == null ? null : result.get("message")), "业务执行失败");
		List<String> missingFields = strings(result == null ? null : result.get("missingFields"));
		List<String> invalidFields = strings(result == null ? null : result.get("invalidFields"));
		List<String> details = new ArrayList<>();
		if (!missingFields.isEmpty()) {
			details.add("缺失字段：" + String.join("、", missingFields));
		}
		if (!invalidFields.isEmpty()) {
			details.add("非法字段：" + String.join("、", invalidFields));
		}
		return details.isEmpty() ? message : message + "（" + String.join("；", details) + "）";
	}

	private NodeResult recoverUnknown(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node,
			Map<String, Object> context) {
		Map<String, Object> executeConfig = node.config() == null ? Map.of() : node.config();
		Map<String, Object> resultQuery = map(executeConfig.get("resultQuery"));
		Long resourceVersionId = longValue(resultQuery.get("resourceVersionId"));
		if (resourceVersionId == null || !StringUtils.hasText(instance.getIdempotencyKey())) {
			throw CheckedException.badRequest("UNKNOWN recovery requires a resultQuery tool and idempotency key");
		}
		Map<String, Object> resultQueryArguments = AgentRequestSnapshotSupport.enrichArguments(arguments(resultQuery, context), request);
		Map<String, Object> result = invokeToolViaGateway(request, instance, version, node.id(), resourceVersionId,
				"READ", false, instance.getIdempotencyKey(), resultQueryArguments, resultQuery);
		String outputPath = firstText(text(resultQuery.get("outputPath")),
				firstText(text(executeConfig.get("outputPath")), "/result"));
		contextMapper.set(context, outputPath, result);
		if (conditionEvaluator.evaluate(map(resultQuery.get("completedCondition")), context)) {
			return new NodeResult(false, false, FlowInstanceStatus.RUNNING,
					firstText(text(resultQuery.get("next")), node.next()), "", "RECOVERED", Map.of(), null,
					instance.getIdempotencyKey(), Map.of("outputPath", outputPath));
		}
		return NodeResult.unknown(render(resultQuery, "pendingText", context,
				"写入结果仍在确认中，系统不会重复提交。"), Map.of("action", "POLL_RESULT"));
	}

	private NodeResult present(FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		String text = render(config, "text", context, "");
		boolean wait = Boolean.TRUE.equals(config.get("waitForAction"));
		Map<String, Object> presentation = new LinkedHashMap<>(config);
		presentation.put("text", text);
		String dataPath = text(config.get("dataPath"));
		if (StringUtils.hasText(dataPath)) {
			presentation.put("data", contextMapper.get(context, dataPath));
		}
		contextMapper.set(context, "/runtime/presentation", presentation);
		return wait ? NodeResult.waiting(text, firstText(text(config.get("action")), "PRESENT"), config)
				: new NodeResult(false, false, FlowInstanceStatus.RUNNING, node.next(), text, "PRESENT",
						config, null, null, Map.of());
	}

	private NodeResult end(DataAgentFlowInstance instance, Map<String, Object> config, Map<String, Object> context) {
		Object value = contextMapper.get(context, "/runtime/presentation");
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> presentation = map(map);
			String presentationText = text(presentation.get("text"));
			if (StringUtils.hasText(presentationText)) {
				return NodeResult.terminal(FlowInstanceStatus.SUCCEEDED, presentationText, "PRESENT", presentation,
						null, instance.getIdempotencyKey());
			}
		}
		return NodeResult.terminal(FlowInstanceStatus.SUCCEEDED,
				render(config, "text", context, "流程已完成。"), "SUCCEEDED", Map.of(), null,
				instance.getIdempotencyKey());
	}

	private NodeResult handoff(FlowNode node, Map<String, Object> config, Map<String, Object> context) {
		String text = render(config, "text", context, "请在指定入口继续办理。" );
		return NodeResult.terminal(FlowInstanceStatus.SUSPENDED, text, "HANDOFF", config, null, null);
	}

	/**
	 * 将等待中的用户输入（自由文本或 UI 动作）按 waiting action 类型分发到对应处理器。
	 * 分发条件的顺序即协议优先级，不可调整。
	 */
	private void applyWaitingInput(AgentRequest request, ModelConfigDTO modelConfig, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, Map<String, Object> context) {
		if (request.getFlowAction() != null && !FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			throw CheckedException.badRequest("当前 FLOW 不处于等待状态，不能执行交互操作");
		}
		if (!FlowInstanceStatus.WAITING.name().equals(instance.getStatus())
				&& !FlowInstanceStatus.UNKNOWN.name().equals(instance.getStatus())) {
			return;
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		if (FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			Map<String, Object> checkpoint = new LinkedHashMap<>();
			checkpoint.put("nodeId", instance.getCurrentNodeId());
			checkpoint.put("waitingPayload", waiting);
			contextMapper.set(context, "/runtime/lastInteractionCheckpoint", checkpoint);
		}
		String action = text(waiting.get("action"));
		validateWaitingAction(action, waiting, request);
		validateResumeVersion(request, instance);
		if (isProceedWithoutEditCommand(request, waiting, version)
				&& applyProceedWithoutExtract(request, waiting, version, context, action)) {
			return;
		}
		if ("EXTRACT".equals(action) && StringUtils.hasText(request.getQuery())) {
			applyExtractQueryInput(request, modelConfig, instance, version, context, waiting);
		}
		else if ("SELECT".equals(action) && request.getFlowAction() != null
				&& "FALLBACK".equalsIgnoreCase(request.getFlowAction().type())) {
			// FALLBACK 已在控制处理中清路径并改道（NEXT_NODE_OVERRIDE），此处无需再处理等待输入；
			// 消费掉动作避免回合内后续 handleTurnControlAction 再次触发重选校验。
			request.setFlowAction(null);
			return;
		}
		else if ("SELECT".equals(action) && request.getFlowAction() != null) {
			applySelectAction(request, version, context, waiting);
		}
		else if ("SELECT".equals(action) && isExplicitSelectionSkip(request.getQuery(), waiting)) {
			applySelectionSkip(context, waiting, version);
		}
		else if ("SELECT".equals(action) && isKeywordSearchInput(request, waiting)) {
			// 空选项等待卡已邀请输入名称/编码搜索：跳过 LLM 抽取，按关键词重查处理。
			// IM 渠道的意图已由 FlowTextInteractionResolver 设置；WEB 等结构化渠道不经过文本命令
			// 解析，由引擎补齐搜索意图（渠道无关）。随后统一由 textSearchStartNode 改道
			// textSearch.resolverNode 以 keyword 重查，select 节点重新评估（命中出候选卡，
			// 唯一命中自动选中推进；未命中出 notFoundText 提示卡）。
			if (request.getFlowTextSearchIntent() == null) {
				request.setFlowTextSearchIntent(buildTextSearchIntent(waiting, instance, request));
			}
			return;
		}
		else if ("SELECT".equals(action) && StringUtils.hasText(request.getQuery())) {
			applyCollectQueryInput(request, modelConfig, instance, version, context, waiting);
		}
		else if ("COLLECT".equals(action) && request.getFlowAction() != null
				&& "SELECT".equalsIgnoreCase(request.getFlowAction().type())) {
			applySelectAction(request, version, context, waiting);
		}
		else if (("COLLECT".equals(action) || "VALIDATION".equals(action)) && request.getFlowAction() != null
				&& request.getFlowAction().payload() != null) {
			applyFormSubmitInput(request, context, waiting, action);
		}
		else if ("COLLECT".equals(action) && StringUtils.hasText(request.getQuery())) {
			applyCollectQueryInput(request, modelConfig, instance, version, context, waiting);
		}
		else if ("VALIDATION".equals(action) && StringUtils.hasText(request.getQuery())) {
			applyValidationQueryInput(request, modelConfig, instance, version, context, waiting);
		}
		else if ("REVIEW".equals(action) && request.getFlowAction() != null) {
			applyReviewAction(request, context);
		}
		else if ("REVIEW".equals(action) && StringUtils.hasText(request.getQuery())) {
			applyReviewQueryInput(request, modelConfig, instance, version, context, waiting);
			if (request.getFlowAction() != null && "SUBMIT".equalsIgnoreCase(request.getFlowAction().type())) {
				applyReviewAction(request, context);
			}
		}
		else if ("CONFIRM".equals(action) && request.getFlowAction() != null
				&& "EDIT".equalsIgnoreCase(request.getFlowAction().type())) {
			applyConfirmEditAction(request, context, waiting);
		}
		else if ("APPROVAL".equals(action) && request.getFlowAction() != null
				&& "APPROVAL_RESUME".equalsIgnoreCase(request.getFlowAction().type())) {
			applyApprovalResume(context, waiting, request);
		}
		else if ("APPROVAL".equals(action) && request.getFlowAction() != null
				&& "CANCEL".equalsIgnoreCase(request.getFlowAction().type())) {
			applyApprovalCancel(request, instance, context, waiting);
		}
	}

	/**
	 * 空选项 SELECT 等待态收到自由文本：按关键词重查处理（该状态由提示卡明确邀请输入名称/编码）。
	 * 渠道无关——IM 意图已由 FlowTextInteractionResolver 预设；WEB 等结构化渠道由引擎补建。
	 */
	private boolean isKeywordSearchInput(AgentRequest request, Map<String, Object> waiting) {
		if (!StringUtils.hasText(request.getQuery())) {
			return false;
		}
		FlowTextSearchIntent intent = request.getFlowTextSearchIntent();
		if (intent != null && intent.consumed()) {
			return false;
		}
		if (intent == null) {
			Map<String, Object> textSearch = map(waiting.get("textSearch"));
			if (!Boolean.TRUE.equals(textSearch.get("enabled"))
					|| !StringUtils.hasText(text(textSearch.get("resolverNode")))) {
				return false;
			}
		}
		return waiting.get("options") instanceof List<?> options && options.isEmpty();
	}

	/** 空选项等待卡的引擎级搜索意图：与 FlowTextInteractionResolver 的构造保持同一契约。 */
	private FlowTextSearchIntent buildTextSearchIntent(Map<String, Object> waiting, DataAgentFlowInstance instance,
			AgentRequest request) {
		Map<String, Object> textSearch = map(waiting.get("textSearch"));
		return new FlowTextSearchIntent(instance.getCurrentNodeId(), text(textSearch.get("resolverNode")),
				text(waiting.get("targetPath")), request.getQuery().trim(),
				firstText(text(textSearch.get("argumentName")), "keyword"), false);
	}

	/** EXTRACT 等待态收到自由文本：重新抽取并回填，必要时按恢复模式直接推进到原目标节点。 */
	private void applyExtractQueryInput(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, DataAgentSkillVersion version, Map<String, Object> context,
			Map<String, Object> waiting) {
		String targetPath = firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		Map<String, Object> nodeSchema = map(waiting.get("schema"));
		Map<String, Object> extracted = extractWithLiteralMappings(request, modelConfig, nodeSchema,
				text(waiting.get("instruction")), context, map(waiting.get("literalMappings")),
				strings(waiting.get("requiredPaths")), strings(waiting.get("requiredAnyPaths")), targetPath,
				extractionConfig(waiting), instance, version);
		// 恢复抽取即本回合主抽取，已消费整句输入：EXTRACT_THEN_ADVANCE 推进到 collect 时不再重复聚焦抽取。
		contextMapper.set(context, PRIMARY_EXTRACT_RAN_PATH, Boolean.TRUE);
		if (applyExtractedTurnIntent(request, version, waiting, context, text(waiting.get("action")))) {
			return;
		}
		applyExtractedUtterance(request, context, utteranceExtractionSchema(nodeSchema, version, context, targetPath),
				targetPath, extracted, waiting, version);
		if ("EXTRACT_THEN_ADVANCE".equalsIgnoreCase(text(waiting.get("recoveryMode")))) {
			contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, text(waiting.get("originNextNodeId")));
		}
	}

	/** SELECT 等待态收到 UI 动作：SKIP 走跳过；SELECT 校验候选并写入目标路径后作废审批。 */
	private void applySelectAction(AgentRequest request, DataAgentSkillVersion version, Map<String, Object> context,
			Map<String, Object> waiting) {
		if ("SKIP".equalsIgnoreCase(request.getFlowAction().type())
				&& Boolean.TRUE.equals(waiting.get("allowSkip"))) {
			applySelectionSkip(context, waiting, version);
			return;
		}
		if (!"SELECT".equalsIgnoreCase(request.getFlowAction().type())) {
			throw CheckedException.badRequest("当前 FLOW 等待选择候选项，不能执行 " + request.getFlowAction().type());
		}
		Map<String, Object> selected = findSelectionOption(waiting.get("options"), request.getFlowAction().value());
		if (selected.isEmpty()) {
			throw CheckedException.badRequest("候选项已失效，请刷新后重新选择");
		}
		String targetPath = text(waiting.get("targetPath"));
		Object value = selected.getOrDefault("rawData", selected);
		contextMapper.set(context, targetPath, value);
		String selectionSource = firstText(text(waiting.get("selectionSource")), "USER_SELECTION");
		markSelectedValueSlots(context, targetPath, value, selectionSource,
				strings(waiting.get("preserveFields")));
		invalidateApproval(context);
	}

	/** COLLECT/VALIDATION 等待态收到表单提交：按 uiSchema 投影、时间归一化后逐字段写入并作废审批。 */
	private void applyFormSubmitInput(AgentRequest request, Map<String, Object> context, Map<String, Object> waiting,
			String action) {
		String targetPath = "VALIDATION".equals(action)
				? firstText(text(waiting.get("targetPath")), INPUT_ROOT_PATH) : INPUT_ROOT_PATH;
		Map<String, Object> formValues = map(projectToSchema(request.getFlowAction().payload(),
				map(waiting.get("uiSchema"))));
		formValues = temporalNormalizer.normalize(formValues, map(waiting.get("schema")), request).values();
		for (Map.Entry<String, Object> entry : formValues.entrySet()) {
			String path = entry.getKey().startsWith("/") ? entry.getKey()
					: targetPath + (targetPath.endsWith("/") ? "" : "/") + entry.getKey();
			contextMapper.set(context, path, entry.getValue());
			markValueSlots(context, path, entry.getValue(), "USER");
		}
		invalidateApproval(context);
	}

	/** COLLECT 等待态收到自由文本：先对上本轮已列出的候选项，再按 schema 抽取补齐。 */
	private void applyCollectQueryInput(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, DataAgentSkillVersion version, Map<String, Object> context,
			Map<String, Object> waiting) {
		String targetPath = firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		SlotMergeCheckpoint listedCheckpoint = captureSlotMerge(context, targetPath);
		// 拷贝失败则无法回滚，与字面量补丁相同：本回合不绑定 listedOptions。
		Map<String, Object> listed = listedCheckpoint == null ? Map.of()
				: applyListedOptionFromQuery(request, context, waiting);
		Map<String, Object> nodeSchema = map(waiting.get("schema"));
		Map<String, Object> extracted = Map.of();
		List<String> filledBeforeExtract = List.copyOf(strings(contextMapper.get(context, FILLED_THIS_TURN_PATH)));
		try {
			extracted = extractWithLiteralMappings(request, modelConfig, nodeSchema,
					text(waiting.get("instruction")), context, map(waiting.get("literalMappings")),
					strings(waiting.get("requiredPaths")), strings(waiting.get("requiredAnyPaths")), targetPath,
					extractionConfig(waiting), instance, version);
		}
		catch (RuntimeException ex) {
			if (listed.isEmpty()) {
				throw ex;
			}
			log.warn("FLOW collect extraction failed after listed option bind, keeping bound values. error={}",
					ex.getMessage());
			restoreListedIdentity(context, waiting, listed);
			return;
		}
		if (applyExtractedTurnIntent(request, version, waiting, context, text(waiting.get("action")),
				filledBeforeExtract)) {
			if (!listed.isEmpty() && listedCheckpoint != null
					&& (isTurnAskOrChitchat(context) || isTurnList(context) || isTurnProceed(context)
							|| isTurnCancel(context))) {
				restoreSlotMerge(context, targetPath, listedCheckpoint);
			}
			else {
				restoreListedIdentity(context, waiting, listed);
			}
			return;
		}
		if (listed.isEmpty()) {
			listed = applySoleListedOption(request, context, waiting);
		}
		Map<String, Object> utteranceSchema = utteranceExtractionSchema(nodeSchema, version, context, targetPath);
		applyExtractedUtterance(request, context, utteranceSchema, targetPath, extracted, waiting, version);
		restoreListedIdentity(context, waiting, listed);
		dropSubstringIdentities(context, utteranceSchema, targetPath,
				request == null ? null : request.getQuery());
	}

	private Map<String, Object> applyListedOptionFromQuery(AgentRequest request, Map<String, Object> context,
			Map<String, Object> waiting) {
		Object optionSource = listedOptionSource(waiting, context);
		if (request == null || !StringUtils.hasText(request.getQuery()) || optionSource == null) {
			return Map.of();
		}
		Map<String, Object> selected = FlowListedOptionSupport.matchUnique(optionSource, request.getQuery());
		if (selected.isEmpty()) {
			return Map.of();
		}
		return bindListedSelection(request, context, waiting, selected);
	}

	private Object listedOptionSource(Map<String, Object> waiting, Map<String, Object> context) {
		if (waiting != null && waiting.get("listedOptions") != null) {
			return waiting.get("listedOptions");
		}
		Object stored = map(contextMapper.get(context, LISTED_CATALOG_PATH)).get("options");
		if (stored != null) {
			return stored;
		}
		return waiting == null ? null : waiting.get("options");
	}

	private Map<String, Object> applySoleListedOption(AgentRequest request, Map<String, Object> context,
			Map<String, Object> waiting) {
		Map<String, Object> selected = FlowListedOptionSupport.matchSole(listedOptionSource(waiting, context));
		if (selected.isEmpty()) {
			return Map.of();
		}
		return bindListedSelection(request, context, waiting, selected);
	}

	private Map<String, Object> bindListedSelection(AgentRequest request, Map<String, Object> context,
			Map<String, Object> waiting, Map<String, Object> selected) {
		Map<String, Object> raw = map(selected.getOrDefault("rawData", selected));
		Map<String, Object> listedWaiting = mergeListedWaiting(waiting, context);
		String catalogPath = firstText(text(listedWaiting.get("catalogTargetPath")),
				text(listedWaiting.get("targetPath")));
		if (isCollectionArray(listedWaiting, catalogPath)) {
			bindListedCollectionItem(request, context, catalogPath, raw, listedWaiting);
			return raw;
		}
		String targetPath = firstText(catalogPath, text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		if (raw.isEmpty()) {
			return Map.of();
		}
		if (INPUT_ROOT_PATH.equals(targetPath) || targetPath.startsWith(INPUT_ROOT_PATH + "/")) {
			contextMapper.mergePresent(context, INPUT_ROOT_PATH, raw);
			markSelectedValueSlots(context, INPUT_ROOT_PATH, raw,
					firstText(text(waiting.get("selectionSource")), "RESOLVED"),
					strings(waiting.get("preserveFields")));
		}
		else {
			contextMapper.set(context, targetPath, raw);
			markSelectedValueSlots(context, targetPath, raw,
					firstText(text(waiting.get("selectionSource")), "USER_SELECTION"),
					strings(waiting.get("preserveFields")));
		}
		invalidateApproval(context);
		return raw;
	}

	private Map<String, Object> mergeListedWaiting(Map<String, Object> waiting, Map<String, Object> context) {
		Map<String, Object> merged = new LinkedHashMap<>(waiting == null ? Map.of() : waiting);
		Map<String, Object> stored = map(contextMapper.get(context, LISTED_CATALOG_PATH));
		if (!stored.isEmpty()) {
			merged.putIfAbsent("catalogTargetPath", stored.get("path"));
			merged.putIfAbsent("identityPaths", stored.get("identityPaths"));
			merged.putIfAbsent("preserveFields", stored.get("preserveFields"));
			merged.putIfAbsent("schema", waiting == null ? null : waiting.get("schema"));
		}
		return merged;
	}

	private boolean isCollectionArray(Map<String, Object> waiting, String path) {
		if (!StringUtils.hasText(path)) {
			return false;
		}
		String root = firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		String field = schemaField(path, root);
		if (!StringUtils.hasText(field) && path.startsWith(INPUT_ROOT_PATH)) {
			field = schemaField(path, INPUT_ROOT_PATH);
		}
		Map<String, Object> fieldSchema = map(map(map(waiting.get("schema")).get("properties")).get(field));
		return "array".equalsIgnoreCase(text(fieldSchema.get("type")));
	}

	private void bindListedCollectionItem(AgentRequest request, Map<String, Object> context, String listPath,
			Map<String, Object> raw, Map<String, Object> waiting) {
		if (raw.isEmpty()) {
			return;
		}
		List<Object> merged = new ArrayList<>();
		Object current = contextMapper.get(context, listPath);
		if (current instanceof List<?> items) {
			items.forEach(item -> merged.add(item instanceof Map<?, ?> value ? new LinkedHashMap<>(map(value)) : item));
		}
		List<String> matchFields = identityFieldNames(waiting);
		int index = findCollectionItem(merged, raw, matchFields);
		if (index < 0 && merged.size() == 1) {
			index = 0;
		}
		Map<String, Object> item = index >= 0 && merged.get(index) instanceof Map<?, ?> existing
				? new LinkedHashMap<>(map(existing)) : new LinkedHashMap<>();
		item.putAll(raw);
		if (index < 0) {
			index = merged.size();
			merged.add(item);
		}
		else {
			merged.set(index, item);
		}
		contextMapper.set(context, listPath, merged);
		String listField = schemaField(listPath, INPUT_ROOT_PATH);
		if (StringUtils.hasText(listField)) {
			recordFilledThisTurn(context, INPUT_ROOT_PATH, Map.of(listField, merged));
		}
		markSelectedValueSlots(context, listPath + "/" + index, item,
				firstText(text(waiting.get("selectionSource")), "RESOLVED"), strings(waiting.get("preserveFields")));
		fillMissingIntegersFromQuery(request, context, map(waiting.get("schema")),
				firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH),
				Map.of(schemaField(listPath, INPUT_ROOT_PATH), List.of(item)));
		invalidateApproval(context);
	}

	private void restoreListedIdentity(Map<String, Object> context, Map<String, Object> waiting,
			Map<String, Object> listed) {
		if (listed == null || listed.isEmpty()) {
			return;
		}
		String catalogPath = firstText(text(waiting.get("catalogTargetPath")), text(waiting.get("targetPath")));
		if (!isCollectionArray(waiting, catalogPath)) {
			return;
		}
		Object current = contextMapper.get(context, catalogPath);
		if (!(current instanceof List<?> items) || items.isEmpty()) {
			return;
		}
		List<String> matchFields = identityFieldNames(waiting);
		List<Object> rows = new ArrayList<>();
		items.forEach(rows::add);
		int index = findCollectionItem(rows, listed, matchFields);
		if (index < 0 && items.size() == 1) {
			index = 0;
		}
		if (index < 0 || !(items.get(index) instanceof Map<?, ?>)) {
			return;
		}
		Map<String, Object> row = new LinkedHashMap<>(map(items.get(index)));
		for (String field : matchFields) {
			if (!contextMapper.isEmpty(listed.get(field))) {
				row.put(field, listed.get(field));
				markValueSlots(context, catalogPath + "/" + index + "/" + escapePointer(field), listed.get(field),
						"RESOLVED");
			}
		}
		List<Object> updated = new ArrayList<>(items);
		updated.set(index, row);
		contextMapper.set(context, catalogPath, updated);
	}

	private List<String> identityFieldNames(Map<String, Object> waiting) {
		List<String> fields = new ArrayList<>();
		for (String path : strings(waiting.get("identityPaths"))) {
			String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
			if (StringUtils.hasText(name) && !fields.contains(name)) {
				fields.add(name);
			}
		}
		return fields;
	}

	private void dropSubstringIdentities(Map<String, Object> context, Map<String, Object> schema, String outputPath,
			String query) {
		if (schema == null || schema.isEmpty()) {
			return;
		}
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (Map.Entry<String, Object> property : map(schema.get("properties")).entrySet()) {
			Map<String, Object> fieldSchema = map(property.getValue());
			if (!"array".equalsIgnoreCase(text(fieldSchema.get("type")))) {
				continue;
			}
			String listPath = root + "/" + escapePointer(property.getKey());
			Object current = contextMapper.get(context, listPath);
			if (!(current instanceof List<?> items) || items.isEmpty()) {
				continue;
			}
			List<Object> updated = new ArrayList<>(items.size());
			boolean changed = false;
			for (int index = 0; index < items.size(); index++) {
				if (!(items.get(index) instanceof Map<?, ?> map)) {
					updated.add(items.get(index));
					continue;
				}
				Map<String, Object> row = new LinkedHashMap<>(map(map));
				if (dropSubstringIdentityFields(row, query)) {
					changed = true;
					for (String field : new ArrayList<>(row.keySet())) {
						if (contextMapper.isEmpty(row.get(field))) {
							markSlot(context, listPath + "/" + index + "/" + escapePointer(field), "CLEARED",
									"DEPENDENCY");
						}
					}
				}
				updated.add(row);
			}
			if (changed) {
				contextMapper.set(context, listPath, updated);
			}
		}
	}

	private boolean dropSubstringIdentityFields(Map<String, Object> row, String query) {
		List<Map.Entry<String, String>> values = new ArrayList<>();
		for (Map.Entry<String, Object> entry : row.entrySet()) {
			if (entry.getValue() instanceof String text && StringUtils.hasText(text)
					&& !"integer".equalsIgnoreCase(text) && !isBusinessIdPath("/" + entry.getKey())) {
				values.add(Map.entry(entry.getKey(), text.trim()));
			}
		}
		boolean changed = false;
		for (Map.Entry<String, String> shorter : values) {
			for (Map.Entry<String, String> longer : values) {
				if (shorter.getKey().equals(longer.getKey()) || shorter.getValue().length() < 3
						|| shorter.getValue().length() >= longer.getValue().length()) {
					continue;
				}
				if (longer.getValue().contains(shorter.getValue()) && !isStandaloneToken(query, shorter.getValue())) {
					row.remove(shorter.getKey());
					changed = true;
				}
			}
		}
		return changed;
	}

	private boolean isStandaloneToken(String query, String value) {
		if (!StringUtils.hasText(query) || !StringUtils.hasText(value)) {
			return false;
		}
		for (String token : query.trim().split("[\\s，。,、;；]+")) {
			if (value.equalsIgnoreCase(token)) {
				return true;
			}
		}
		return false;
	}

	private void retryBlockedReview(AgentRequest request, Map<String, Object> waiting, DataAgentSkillVersion version,
			Map<String, Object> context) {
		Map<String, Object> schema = map(waiting.get("schema"));
		String targetPath = firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		dropSubstringIdentities(context, schema, targetPath, request == null ? null : request.getQuery());
		for (String path : reviewIssuePaths(waiting)) {
			contextMapper.set(context, path, List.of());
		}
		List<String> changed = new ArrayList<>();
		for (Map.Entry<String, Object> property : map(schema.get("properties")).entrySet()) {
			if (!"array".equalsIgnoreCase(text(map(property.getValue()).get("type")))) {
				continue;
			}
			String path = targetPath + "/" + escapePointer(property.getKey());
			if (!contextMapper.isEmpty(contextMapper.get(context, path))) {
				changed.add(path);
			}
		}
		if (!changed.isEmpty()) {
			contextMapper.set(context, LAST_CHANGED_PATHS_PATH, changed);
		}
		String next = reviewRefreshNext(waiting, context);
		if (!StringUtils.hasText(next)) {
			next = firstResolveNodeForChanged(version, changed);
		}
		if (StringUtils.hasText(next)) {
			contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, next);
		}
	}

	private List<String> reviewIssuePaths(Map<String, Object> waiting) {
		List<String> issuePaths = strings(waiting.get("issuesPaths"));
		if (issuePaths.isEmpty() && StringUtils.hasText(text(waiting.get("issuesPath")))) {
			issuePaths = List.of(text(waiting.get("issuesPath")));
		}
		return issuePaths;
	}

	private String firstResolveNodeForChanged(DataAgentSkillVersion version, List<String> changed) {
		for (FlowNode node : flowNodes(version)) {
			if (node == null || !"resolve".equalsIgnoreCase(node.type()) || node.config() == null) {
				continue;
			}
			String forEach = text(node.config().get("forEach"));
			if (changed.stream().anyMatch(path -> pathEqualsOrChild(path, forEach))) {
				return node.id();
			}
		}
		return null;
	}

	/** VALIDATION 等待态收到自由文本：针对校验目标路径重新抽取修正值。 */
	private void applyValidationQueryInput(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, DataAgentSkillVersion version, Map<String, Object> context,
			Map<String, Object> waiting) {
		String targetPath = firstText(text(waiting.get("targetPath")), INPUT_ROOT_PATH);
		Map<String, Object> nodeSchema = map(waiting.get("schema"));
		Map<String, Object> corrected = extractWithLiteralMappings(request, modelConfig, nodeSchema,
				text(waiting.get("instruction")), context, map(waiting.get("literalMappings")),
				strings(waiting.get("requiredPaths")), strings(waiting.get("requiredAnyPaths")), targetPath,
				extractionConfig(waiting), instance, version);
		if (applyExtractedTurnIntent(request, version, waiting, context, text(waiting.get("action")))) {
			return;
		}
		applyExtractedUtterance(request, context, utteranceExtractionSchema(nodeSchema, version, context, targetPath),
				targetPath, corrected, waiting, version);
	}

	/** REVIEW 等待态收到 UI 动作：SUBMIT 记录已审阅版本；CLEAR_REFERENCE 清除历史参考。 */
	private void applyReviewAction(AgentRequest request, Map<String, Object> context) {
		String actual = text(request.getFlowAction().type()).toUpperCase(Locale.ROOT);
		if ("SUBMIT".equals(actual)) {
			contextMapper.set(context, "/runtime/reviewedRevision",
					number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L));
		}
		else if ("CLEAR_REFERENCE".equals(actual)) {
			clearHistoryReference(context);
		}
	}

	/** REVIEW 等待态收到自由文本：抽取修正值，并按变更路径匹配刷新路由决定下一节点。 */
	private void applyReviewQueryInput(AgentRequest request, ModelConfigDTO modelConfig,
			DataAgentFlowInstance instance, DataAgentSkillVersion version, Map<String, Object> context,
			Map<String, Object> waiting) {
		String targetPath = firstText(text(waiting.get("originOutputPath")), INPUT_ROOT_PATH);
		Map<String, Object> nodeSchema = map(waiting.get("schema"));
		Map<String, Object> corrected = extractWithLiteralMappings(request, modelConfig, nodeSchema,
				text(waiting.get("instruction")), context, map(waiting.get("literalMappings")),
				strings(waiting.get("requiredPaths")), strings(waiting.get("requiredAnyPaths")), targetPath,
				extractionConfig(waiting), instance, version);
		if (applyExtractedTurnIntent(request, version, waiting, context, text(waiting.get("action")))) {
			return;
		}
		applyExtractedUtterance(request, context, utteranceExtractionSchema(nodeSchema, version, context, targetPath),
				targetPath, corrected, waiting, version);
		if ("REVIEW_EDIT_THEN_REFRESH".equalsIgnoreCase(text(waiting.get("recoveryMode")))) {
			contextMapper.set(context, PENDING_EDIT_QUERY_PATH, null);
		}
		String refreshNext = reviewRefreshNext(waiting, context);
		if (StringUtils.hasText(refreshNext)) {
			contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, refreshNext);
		}
	}

	/** CONFIRM 等待态收到 EDIT 动作：作废审批并跳回配置的修改入口节点，直改文本随行暂存。 */
	private void applyConfirmEditAction(AgentRequest request, Map<String, Object> context,
			Map<String, Object> waiting) {
		String editNode = text(waiting.get("editNode"));
		if (!StringUtils.hasText(editNode)) {
			throw CheckedException.badRequest("当前确认节点未配置返回修改入口");
		}
		invalidateApproval(context);
		if (request.isFlowTextDirectEdit() && StringUtils.hasText(request.getQuery())) {
			contextMapper.set(context, PENDING_EDIT_QUERY_PATH, request.getQuery());
		}
		contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, editNode);
	}

	private String reviewRefreshNext(Map<String, Object> waiting, Map<String, Object> context) {
		List<String> changedPaths = strings(contextMapper.get(context, LAST_CHANGED_PATHS_PATH));
		for (Map<String, Object> route : mapList(waiting.get("refreshRoutes"))) {
			List<String> triggers = strings(route.get("whenAny"));
			boolean matches = changedPaths.stream().anyMatch(changed -> triggers.stream().anyMatch(trigger ->
					changed.equals(trigger) || changed.startsWith(trigger + "/") || trigger.startsWith(changed + "/")));
			if (matches && StringUtils.hasText(text(route.get("next")))) {
				return text(route.get("next"));
			}
		}
		return text(waiting.get("refreshNext"));
	}

	private void applySelectionSkip(Map<String, Object> context, Map<String, Object> waiting,
			DataAgentSkillVersion version) {
		contextMapper.set(context, text(waiting.get("skipPath")), true);
		String decisionPath = text(waiting.get("skipDecisionPath"));
		if (StringUtils.hasText(decisionPath)) {
			contextMapper.set(context, decisionPath, waiting.get("skipDecisionValue"));
		}
		if ("SKIP_CURRENT_FLOW".equalsIgnoreCase(text(waiting.get("skipDecisionValue")))
				|| "SKIP_CURRENT_FLOW".equalsIgnoreCase(text(contextMapper.get(context, REFERENCE_DECISION_PATH)))) {
			markSkippedReferenceResolvers(context, version);
		}
		invalidateApproval(context);
	}

	private void markSkippedReferenceResolvers(Map<String, Object> context, DataAgentSkillVersion version) {
		for (String resolverId : referenceResolverIds(version)) {
			contextMapper.set(context, "/resolverState/" + escapePointer(resolverId), Map.of("status", "SKIPPED"));
		}
	}

	private boolean isExplicitSelectionSkip(String query, Map<String, Object> waiting) {
		if (!Boolean.TRUE.equals(waiting.get("allowSkip")) || !StringUtils.hasText(text(waiting.get("skipPath")))
				|| !StringUtils.hasText(query)) {
			return false;
		}
		List<String> commands = strings(waiting.get("skipCommands"));
		if (commands.isEmpty()) {
			return false;
		}
		String command = normalizeFlowCommand(query);
		return commands.stream().map(this::normalizeFlowCommand).anyMatch(command::equals);
	}

	private String normalizeFlowCommand(String value) {
		return text(value).trim().toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？,.!?]", "");
	}

	private void markSelectedValueSlots(Map<String, Object> context, String targetPath, Object value,
			String source, List<String> preserveFields) {
		if (!"RESOLVED".equalsIgnoreCase(source) || !(value instanceof Map<?, ?> selected)) {
			markValueSlots(context, targetPath, value, source);
			return;
		}
		boolean historyOrigin = hasHistoryOrigin(context, targetPath);
		for (Map.Entry<String, Object> entry : map(selected).entrySet()) {
			if (!preserveFields.contains(entry.getKey())) {
				String path = targetPath + "/" + escapePointer(entry.getKey());
				markValueSlots(context, path, entry.getValue(), source);
				if (historyOrigin) {
					markValueOrigin(context, path, entry.getValue(), "HISTORY");
				}
			}
		}
	}

	/**
	 * 恢复请求携带 resume_version 时必须与实例当前版本一致；过期版本说明等待状态已被其他
	 * 回合消费或刷新，直接拒绝，避免基于旧提示的操作写入新状态。
	 */
	private void validateResumeVersion(AgentRequest request, DataAgentFlowInstance instance) {
		FlowAction action = request.getFlowAction();
		if (action == null || action.payload() == null || instance.getResumeVersion() == null) {
			return;
		}
		Object requested = action.payload().get("resumeVersion");
		if (requested == null) {
			return;
		}
		long requestedVersion = number(requested, Long.MIN_VALUE);
		if (requestedVersion != instance.getResumeVersion().longValue()) {
			throw CheckedException.badRequest("流程等待状态已更新，当前操作基于过期版本，请刷新最新提示后重试");
		}
	}

	private void validateWaitingAction(String expectedAction, Map<String, Object> waiting, AgentRequest request) {
		if (request.getFlowAction() == null) {
			return;
		}
		String actual = text(request.getFlowAction().type()).toUpperCase(Locale.ROOT);
		List<Map<String, Object>> configuredActions = actionConfigs(waiting);
		boolean allowed = waiting.containsKey("uiActions") ? configuredActions.stream()
				.anyMatch(item -> actual.equalsIgnoreCase(text(item.get("type")))
						&& actionAllowedForWaiting(expectedAction, actual)) : switch (expectedAction) {
			case "SELECT" -> "SELECT".equals(actual) || "SKIP".equals(actual) || "FALLBACK".equals(actual);
			case "COLLECT", "VALIDATION" -> "SUBMIT".equals(actual) && UI_MODE_FORM.equals(uiMode(waiting));
			case "REVIEW" -> "SUBMIT".equals(actual) || "CLEAR_REFERENCE".equals(actual);
			case "CONFIRM" -> "CONFIRM".equals(actual) || "EDIT".equals(actual);
			case "APPROVAL" -> "CANCEL".equals(actual) || "APPROVAL_RESUME".equals(actual);
			default -> false;
		};
		if (!allowed) {
			throw CheckedException.badRequest("当前 FLOW 等待 " + expectedAction + "，不能执行 " + actual);
		}
	}

	private Map<String, Object> findSelectionOption(Object options, Object selectedValue) {
		if (!(options instanceof Iterable<?> iterable)) {
			return Map.of();
		}
		int selectedIndex = selectionIndex(selectedValue);
		int index = 0;
		for (Object option : iterable) {
			if (!(option instanceof Map<?, ?> map)) {
				index++;
				continue;
			}
			Map<String, Object> candidate = map(map);
			if (selectedIndex == index || sameSelectionValue(candidate.get("value"), selectedValue)
					|| sameSelectionValue(candidate.get("internalValue"), selectedValue)) {
				return candidate;
			}
			index++;
		}
		return Map.of();
	}

	private String selectionKey(int index) {
		return "option-" + (index + 1);
	}

	private int selectionIndex(Object value) {
		String selected = text(value);
		if (!StringUtils.hasText(selected) || !selected.startsWith("option-")) {
			return -1;
		}
		try {
			return Integer.parseInt(selected.substring("option-".length())) - 1;
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private boolean sameSelectionValue(Object left, Object right) {
		if (left == null || right == null) {
			return left == right;
		}
		return left.equals(right) || String.valueOf(left).equals(String.valueOf(right));
	}

	private FlowExecutionResult fail(DataAgentFlowInstance instance, AgentRequest request, FlowNode node,
			Map<String, Object> context, String errorCode, String message) {
		String safeMessage = sanitizeUserVisibleText(StringUtils.hasText(message) ? message : "FLOW execution failed");
		if (!StringUtils.hasText(safeMessage)) {
			safeMessage = "流程执行失败，请稍后重试。";
		}
		instance = instanceService.advance(instance, FlowInstanceStatus.FAILED,
				node == null ? instance.getCurrentNodeId() : node.id(), context, Map.of(), instance.getIdempotencyKey(),
				request.getRuntimeRequestId(), errorCode, safeMessage, Instant.now());
		return response(request, instance, node, NodeResult.terminal(FlowInstanceStatus.FAILED, safeMessage, "ERROR",
				Map.of(), errorCode, instance.getIdempotencyKey()), true, 0L);
	}

	private FlowExecutionResult response(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			NodeResult result, boolean terminal, long durationMs) {
		FlowTurnOutcome turnOutcome = result.status() == FlowInstanceStatus.FAILED ? FlowTurnOutcome.FAILED
				: terminal ? FlowTurnOutcome.SUCCEEDED : FlowTurnOutcome.WAITING;
		return response(request, instance, node, result, terminal, durationMs, turnOutcome);
	}

	private FlowExecutionResult response(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			NodeResult result, boolean terminal, long durationMs, FlowTurnOutcome turnOutcome) {
		String progressStatus = turnOutcome == FlowTurnOutcome.FAILED ? AgentRuntimeProgressService.STATUS_FAILED
				: !terminal ? AgentRuntimeProgressService.STATUS_WAITING
				: result.status() == FlowInstanceStatus.SUCCEEDED ? AgentRuntimeProgressService.STATUS_SUCCESS
				: result.status() == FlowInstanceStatus.CANCELLED ? AgentRuntimeProgressService.STATUS_CANCELLED
				: AgentRuntimeProgressService.STATUS_FAILED;
		String progressStage = turnOutcome == FlowTurnOutcome.FAILED && !terminal ? "FLOW_INPUT_FAILED"
				: terminal && AgentRuntimeProgressService.STATUS_FAILED.equals(progressStatus)
						? "FLOW_FAILED" : terminal ? "FLOW_FINISHED" : "FLOW_WAITING";
		String progressDisplayName = AgentRuntimeProgressService.STATUS_FAILED.equals(progressStatus)
				? firstText(result.text(), result.action()) : result.action();
		emitFlowProgress(request, instance, node, null, progressStage, progressStatus, durationMs, progressDisplayName);
		List<Map<String, Object>> options = publicOptions(result.waitingPayload());
		Map<String, Object> values = publicValues(result.action(), result.waitingPayload());
		// 客户端恢复等待中的流程时回传该版本号；服务端用它拒绝基于过期提示的操作。
		if (!terminal && instance.getResumeVersion() != null) {
			values.put("resumeVersion", instance.getResumeVersion());
		}
		// 终态 FLOW 进度事件已在上方发射，这里取同一事实源的步骤轨迹随卡片透出。
		List<AgentRuntimeProgressService.FlowStepView> flowSteps = runtimeProgressService == null ? List.of()
				: runtimeProgressService.flowSteps(request);
		List<AgentUiMessage.Step> steps = flowSteps.stream()
			.map(step -> new AgentUiMessage.Step(step.kind(), step.label(), step.status(), step.durationMs()))
			.toList();
		AgentUiMessage.Source source = new AgentUiMessage.Source(request.getAgentId(), instance.getSkillCode(),
				String.valueOf(instance.getId()), node == null ? instance.getCurrentNodeId() : node.id());
		AgentUiMessage.Content content = new AgentUiMessage.Content(
				firstText(text(result.waitingPayload().get("format")), "markdown"), result.text());
		AgentUiMessage.Payload payload = new AgentUiMessage.Payload(result.action(), Map.copyOf(values), options);
		List<AgentUiMessage.Action> cardActions = actions(result.action(), result.waitingPayload());
		AgentUiMessage.Timing timing = new AgentUiMessage.Timing(terminal ? "FLOW_DONE" : "FLOW_WAITING", durationMs);
		AgentUiMessage message = steps.isEmpty()
				? new AgentUiMessage("agent-ui/v2", "skill-flow", request.getRuntimeRequestId(), source, content,
						payload, cardActions, timing)
				: new AgentUiMessage("agent-ui/v2", "skill-flow", request.getRuntimeRequestId(), source, content,
						payload, cardActions, timing, steps);
		return new FlowExecutionResult(instance, message, result.text(), terminal, turnOutcome);
	}

	private FlowExecutionResult processingResponse(AgentRequest request, DataAgentFlowInstance instance) {
		return response(request, instance, null,
				NodeResult.waiting("正在处理已提交的信息，请稍后查看处理结果。", "PROCESSING", Map.of()), false, 0L);
	}

	private Map<String, Object> arguments(Map<String, Object> config, Map<String, Object> context) {
		Map<String, Object> result = new LinkedHashMap<>();
		Map<String, Object> mappings = map(config.get("argumentMappings"));
		for (Map.Entry<String, Object> entry : mappings.entrySet()) {
			result.put(entry.getKey(), contextMapper.get(context, String.valueOf(entry.getValue())));
		}
		return result;
	}

	private Map<String, Object> applyTextSearchOverride(AgentRequest request, String resolverNodeId, String itemPath,
			Map<String, Object> arguments) {
		FlowTextSearchIntent intent = request == null ? null : request.getFlowTextSearchIntent();
		if (intent == null || intent.consumed() || !resolverNodeId.equals(intent.resolverNodeId())) {
			return arguments;
		}
		if (StringUtils.hasText(itemPath) && StringUtils.hasText(intent.targetPath())
				&& !itemPath.equals(intent.targetPath())) {
			return arguments;
		}
		if (!StringUtils.hasText(intent.argumentName()) || !StringUtils.hasText(intent.keyword())) {
			throw CheckedException.badRequest("文本查询关键词或 Resolver 参数配置无效");
		}
		Map<String, Object> overridden = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
		overridden.put(intent.argumentName(), intent.keyword());
		request.setFlowTextSearchIntent(intent.consume());
		log.info("FLOW text search resolver override applied. provider={}, flowNodeId={}, runtimeRequestId={}",
				request.getProvider(), resolverNodeId, request.getRuntimeRequestId());
		return overridden;
	}

	private String render(Map<String, Object> config, String key, Map<String, Object> context, String fallback) {
		String template = firstText(text(config.get(key)), fallback);
		if (!StringUtils.hasText(template)) {
			return "";
		}
		String rendered = template;
		for (Map.Entry<String, Object> entry : flatten(context).entrySet()) {
			rendered = rendered.replace("{{" + entry.getKey() + "}}", text(entry.getValue()));
		}
		return rendered;
	}

	private Map<String, Object> flatten(Map<String, Object> context) {
		Map<String, Object> result = new LinkedHashMap<>();
		flattenInto(result, "", context);
		return result;
	}

	private void flattenInto(Map<String, Object> result, String prefix, Map<String, Object> value) {
		for (Map.Entry<String, Object> entry : value.entrySet()) {
			String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			if (entry.getValue() instanceof Map<?, ?> map) {
				flattenInto(result, path, map(map));
			}
			else {
				result.put(path, entry.getValue());
			}
		}
	}

	private void addUiConfig(Map<String, Object> payload, Map<String, Object> config, Map<String, Object> context) {
		String mode = uiMode(config);
		payload.put("uiMode", mode);
		if (config != null && config.containsKey("uiActions") && !payload.containsKey("uiActions")) {
			payload.put("uiActions", config.get("uiActions"));
		}
		if (config != null && config.containsKey("cancelPolicy")) {
			payload.put("cancelPolicy", map(config.get("cancelPolicy")));
		}
		if (config != null && config.containsKey("textSearch")) {
			payload.put("textSearch", map(config.get("textSearch")));
		}
		if (UI_MODE_FORM.equals(mode)) {
			Map<String, Object> uiSchema = publicUiSchema(map(config.get("uiSchema")));
			payload.put("uiSchema", uiSchema);
			payload.put("uiValues", projectToSchema(contextMapper.get(context, INPUT_ROOT_PATH), uiSchema));
			return;
		}
		if (UI_MODE_SUMMARY.equals(mode)) {
			payload.put("displayFields", buildDisplayFields(config, context));
			payload.put("contextRevision", number(contextMapper.get(context, CONTEXT_REVISION_PATH), 0L));
		}
	}

	private void putInternalExtractionConfig(Map<String, Object> payload, Map<String, Object> config) {
		if (config == null) {
			return;
		}
		Map<String, Object> extraction = extractionConfig(config);
		if (!extraction.isEmpty()) {
			payload.put("extraction", extraction);
		}
		if (!payload.containsKey("requiredPaths") && config.containsKey("requiredPaths")) {
			payload.put("requiredPaths", strings(config.get("requiredPaths")));
		}
		if (!payload.containsKey("requiredAnyPaths") && config.containsKey("requiredAnyPaths")) {
			payload.put("requiredAnyPaths", strings(config.get("requiredAnyPaths")));
		}
		copyConfigValue(payload, config, "catalogQueries");
		copyConfigValue(payload, config, "pairExtractions");
		copyConfigValue(payload, config, "errorPrompts");
	}

	private void copyConfigValue(Map<String, Object> payload, Map<String, Object> config, String key) {
		if (!payload.containsKey(key) && config.containsKey(key)) {
			payload.put(key, config.get(key));
		}
	}

	private String uiMode(Map<String, Object> config) {
		String mode = text(config == null ? null : config.get("uiMode"));
		return StringUtils.hasText(mode) ? mode.trim().toUpperCase(Locale.ROOT) : UI_MODE_CHAT;
	}

	private Map<String, Object> publicValues(String action, Map<String, Object> payload) {
		Map<String, Object> values = new LinkedHashMap<>();
		String mode = uiMode(payload);
		values.put("uiMode", mode);
		if ("SELECT".equals(action)) {
			copyPublicFlag(values, payload, "allowSkip");
			if (payload.containsKey("skipCommands")) {
				values.put("skipCommands", payload.get("skipCommands"));
			}
			// textSearch 开关是客户端渲染搜索框的契约字段，随 SELECT 等待卡一并透出。
			if (Boolean.TRUE.equals(map(payload.get("textSearch")).get("enabled"))) {
				values.put("textSearchEnabled", true);
			}
		}
		if (UI_MODE_FORM.equals(mode)) {
			Map<String, Object> uiSchema = publicUiSchema(map(payload.get("uiSchema")));
			values.put("schema", uiSchema);
			values.put("currentValues", projectToSchema(payload.get("uiValues"), uiSchema));
		}
		else if (UI_MODE_SUMMARY.equals(mode)) {
			values.put("displayFields", payload.getOrDefault("displayFields", List.of()));
			values.put("contextRevision", payload.getOrDefault("contextRevision", 0L));
		}
		copyPublicFlag(values, payload, "retryable");
		copyPublicFlag(values, payload, "blocking");
		copyPublicFlag(values, payload, "referenceApplied");
		copyPublicFlag(values, payload, "recoveryMode");
		// Public skill-flow contract is payload.values.missing, not a sibling of action.
		if (payload.containsKey("missing")) {
			values.put("missing", payload.get("missing"));
		}
		if (payload.containsKey("errorCode")) {
			values.put("errorCode", safePublicFlowErrorCode(text(payload.get("errorCode"))));
		}
		return values;
	}

	private String safePublicFlowErrorCode(String errorCode) {
		return errorCode != null && errorCode.startsWith("FLOW_EXTRACT_") ? errorCode : "FLOW_INPUT_UNAVAILABLE";
	}

	private void copyPublicFlag(Map<String, Object> target, Map<String, Object> source, String key) {
		if (source.containsKey(key)) {
			target.put(key, source.get(key));
		}
	}

	private List<Map<String, Object>> buildDisplayFields(Map<String, Object> config, Map<String, Object> context) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (Map<String, Object> field : mapList(config.get("displayFields"))) {
			String path = text(field.get("path"));
			if (!isBusinessIdPath(path)) {
				addDisplayField(result, text(field.get("label")), contextMapper.get(context, path), field, context);
			}
		}
		for (Map<String, Object> collection : mapList(config.get("displayCollections"))) {
			String collectionPath = text(collection.get("path"));
			if (isBusinessIdPath(collectionPath)) {
				continue;
			}
			Object value = contextMapper.get(context, collectionPath);
			if (!(value instanceof List<?> items)) {
				continue;
			}
			int index = 0;
			for (Object item : items) {
				if (!(item instanceof Map<?, ?> itemMap)) {
					index++;
					continue;
				}
				Map<String, Object> row = map(itemMap);
				String rowLabel = collectionRowLabel(collection, row, index);
				List<String> parts = new ArrayList<>();
				for (Map<String, Object> field : mapList(collection.get("itemFields"))) {
					String path = text(field.get("path"));
					if (isBusinessIdPath(path) || isSummaryHiddenItemField(path) || isCollectionRoleField(path, rowLabel)) {
						continue;
					}
					Object fieldValue = contextMapper.get(row, path);
					if (contextMapper.isEmpty(fieldValue)) {
						continue;
					}
					String rendered = displayValue(fieldValue, field, context);
					if (!StringUtils.hasText(rendered)) {
						continue;
					}
					String fieldLabel = firstText(text(field.get("label")), "字段");
					if (isPrimaryCollectionName(path) && parts.isEmpty()) {
						parts.add(rendered);
					}
					else {
						parts.add(fieldLabel + "：" + rendered);
					}
				}
				if (!parts.isEmpty()) {
					result.add(Map.of("label", rowLabel, "value", String.join("，", parts)));
				}
				index++;
			}
		}
		return List.copyOf(result);
	}

	private String collectionRowLabel(Map<String, Object> collection, Map<String, Object> row, int index) {
		String itemLabel = firstText(text(collection.get("itemLabel")), text(collection.get("label")), "明细");
		String role = collectionRoleLabel(collection, row);
		if (StringUtils.hasText(role)) {
			String head = role.split("[/、,，]")[0].trim();
			if (itemLabel.contains("地址") || itemLabel.contains("网点")) {
				return head.endsWith("网点") || head.endsWith("地址") ? head : head + "网点";
			}
			return head;
		}
		return itemLabel + (index + 1);
	}

	private String collectionRoleLabel(Map<String, Object> collection, Map<String, Object> row) {
		for (Map<String, Object> field : mapList(collection.get("itemFields"))) {
			String path = text(field.get("path"));
			if (!"type".equalsIgnoreCase(lastPathSegment(path))) {
				continue;
			}
			Object value = contextMapper.get(row, path);
			if (contextMapper.isEmpty(value)) {
				return "";
			}
			return displayValue(value, field, Map.of());
		}
		return "";
	}

	private boolean isCollectionRoleField(String path, String rowLabel) {
		return "type".equalsIgnoreCase(lastPathSegment(path)) && StringUtils.hasText(rowLabel)
				&& (rowLabel.contains("网点") || rowLabel.contains("发货") || rowLabel.contains("收货")
						|| rowLabel.contains("提货") || rowLabel.contains("到达"));
	}

	private boolean isPrimaryCollectionName(String path) {
		String name = lastPathSegment(path).toLowerCase(Locale.ROOT);
		return "sitename".equals(name) || "productname".equals(name) || "name".equals(name);
	}

	private boolean isSummaryHiddenItemField(String path) {
		String name = lastPathSegment(path).toLowerCase(Locale.ROOT);
		if (!StringUtils.hasText(name) || SUMMARY_HIDDEN_ITEM_FIELDS.contains(name)) {
			return true;
		}
		return name.endsWith("code") || name.endsWith("volume") || name.endsWith("weight")
				|| name.startsWith("size");
	}

	private String lastPathSegment(String path) {
		if (!StringUtils.hasText(path)) {
			return "";
		}
		String[] segments = path.split("/");
		return segments[segments.length - 1];
	}

	private boolean isBusinessIdPath(String path) {
		if (!StringUtils.hasText(path)) {
			return false;
		}
		String[] segments = path.split("/");
		String name = segments[segments.length - 1];
		String lower = name.toLowerCase(Locale.ROOT);
		if ("id".equals(lower) || "ids".equals(lower) || lower.endsWith("_id") || lower.endsWith("_ids")
				|| lower.endsWith("-id") || lower.endsWith("-ids") || name.endsWith("Id") || name.endsWith("Ids")) {
			return true;
		}
		return lower.endsWith("id") && List.of("company", "customer", "project", "product", "site", "industry",
				"contract", "demand", "order", "bill").stream().anyMatch(lower::contains);
	}

	private void addDisplayField(List<Map<String, Object>> fields, String label, Object value,
			Map<String, Object> config, Map<String, Object> context) {
		if (!StringUtils.hasText(label) || contextMapper.isEmpty(value)) {
			return;
		}
		fields.add(Map.of("label", label, "value", displayValue(value, config, context)));
	}

	private String displayValue(Object value, Map<String, Object> config, Map<String, Object> context) {
		Map<String, Object> labels = map(config.get("valueLabels"));
		Object mapped = labels.get(String.valueOf(value));
		String rendered = text(mapped == null ? value : mapped);
		String formattedTime = formatDisplayInstant(rendered, context);
		return StringUtils.hasText(formattedTime) ? formattedTime : rendered;
	}

	private String formatDisplayInstant(String value, Map<String, Object> context) {
		Instant instant = parseDisplayInstant(value);
		if (instant == null) {
			return "";
		}
		ZonedDateTime zoned = instant.atZone(displayZone(context));
		if (zoned.toLocalTime().equals(LocalTime.MIDNIGHT)) {
			return DISPLAY_DATE.format(zoned);
		}
		return DISPLAY_DATE_TIME.format(zoned);
	}

	private Instant parseDisplayInstant(String value) {
		if (!StringUtils.hasText(value) || value.length() < 20 || value.indexOf('T') < 0) {
			return null;
		}
		try {
			return Instant.parse(value);
		}
		catch (RuntimeException ignored) {
			try {
				return OffsetDateTime.parse(value).toInstant();
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
	}

	private ZoneId displayZone(Map<String, Object> context) {
		String zoneId = text(contextMapper.get(context, "/runtime/temporalPolicy/zoneId"));
		if (StringUtils.hasText(zoneId)) {
			try {
				return ZoneId.of(zoneId);
			}
			catch (RuntimeException ignored) {
				// fall through to default
			}
		}
		return ZoneId.of("Asia/Shanghai");
	}

	private String withSummary(String text, Map<String, Object> payload) {
		if (!UI_MODE_SUMMARY.equals(uiMode(payload))) {
			return text;
		}
		List<Map<String, Object>> fields = mapList(payload.get("displayFields"));
		if (fields.isEmpty()) {
			return text;
		}
		StringBuilder summary = new StringBuilder(firstText(text, "请核对以下信息。"));
		for (Map<String, Object> field : fields) {
			summary.append("\n- ").append(text(field.get("label"))).append("：").append(text(field.get("value")));
		}
		return summary.toString();
	}

	private Object projectToSchema(Object value, Map<String, Object> schema) {
		if (value == null || schema.isEmpty()) {
			return Map.of();
		}
		String type = text(schema.get("type"));
		if ("array".equals(type) && value instanceof List<?> list) {
			Map<String, Object> itemSchema = map(schema.get("items"));
			return list.stream().map(item -> projectToSchema(item, itemSchema)).toList();
		}
		Map<String, Object> properties = map(schema.get("properties"));
		if (!properties.isEmpty() && value instanceof Map<?, ?> sourceMap) {
			Map<String, Object> source = map(sourceMap);
			Map<String, Object> projected = new LinkedHashMap<>();
			for (Map.Entry<String, Object> entry : properties.entrySet()) {
				if (isBusinessIdPath("/" + entry.getKey())) {
					continue;
				}
				if (source.containsKey(entry.getKey())) {
					projected.put(entry.getKey(), projectToSchemaValue(source.get(entry.getKey()), map(entry.getValue())));
				}
			}
			return projected;
		}
		return value;
	}

	private Map<String, Object> publicUiSchema(Map<String, Object> schema) {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<String, Object> entry : map(schema).entrySet()) {
			if ("properties".equals(entry.getKey())) {
				Map<String, Object> properties = new LinkedHashMap<>();
				for (Map.Entry<String, Object> property : map(entry.getValue()).entrySet()) {
					if (!isBusinessIdPath("/" + property.getKey())) {
						properties.put(property.getKey(), property.getValue() instanceof Map<?, ?> nested
								? publicUiSchema(map(nested)) : property.getValue());
					}
				}
				result.put(entry.getKey(), properties);
			}
			else if ("required".equals(entry.getKey())) {
				result.put(entry.getKey(), strings(entry.getValue()).stream()
						.filter(name -> !isBusinessIdPath("/" + name)).toList());
			}
			else if (entry.getValue() instanceof Map<?, ?> nested) {
				result.put(entry.getKey(), publicUiSchema(map(nested)));
			}
			else if (entry.getValue() instanceof List<?> values) {
				result.put(entry.getKey(), values.stream().map(value -> value instanceof Map<?, ?> nested
						? publicUiSchema(map(nested)) : value).toList());
			}
			else {
				result.put(entry.getKey(), entry.getValue());
			}
		}
		return result;
	}

	private Object projectToSchemaValue(Object value, Map<String, Object> schema) {
		if (value == null) {
			return null;
		}
		if ("array".equals(text(schema.get("type"))) || !map(schema.get("properties")).isEmpty()) {
			return projectToSchema(value, schema);
		}
		return value;
	}

	private void putIfPresent(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private List<AgentUiMessage.Action> actions(String action, Map<String, Object> payload) {
		List<Map<String, Object>> configuredActions = actionConfigs(payload);
		if (!configuredActions.isEmpty() || payload.containsKey("uiActions")) {
			return configuredActions.stream().filter(item -> actionVisible(item, action, payload))
					.map(this::toUiAction).toList();
		}
		if ("CONFIRM".equals(action)) {
			return List.of(new AgentUiMessage.Action("confirm-submit", "CONFIRM", "确认提交", true, Map.of()),
					new AgentUiMessage.Action("edit-flow", "EDIT", "返回修改", false, Map.of()));
		}
		if ("REVIEW".equals(action)) {
			List<AgentUiMessage.Action> defaults = new ArrayList<>();
			if (!Boolean.TRUE.equals(payload.get("blocking"))) {
				defaults.add(new AgentUiMessage.Action("review-submit", "SUBMIT", "信息无误，继续", true, Map.of()));
			}
			if (Boolean.TRUE.equals(payload.get("referenceApplied"))) {
				defaults.add(new AgentUiMessage.Action("clear-reference", "CLEAR_REFERENCE", "清除历史参考", false,
						Map.of()));
			}
			return List.copyOf(defaults);
		}
		if ("CANCEL_CONFIRM".equals(action)) {
			Map<String, Object> policy = cancelPolicy(payload);
			return List.of(new AgentUiMessage.Action("confirm-cancel", "CONFIRM_CANCEL",
					cancelPolicyText(policy, "confirmLabel", "确认取消"), true, Map.of()),
					new AgentUiMessage.Action("keep-flow", "KEEP_FLOW",
							cancelPolicyText(policy, "keepLabel", "继续当前流程"), false, Map.of()));
		}
		if ("SELECT".equals(action)) {
			List<AgentUiMessage.Action> actions = new ArrayList<>();
			actions.add(new AgentUiMessage.Action("select-option", "SELECT", "选择", null, Map.of()));
			if (Boolean.TRUE.equals(payload.get("allowSkip"))) {
				actions.add(new AgentUiMessage.Action("skip-selection", "SKIP", "跳过", true, Map.of()));
			}
			return List.copyOf(actions);
		}
		if ("COLLECT".equals(action) || "VALIDATION".equals(action)) {
			if (UI_MODE_FORM.equals(uiMode(payload))) {
				return List.of(new AgentUiMessage.Action("submit-fields", "SUBMIT", "提交", true, Map.of()));
			}
			return List.of();
		}
		return List.of();
	}

	private List<Map<String, Object>> actionConfigs(Map<String, Object> payload) {
		return mapList(payload == null ? null : payload.get("uiActions"));
	}

	private boolean actionVisible(Map<String, Object> action, String expectedAction, Map<String, Object> payload) {
		String type = text(action.get("type")).toUpperCase(Locale.ROOT);
		if (!actionAllowedForWaiting(expectedAction, type)) {
			return false;
		}
		if ("REVIEW".equals(expectedAction) && "SUBMIT".equals(type) && Boolean.TRUE.equals(payload.get("blocking"))) {
			return false;
		}
		if ("REVIEW".equals(expectedAction) && "CLEAR_REFERENCE".equals(type)
				&& !Boolean.TRUE.equals(payload.get("referenceApplied"))) {
			return false;
		}
		if ("SELECT".equals(expectedAction) && "SKIP".equals(type)
				&& !Boolean.TRUE.equals(payload.get("allowSkip"))) {
			return false;
		}
		return true;
	}

	private boolean actionAllowedForWaiting(String expectedAction, String actionType) {
		return switch (expectedAction) {
			case "COLLECT", "VALIDATION" -> Set.of("SUBMIT", "CANCEL", "SELECT").contains(actionType);
			case "REVIEW" -> Set.of("SUBMIT", "CLEAR_REFERENCE", "CANCEL").contains(actionType);
			case "CONFIRM" -> Set.of("CONFIRM", "EDIT", "CANCEL").contains(actionType);
			case "SELECT" -> Set.of("SELECT", "SKIP", "CANCEL", "FALLBACK").contains(actionType);
			default -> false;
		};
	}

	private AgentUiMessage.Action toUiAction(Map<String, Object> config) {
		return new AgentUiMessage.Action(text(config.get("actionId")), text(config.get("type")),
				text(config.get("label")), config.get("value"), map(config.get("payload")));
	}

	private Map<String, Object> cancelPolicy(Map<String, Object> payload) {
		return map(payload == null ? null : payload.get("cancelPolicy"));
	}

	private String cancelPolicyTextFromPayload(Map<String, Object> payload, String key, String fallback) {
		return cancelPolicyText(cancelPolicy(payload), key, fallback);
	}

	private String cancelPolicyText(Map<String, Object> policy, String key, String fallback) {
		return firstText(text(policy.get(key)), fallback);
	}

	private List<Map<String, Object>> publicOptions(Map<String, Object> payload) {
		Object options = payload.get("options");
		if (!(options instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<Map<String, Object>> result = new ArrayList<>();
		int index = 0;
		for (Object item : iterable) {
			if (item instanceof Map<?, ?> map) {
				Map<String, Object> internal = map(map);
				Map<String, Object> option = new LinkedHashMap<>();
				option.put("label", firstText(text(internal.get("label")), "选项" + (index + 1)));
				option.put("value", selectionKey(index));
				putIfPresent(option, "summary", internal.get("summary"));
				Object rawData = internal.get("rawData");
				if (rawData instanceof Map<?, ?> rawMap) {
					putIfPresent(option, "displayFields", map(rawMap).get("displayFields"));
				}
				result.add(option);
			}
			else {
				result.add(Map.of("label", text(item), "value", selectionKey(index)));
			}
			index++;
		}
		return result;
	}

	private List<Map<String, Object>> selectionOptions(List<?> values, Map<String, Object> config) {
		String labelPath = text(config.get("labelPath"));
		String valuePath = text(config.get("valuePath"));
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object value : values) {
			String selectionKey = selectionKey(result.size());
			if (!(value instanceof Map<?, ?> mapValue)) {
				result.add(Map.of("label", text(value), "value", selectionKey, "rawData", value));
				continue;
			}
			Map<String, Object> rawData = map(mapValue);
			Object label = StringUtils.hasText(labelPath) ? contextMapper.get(rawData, labelPath) : rawData.get("label");
			Object optionValue = StringUtils.hasText(valuePath) ? contextMapper.get(rawData, valuePath)
					: rawData.get("value");
			Map<String, Object> option = new LinkedHashMap<>();
			option.put("label", firstText(text(label), text(optionValue), "Option"));
			option.put("value", selectionKey);
			putIfPresent(option, "internalValue", optionValue);
			option.put("rawData", rawData);
			if (rawData.get("summary") != null) {
				option.put("summary", rawData.get("summary"));
			}
			result.add(option);
		}
		return List.copyOf(result);
	}

	private boolean isUncertainWriteFailure(RuntimeException ex) {
		Throwable current = ex;
		for (int depth = 0; current != null && depth < 8; depth++) {
			String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
			String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
			if (name.contains("timeout") || message.contains("timeout") || message.contains("timed out")
					|| message.contains("connection reset")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private boolean requiresIdempotency(FlowNode node) {
		Long resourceVersionId = longValue(node.config() == null ? null : node.config().get("resourceVersionId"));
		AgentExecutionResourceVersion resource = resourceVersionMapper.findPublished(resourceVersionId);
		return resource != null && Boolean.TRUE.equals(resource.getIdempotencyRequired());
	}

	private boolean resourceSuccessAlreadyPublished(Map<String, Object> context, FlowNode node) {
		return Boolean.TRUE.equals(contextMapper.get(context,
				"/runtime/resourceSuccessPublished/" + escapePointer(node.id())));
	}

	private void markResourceSuccessPublished(Map<String, Object> context, FlowNode node) {
		contextMapper.set(context, "/runtime/resourceSuccessPublished/" + escapePointer(node.id()), true);
	}

	private boolean hasResultQuery(FlowNode node) {
		return !map(node.config() == null ? null : node.config().get("resultQuery")).isEmpty();
	}

	private Map<String, Object> readMap(String json) {
		if (!StringUtils.hasText(json)) {
			return new LinkedHashMap<>();
		}
		try {
			return new LinkedHashMap<>(objectMapper.readValue(json, MAP_TYPE));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Invalid persisted FLOW JSON", ex);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private List<String> strings(Object value) {
		if (!(value instanceof Iterable<?> iterable)) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		iterable.forEach(item -> result.add(text(item)));
		return result;
	}

	private Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? null : Long.valueOf(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Map<String, Object> extractionConfig(Map<String, Object> source) {
		Map<String, Object> extraction = new LinkedHashMap<>(map(source.get("extraction")));
		if (source.containsKey("pairExtractions") && !extraction.containsKey("pairExtractions")) {
			extraction.put("pairExtractions", source.get("pairExtractions"));
		}
		return extraction;
	}

	private boolean isIncompletePath(Map<String, Object> config, Map<String, Object> context, String path) {
		Object value = contextMapper.get(context, path);
		if (contextMapper.isEmpty(value)) {
			return true;
		}
		String field = schemaField(path, INPUT_ROOT_PATH);
		if (!StringUtils.hasText(field)) {
			return false;
		}
		return collectionIncomplete(value, map(map(map(config.get("schema")).get("properties")).get(field)));
	}

	private boolean collectionIncomplete(Object value, Map<String, Object> fieldSchema) {
		if (!"array".equalsIgnoreCase(text(fieldSchema.get("type"))) || !(value instanceof List<?> items)) {
			return false;
		}
		int minItems = (int) number(fieldSchema.get("minItems"), 0L);
		if (items.size() < minItems) {
			return true;
		}
		Map<String, Object> itemSchema = map(fieldSchema.get("items"));
		List<String> required = strings(itemSchema.get("required")).stream()
			.filter(name -> !isBusinessIdPath("/" + name))
			.toList();
		if (required.isEmpty()) {
			return false;
		}
		for (Object item : items) {
			Map<String, Object> row = item instanceof Map<?, ?> map ? map(map) : Map.of();
			for (String field : required) {
				if (contextMapper.isEmpty(row.get(field))) {
					return true;
				}
			}
		}
		return false;
	}

	private List<String> incompleteRequiredPaths(Map<String, Object> config, Map<String, Object> context) {
		return strings(config.get("requiredPaths")).stream()
			.filter(path -> isIncompletePath(config, context, path))
			.toList();
	}

	private List<String> incompleteRequiredAnyPaths(Map<String, Object> config, Map<String, Object> context) {
		List<String> anyPaths = strings(config.get("requiredAnyPaths"));
		if (anyPaths.isEmpty()
				|| anyPaths.stream().anyMatch(path -> !isIncompletePath(config, context, path))) {
			return List.of();
		}
		return anyPaths;
	}

	private NodeResult tryCatalogListing(AgentRequest request, DataAgentFlowInstance instance,
			DataAgentSkillVersion version, FlowNode node, Map<String, Object> config, Map<String, Object> context,
			String waitingAction) {
		if (!isTurnList(context)) {
			return null;
		}
		Map<String, Object> catalogQuery = matchingCatalogListing(listingMatchQuery(request, context), version, config);
		if (!catalogQuery.isEmpty() && filledThisTurn(context, text(catalogQuery.get("whenMissing")))) {
			return null;
		}
		if (catalogQuery.isEmpty() || instance == null || version == null) {
			return null;
		}
		FlowNode resolver = findFlowNode(version, text(catalogQuery.get("resolverNode")));
		if (resolver == null || resolver.config() == null) {
			return null;
		}
		List<String> missingReqs = missingRequirements(resolver.config(), context);
		if (!missingReqs.isEmpty()) {
			return catalogReferenceWaiting(config, catalogQuery, resolver, List.of(), List.of(), waitingAction, context,
					catalogPrerequisitePrompt(config, missingReqs));
		}
		Long resourceVersionId = longValue(resolver.config().get("resourceVersionId"));
		Map<String, Object> arguments = new LinkedHashMap<>(arguments(resolver.config(), context));
		arguments.putIfAbsent("limit", FlowSelectTextSupport.MAX_OPTIONS);
		Map<String, Object> result;
		try {
			result = invokeToolViaGateway(request, instance, version, resolver.id(), resourceVersionId, "READ", false,
					null, arguments, resolver.config());
		}
		catch (RuntimeException ex) {
			log.warn("FLOW catalog listing failed, falling back to collect. nodeId={}, resolverNode={}, error={}",
					node.id(), resolver.id(), ex.getMessage());
			return null;
		}
		List<Map<String, Object>> candidates = candidateItems(result == null ? Map.of() : result, resolver.config());
		List<Map<String, Object>> options = selectionOptions(candidates, resolver.config());
		return catalogReferenceWaiting(config, catalogQuery, resolver, options, candidates, waitingAction, context,
				null);
	}

	private NodeResult catalogReferenceWaiting(Map<String, Object> config, Map<String, Object> catalogQuery,
			FlowNode resolver, List<Map<String, Object>> options, List<Map<String, Object>> candidates,
			String waitingAction, Map<String, Object> context, String overridePrompt) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("requiredPaths", strings(config.get("requiredPaths")));
		payload.put("requiredAnyPaths", strings(config.get("requiredAnyPaths")));
		payload.put("schema", map(config.get("schema")));
		payload.put("catalogQueries", config.get("catalogQueries"));
		payload.put("instruction", text(config.get("instruction")));
		putInternalExtractionConfig(payload, config);
		if (options != null && !options.isEmpty()) {
			payload.put("listedOptions", options);
			payload.put("catalogTargetPath", text(catalogQuery.get("whenMissing")));
			payload.put("identityPaths", strings(resolver.config().get("identityPaths")));
			payload.put("preserveFields", strings(resolver.config().get("preserveUserFields")));
			payload.put("selectionSource", "RESOLVED");
			storeListedCatalog(context, payload, firstText(text(resolver.config().get("itemLabel")),
					text(resolver.config().get("displayName")), "候选项"));
		}
		String action = firstText(waitingAction, "COLLECT");
		if ("COLLECT".equals(action) || "VALIDATION".equals(action)) {
			payload.put("uiActions", List.of());
			payload.put("missing", missingRequiredSlots(config, context, incompleteRequiredPaths(config, context),
					incompleteRequiredAnyPaths(config, context)));
		}
		if ("REVIEW".equals(action)) {
			List<String> issues = reviewIssues(config, context);
			if (!issues.isEmpty()) {
				payload.put("errors", issues);
				payload.put("blocking", true);
			}
			payload.put("currentValues", map(contextMapper.get(context, INPUT_ROOT_PATH)));
			payload.put("data", map(contextMapper.get(context, INPUT_ROOT_PATH)));
		}
		addUiConfig(payload, config, context);
		String prompt = firstText(overridePrompt, catalogListPrompt(resolver, options, candidates));
		if ("REVIEW".equals(action) && !strings(payload.get("errors")).isEmpty()) {
			prompt = String.join("\n", strings(payload.get("errors"))) + "\n" + prompt;
		}
		return NodeResult.waiting(prompt, action, payload);
	}

	private String catalogListPrompt(FlowNode resolver, List<Map<String, Object>> options,
			List<Map<String, Object>> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return firstText(text(map(resolver.config().get("textSearch")).get("notFoundText")),
					"未找到可选项，请换一个名称或编码。");
		}
		String itemLabel = firstText(text(resolver.config().get("itemLabel")),
				text(resolver.config().get("displayName")), "候选项");
		StringBuilder listed = new StringBuilder();
		listed.append("当前可选").append(itemLabel).append("如下，请直接说明要办理的项（可多项）及每项仍缺的信息：");
		int index = 1;
		for (Map<String, Object> option : options) {
			listed.append('\n').append(index++).append(". ").append(catalogReferenceLine(option, resolver.config()));
		}
		return listed.toString();
	}

	private String catalogPrerequisitePrompt(Map<String, Object> config, List<String> missing) {
		Map<String, Object> fieldPrompts = map(map(config.get("collectionPresentation")).get("fieldPrompts"));
		List<String> lines = new ArrayList<>();
		for (String path : missing) {
			String prompt = firstText(text(fieldPrompts.get(path)));
			if (StringUtils.hasText(prompt) && !lines.contains(prompt)) {
				lines.add(prompt);
			}
		}
		return lines.isEmpty() ? "请先补充查询所需的信息。" : String.join("\n", lines);
	}

	private String catalogReferenceLine(Map<String, Object> option, Map<String, Object> resolverConfig) {
		String label = firstText(text(option.get("label")), "未命名");
		Map<String, Object> raw = map(option.get("rawData"));
		List<String> extras = new ArrayList<>();
		for (String identityPath : strings(resolverConfig.get("identityPaths"))) {
			if (isBusinessIdPath(identityPath)) {
				continue;
			}
			Object value = contextMapper.get(raw, identityPath);
			if (value != null && StringUtils.hasText(String.valueOf(value))
					&& !label.contains(String.valueOf(value)) && !extras.contains(String.valueOf(value))) {
				extras.add(String.valueOf(value));
			}
		}
		return extras.isEmpty() ? label : label + "（" + String.join(" / ", extras) + "）";
	}

	private Map<String, Object> matchingCatalogListing(String query, DataAgentSkillVersion version,
			Map<String, Object> currentConfig) {
		if (!StringUtils.hasText(query)) {
			return Map.of();
		}
		List<Map<String, Object>> hits = new ArrayList<>();
		addCatalogQueryHits(hits, query, mapList(currentConfig == null ? null : currentConfig.get("catalogQueries")));
		for (FlowNode node : flowNodes(version)) {
			if (node == null || node.config() == null) {
				continue;
			}
			addCatalogQueryHits(hits, query, mapList(node.config().get("catalogQueries")));
			if ("resolve".equalsIgnoreCase(node.type())) {
				addResolverLabelHit(hits, query, node);
			}
			addDisplayFieldHits(hits, query, node, version);
		}
		return longestCatalogHit(hits);
	}

	private void addCatalogQueryHits(List<Map<String, Object>> hits, String query,
			List<Map<String, Object>> catalogQueries) {
		for (Map<String, Object> catalogQuery : catalogQueries) {
			String matched = longestContained(query, strings(catalogQuery.get("intents")));
			if (!StringUtils.hasText(matched)) {
				continue;
			}
			Map<String, Object> hit = new LinkedHashMap<>(catalogQuery);
			hit.put("matchedLabel", matched);
			hits.add(hit);
		}
	}

	private void addResolverLabelHit(List<Map<String, Object>> hits, String query, FlowNode node) {
		List<String> labels = new ArrayList<>();
		if (StringUtils.hasText(text(node.config().get("itemLabel")))) {
			labels.add(text(node.config().get("itemLabel")));
		}
		if (StringUtils.hasText(text(node.config().get("displayName")))) {
			labels.add(text(node.config().get("displayName")));
		}
		String matched = longestContained(query, labels);
		if (!StringUtils.hasText(matched)) {
			return;
		}
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("resolverNode", node.id());
		hit.put("whenMissing", resolverTargetPath(node));
		hit.put("matchedLabel", matched);
		hit.put("intents", labels);
		hits.add(hit);
	}

	private void addDisplayFieldHits(List<Map<String, Object>> hits, String query, FlowNode node,
			DataAgentSkillVersion version) {
		Map<String, String> labelToPath = new LinkedHashMap<>();
		collectDisplayLabels(labelToPath, mapList(node.config().get("displayFields")));
		for (Map<String, Object> collection : mapList(node.config().get("displayCollections"))) {
			String path = text(collection.get("path"));
			putDisplayLabel(labelToPath, text(collection.get("itemLabel")), path);
			putDisplayLabel(labelToPath, text(collection.get("label")), path);
			collectDisplayLabels(labelToPath, mapList(collection.get("itemFields")), path);
		}
		String matched = longestContained(query, List.copyOf(labelToPath.keySet()));
		if (!StringUtils.hasText(matched)) {
			return;
		}
		String path = labelToPath.get(matched);
		String resolverNode = resolverNodeForPath(version, path);
		if (!StringUtils.hasText(resolverNode)) {
			return;
		}
		Map<String, Object> hit = new LinkedHashMap<>();
		hit.put("resolverNode", resolverNode);
		hit.put("whenMissing", path);
		hit.put("matchedLabel", matched);
		hit.put("intents", List.of(matched));
		hits.add(hit);
	}

	private void collectDisplayLabels(Map<String, String> labelToPath, List<Map<String, Object>> fields) {
		collectDisplayLabels(labelToPath, fields, null);
	}

	private void collectDisplayLabels(Map<String, String> labelToPath, List<Map<String, Object>> fields,
			String parentPath) {
		for (Map<String, Object> field : fields) {
			String path = text(field.get("path"));
			if (StringUtils.hasText(parentPath) && path.startsWith("/") && !path.startsWith(parentPath)) {
				path = parentPath + path;
			}
			else if (!StringUtils.hasText(path)) {
				path = parentPath;
			}
			putDisplayLabel(labelToPath, text(field.get("label")), path);
			putDisplayLabel(labelToPath, text(field.get("itemLabel")), path);
		}
	}

	private void putDisplayLabel(Map<String, String> labelToPath, String label, String path) {
		if (StringUtils.hasText(label) && StringUtils.hasText(path)) {
			labelToPath.putIfAbsent(label, path);
		}
	}

	private String resolverNodeForPath(DataAgentSkillVersion version, String path) {
		if (!StringUtils.hasText(path)) {
			return null;
		}
		String catalogResolver = null;
		String routeResolver = null;
		String argumentResolver = null;
		for (FlowNode node : flowNodes(version)) {
			if (node == null || node.config() == null) {
				continue;
			}
			for (Map<String, Object> catalogQuery : mapList(node.config().get("catalogQueries"))) {
				if (pathEqualsOrChild(path, text(catalogQuery.get("whenMissing")))) {
					catalogResolver = firstText(catalogResolver, text(catalogQuery.get("resolverNode")));
				}
			}
			for (Map<String, Object> route : mapList(node.config().get("refreshRoutes"))) {
				boolean matched = strings(route.get("whenAny")).stream()
					.anyMatch(trigger -> pathEqualsOrChild(path, trigger));
				if (matched) {
					routeResolver = firstText(routeResolver, text(route.get("next")));
				}
			}
			if ("resolve".equalsIgnoreCase(node.type()) && resolverTouchesPath(node, path)) {
				argumentResolver = firstText(argumentResolver, node.id());
			}
		}
		return firstText(catalogResolver, routeResolver, argumentResolver);
	}

	private boolean resolverTouchesPath(FlowNode node, String path) {
		if (pathEqualsOrChild(path, text(node.config().get("forEach")))) {
			return true;
		}
		if (strings(node.config().get("completePaths")).stream().anyMatch(item -> pathEqualsOrChild(path, item))) {
			return true;
		}
		return map(node.config().get("argumentMappings")).values().stream()
			.map(this::text)
			.anyMatch(item -> pathEqualsOrChild(path, item));
	}

	private boolean pathEqualsOrChild(String left, String right) {
		return StringUtils.hasText(left) && StringUtils.hasText(right)
				&& (left.equals(right) || left.startsWith(right + "/") || right.startsWith(left + "/"));
	}

	private String resolverTargetPath(FlowNode node) {
		String forEach = text(node.config().get("forEach"));
		if (StringUtils.hasText(forEach)) {
			return forEach;
		}
		List<String> complete = strings(node.config().get("completePaths"));
		if (!complete.isEmpty()) {
			return complete.get(0);
		}
		for (Object value : map(node.config().get("argumentMappings")).values()) {
			String path = text(value);
			if (path.startsWith(INPUT_ROOT_PATH)) {
				return path;
			}
		}
		return "";
	}

	private Map<String, Object> longestCatalogHit(List<Map<String, Object>> hits) {
		Map<String, Object> best = Map.of();
		int bestLength = -1;
		for (Map<String, Object> hit : hits) {
			int length = text(hit.get("matchedLabel")).length();
			if (length > bestLength) {
				best = hit;
				bestLength = length;
			}
		}
		return best;
	}

	private String longestContained(String query, List<String> labels) {
		String best = null;
		for (String label : labels) {
			if (StringUtils.hasText(label) && query.contains(label)
					&& (best == null || label.length() > best.length())) {
				best = label;
			}
		}
		return best;
	}

	private void applyExtractedUtterance(AgentRequest request, Map<String, Object> context, Map<String, Object> schema,
			String outputPath, Map<String, Object> extracted, Map<String, Object> config,
			DataAgentSkillVersion version) {
		Map<String, Object> values = mergePairExtractions(request == null ? null : request.getQuery(), extracted,
				config, context, outputPath, version);
		applyExtractionPatch(context, outputPath, values);
		recordFilledThisTurn(context, outputPath, values);
		fillConfiguredPairRoles(context, config, outputPath);
		fillMissingIntegersFromQuery(request, context, schema, outputPath, values);
		dropSubstringIdentities(context, schema, outputPath, request == null ? null : request.getQuery());
	}

	private Map<String, Object> mergePairExtractions(String query, Map<String, Object> extracted,
			Map<String, Object> config, Map<String, Object> context, String outputPath,
			DataAgentSkillVersion version) {
		List<Map<String, Object>> pairs = flowPairExtractions(version, extractionConfig(config));
		if (pairs.isEmpty()) {
			pairs = mapList(config.get("pairExtractions"));
		}
		Map<String, Object> fromQuery = FlowAddressPairExtractor.extract(query, pairs);
		if (fromQuery.isEmpty()) {
			return extracted == null ? Map.of() : extracted;
		}
		Map<String, Object> merged = new LinkedHashMap<>(extracted == null ? Map.of() : extracted);
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (Map.Entry<String, Object> entry : fromQuery.entrySet()) {
			if (merged.containsKey(entry.getKey()) && !contextMapper.isEmpty(merged.get(entry.getKey()))) {
				continue;
			}
			Object current = contextMapper.get(context, root + "/" + escapePointer(entry.getKey()));
			if (!contextMapper.isEmpty(current)) {
				continue;
			}
			merged.put(entry.getKey(), entry.getValue());
		}
		return merged;
	}

	private void recordFilledThisTurn(Map<String, Object> context, String outputPath, Map<String, Object> values) {
		if (values == null || values.isEmpty()) {
			return;
		}
		List<String> filled = new ArrayList<>(strings(contextMapper.get(context, FILLED_THIS_TURN_PATH)));
		String root = firstText(outputPath, INPUT_ROOT_PATH);
		for (String field : values.keySet()) {
			if (contextMapper.isEmpty(values.get(field))) {
				continue;
			}
			String path = root + "/" + escapePointer(field);
			if (!filled.contains(path)) {
				filled.add(path);
			}
		}
		contextMapper.set(context, FILLED_THIS_TURN_PATH, filled);
	}

	private boolean filledThisTurn(Map<String, Object> context, String path) {
		if (!StringUtils.hasText(path)) {
			return false;
		}
		return strings(contextMapper.get(context, FILLED_THIS_TURN_PATH)).stream()
			.anyMatch(filled -> pathEqualsOrChild(filled, path));
	}

	private void fillMissingIntegersFromQuery(AgentRequest request, Map<String, Object> context,
			Map<String, Object> schema, String outputPath, Map<String, Object> extractedThisTurn) {
		if (request == null || !StringUtils.hasText(request.getQuery()) || schema == null || schema.isEmpty()) {
			return;
		}
		boolean soleNumber = isSoleNumberQuery(request.getQuery());
		int globalMissing = 0;
		int[] fillSlot = { -1, -1 };
		String fillPath = null;
		String fillField = null;
		for (Map.Entry<String, Object> property : map(schema.get("properties")).entrySet()) {
			Map<String, Object> fieldSchema = map(property.getValue());
			if (!"array".equalsIgnoreCase(text(fieldSchema.get("type")))) {
				continue;
			}
			String listPath = outputPath + "/" + escapePointer(property.getKey());
			Object current = contextMapper.get(context, listPath);
			if (!(current instanceof List<?> items) || items.isEmpty()) {
				continue;
			}
			Map<String, Object> itemSchema = map(fieldSchema.get("items"));
			List<String> integerFields = integerItemFields(itemSchema);
			if (integerFields.isEmpty()) {
				continue;
			}
			boolean collectionTouched = extractedThisTurn != null && extractedThisTurn.containsKey(property.getKey());
			List<Object> updated = new ArrayList<>(items.size());
			boolean changed = false;
			for (int index = 0; index < items.size(); index++) {
				Map<String, Object> row = items.get(index) instanceof Map<?, ?> map
						? new LinkedHashMap<>(map(map)) : new LinkedHashMap<>();
				List<String> missing = new ArrayList<>();
				for (String field : integerFields) {
					if (contextMapper.isEmpty(row.get(field))) {
						missing.add(field);
					}
				}
				if (missing.size() == 1) {
					globalMissing++;
					fillSlot[0] = index;
					fillPath = listPath;
					fillField = missing.get(0);
					fillSlot[1] = updated.size();
				}
				if (!missing.isEmpty() && collectionTouched) {
					List<Long> numbers = leftoverIntegers(request.getQuery(), row, itemSchema);
					if (numbers.size() == missing.size()) {
						for (int n = 0; n < missing.size(); n++) {
							row.put(missing.get(n), numbers.get(n));
							markValueSlots(context, listPath + "/" + index + "/" + escapePointer(missing.get(n)),
									numbers.get(n), "USER");
						}
						changed = true;
					}
				}
				updated.add(row);
			}
			if (changed) {
				contextMapper.set(context, listPath, updated);
			}
		}
		if (soleNumber && globalMissing == 1 && fillPath != null && StringUtils.hasText(fillField)) {
			Object current = contextMapper.get(context, fillPath);
			if (current instanceof List<?> items && fillSlot[0] >= 0 && fillSlot[0] < items.size()
					&& items.get(fillSlot[0]) instanceof Map<?, ?> map) {
				Long number = leadingInteger(request.getQuery());
				if (number != null && contextMapper.isEmpty(map(map).get(fillField))) {
					Map<String, Object> row = new LinkedHashMap<>(map(map));
					row.put(fillField, number);
					List<Object> updated = new ArrayList<>(items);
					updated.set(fillSlot[0], row);
					contextMapper.set(context, fillPath, updated);
					markValueSlots(context, fillPath + "/" + fillSlot[0] + "/" + escapePointer(fillField), number,
							"USER");
				}
			}
		}
	}

	private List<String> integerItemFields(Map<String, Object> itemSchema) {
		Map<String, Object> properties = map(itemSchema.get("properties"));
		List<String> result = new ArrayList<>();
		for (String name : strings(itemSchema.get("required"))) {
			if (isBusinessIdPath("/" + name)) {
				continue;
			}
			String type = text(map(properties.get(name)).get("type"));
			if ("integer".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) {
				result.add(name);
			}
		}
		return result;
	}

	private List<Long> leftoverIntegers(String query, Map<String, Object> row, Map<String, Object> itemSchema) {
		String remainder = query;
		List<String> identities = new ArrayList<>();
		for (Map.Entry<String, Object> property : map(itemSchema.get("properties")).entrySet()) {
			String type = text(map(property.getValue()).get("type"));
			if ("integer".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) {
				continue;
			}
			Object value = row.get(property.getKey());
			if (value != null && StringUtils.hasText(String.valueOf(value))) {
				identities.add(String.valueOf(value));
			}
		}
		identities.sort(Comparator.comparingInt(String::length).reversed());
		for (String identity : identities) {
			remainder = remainder.replace(identity, " ");
		}
		Matcher matcher = DIGIT_GROUP.matcher(remainder);
		List<Long> numbers = new ArrayList<>();
		while (matcher.find()) {
			numbers.add(Long.parseLong(matcher.group()));
		}
		return numbers;
	}

	private boolean isSoleNumberQuery(String query) {
		if (!StringUtils.hasText(query)) {
			return false;
		}
		Matcher matcher = LEADING_INTEGER.matcher(query.trim());
		if (!matcher.find() || matcher.start() != 0) {
			return false;
		}
		return !DIGIT_GROUP.matcher(query.trim().substring(matcher.end())).find();
	}

	private Long leadingInteger(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		Matcher matcher = LEADING_INTEGER.matcher(value);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Long.parseLong(matcher.group(1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private String trimTrailingIdentityPunctuation(String value) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		return TRAILING_IDENTITY_PUNCT.matcher(value.trim()).replaceAll("");
	}

	private boolean isProceedWithoutEditCommand(AgentRequest request, Map<String, Object> waiting,
			DataAgentSkillVersion version) {
		if (request == null || request.getFlowAction() != null || !StringUtils.hasText(request.getQuery())) {
			return false;
		}
		return isProceedQuery(request.getQuery(), waiting, version);
	}

	private boolean isProceedQuery(String query, Map<String, Object> waiting, DataAgentSkillVersion version) {
		String normalized = normalizeFlowCommand(query);
		if (!StringUtils.hasText(normalized)) {
			return false;
		}
		Set<String> phrases = proceedPhrases(waiting, version);
		if (phrases.contains(normalized)) {
			return true;
		}
		String remaining = normalized;
		boolean stripped = false;
		while (StringUtils.hasText(remaining)) {
			String match = null;
			for (String phrase : phrases) {
				if (remaining.startsWith(phrase) && (match == null || phrase.length() > match.length())) {
					match = phrase;
				}
			}
			if (!StringUtils.hasText(match)) {
				return false;
			}
			remaining = remaining.substring(match.length());
			stripped = true;
		}
		return stripped;
	}

	private Set<String> proceedPhrases(Map<String, Object> waiting, DataAgentSkillVersion version) {
		Set<String> phrases = new LinkedHashSet<>();
		addProceedLabels(phrases, waiting);
		for (FlowNode node : flowNodes(version)) {
			if (node != null) {
				addProceedLabels(phrases, node.config());
			}
		}
		phrases.removeIf(item -> !StringUtils.hasText(item));
		return phrases;
	}

	private void addProceedLabels(Set<String> phrases, Map<String, Object> config) {
		if (config == null || config.isEmpty()) {
			return;
		}
		for (Map<String, Object> action : mapList(config.get("uiActions"))) {
			String type = text(action.get("type")).trim().toUpperCase(Locale.ROOT);
			if ("SUBMIT".equals(type) || "CONFIRM".equals(type) || "KEEP_FLOW".equals(type)) {
				phrases.add(normalizeFlowCommand(text(action.get("label"))));
			}
		}
		phrases.add(normalizeFlowCommand(text(map(config.get("cancelPolicy")).get("keepLabel"))));
	}

	private FlowExecutionResult handleTurnControlAction(AgentRequest request, DataAgentFlowInstance instance,
			Map<String, Object> context, Map<String, FlowNode> nodeMap) {
		if (request != null && request.getFlowAction() == null && isTurnCancel(context)) {
			request.setFlowAction(new FlowAction("cancel-flow", "CANCEL", false, Map.of()));
		}
		return handleControlAction(request, instance, context, nodeMap);
	}

	private boolean applyProceedWithoutExtract(AgentRequest request, Map<String, Object> waiting,
			DataAgentSkillVersion version, Map<String, Object> context, String action) {
		if ("REVIEW".equals(action) && !isBlockingReview(waiting)) {
			request.setFlowAction(new FlowAction("review-submit", "SUBMIT", true, Map.of()));
			return false;
		}
		if ("REVIEW".equals(action) && isBlockingReview(waiting)) {
			retryBlockedReview(request, waiting, version, context);
			return true;
		}
		return true;
	}

	private boolean applyExtractedTurnIntent(AgentRequest request, DataAgentSkillVersion version,
			Map<String, Object> waiting, Map<String, Object> context, String waitingAction) {
		return applyExtractedTurnIntent(request, version, waiting, context, waitingAction, List.of());
	}

	private boolean applyExtractedTurnIntent(AgentRequest request, DataAgentSkillVersion version,
			Map<String, Object> waiting, Map<String, Object> context, String waitingAction,
			List<String> filledBeforeExtract) {
		if (isTurnCancel(context)) {
			return true;
		}
		if (hasNewFillsThisTurn(context, filledBeforeExtract)) {
			return false;
		}
		if (isTurnProceed(context)) {
			applyProceedWithoutExtract(request, waiting, version, context, waitingAction);
			return true;
		}
		return isTurnAskOrChitchat(context) || isTurnList(context);
	}

	private Map<String, Object> takeTurnIntent(Map<String, Object> extracted, Map<String, Object> context) {
		if (extracted == null || extracted.isEmpty()) {
			return extracted == null ? Map.of() : extracted;
		}
		Map<String, Object> values = new LinkedHashMap<>(extracted);
		String action = text(values.remove(TURN_ACTION_FIELD)).trim().toLowerCase(Locale.ROOT);
		String label = text(values.remove(LIST_LABEL_FIELD)).trim();
		String reply = text(values.remove(TURN_REPLY_FIELD)).trim();
		if (TURN_ACTIONS.contains(action)) {
			contextMapper.set(context, TURN_ACTION_PATH, action);
		}
		if (StringUtils.hasText(label)) {
			contextMapper.set(context, LIST_LABEL_PATH, label);
		}
		if ("chitchat".equals(action) && !StringUtils.hasText(reply)) {
			reply = "咱们先把当前这单办完。";
		}
		if (StringUtils.hasText(reply)) {
			contextMapper.set(context, TURN_REPLY_PATH, sanitizeUserVisibleText(reply));
		}
		return values;
	}

	private boolean onlyTurnIntentProperties(Map<String, Object> properties) {
		if (properties == null || properties.isEmpty()) {
			return true;
		}
		return properties.keySet().stream()
			.allMatch(field -> TURN_ACTION_FIELD.equals(field) || LIST_LABEL_FIELD.equals(field)
					|| TURN_REPLY_FIELD.equals(field));
	}

	private void injectTurnIntentFields(Map<String, Object> properties, Map<String, Object> context) {
		properties.put(TURN_ACTION_FIELD, Map.of("type", "string",
				"enum", TURN_ACTION_VALUES,
				"description", "fill=providing form values; list=show catalog options; proceed=continue; "
						+ "cancel=stop only when the user clearly wants to abort; ask=user asked a question; "
						+ "chitchat=off-topic small talk, briefly pull the user back to this form. "
						+ "Ambiguous 算了 is proceed or chitchat, not cancel."));
		Map<String, Object> listLabel = new LinkedHashMap<>();
		listLabel.put("type", "string");
		List<String> labels = strings(contextMapper.get(context, CATALOG_LABELS_PATH));
		if (!labels.isEmpty() && labels.size() <= 12) {
			listLabel.put("description", "Resource to list; one of: " + String.join(", ", labels));
		}
		properties.put(LIST_LABEL_FIELD, listLabel);
		properties.put(TURN_REPLY_FIELD, Map.of("type", "string",
				"description", "When turnAction is ask or chitchat: answer briefly in the user's language, "
						+ "then say what this task still needs using the missing field titles. Never output "
						+ "English schema keys, JSON pointers, or phrases like 'is required'. Do not invent form values."));
	}

	private List<String> catalogResourceLabels(DataAgentSkillVersion version) {
		Set<String> labels = new LinkedHashSet<>();
		for (FlowNode node : flowNodes(version)) {
			if (node == null || node.config() == null) {
				continue;
			}
			for (Map<String, Object> catalogQuery : mapList(node.config().get("catalogQueries"))) {
				labels.addAll(strings(catalogQuery.get("intents")));
			}
			if ("resolve".equalsIgnoreCase(node.type()) && StringUtils.hasText(text(node.config().get("itemLabel")))) {
				labels.add(text(node.config().get("itemLabel")));
			}
		}
		labels.removeIf(item -> !StringUtils.hasText(item));
		return List.copyOf(labels);
	}

	private String listingMatchQuery(AgentRequest request, Map<String, Object> context) {
		return firstText(text(contextMapper.get(context, LIST_LABEL_PATH)),
				request == null ? "" : request.getQuery());
	}

	private boolean isTurnList(Map<String, Object> context) {
		return "list".equalsIgnoreCase(text(contextMapper.get(context, TURN_ACTION_PATH)));
	}

	private boolean isTurnCancel(Map<String, Object> context) {
		return "cancel".equalsIgnoreCase(text(contextMapper.get(context, TURN_ACTION_PATH)));
	}

	private boolean isTurnProceed(Map<String, Object> context) {
		return "proceed".equalsIgnoreCase(text(contextMapper.get(context, TURN_ACTION_PATH)));
	}

	private boolean isTurnAskOrChitchat(Map<String, Object> context) {
		String action = text(contextMapper.get(context, TURN_ACTION_PATH)).trim().toLowerCase(Locale.ROOT);
		return "ask".equals(action) || "chitchat".equals(action);
	}

	private SlotMergeCheckpoint captureSlotMerge(Map<String, Object> context, String outputPath) {
		try {
			return new SlotMergeCheckpoint(copyValue(contextMapper.get(context, outputPath)),
					copyValue(contextMapper.get(context, "/slotMeta")),
					copyValue(contextMapper.get(context, LAST_CHANGED_PATHS_PATH)),
					copyValue(contextMapper.get(context, "/runtime/reviewedRevision")),
					copyValue(contextMapper.get(context, "/runtime/confirmedRevision")),
					copyValue(contextMapper.get(context, "/runtime/confirmed")),
					copyValue(contextMapper.get(context, FILLED_THIS_TURN_PATH)));
		}
		catch (RuntimeException ex) {
			log.warn("FLOW slot checkpoint copy failed, skipping this-turn literal merge. path={}", outputPath);
			return null;
		}
	}

	private void restoreSlotMerge(Map<String, Object> context, String outputPath, SlotMergeCheckpoint checkpoint) {
		if (checkpoint == null || !StringUtils.hasText(outputPath)) {
			return;
		}
		contextMapper.set(context, outputPath, checkpoint.input());
		contextMapper.set(context, "/slotMeta", checkpoint.slotMeta());
		contextMapper.set(context, LAST_CHANGED_PATHS_PATH, checkpoint.lastChangedPaths());
		contextMapper.set(context, "/runtime/reviewedRevision", checkpoint.reviewedRevision());
		contextMapper.set(context, "/runtime/confirmedRevision", checkpoint.confirmedRevision());
		contextMapper.set(context, "/runtime/confirmed", checkpoint.confirmed());
		contextMapper.set(context, FILLED_THIS_TURN_PATH, checkpoint.filledThisTurn());
	}

	private Object copyValue(Object value) {
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> copy = new LinkedHashMap<>();
			for (Map.Entry<String, Object> entry : map(map).entrySet()) {
				copy.put(entry.getKey(), copyValue(entry.getValue()));
			}
			return copy;
		}
		if (value instanceof List<?> list) {
			List<Object> copy = new ArrayList<>(list.size());
			for (Object item : list) {
				copy.add(copyValue(item));
			}
			return copy;
		}
		throw new IllegalArgumentException("FLOW slot checkpoint cannot copy " + value.getClass().getName());
	}

	private String extractionFailurePrompt(Map<String, Object> config, Map<String, Object> context,
			boolean inputCapacityExceeded, boolean partialCollected) {
		List<String> missing = incompleteRequiredPaths(config, context);
		List<String> anyPaths = strings(config.get("requiredAnyPaths"));
		List<String> missingAny = !anyPaths.isEmpty()
				&& anyPaths.stream().noneMatch(path -> !isIncompletePath(config, context, path)) ? anyPaths : List.of();
		String missingPrompt = collectionPrompt(config, context, missing, missingAny);
		if (inputCapacityExceeded) {
			return firstText(missingPrompt, "本次信息超出当前模型可处理范围，已保留已有内容，请按当前提示继续补充。");
		}
		if (!missing.isEmpty() || !missingAny.isEmpty()) {
			return missingPrompt;
		}
		if (partialCollected || !contextMapper.isEmpty(contextMapper.get(context, INPUT_ROOT_PATH))) {
			return "已识别部分办理信息，请按当前提示继续补充。";
		}
		return "刚才未能完整识别本次信息，请重新说明需要办理的内容。";
	}

	private boolean isBlockingReview(Map<String, Object> waiting) {
		return Boolean.TRUE.equals(waiting.get("blocking")) || !strings(waiting.get("errors")).isEmpty();
	}

	private List<String> reviewIssues(Map<String, Object> config, Map<String, Object> context) {
		List<String> issuePaths = strings(config.get("issuesPaths"));
		if (issuePaths.isEmpty() && StringUtils.hasText(text(config.get("issuesPath")))) {
			issuePaths = List.of(text(config.get("issuesPath")));
		}
		return issuePaths.stream().flatMap(path -> strings(contextMapper.get(context, path)).stream()).toList();
	}

	private List<FlowNode> flowNodes(DataAgentSkillVersion version) {
		if (version == null) {
			return List.of();
		}
		try {
			FlowDefinition definition = objectMapper.convertValue(readMap(version.getFlowDefinition()),
					FlowDefinition.class);
			if (definition == null || definition.nodes() == null) {
				return List.of();
			}
			return definition.nodes();
		}
		catch (RuntimeException ex) {
			return List.of();
		}
	}

	private FlowNode findFlowNode(DataAgentSkillVersion version, String nodeId) {
		if (!StringUtils.hasText(nodeId)) {
			return null;
		}
		return flowNodes(version).stream().filter(node -> node != null && nodeId.equals(node.id())).findFirst()
			.orElse(null);
	}

	private String displayValidationErrors(List<String> errors, Map<String, Object> config) {
		Map<String, Object> prompts = new LinkedHashMap<>(map(config.get("errorPrompts")));
		Map<String, Object> fieldPrompts = map(map(config.get("collectionPresentation")).get("fieldPrompts"));
		fieldPrompts.forEach((path, prompt) -> {
			String relative = path.startsWith(INPUT_ROOT_PATH) ? path.substring(INPUT_ROOT_PATH.length()) : path;
			if (StringUtils.hasText(relative) && !prompts.containsKey(relative)) {
				prompts.put(relative, prompt);
			}
		});
		List<String> lines = new ArrayList<>();
		for (String error : errors) {
			String mapped = matchErrorPrompt(error, prompts);
			String line = StringUtils.hasText(mapped) ? mapped : "请补充缺失的办理信息。";
			if (!lines.contains(line)) {
				lines.add(line);
			}
		}
		return sanitizeUserVisibleText(lines.isEmpty() ? "请补充缺失的办理信息。" : String.join("\n", lines));
	}

	private String matchErrorPrompt(String error, Map<String, Object> prompts) {
		String pointer = error == null ? "" : error.trim();
		int separator = pointer.indexOf(' ');
		if (separator > 0) {
			pointer = pointer.substring(0, separator);
		}
		int slash = pointer.lastIndexOf('/');
		String lastSegment = slash >= 0 ? pointer.substring(slash + 1) : pointer;
		if ("num".equals(lastSegment)) {
			return firstText(text(prompts.get("/num")), "请补充数量。");
		}
		String bestKey = "";
		for (String key : prompts.keySet()) {
			if (pointer.equals(key) || pointer.startsWith(key + "/") || pointer.contains(key + "/")) {
				if (key.length() >= bestKey.length()) {
					bestKey = key;
				}
			}
		}
		return StringUtils.hasText(bestKey) ? text(prompts.get(bestKey)) : "";
	}

	private long number(Object value, long fallback) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return value == null ? fallback : Long.parseLong(String.valueOf(value));
		}
		catch (NumberFormatException ex) {
			return fallback;
		}
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private String text(Object value) {
		return value == null ? "" : String.valueOf(value);
	}

	private String escapePointer(String value) {
		return value.replace("~", "~0").replace("/", "~1");
	}

	private long elapsedMs(long startNanos) {
		return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
	}

	/**
	 * 并行/单路 Resolver 待执行任务；resolvedToolName 取自已发布快照的工具名，供卡片步骤展示工具标题。
	 */
	private record ResolverTask(String id, Long resourceVersionId, Map<String, Object> config,
			Map<String, Object> arguments, String fingerprint, long revision, String resolvedToolName) {
	}

	private record ResolverOutcome(Map<String, Object> result, Throwable error) {
	}

	private record EachResolverTask(String id, int index, String itemPath, Map<String, Object> item,
			Map<String, Object> arguments, String fingerprint, long revision) {
	}

	private record EachSelection(EachResolverTask task, List<Map<String, Object>> candidates) {
	}

	private record SlotMergeCheckpoint(Object input, Object slotMeta, Object lastChangedPaths, Object reviewedRevision,
			Object confirmedRevision, Object confirmed, Object filledThisTurn) {
	}

	private static final class ResolverRun {

		private final List<CompletableFuture<?>> futures = new CopyOnWriteArrayList<>();

		private volatile boolean cancelled;

		private void register(CompletableFuture<?> future) {
			futures.add(future);
			if (cancelled) {
				future.cancel(true);
			}
		}

		private void cancel() {
			cancelled = true;
			futures.forEach(future -> future.cancel(true));
		}

		private boolean cancelled() {
			return cancelled;
		}
	}

	private boolean frozenApprovalWait(DataAgentFlowInstance instance, AgentRequest request) {
		if (instance == null || !FlowInstanceStatus.WAITING.name().equals(instance.getStatus())) {
			return false;
		}
		Map<String, Object> waiting = readMap(instance.getWaitingPayload());
		if (!"APPROVAL".equalsIgnoreCase(text(waiting.get("action")))) {
			return false;
		}
		FlowAction action = request == null ? null : request.getFlowAction();
		return action == null || !"CANCEL".equalsIgnoreCase(text(action.type()));
	}

	private NodeResult managerApprovalWaiting(AgentRequest request, DataAgentFlowInstance instance, FlowNode node,
			DataAgentSkillVersion version, Map<String, Object> context, CapabilityApprovalRequiredException ex) {
		if (contextMapper.get(context, "/runtime/callerSnapshot") == null) {
			contextMapper.set(context, "/runtime/callerSnapshot", callerSnapshot(request));
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("approvalId", ex.getApprovalId());
		payload.put("capabilityCode", ex.getCapabilityCode());
		payload.put("paramsHash", ex.getParamsHash());
		payload.put("nodeId", node.id());
		payload.put("confirmNodeId", precedingConfirmNodeId(version, node.id()));
		payload.put("confirmedRevision", number(contextMapper.get(context, "/runtime/confirmedRevision"), 0L));
		payload.put("uiActions", List.of());
		return NodeResult.waiting("已提交管理端审批，通过后将自动创建。", "APPROVAL", payload);
	}

	private String precedingConfirmNodeId(DataAgentSkillVersion version, String executeNodeId) {
		if (version == null || !StringUtils.hasText(executeNodeId)) {
			return null;
		}
		try {
			FlowDefinition definition = objectMapper.convertValue(readMap(version.getFlowDefinition()),
					FlowDefinition.class);
			for (FlowNode node : definition.nodes() == null ? List.<FlowNode>of() : definition.nodes()) {
				if (node != null && "confirm".equalsIgnoreCase(node.type()) && executeNodeId.equals(node.next())) {
					return node.id();
				}
			}
		}
		catch (RuntimeException ignored) {
			return null;
		}
		return null;
	}

	private Map<String, Object> callerSnapshot(AgentRequest request) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		if (request == null) {
			return snapshot;
		}
		putIfPresent(snapshot, "userId", request.getUserIdSnapshot());
		putIfPresent(snapshot, "userNickName", request.getUserNickNameSnapshot());
		putIfPresent(snapshot, "tenantId", request.getTenantIdSnapshot());
		putIfPresent(snapshot, "tenantCode", request.getTenantCodeSnapshot());
		putIfPresent(snapshot, "clientId", request.getClientIdSnapshot());
		if (request.getTeamIdsSnapshot() != null) {
			snapshot.put("teamIds", request.getTeamIdsSnapshot());
		}
		putIfPresent(snapshot, "ownerType", request.getOwnerType());
		if (request.getOwnerId() != null) {
			snapshot.put("ownerId", request.getOwnerId());
		}
		if (request.getReleaseId() != null) {
			snapshot.put("releaseId", request.getReleaseId());
		}
		if (request.getDataPermissionSnapshot() != null) {
			snapshot.put("dataPermission", request.getDataPermissionSnapshot());
		}
		return snapshot;
	}

	private void applyApprovalResume(Map<String, Object> context, Map<String, Object> waiting, AgentRequest request) {
		Long waitingId = longValue(waiting.get("approvalId"));
		Long requestedId = request.getFlowAction().payload() == null ? null
				: longValue(request.getFlowAction().payload().get("approvalId"));
		if (waitingId == null || requestedId == null || !waitingId.equals(requestedId)) {
			throw CheckedException.badRequest("审批续跑与当前等待的审批单不匹配");
		}
		contextMapper.set(context, "/runtime/resumeApprovalId", waitingId);
	}

	private void applyApprovalCancel(AgentRequest request, DataAgentFlowInstance instance, Map<String, Object> context,
			Map<String, Object> waiting) {
		Long approvalId = longValue(waiting.get("approvalId"));
		if (approvalService != null && approvalId != null) {
			String tenant = instance.getTenantId();
			if (StringUtils.hasText(tenant)) {
				try {
					approvalService.cancel(tenant, firstText(request.getUserIdSnapshot(), instance.getUserId(), "flow"),
							approvalId, "用户撤回确认提交");
				}
				catch (CheckedException ex) {
					log.info("撤回管理端审批跳过（可能已处理）. approvalId={}, message={}", approvalId, ex.getMessage());
				}
			}
		}
		invalidateApproval(context);
		contextMapper.set(context, "/runtime/resumeApprovalId", null);
		String confirmNodeId = text(waiting.get("confirmNodeId"));
		if (StringUtils.hasText(confirmNodeId)) {
			contextMapper.set(context, NEXT_NODE_OVERRIDE_PATH, confirmNodeId);
		}
	}

	private Long parseLong(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private record NodeResult(boolean waiting, boolean terminal, FlowInstanceStatus status, String nextNodeId,
			String text, String action, Map<String, Object> waitingPayload, String errorCode, String idempotencyKey,
			Map<String, Object> output) {

		private static NodeResult next(String nextNodeId, Map<String, Object> output) {
			return new NodeResult(false, false, FlowInstanceStatus.RUNNING, nextNodeId, "", "CONTINUE", Map.of(),
					null, null, output == null ? Map.of() : output);
		}

		private static NodeResult waiting(String text, String action, Map<String, Object> payload) {
			Map<String, Object> waiting = new LinkedHashMap<>(payload == null ? Map.of() : payload);
			waiting.put("action", action);
			return new NodeResult(true, false, FlowInstanceStatus.WAITING, null, text, action, waiting, null, null,
					Map.of());
		}

		private static NodeResult unknown(String text, Map<String, Object> payload) {
			Map<String, Object> waiting = new LinkedHashMap<>(payload == null ? Map.of() : payload);
			waiting.put("action", "POLL_RESULT");
			return new NodeResult(true, false, FlowInstanceStatus.UNKNOWN, null, text, "UNKNOWN", waiting, null, null,
					Map.of());
		}

		private static NodeResult terminal(FlowInstanceStatus status, String text, String action,
				Map<String, Object> payload, String errorCode, String idempotencyKey) {
			return new NodeResult(false, true, status, null, text, action, payload == null ? Map.of() : payload,
					errorCode, idempotencyKey, Map.of());
		}

	}

}
