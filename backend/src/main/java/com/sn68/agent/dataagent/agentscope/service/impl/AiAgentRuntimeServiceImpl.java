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
package com.sn68.agent.dataagent.agentscope.service.impl;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentScopeMemoryFactory;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeError;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorClassifier;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeEventPublisher;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeExtensionFactory;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProtocolException;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeTerminalClassifier;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeTerminalClassifier.TerminalResult;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeTerminalOutcome;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolFailureException;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.dataagent.agentscope.runtime.AgentUiResponseSupport;
import com.sn68.agent.dataagent.agentscope.runtime.PreparedMemory;
import com.sn68.agent.dataagent.agentscope.runtime.QueryClarifyService;
import com.sn68.agent.dataagent.agentscope.runtime.QueryClarifyService.QueryClarifyAssessment;
import com.sn68.agent.dataagent.agentscope.runtime.AgentScopeToolkitFactory;
import com.sn68.agent.dataagent.agentscope.runtime.ConversationAuthorizationGuard;
import com.sn68.agent.dataagent.agentscope.runtime.ContextCompressionService;
import com.sn68.agent.dataagent.agentscope.runtime.ToolkitAuthorizationFilter;
import com.sn68.agent.dataagent.agentscope.service.AgentScopeModelFactory;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry.RuntimeExecutionStateView;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.template.AgentRunContext;
import com.sn68.agent.dataagent.agentscope.template.AgentRuntimeExtensions;
import com.sn68.agent.dataagent.agentscope.template.ManagedAgent;
import com.sn68.agent.dataagent.agentscope.template.ManagedAgentRegistry;
import com.sn68.agent.dataagent.agentscope.template.PromptBackedReActAgent;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceRuntimeContextCache;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextHook;
import com.sn68.agent.dataagent.agentscope.runtime.AgentScopeStreamingHook;
import com.sn68.agent.dataagent.linking.AppLinkResolver;
import com.sn68.agent.dataagent.linking.LinkKeyExtractor;
import com.sn68.agent.dataagent.linking.SessionLinkCarryover;
import com.sn68.agent.dataagent.agentscope.v2.AgentBudgetExceededException;
import com.sn68.agent.dataagent.agentscope.v2.HarnessAgentFactory;
import com.sn68.agent.dataagent.agentscope.v2.V2AgentStateStore;
import com.sn68.agent.dataagent.agentscope.v2.V2EventToAgentResponseMapper;
import com.sn68.agent.dataagent.agentscope.v2.V2RuntimeSnapshot;
import com.sn68.agent.dataagent.agentscope.v2.V2ToolkitFilter;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.constant.AgentRuntimeConstant;
import com.sn68.agent.dataagent.constant.AgentSessionConstant;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.constant.OrchestrationStatus;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.ExecutionIntentContext;
import com.sn68.agent.dataagent.entity.*;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseSnapshot;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeModelConfigService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseSnapshotResolver;
import com.sn68.agent.dataagent.enums.AgentRequestSourceDict;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.channel.enums.ChannelSessionErrorDict;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.dto.chat.ChatAttachmentDTO;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore;
import com.sn68.agent.dataagent.observability.SessionTraceStore;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.DataAgentSkillMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import com.sn68.agent.dataagent.routing.HybridRouteCoordinator;
import com.sn68.agent.dataagent.routing.RouteUnavailableException;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.routing.model.RouteDecisionType;
import com.sn68.agent.dataagent.routing.model.RouteSelection;
import com.sn68.agent.dataagent.routing.model.RouteTargetType;
import com.sn68.agent.dataagent.routing.model.RouteClarification;
import com.sn68.agent.dataagent.routing.model.RouteClarificationOption;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.hook.service.RuntimeHookDispatcher;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskVersion;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskVersionMapper;
import com.sn68.agent.dataagent.service.agent.orchestration.CollaboratorExecutionResult;
import com.sn68.agent.dataagent.service.agent.orchestration.CollaboratorRoute;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine.CollaboratorStepRunner;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine.EngineFatalException;
import com.sn68.agent.dataagent.service.agent.orchestration.EventDrivenCollaboratorEngine.InFlightHandle;
import com.sn68.agent.dataagent.service.agent.orchestration.OrchestrationRuntimeSupport;
import com.sn68.agent.dataagent.service.agent.orchestration.OrchestrationRuntimeSupport.OrchestrationContext;
import com.sn68.agent.dataagent.service.agent.orchestration.OrchestrationRuntimeSupport.RuntimeTiming;
import com.sn68.agent.dataagent.service.agent.orchestration.PredecessorAlignmentNames;
import com.sn68.agent.dataagent.service.chat.ChatMessageService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.chat.DataChatTurnService;
import com.sn68.agent.dataagent.service.routing.RoutePendingService;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.PendingInteraction;
import com.sn68.agent.dataagent.service.routing.RoutePendingService.PendingResolution;
import com.sn68.agent.dataagent.multimodal.AttachmentAnswerGuard;
import com.sn68.agent.dataagent.multimodal.ExtractCard;
import com.sn68.agent.dataagent.multimodal.TurnArtifact;
import com.sn68.agent.dataagent.multimodal.TurnFusionService;
import com.sn68.agent.dataagent.multimodal.VisionExtractService;
import com.sn68.agent.dataagent.service.file.LocalFileService;
import com.sn68.agent.dataagent.service.memory.LongTermMemoryExtractionService;
import com.sn68.agent.dataagent.service.memory.LongTermMemoryRecallService;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryRecallResultDTO;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfig;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfigParser;
import com.sn68.agent.dataagent.service.analysis.AnalysisResultEmitPolicy;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnDecision;
import com.sn68.agent.dataagent.service.analysis.AnalysisTurnIntentClassifier;
import com.sn68.agent.dataagent.service.report.AnalysisReportService;
import com.sn68.agent.dataagent.service.report.AnalysisUiOptions;
import com.sn68.agent.dataagent.service.report.ReportIntentDetector;
import com.sn68.agent.dataagent.service.report.SkillReportProfileParser;
import com.sn68.agent.dataagent.ui.AgentUiMessage;
import com.sn68.agent.dataagent.service.security.DataAgentOutputSanitizer;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.flow.FlowEngine;
import com.sn68.agent.dataagent.flow.FlowExecutionResult;
import com.sn68.agent.dataagent.im.service.ImUnmatchedRouteCopy;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.execution.SkillExecutionContext;
import com.sn68.agent.dataagent.skill.execution.SkillExecutionResult;
import com.sn68.agent.dataagent.skill.execution.SkillExecutionOutcome;
import com.sn68.agent.dataagent.skill.execution.SkillExecutorRegistry;
import com.sn68.agent.dataagent.skill.execution.ReactRuntimeBudgetPolicy;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResourceLoader;
import com.sn68.agent.dataagent.temporal.AgentTemporalService;
import com.sn68.agent.dataagent.agentscope.tool.SkillRuntimeToolCatalogService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import static com.sn68.agent.dataagent.constant.Constant.STREAM_EVENT_COMPLETE;
import static com.sn68.agent.dataagent.constant.Constant.STREAM_EVENT_ERROR;

/**
 * DataAgent AgentScope 运行时入口，负责编排会话、工具、模型和流式响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentRuntimeServiceImpl implements DataAgentService {

	private static final AgentRuntimeTerminalClassifier TERMINAL_CLASSIFIER = new AgentRuntimeTerminalClassifier();

	private static final String RUNTIME_NODE_NAME = "AgentScopeRuntime";

	private static final String REPORT_NODE_NAME = "ReportGeneratorNode";

	private static final String CONTENT_FORMAT_KEY = "contentFormat";

	private static final String CONTENT_FORMAT_MARKDOWN = "markdown";

	private static final String ANSWER_EXPLAIN_MESSAGE_TYPE = "answer-explain";

	private static final String THINKING_MESSAGE_TYPE = "thinking";

	private static final String MESSAGE_TYPE_MARKDOWN = "markdown";

	private static final String MESSAGE_TYPE_RESULT_SET = "result-set";

	private static final String PERSIST_KEY_METADATA = "persistKey";

	private static final String STREAM_EVENT_USER_MESSAGE = "user_message";

	private static final String STREAM_EVENT_MESSAGE = "message";

	/** 预算到点部分答案末尾追加的可见标记，措辞覆盖墙钟 / token / 费用 / 轮次四类预算。 */
	private static final String BUDGET_PARTIAL_ANSWER_SUFFIX = "\n\n（已达本轮运行预算上限，以上为部分结果）";

	private static final String ROOT_SPAN_NAME = "data-agent.agent.run";

	private static final String AGENT_STATUS_PUBLISHED = "published";

	private static final String AGENT_STATUS_OFFLINE = "offline";

	/** 数字员工运行主体类型（与 AuthorizationOwnerType.DIGITAL_EMPLOYEE 同码，避免跨包依赖）。 */
	private static final String OWNER_TYPE_DIGITAL_EMPLOYEE = "DIGITAL_EMPLOYEE";

	private static final String OWNER_TYPE_CALLER = "CALLER";

	private static final String RUN_MODE_CHAT = "CHAT";

	private static final String CHAT_LEASE_OWNER = buildChatLeaseOwner();

	private static final Duration DEFAULT_CHAT_DEADLINE = Duration.ofMinutes(20);

	/** 最后一名协作者结束后留给合并答案的时间，避免把 120s 预算吃光后无法收口。 */
	private static final Duration ORCHESTRATION_SUMMARY_RESERVE = Duration.ofSeconds(8);

	/**
	 * 本线程最近一次 executeAgent 的运行计时。这里仍用 ThreadLocal，因为编排者在协作者
	 * <b>抛出异常</b>后（{@code executeCollaborator} / {@code resumeOrchestrationInteraction} 的 catch 分支）
	 * 也要读取这份计时来落库步骤耗时，此时没有返回值可承载；`AiDataAgentRuntimeServiceImplTest`
	 * 也按字段名反射校验失败链路的计时。改成返回值前需先解决这两点。
	 */
	private static final ThreadLocal<RuntimeTiming> LAST_RUNTIME_TIMING = new ThreadLocal<>();

	private static final String REPORT_DATA_SUFFICIENCY_PROMPT = """
			报告模式只输出简洁问答结果，不要输出报告标签、报告正文或图表 JSON。
			为后端确定性报告准备足够的本轮数据：
			- 详情、明细、费用构成等问题需要查询支撑结论的必要明细或明细聚合。
			- 前N、TopN、排名N个问题应列满 N 条；实际不足时明确实际条数。
			- 结论只能依据本轮工具返回的结构化结果，不得引用历史明细或编造数据。
			""";

	private final AgentRuntimeRegistry runtimeRegistry;

	private final AgentModelConfigService agentModelConfigService;

	private final DynamicModelFactory dynamicModelFactory;

	private final AgentScopeModelFactory agentScopeModelFactory;

	private final AgentTokenUsageService tokenUsageService;

	private final AgentScopeToolkitFactory agentScopeToolkitFactory;

	private final ManagedAgentRegistry managedAgentRegistry;

	private final AgentRuntimeExtensionFactory agentRuntimeExtensionFactory;

	private final AgentScopeMemoryFactory agentScopeMemoryFactory;

	private final com.sn68.agent.dataagent.service.agent.DataAgentService agentService;

	@Qualifier("agentScopeTracer")
	private final Tracer tracer;

	private final AnswerTraceExplainStore answerTraceExplainStore;

	private final DataChatSessionService chatSessionService;

	private final ChatMessageService chatMessageService;

	private final DataChatTurnService chatTurnService;

	private final LocalFileService localFileService;

	private final ObjectMapper objectMapper;

	private final QueryClarifyService queryClarifyService;

	private final AgentScopeNativeSessionService nativeSessionService;

	private final DataAgentThinkingPermissionService thinkingPermissionService;

	private final DatasourceRuntimeContextCache datasourceRuntimeContextCache;

	private final OrchestrationRuntimeSupport orchestrationRuntimeSupport;

	private final DataAgentProperties dataAgentProperties;

	private final AnalysisReportService analysisReportService;

	private final ReportIntentDetector reportIntentDetector;

	private final ContextCompressionService contextCompressionService;

	private final LongTermMemoryRecallService longTermMemoryRecallService;

	private final LongTermMemoryExtractionService longTermMemoryExtractionService;

	private final DataAgentOutputSanitizer outputSanitizer;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final RuntimeHookDispatcher runtimeHookDispatcher;

	private final AgentRuntimeProgressService runtimeProgressService;

	@Qualifier("orchestrationExecutor")
	private final ExecutorService orchestrationExecutor;

	@Qualifier("dbOperationExecutor")
	private final ExecutorService dbOperationExecutor;

	private final HybridRouteCoordinator routeCoordinator;

	private final RoutePendingService routePendingService;

	private final DataAgentSkillMapper skillMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final FlowEngine flowEngine;

	private final SkillExecutorRegistry skillExecutorRegistry;

	private final SkillVersionResourceLoader skillVersionResourceLoader;

	private final SkillRuntimeToolCatalogService skillRuntimeToolCatalogService;

	private final AgentTemporalService agentTemporalService;

	/** W7 记忆接线：按 runtimeRequestId 解析权威 Run ID，供记忆写入做真实 Run 终态查证。 */
	private final RuntimeRunService runtimeRunService;

	/** CHAT 一轮执行租约（P0）。 */
	private final RuntimeStateService runtimeStateService;

	/** W2 深度接线：事件驱动协作者执行引擎（单分支终态立即释放就绪下游，替代整批 barrier）。 */
	private final EventDrivenCollaboratorEngine eventDrivenCollaboratorEngine;

	/** 任务级 Release 钉死（任务14）：数字员工链路按冻结 Release 快照装配运行配置的事实源解析器。 */
	private final EmployeeReleaseSnapshotResolver employeeReleaseSnapshotResolver;

	/** 数字员工运行时身份：合成 DataAgent 的事实源（草稿提示词/模型），禁止回源 live DataAgent。 */
	private final DigitalEmployeeMapper digitalEmployeeMapper;

	/** 数字员工可用模型：禁止把员工主键写入 agent_model_config.agent_id。 */
	private final EmployeeModelConfigService employeeModelConfigService;

	/** 任务级 Release 钉死（任务14）：按快照冻结 modelConfigId 直接解析运行模型配置（不走用户可选择性校验）。 */
	private final ModelConfigDataService modelConfigDataService;

	/** 任务级 Release 钉死（任务14）：按权威 Run 反查任务台账行（只读），用于任务版本冻结锚校验。 */
	private final AgentTaskRunMapper taskRunMapper;

	/** 任务级 Release 钉死（任务14）：只读消费任务版本冻结列 employeeReleaseId（漂移判据）。 */
	private final AgentTaskVersionMapper taskVersionMapper;

	/** 任务级 Release 钉死（任务14）：请求未携带租户快照时回退授权上下文解析租户（与能力网关同模式）。 */
	private final AuthenticationContext authenticationContext;

	/** PR-4 对话入口 USE 判定（SHADOW 影子记录，ENFORCE 下 PDP deny 拒绝）。 */
	private final ConversationAuthorizationGuard conversationAuthorizationGuard;

	/** PR-4 工具列表部分授权（ENFORCE 租户未授权工具对模型不可见；SHADOW 不过滤）。 */
	private final ToolkitAuthorizationFilter toolkitAuthorizationFilter;

	private final TurnFusionService turnFusionService;

	private final ObjectProvider<VisionExtractService> visionExtractServiceProvider;

	/** IM 文本通道 NO_MATCH 引导：用已绑定技能描述开口，不列出技能名。 */
	private final ImUnmatchedRouteCopy unmatchedRouteCopy;

	private final AppLinkResolver appLinkResolver;

	/**
	 * AgentScope 2.0 Harness 工厂。ReAct 主循环必须走此路径；缺失时失败关闭，禁止回退 {@code ReActAgent.call}。
	 */
	private final ObjectProvider<HarnessAgentFactory> harnessAgentFactoryProvider;

	/** as2: 会话状态。召回近讯用；缺失时近讯为空，召回本身仍执行。 */
	private final ObjectProvider<V2AgentStateStore> v2AgentStateStoreProvider;

	@Override
	public Flux<ServerSentEvent<AgentResponse>> streamSearch(AgentRequest request) {
		Long numericAgentId = parseRequiredAgentId(request.getAgentId());
		Long sessionId = parseRequiredThreadId(request.getThreadId());
		DataChatSession session = chatSessionService.requireSessionForAgent(sessionId, numericAgentId);
		rejectEmployeeStreamBypass(request, session);
		validateStreamSessionAccess(request, session);
		// PR-4 对话入口 USE 判定：SHADOW 仅影子记录；ENFORCE 下 PDP deny / 空主体抛 CheckedException
		// （在 startTurn 等会话写操作之前完成，被拒绝请求不产生轮次残留）。
		// 员工 Facade 已在换票前按 CALLER 判定 USE，此处跳过以免用 Principal 身份重判。
		if (request == null || !request.isEmployeeFacadeStream()) {
			conversationAuthorizationGuard.authorizeConversationUse(request);
		}
		if (turnFusionService.hasAttachments(request) && (request.isHumanFeedback()
				|| StringUtils.hasText(request.getHumanFeedbackContent()))) {
			return Flux.just(ServerSentEvent.builder(AgentResponse.error(request.getAgentId(), request.getThreadId(),
					"澄清反馈回合暂不支持新附件。")).event(STREAM_EVENT_ERROR).build());
		}
		try {
			Sinks.Many<ServerSentEvent<AgentResponse>> sink = Sinks.many().unicast().onBackpressureBuffer();
			if (turnFusionService.hasAttachments(request)) {
				List<ChatAttachmentDTO> attachments = turnFusionService.validatePointers(request.getAttachments());
				request.setAttachments(attachments);
				DataChatMessage userMessage = persistUserMessage(request, attachments);
				emitStreamEvent(sink, ServerSentEvent.builder(userMessageEvent(request, userMessage))
					.event(STREAM_EVENT_USER_MESSAGE)
					.build());
			}
			else if (StringUtils.hasText(request.getQuery())) {
				persistUserMessage(request, List.of());
			}
			graphStreamProcess(sink, request);
			return streamFlux(sink, request);
		}
		catch (RuntimeException ex) {
			return immediateStreamFailure(request, ex);
		}
		catch (LinkageError error) {
			return immediateStreamFailure(request, normalizeRuntimeFailure(error));
		}
	}

	private Flux<ServerSentEvent<AgentResponse>> immediateStreamFailure(AgentRequest request, RuntimeException failure) {
		// The client only receives the classified message; without this the stack trace is lost entirely.
		log.error("AgentScope stream failed before the runtime started. agentId={}, threadId={}, runtimeRequestId={}",
				request == null ? null : request.getAgentId(), request == null ? null : request.getThreadId(),
				request == null ? null : request.getRuntimeRequestId(), failure);
		updateFailedTurnSafely(request, failure);
		if (request != null) {
			runtimeRegistry.finish(request.getThreadId(), request.getRuntimeRequestId());
			try {
				runtimeProgressService.unregister(request);
				clearDatasourceRuntimeCache(request.getThreadId(), request.getRuntimeRequestId());
			}
			catch (RuntimeException | LinkageError cleanupError) {
				log.warn("Failed to clean synchronous AgentScope stream state. threadId={}, runtimeRequestId={}",
						request.getThreadId(), request.getRuntimeRequestId(), cleanupError);
			}
		}
		AgentRuntimeError runtimeError = AgentRuntimeErrorClassifier.classify(failure);
		return Flux.just(ServerSentEvent.builder(AgentResponse.error(request == null ? null : request.getAgentId(),
				request == null ? null : request.getThreadId(), runtimeError.message(),
				request == null ? runtimeError.toMetadata(null) : runtimeError.toMetadata(request.getRuntimeRequestId())))
			.event(STREAM_EVENT_ERROR)
			.build());
	}

	@Override
	public void graphStreamProcess(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest agentRequest) {
		if (agentRequest != null) {
			agentRequest.setStreamSearchRuntime(true);
		}
		initializeRuntimeRequest(agentRequest);
		bindDurableChatRun(agentRequest);
		runtimeProgressService.recordAcceptedUserMessage(agentRequest);
		chatTurnService.startTurn(agentRequest);
		boolean reportMode = isReportMode(agentRequest);
		String threadId = agentRequest.getThreadId();
		String runtimeRequestId = agentRequest.getRuntimeRequestId();
		StreamTextTracker streamTextTracker = new StreamTextTracker();
		ThinkingTraceCollector thinkingTraceCollector = new ThinkingTraceCollector(agentRequest);
		AtomicBoolean streamTerminated = new AtomicBoolean(false);
		if (!runtimeRegistry.tryRegisterExclusive(threadId, runtimeRequestId, agentRequest.getRequestSource(),
				agentRequest.getAgentId())) {
			updateFailedTurnSafely(agentRequest,
					new IllegalStateException(AgentRuntimeErrorCode.SESSION_BUSY.getLabel()));
			AgentRuntimeError runtimeError = AgentRuntimeError.of(AgentRuntimeErrorCode.SESSION_BUSY);
			emitStreamEvent(sink, ServerSentEvent.builder(AgentResponse.error(agentRequest.getAgentId(), threadId,
					runtimeError.message(), runtimeError.toMetadata(runtimeRequestId))).event(STREAM_EVENT_ERROR).build());
			completeStream(sink);
			return;
		}
		runtimeProgressService.register(agentRequest, sink);
		runtimeProgressService.emit(agentRequest, "AGENT_ENTERED", AgentRuntimeProgressService.STATUS_RUNNING);
		AgentRuntimeEventPublisher eventPublisher = response -> {
			if (!runtimeRegistry.isActive(threadId, runtimeRequestId)) {
				return;
			}
			// 思考快照只采集脱敏后的公开响应，避免工具原始结果 JSON（含表/字段物理名）进入思考通道。
			AgentResponse publicResponse = sanitizePublicResponse(response, agentRequest);
			thinkingTraceCollector.record(publicResponse);
			if (publicResponse == null) {
				return;
			}
			if (publicResponse.getTextType() == TextType.TEXT && StringUtils.hasText(publicResponse.getText())
					&& RUNTIME_NODE_NAME.equals(publicResponse.getNodeName())) {
				streamTextTracker.record(publicResponse.getNodeName(), publicResponse.getText());
				runtimeProgressService.recordAssistantDelta(agentRequest, publicResponse.getText());
			}
			if (publicResponse.getTextType() == TextType.RESULT_SET) {
				streamTextTracker.recordResultSet();
			}
			if (AgentUiResponseSupport.isStructuredUiResponse(publicResponse)) {
				streamTextTracker.recordStructuredUiMessage();
			}
			emitStreamEvent(sink, ServerSentEvent.builder(publicResponse).event(STREAM_EVENT_MESSAGE).build());
		};

		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		Mono.fromCallable(() -> asyncContextBridge.callWith(asyncContext,
				() -> executeAgent(agentRequest, eventPublisher, null).answer()))
			.doFinally(signalType -> asyncContextBridge.runWith(asyncContext, () -> {
				runtimeRegistry.finish(threadId, runtimeRequestId);
				runtimeProgressService.unregister(agentRequest);
				clearDatasourceRuntimeCache(threadId, runtimeRequestId);
			}))
			.subscribeOn(Schedulers.boundedElastic())
			.subscribe(result -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					persistThinkingTraceSnapshot(agentRequest, thinkingTraceCollector);
					emitSuccess(sink, agentRequest, result, streamTextTracker, reportMode, streamTerminated);
				}
				catch (RuntimeException | LinkageError error) {
					emitError(sink, agentRequest, normalizeRuntimeFailure(error), streamTerminated);
				}
			}), error -> asyncContextBridge.runWith(asyncContext, () -> {
				try {
					persistThinkingTraceSnapshot(agentRequest, thinkingTraceCollector);
				}
				catch (RuntimeException | LinkageError snapshotError) {
					error.addSuppressed(snapshotError);
				}
				emitError(sink, agentRequest, error, streamTerminated);
			}));
	}

	@Override
	public boolean stopStreamProcessing(String threadId, String runtimeRequestId) {
		return runtimeRegistry.markCancelled(threadId, runtimeRequestId);
	}

	@Override
	public void authorizeRuntimeControl(AgentRequest request) {
		if (request == null) {
			throw CheckedException.badRequest("threadId不能为空");
		}
		Long sessionId = parseRequiredThreadId(request.getThreadId());
		DataChatSession session = chatSessionService.findBySessionId(sessionId);
		if (session == null) {
			throw CheckedException.notFound(ChannelSessionErrorDict.SESSION_NOT_FOUND.getValue(),
					ChannelSessionErrorDict.SESSION_NOT_FOUND.getLabel());
		}
		validateSessionRuntimeAccess(request, session);
	}

	@Override
	public RuntimeExecutionStateView activeRuntime(String threadId) {
		return runtimeRegistry.activeRequest(threadId);
	}

	@Override
	public RuntimeRunResp activeDurableChatRun(AgentRequest request) {
		authorizeRuntimeControl(request);
		String tenantId = request.getTenantIdSnapshot();
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		return runtimeRunService.findActiveChatRun(tenantId, request.getThreadId());
	}

	private boolean shouldEmitStreamEvent(ServerSentEvent<AgentResponse> event) {
		if (STREAM_EVENT_COMPLETE.equals(event.event()) || STREAM_EVENT_ERROR.equals(event.event())) {
			return true;
		}
		if (AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS.equals(event.event())) {
			return true;
		}
		if (STREAM_EVENT_USER_MESSAGE.equals(event.event())) {
			return true;
		}
		if (event.data() != null && AgentUiResponseSupport.isStructuredUiResponse(event.data())) {
			return true;
		}
		return event.data() != null && event.data().getText() != null && !event.data().getText().isEmpty();
	}

	private Flux<ServerSentEvent<AgentResponse>> streamFlux(Sinks.Many<ServerSentEvent<AgentResponse>> sink,
			AgentRequest request) {
		return sink.asFlux()
			.onBackpressureLatest()
			.filter(this::shouldEmitStreamEvent)
			.filter(event -> thinkingPermissionService.shouldExposeStreamEvent(event, false))
			.doOnSubscribe(subscription -> log.info("Client subscribed to aiagent stream, threadId: {}",
					request.getThreadId()))
			.doOnCancel(() -> log.info("Client detached from aiagent stream, threadId: {}", request.getThreadId()))
			.doOnError(error -> log.error("Error occurred during aiagent streaming, threadId: {}",
					request.getThreadId(), error))
			.doOnComplete(() -> log.info("Aiagent stream closed, threadId: {}", request.getThreadId()));
	}

	private void fuseTurnAttachments(AgentRequest request, ModelConfigDTO modelConfig) {
		TurnArtifact artifact = turnFusionService.fuseOrReuse(request);
		ExtractApply extractApply = applyVisionExtract(request, modelConfig, artifact);
		artifact = extractApply.artifact();
		if (artifact != null) {
			request.setTurnArtifactId(artifact.artifactId());
			runtimeProgressService.emitFusionTrace(request, artifact, extractApply.card(), extractApply.durationMs());
		}
		validateVisionModel(request, modelConfig);
	}

	private ExtractApply applyVisionExtract(AgentRequest request, ModelConfigDTO modelConfig, TurnArtifact artifact) {
		ExtractCard card = request == null ? null : request.getExtractCard();
		if (request == null || artifact == null || !artifact.hasPixelImage() || visionExtractServiceProvider == null) {
			return new ExtractApply(artifact, card, null);
		}
		VisionExtractService extractor = visionExtractServiceProvider.getIfAvailable();
		if (extractor == null) {
			return new ExtractApply(artifact, card, null);
		}
		VisionExtractService.ExtractResult extracted = extractor.extract(request, modelConfig, artifact);
		if (extracted == null || extracted.card() == null) {
			return new ExtractApply(artifact, card, extracted == null ? null : extracted.durationMs());
		}
		card = extracted.card();
		request.setExtractCard(card);
		artifact = artifact.withExtractCard(card);
		if (!card.needsVisionFollowup() && !card.hasUnreadReason()) {
			artifact = artifact.toPointerArtifact();
		}
		request.setTurnArtifact(artifact);
		turnFusionService.persistSnapshot(request, artifact);
		return new ExtractApply(artifact, card, extracted.durationMs());
	}

	private record ExtractApply(TurnArtifact artifact, ExtractCard card, Long durationMs) {
	}

	private DataChatMessage persistUserMessage(AgentRequest request, List<ChatAttachmentDTO> attachments) {
		Long sessionId = parseRequiredThreadId(request.getThreadId());
		try {
			DataChatMessage existing = findRecentDuplicateUserMessage(sessionId, request.getQuery());
			if (existing != null) {
				return existing;
			}
			List<ChatAttachmentDTO> persistedAttachments = attachments == null ? List.of() : attachments.stream()
				.map(attachment -> ChatAttachmentDTO.builder()
					.type(attachment.getType())
					.storageKey(attachment.getStorageKey())
					.contentType(attachment.getContentType())
					.fileName(attachment.getFileName())
					.size(attachment.getSize())
					.build())
				.toList();
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("attachments", persistedAttachments);
			DataChatMessage savedMessage = chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(sessionId)
				.role("user")
				.content(request.getQuery())
				.messageType("text")
				.metadata(objectMapper.writeValueAsString(metadata))
				.build(), parseRequiredAgentId(request.getAgentId()));
			chatSessionService.updateSessionTime(sessionId, parseRequiredAgentId(request.getAgentId()));
			return savedMessage;
		}
		catch (Exception ex) {
			throw CheckedException.fail("保存用户消息失败：" + ex.getMessage());
		}
	}

	private AgentResponse userMessageEvent(AgentRequest request, DataChatMessage userMessage) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("message", userMessage);
		return AgentResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.nodeName(RUNTIME_NODE_NAME)
			.textType(TextType.JSON)
			.text("")
			.metadata(metadata)
			.build();
	}

	private void stopRuntimeIfPossible(AgentRequest request) {
		if (request.getThreadId() != null && request.getRuntimeRequestId() != null) {
			stopStreamProcessing(request.getThreadId(), request.getRuntimeRequestId());
		}
	}

	private void emitSuccess(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request, String result,
			StreamTextTracker streamTextTracker, boolean reportMode, AtomicBoolean streamTerminated) {
		String threadId = request.getThreadId();
		String runtimeRequestId = request.getRuntimeRequestId();
		if (runtimeRegistry.isCancelled(threadId, runtimeRequestId)) {
			emitComplete(sink, request, streamTerminated);
			return;
		}
		if (!runtimeRegistry.isActive(threadId, runtimeRequestId)) {
			emitComplete(sink, request, streamTerminated);
			return;
		}
		String safeResult = sanitizePublicText(result, request);
		AnswerTraceExplainStore.AnswerTraceExplainView explain = answerTraceExplainStore
			.getExplain(threadId, runtimeRequestId)
			.orElse(null);
		String completeResult = analysisReportService.ensureCompleteTopNAnswer(safeResult, explain);
		if (!streamTextTracker.hasResultSet()) {
			emitPublicResultSets(sink, request, explain);
		}
		List<Map<String, Object>> followUps = analysisFollowUps(request, explain);
		if (shouldEmitFinalResponse(completeResult, streamTextTracker)) {
			Map<String, Object> metadata = buildFinalAnswerMetadata(request);
			if (!reportMode) {
				putAnalysisFollowUps(metadata, followUps);
			}
			AgentResponse response = AgentResponse.builder()
				.agentId(request.getAgentId())
				.threadId(threadId)
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text(completeResult)
				.metadata(sanitizePublicMetadata(metadata, request))
				.build();
			emitStreamEvent(sink, ServerSentEvent.builder(response).event(STREAM_EVENT_MESSAGE).build());
		}
		else if (completeResult.length() > safeResult.trim().length()) {
			emitRuntimeText(sink, request, completeResult.substring(safeResult.trim().length()).trim());
		}
		if (reportMode) {
			emitReportContent(sink, request, analysisReportService.generateDeterministicReport(explain), followUps);
		}
		persistPublicTurnMessages(request, completeResult, explain, streamTextTracker);
		finishDurableChatRun(request, true, completeResult, null, null);
		emitComplete(sink, request, streamTerminated);
	}

	private void emitPublicResultSets(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request,
			AnswerTraceExplainStore.AnswerTraceExplainView explain) {
		List<String> resultSetJsons = new ArrayList<>(analysisReportService.buildPublicResultSetJsons(explain));
		if (resultSetJsons.isEmpty()) {
			String resultSetJson = analysisReportService.buildPublicResultSetJson(explain);
			if (StringUtils.hasText(resultSetJson)) {
				resultSetJsons.add(resultSetJson);
			}
		}
		for (String resultSetJson : resultSetJsons) {
			if (!StringUtils.hasText(resultSetJson)) {
				continue;
			}
			AgentResponse response = AgentResponse.builder()
				.agentId(request.getAgentId())
				.threadId(request.getThreadId())
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.RESULT_SET)
				.text(resultSetJson)
				.metadata(sanitizePublicMetadata(buildFinalAnswerMetadata(request), request))
				.build();
			emitStreamEvent(sink, ServerSentEvent.builder(sanitizePublicResponse(response, request))
				.event(STREAM_EVENT_MESSAGE)
				.build());
		}
	}

	private void emitRuntimeText(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request, String text) {
		AgentResponse response = AgentResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.nodeName(RUNTIME_NODE_NAME)
			.textType(TextType.TEXT)
			.text(sanitizePublicText(text, request))
			.metadata(sanitizePublicMetadata(buildFinalAnswerMetadata(request), request))
			.build();
		emitStreamEvent(sink, ServerSentEvent.builder(response).event(STREAM_EVENT_MESSAGE).build());
	}

	private Map<String, Object> buildLongTermMemoryMetadata(AgentRequest request) {
		AgentMemoryRecallResultDTO result = request == null ? null : request.getMemoryRecallResult();
		if (result == null || result.injectedCount() <= 0 || result.hits() == null || result.hits().isEmpty()) {
			return null;
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("longTermMemory",
				Map.of("referencedCount", result.injectedCount(), "estimatedTokens", result.estimatedTokens(), "hits",
						result.hits()
							.stream()
							.map(hit -> {
								Map<String, Object> hitMap = new LinkedHashMap<>();
								hitMap.put("type", hit.memoryType());
								hitMap.put("summary", hit.summary());
								hitMap.put("sourceTime", hit.sourceTime());
								hitMap.put("similarity", hit.similarity());
								hitMap.put("injected", hit.injected());
								return hitMap;
							})
							.toList()));
		return metadata;
	}

	private Map<String, Object> buildFinalAnswerMetadata(AgentRequest request) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		Map<String, Object> memoryMetadata = buildLongTermMemoryMetadata(request);
		if (memoryMetadata != null) {
			metadata.putAll(memoryMetadata);
		}
		if (StringUtils.hasText(request.getRuntimeRequestId())) {
			metadata.put("runtimeRequestId", request.getRuntimeRequestId());
		}
		if (request.getDurableRunId() != null) {
			metadata.put("runtimeRunId", request.getDurableRunId());
		}
		metadata.put(CONTENT_FORMAT_KEY, CONTENT_FORMAT_MARKDOWN);
		return metadata;
	}

	private void emitReportContent(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request,
			String content, List<Map<String, Object>> followUps) {
		Map<String, Object> metadata = buildFinalAnswerMetadata(request);
		putAnalysisFollowUps(metadata, followUps);
		emitStreamEvent(sink, ServerSentEvent.builder(AgentResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.nodeName(REPORT_NODE_NAME)
			.textType(TextType.MARK_DOWN)
			.text(sanitizePublicReportText(content, request))
			.metadata(sanitizePublicMetadata(metadata, request))
			.build()).event(STREAM_EVENT_MESSAGE).build());
	}

	private List<Map<String, Object>> analysisFollowUps(AgentRequest request,
			AnswerTraceExplainStore.AnswerTraceExplainView explain) {
		AnalysisConfig config = routedAnalysisConfig(request);
		boolean reportMode = request != null && "report".equalsIgnoreCase(request.getResponseMode());
		if (!AnalysisResultEmitPolicy.shouldEmit(request == null ? null : request.getRoutedSkillExecutionMode(),
				reportMode, config)) {
			return List.of();
		}
		boolean hasAttachment = request != null && request.getAttachments() != null
				&& !request.getAttachments().isEmpty();
		AnalysisUiOptions options = analysisReportService.optionsForTurn(request == null ? null : request.getQuery(),
				hasAttachment, config, explain);
		List<Map<String, Object>> followUps = analysisReportService.buildAnalysisFollowUps(explain, options);
		return followUps == null ? List.of() : followUps;
	}

	private static void putAnalysisFollowUps(Map<String, Object> metadata, List<Map<String, Object>> followUps) {
		if (metadata == null || followUps == null || followUps.isEmpty()) {
			return;
		}
		metadata.put("analysisFollowUps", List.copyOf(followUps));
	}

	private AnalysisConfig routedAnalysisConfig(AgentRequest request) {
		if (request == null || request.getRoutedSkillVersionId() == null) {
			return AnalysisConfig.empty();
		}
		DataAgentSkillVersion version = skillVersionMapper.selectById(request.getRoutedSkillVersionId());
		if (version == null || !StringUtils.hasText(version.getAnalysisConfig())) {
			return AnalysisConfig.empty();
		}
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> draft = objectMapper.readValue(version.getAnalysisConfig(), Map.class);
			return new AnalysisConfigParser().parse(draft).config();
		}
		catch (Exception ex) {
			log.warn("Failed to parse routed analysisConfig. skillVersionId={}", request.getRoutedSkillVersionId(),
					ex);
			return AnalysisConfig.empty();
		}
	}

	private void emitComplete(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request,
			AtomicBoolean streamTerminated) {
		if (!streamTerminated.compareAndSet(false, true)) {
			return;
		}
		emitStreamEvent(sink, ServerSentEvent.builder(AgentResponse.complete(request.getAgentId(), request.getThreadId()))
			.event(STREAM_EVENT_COMPLETE)
			.build());
		completeStream(sink);
	}

	private AgentResponse sanitizePublicResponse(AgentResponse response, AgentRequest request) {
		return outputSanitizer.sanitizeAgentResponse(response, currentExplain(request).orElse(null));
	}

	private String sanitizePublicText(String text, AgentRequest request) {
		return AttachmentAnswerGuard.apply(request,
				outputSanitizer.sanitizeText(text, currentExplain(request).orElse(null)));
	}

	private String finalizePublicAnswer(AgentRequest request, String answer) {
		String sanitized = sanitizePublicText(answer, request);
		AnswerTraceExplainStore.AnswerTraceExplainView explain = currentExplain(request).orElse(null);
		if (isTextChannel(request)) {
			return analysisReportService.completeTextChannelAnswer(sanitized, explain);
		}
		return analysisReportService.ensureCompleteTopNAnswer(sanitized, explain);
	}

	private boolean isTextChannel(AgentRequest request) {
		if (request == null) {
			return false;
		}
		if (request.supportsTextCommands()) {
			return true;
		}
		return AgentRequestSourceDict.IM.getValue().equalsIgnoreCase(request.getRequestSource());
	}

	private String sanitizePublicReportText(String text, AgentRequest request) {
		return outputSanitizer.sanitizeTextPreservingEcharts(text, currentExplain(request).orElse(null));
	}

	private Map<String, Object> sanitizePublicMetadata(Map<String, Object> metadata, AgentRequest request) {
		return outputSanitizer.sanitizeMetadata(metadata, currentExplain(request).orElse(null));
	}

	private Optional<AnswerTraceExplainStore.AnswerTraceExplainView> currentExplain(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return Optional.empty();
		}
		return answerTraceExplainStore.getExplain(request.getThreadId(), request.getRuntimeRequestId());
	}

	private boolean shouldEmitFinalResponse(String result, StreamTextTracker streamTextTracker) {
		return StringUtils.hasText(result) && !streamTextTracker.hasStructuredUiMessage()
				&& !streamTextTracker.containsFinalAnswer(result);
	}

	private DataChatMessage findRecentDuplicateUserMessage(Long sessionId, String query) {
		if (sessionId == null || !StringUtils.hasText(query)) {
			return null;
		}
		List<DataChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return null;
		}
		for (int index = messages.size() - 1; index >= 0; index--) {
			DataChatMessage message = messages.get(index);
			if (message == null) {
				continue;
			}
			String messageType = message.getMessageType();
			if (AgentSessionConstant.MESSAGE_TYPE_ANSWER_EXPLAIN.equals(messageType)
					|| "thinking".equals(messageType)) {
				continue;
			}
			if ("user".equalsIgnoreCase(message.getRole()) && query.equals(message.getContent())) {
				Instant created = message.getCreateTime();
				if (created != null && created.isBefore(Instant.now().minus(Duration.ofMinutes(2)))) {
					return null;
				}
				return message;
			}
			return null;
		}
		return null;
	}

	private void persistPublicTurnMessages(AgentRequest request, String completeResult,
			AnswerTraceExplainStore.AnswerTraceExplainView explain, StreamTextTracker streamTextTracker) {
		Long sessionId = parseThreadIdOrNull(request.getThreadId());
		if (sessionId == null || chatSessionService.findBySessionId(sessionId) == null) {
			return;
		}
		Long agentId = parseRequiredAgentId(request.getAgentId());
		String runtimeRequestId = request.getRuntimeRequestId();
		try {
			Set<String> persistedKeys = persistedTurnMessageKeys(sessionId, runtimeRequestId);
			List<String> resultSetJsons = explain == null ? List.of()
					: new ArrayList<>(analysisReportService.buildPublicResultSetJsons(explain));
			if (resultSetJsons.isEmpty() && explain != null) {
				String resultSetJson = analysisReportService.buildPublicResultSetJson(explain);
				if (StringUtils.hasText(resultSetJson)) {
					resultSetJsons = List.of(resultSetJson);
				}
			}
			int resultSetSeq = 0;
			for (String resultSetJson : resultSetJsons) {
				if (!StringUtils.hasText(resultSetJson)) {
					continue;
				}
				String persistKey = turnMessageKey(MESSAGE_TYPE_RESULT_SET, runtimeRequestId, resultSetSeq);
				if (persistKey != null && persistedKeys.contains(persistKey)) {
					resultSetSeq++;
					continue;
				}
				if (persistKey != null) {
					persistedKeys.add(persistKey);
				}
				chatMessageService.saveMessage(DataChatMessage.builder()
					.sessionId(sessionId)
					.role("assistant")
					.content(resultSetJson)
					.messageType(MESSAGE_TYPE_RESULT_SET)
					.metadata(turnMessageMetadata(runtimeRequestId, MESSAGE_TYPE_RESULT_SET, resultSetSeq))
					.build(), agentId);
				resultSetSeq++;
			}
			// 落库门闩与 SSE 解耦：终答正文只要存在就保证刷新可见，是否再推终态 SSE 由
			// shouldEmitFinalResponse 单独决定；tracker 有流式文本不再阻止落库。
			String markdownKey = turnMessageKey(MESSAGE_TYPE_MARKDOWN, runtimeRequestId, null);
			if (StringUtils.hasText(completeResult) && !streamTextTracker.hasStructuredUiMessage()
					&& (markdownKey == null || !persistedKeys.contains(markdownKey))) {
				chatMessageService.saveMessage(DataChatMessage.builder()
					.sessionId(sessionId)
					.role("assistant")
					.content(completeResult)
					.messageType(MESSAGE_TYPE_MARKDOWN)
					.metadata(turnMessageMetadata(runtimeRequestId, MESSAGE_TYPE_MARKDOWN, null))
					.build(), agentId);
			}
		}
		catch (Exception ex) {
			log.warn("Failed to persist assistant turn messages. sessionId={}, runtimeRequestId={}",
					request.getThreadId(), request.getRuntimeRequestId(), ex);
		}
	}

	/**
	 * 本轮公开消息的去重键：同一 runtimeRequestId 重放/重订阅时不得双写可见消息。
	 */
	private String turnMessageKey(String messageType, String runtimeRequestId, Integer seq) {
		if (!StringUtils.hasText(runtimeRequestId)) {
			return null;
		}
		return messageType + ":" + runtimeRequestId + (seq == null ? "" : ":" + seq);
	}

	/**
	 * 扫描会话内已有 markdown/result-set 行，收集属于本轮 runtimeRequestId 的去重键。
	 * 历史行无键或 metadata 解析失败时视为未命中：宁可重插，不可丢终答。
	 */
	private Set<String> persistedTurnMessageKeys(Long sessionId, String runtimeRequestId) {
		Set<String> keys = new HashSet<>();
		if (sessionId == null || !StringUtils.hasText(runtimeRequestId)) {
			return keys;
		}
		List<DataChatMessage> messages = chatMessageService.findBySessionId(sessionId);
		if (messages == null || messages.isEmpty()) {
			return keys;
		}
		for (DataChatMessage message : messages) {
			if (message == null || !StringUtils.hasText(message.getMetadata())) {
				continue;
			}
			String messageType = message.getMessageType();
			if (!MESSAGE_TYPE_MARKDOWN.equals(messageType) && !MESSAGE_TYPE_RESULT_SET.equals(messageType)) {
				continue;
			}
			try {
				Map<?, ?> metadata = objectMapper.readValue(message.getMetadata(), Map.class);
				if (!runtimeRequestId.equals(metadata.get("runtimeRequestId"))) {
					continue;
				}
				if (metadata.get(PERSIST_KEY_METADATA) instanceof String persistKey) {
					keys.add(persistKey);
				}
			}
			catch (Exception ex) {
				log.debug("Skip unparsable chat message metadata during turn dedup. sessionId={}, messageType={}",
						sessionId, messageType);
			}
		}
		return keys;
	}

	/**
	 * 公开轮次消息 metadata：只带去重与前端对齐所需的最小字段，不复用
	 * buildFinalAnswerMetadata，避免记忆摘要等内部信息随可见行外泄。
	 */
	private String turnMessageMetadata(String runtimeRequestId, String messageType, Integer seq) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (StringUtils.hasText(runtimeRequestId)) {
			metadata.put("runtimeRequestId", runtimeRequestId);
			metadata.put(PERSIST_KEY_METADATA, turnMessageKey(messageType, runtimeRequestId, seq));
		}
		metadata.put(CONTENT_FORMAT_KEY, messageType);
		try {
			return objectMapper.writeValueAsString(metadata);
		}
		catch (Exception ex) {
			// metadata 失败不阻断正文落库：刷新可见优先于去重键。
			log.warn("Failed to serialize turn message metadata. runtimeRequestId={}, messageType={}",
					runtimeRequestId, messageType, ex);
			return null;
		}
	}

	private void emitError(Sinks.Many<ServerSentEvent<AgentResponse>> sink, AgentRequest request, Throwable error,
			AtomicBoolean streamTerminated) {
		if (!streamTerminated.compareAndSet(false, true)) {
			return;
		}
		String threadId = request.getThreadId();
		String runtimeRequestId = request.getRuntimeRequestId();
		try {
			if (runtimeRegistry.isCancelled(threadId, runtimeRequestId)) {
				updateCancelledTurnSafely(request);
				log.info("AgentScope runtime cancelled, suppress error propagation. threadId={}, runtimeRequestId={}",
						threadId, runtimeRequestId);
				emitStreamEvent(sink,
						ServerSentEvent.builder(AgentResponse.complete(request.getAgentId(), threadId))
							.event(STREAM_EVENT_COMPLETE)
							.build());
				return;
			}
			updateFailedTurnSafely(request, error);
			AgentRuntimeError runtimeError = AgentRuntimeErrorClassifier.classify(error);
			String publicText = runtimeError.message();
			finishDurableChatRun(request, false, publicText, runtimeError.code().getValue(), publicText);
			persistFailureAnswer(request, publicText);
			log.error("AgentScope runtime failed, threadId={}, runtimeRequestId={}, errorCode={}, diagnosticCode={}",
					threadId, runtimeRequestId, runtimeError.code().getValue(), runtimeError.diagnosticCode(), error);
			Map<String, Object> metadata = runtimeError.toMetadata(runtimeRequestId);
			metadata.remove("discardPartialOutput");
			emitStreamEvent(sink, ServerSentEvent
				.builder(AgentResponse.builder()
					.agentId(request.getAgentId())
					.threadId(threadId)
					.nodeName(RUNTIME_NODE_NAME)
					.textType(TextType.TEXT)
					.text(publicText)
					.metadata(metadata)
					.build())
				.event(STREAM_EVENT_MESSAGE)
				.build());
			emitStreamEvent(sink, ServerSentEvent
				.builder(AgentResponse.complete(request.getAgentId(), threadId))
				.event(STREAM_EVENT_COMPLETE)
				.build());
		}
		finally {
			completeStream(sink);
		}
	}

	private void persistFailureAnswer(AgentRequest request, String publicText) {
		if (request == null || !StringUtils.hasText(publicText)) {
			return;
		}
		Long sessionId = parseThreadIdOrNull(request.getThreadId());
		if (sessionId == null || chatSessionService.findBySessionId(sessionId) == null) {
			return;
		}
		String runtimeRequestId = request.getRuntimeRequestId();
		String markdownKey = turnMessageKey(MESSAGE_TYPE_MARKDOWN, runtimeRequestId, null);
		try {
			Set<String> persistedKeys = persistedTurnMessageKeys(sessionId, runtimeRequestId);
			if (markdownKey != null && persistedKeys.contains(markdownKey)) {
				return;
			}
			chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(sessionId)
				.role("assistant")
				.content(publicText)
				.messageType(MESSAGE_TYPE_MARKDOWN)
				.metadata(turnMessageMetadata(runtimeRequestId, MESSAGE_TYPE_MARKDOWN, null))
				.build(), parseRequiredAgentId(request.getAgentId()));
		}
		catch (Exception ex) {
			log.warn("Failed to persist failure answer. sessionId={}, runtimeRequestId={}", request.getThreadId(),
					runtimeRequestId, ex);
		}
	}

	private boolean canUseSearchWrapUpAsAnswer(AgentRuntimeToolMetrics toolMetrics, TerminalResult terminalResult) {
		if (toolMetrics == null || terminalResult == null
				|| !AgentRuntimeErrorCode.MODEL_EMPTY_COMPLETION.getValue().equals(terminalResult.errorCode())) {
			return false;
		}
		return toolMetrics.searchExecutionWrappedUp();
	}

	private void updateCancelledTurnSafely(AgentRequest request) {
		try {
			chatTurnService.cancelTurn(request);
		}
		catch (RuntimeException | LinkageError error) {
			log.error("Failed to persist cancelled AgentScope turn. threadId={}, runtimeRequestId={}",
					request.getThreadId(), request.getRuntimeRequestId(), error);
		}
	}

	private void updateFailedTurnSafely(AgentRequest request, Throwable failure) {
		try {
			chatTurnService.failTurn(request, failure);
		}
		catch (RuntimeException | LinkageError error) {
			log.error("Failed to persist failed AgentScope turn. threadId={}, runtimeRequestId={}", request.getThreadId(),
					request.getRuntimeRequestId(), error);
		}
	}

	public String executeAgentOnce(AgentRequest request) {
		return executeSingleTurn(request, null);
	}

	private String executeSingleTurn(AgentRequest request,
			Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector) {
		initializeRuntimeRequest(request);
		bindDurableChatRun(request);
		runtimeProgressService.recordAcceptedUserMessage(request);
		// PR-4 单轮入口 USE 判定（与 streamSearch 同一守卫；协作者子请求不经此入口，无重复判定）。
		conversationAuthorizationGuard.authorizeConversationUse(request);
		String threadId = request.getThreadId();
		String runtimeRequestId = request.getRuntimeRequestId();
		if (!runtimeRegistry.tryRegisterExclusive(threadId, runtimeRequestId, request.getRequestSource(),
				request.getAgentId())) {
			chatTurnService.failTurn(request, new IllegalStateException(AgentRuntimeErrorCode.SESSION_BUSY.getLabel()));
			throw new IllegalStateException(AgentRuntimeErrorCode.SESSION_BUSY.getLabel());
		}
		try {
			chatTurnService.startTurn(request);
			String answer = executeAgent(request, null, uiCollector).answer();
			finishDurableChatRun(request, true, answer, null, null);
			return answer;
		}
		catch (RuntimeException ex) {
			if (runtimeRegistry.isCancelled(threadId, runtimeRequestId)) {
				updateCancelledTurnSafely(request);
			}
			else {
				updateFailedTurnSafely(request, ex);
				finishDurableChatRun(request, false, null, "RUNTIME_FAILED", ex.getMessage());
			}
			throw ex;
		}
		catch (LinkageError error) {
			RuntimeException failure = normalizeRuntimeFailure(error);
			if (runtimeRegistry.isCancelled(threadId, runtimeRequestId)) {
				updateCancelledTurnSafely(request);
			}
			else {
				updateFailedTurnSafely(request, failure);
				finishDurableChatRun(request, false, null, "RUNTIME_FAILED", failure.getMessage());
			}
			throw failure;
		}
		finally {
			runtimeRegistry.finish(threadId, runtimeRequestId);
			clearDatasourceRuntimeCache(threadId, runtimeRequestId);
		}
	}

	private String executeAgent(AgentRequest request) {
		return executeAgent(request, null, null).answer();
	}

	private void initializeRuntimeRequest(AgentRequest request) {
		if (!StringUtils.hasText(request.getThreadId())) {
			throw new IllegalArgumentException("threadId must not be empty");
		}
		if (!StringUtils.hasText(request.getRuntimeRequestId())) {
			request.setRuntimeRequestId(UUID.randomUUID().toString());
		}
		if (!StringUtils.hasText(request.getRootRuntimeRequestId())) {
			request.setRootRuntimeRequestId(request.getRuntimeRequestId());
		}
		if (request.getDurableRunId() == null) {
			request.setDurableRunId(resolveAuthoritativeRunId(request));
		}
	}

	/**
	 * P0：把问答这一轮接到 agent_runtime_run。无租户或协作者子请求不建 Run（测试/内部路径保持原语义）。
	 */
	private void bindDurableChatRun(AgentRequest request) {
		if (request == null || request.isCollaboratorChild() || !StringUtils.hasText(request.getThreadId())) {
			return;
		}
		String tenantId = request.getTenantIdSnapshot();
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			return;
		}
		if (request.getDurableRunId() != null) {
			claimChatRunLease(request);
			return;
		}
		RuntimeRunResp active = runtimeRunService.findActiveChatRun(tenantId, request.getThreadId());
		if (active != null) {
			if (isChatTurnContinuation(request) && isWaitingChat(active.state())) {
				bindExistingChatRun(request, active);
				claimChatRunLease(request);
				return;
			}
			throw new IllegalStateException(AgentRuntimeErrorCode.SESSION_BUSY.getLabel());
		}
		if (!StringUtils.hasText(request.getRuntimeRequestId())) {
			request.setRuntimeRequestId(UUID.randomUUID().toString());
		}
		RuntimeRunResp created = runtimeRunService.startInteractiveRun(tenantId, request.getUserIdSnapshot(),
				new RuntimeRunCreateReq("CHAT:" + request.getThreadId() + ":" + request.getRuntimeRequestId(),
						StringUtils.hasText(request.getOwnerType()) ? request.getOwnerType() : OWNER_TYPE_CALLER,
						resolveChatOwnerId(request),
						OWNER_TYPE_DIGITAL_EMPLOYEE.equals(request.getOwnerType()) ? request.getOwnerId() : null,
						request.getReleaseId(), parseAgentIdOrNull(request.getAgentId()), request.getThreadId(),
						request.getQuery(), RUN_MODE_CHAT, chatDeadlineSeconds(),
						StringUtils.hasText(request.getRequestSource()) ? request.getRequestSource() : RUN_MODE_CHAT));
		if (created == null || created.id() == null) {
			log.warn("会话运行未创建, 本轮不挂接权威 Run. threadId={}", request.getThreadId());
			return;
		}
		bindExistingChatRun(request, created);
		claimChatRunLease(request);
	}

	private void bindExistingChatRun(AgentRequest request, RuntimeRunResp run) {
		request.setDurableRunId(run.id());
		if (!StringUtils.hasText(request.getRuntimeRequestId()) && StringUtils.hasText(run.runtimeRequestId())) {
			request.setRuntimeRequestId(run.runtimeRequestId());
		}
	}

	private void claimChatRunLease(AgentRequest request) {
		if (request == null || request.getDurableRunId() == null || runtimeStateService == null) {
			return;
		}
		Long fence = runtimeStateService.acquireRunLease(request.getDurableRunId(), CHAT_LEASE_OWNER,
				chatLeaseDuration());
		if (fence == null) {
			throw new IllegalStateException(AgentRuntimeErrorCode.SESSION_BUSY.getLabel());
		}
		request.setFenceToken(fence);
		request.setLeaseOwner(CHAT_LEASE_OWNER);
	}

	private void finishDurableChatRun(AgentRequest request, boolean success, String answer, String errorCode,
			String errorMessage) {
		if (request == null || request.getDurableRunId() == null || !StringUtils.hasText(request.getTenantIdSnapshot())) {
			return;
		}
		try {
			if (success) {
				runtimeRunService.succeedInteractiveRun(request.getTenantIdSnapshot(), request.getDurableRunId(),
						answer == null ? "" : answer);
			}
			else {
				runtimeRunService.failInteractiveRun(request.getTenantIdSnapshot(), request.getDurableRunId(),
						errorCode == null ? "RUNTIME_FAILED" : errorCode,
						errorMessage);
			}
		}
		catch (RuntimeException ex) {
			log.error("会话运行终态回写失败. runtimeRunId={}, success={}", request.getDurableRunId(), success, ex);
		}
	}

	private static boolean isChatTurnContinuation(AgentRequest request) {
		return request.getFlowAction() != null || request.getClarificationResponse() != null
				|| request.isHumanFeedback();
	}

	private static boolean isWaitingChat(String state) {
		RuntimeRunState runState = RuntimeRunState.of(state);
		return runState == RuntimeRunState.WAITING_INPUT || runState == RuntimeRunState.WAITING_APPROVAL;
	}

	private Long resolveChatOwnerId(AgentRequest request) {
		if (request.getOwnerId() != null) {
			return request.getOwnerId();
		}
		return parseAgentIdOrNull(request.getUserIdSnapshot());
	}

	private Long parseAgentIdOrNull(String value) {
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

	private long chatDeadlineSeconds() {
		Duration deadline = chatLeaseDuration();
		long seconds = deadline.toSeconds();
		return seconds > 0 ? seconds : DEFAULT_CHAT_DEADLINE.toSeconds();
	}

	private Duration chatLeaseDuration() {
		if (dataAgentProperties == null || dataAgentProperties.getRuntime() == null
				|| dataAgentProperties.getRuntime().getChatDeadline() == null
				|| dataAgentProperties.getRuntime().getChatDeadline().isZero()
				|| dataAgentProperties.getRuntime().getChatDeadline().isNegative()) {
			return DEFAULT_CHAT_DEADLINE;
		}
		return dataAgentProperties.getRuntime().getChatDeadline();
	}

	private static String buildChatLeaseOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			host = "node";
		}
		return "chat-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private AgentRunResult executeAgent(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector) {
		AgentExecutionState state = new AgentExecutionState(System.nanoTime(), uiCollector);
		LAST_RUNTIME_TIMING.remove();
		runtimeRegistry.markRunning(request.getThreadId(), request.getRuntimeRequestId(), Thread.currentThread());
		answerTraceExplainStore.openScope(request);
		Span rootSpan = startRuntimeSpan(request);
		try {
			try (Scope ignored = rootSpan.makeCurrent()) {
				return new AgentRunResult(runAgentPipeline(request, eventPublisher, rootSpan, state),
						state.pendingInteraction);
			}
		}
		catch (RuntimeException ex) {
			finishPendingExecution(request, RoutePendingService.EXECUTION_FAILED);
			recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.RUNTIME_FAILED);
			recordRuntimeFailure(rootSpan, ex);
			emitRuntimeProgress(request, "FAILED", AgentRuntimeProgressService.STATUS_FAILED,
					elapsedMs(state.totalStart));
			dispatchAfterAgentFailed(request, ex);
			throw ex;
		}
		catch (LinkageError error) {
			RuntimeException failure = normalizeRuntimeFailure(error);
			finishPendingExecution(request, RoutePendingService.EXECUTION_FAILED);
			recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.RUNTIME_FAILED);
			recordRuntimeFailure(rootSpan, failure);
			emitRuntimeProgress(request, "FAILED", AgentRuntimeProgressService.STATUS_FAILED,
					elapsedMs(state.totalStart));
			dispatchAfterAgentFailed(request, failure);
			throw failure;
		}
		finally {
			AgentRuntimeToolMetrics toolMetrics = state.toolMetrics;
			if (StringUtils.hasText(state.successfulAnswer)) {
				finishPendingExecution(request, RoutePendingService.EXECUTION_SUCCEEDED);
				dispatchAfterAgentSuccess(request, state.successfulAnswer, toolMetrics);
			}
			if (state.runtimeExtensions != null) {
				state.runtimeExtensions.close();
			}
			rootSpan.end();
			runtimeRegistry.clearRunning(request.getThreadId(), request.getRuntimeRequestId());
			answerTraceExplainStore.closeScope();
			clearDatasourceRuntimeCache(request.getThreadId(), request.getRuntimeRequestId());
			long totalMs = elapsedMs(state.totalStart);
			LAST_RUNTIME_TIMING.set(new RuntimeTiming(state.reactMs, toolCount(toolMetrics), toolFailCount(toolMetrics),
					totalMs));
			log.info(
					"Agent runtime timing. agentId={}, threadId={}, runtimeRequestId={}, isolatedMemory={}, memoryMs={}, clarifyMs={}, configMs={}, routeMs={}, initMs={}, reactMs={}, toolDurationMs={}, modelCallCount={}, modelRetryCount={}, modelDurationMs={}, promptTokens={}, peakPromptTokens={}, completionTokens={}, reactIterationCount={}, terminalOutcome={}, protocolRepairCount={}, clarificationReason={}, toolCount={}, toolFailCount={}, persistMs={}, totalMs={}, budgetPartial={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), request.isIsolatedMemory(),
					state.memoryMs, state.clarifyMs, state.configMs, state.routeMs, state.initMs, state.reactMs,
					toolMetrics == null ? 0L : toolMetrics.toolDurationMs(),
					toolMetrics == null ? 0 : toolMetrics.modelCallCount(),
					toolMetrics == null ? 0 : toolMetrics.modelRetryCount(),
					toolMetrics == null ? 0L : toolMetrics.modelDurationMs(),
					toolMetrics == null ? 0L : toolMetrics.promptTokens(),
					toolMetrics == null ? 0L : toolMetrics.peakPromptTokens(),
					toolMetrics == null ? 0L : toolMetrics.completionTokens(),
					toolMetrics == null ? 0 : toolMetrics.reactIterationCount(), terminalOutcome(toolMetrics),
					toolMetrics == null ? 0 : toolMetrics.protocolRepairCount(),
					toolMetrics == null ? null : toolMetrics.clarificationReason(), toolCount(toolMetrics),
					toolFailCount(toolMetrics), state.persistMs, totalMs, state.budgetPartial);
		}
	}

	/**
	 * 单轮运行的阶段流水线。阶段按业务顺序排列，任一阶段产出最终答案即结束本轮，
	 * 其余中间产物通过返回值在阶段之间传递，跨阶段共享的计时与句柄放在 {@code state} 中。
	 */
	private String runAgentPipeline(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			AgentExecutionState state) {
		AgentRuntimeConfiguration configuration = prepareRuntimeConfiguration(request, rootSpan, state);
		PhaseResult<PendingResolution> pendingPhase = consumePendingRouteDecision(request, eventPublisher, rootSpan,
				configuration, state);
		if (pendingPhase.terminated()) {
			return pendingPhase.answer();
		}
		PendingResolution pendingResolution = pendingPhase.value();
		appLinkResolver.resolve(request);
		OrchestrationScope scope = resolveOrchestrationScope(request, configuration);
		Optional<String> clarifyAnswer = assessQueryClarification(request, eventPublisher, rootSpan, scope,
				pendingResolution, state);
		if (clarifyAnswer.isPresent()) {
			return clarifyAnswer.get();
		}
		fuseTurnAttachments(request, configuration.modelConfig());
		RouteDecision route = routeBusinessRequest(request, rootSpan, configuration, pendingResolution, state);
		carrySessionLinkKeys(request, route);
		PhaseResult<RoutedTarget> dispatchPhase = dispatchRouteDecision(request, eventPublisher, rootSpan, configuration,
				scope, route, state);
		if (dispatchPhase.terminated()) {
			return dispatchPhase.answer();
		}
		RoutedTarget routedTarget = dispatchPhase.value();
		Optional<String> skillAnswer = executeRoutedSkill(request, eventPublisher, rootSpan, configuration,
				routedTarget, state);
		if (skillAnswer.isPresent()) {
			return skillAnswer.get();
		}
		PhaseResult<PreparedMemory> memoryPhase = prepareWorkingMemory(request, rootSpan, state);
		if (memoryPhase.terminated()) {
			return memoryPhase.answer();
		}
		Optional<String> orchestrationAnswer = runCollaboratorOrchestration(request, eventPublisher, rootSpan,
				configuration, scope, routedTarget, state);
		if (orchestrationAnswer.isPresent()) {
			return orchestrationAnswer.get();
		}
		return runReactRuntime(request, eventPublisher, rootSpan, configuration, routedTarget, memoryPhase.value(),
				state);
	}

	/**
	 * 阶段：解析托管智能体与 CHAT 模型配置，初始化时间上下文与本轮运行截止时间。
	 *
	 * <p>数字员工链路不以 live DataAgent 为运行主体：按员工主键装配合成 DataAgent
	 * （提示词/模型来自 PUBLISHED 快照或草稿），技能路由走 {@code pinnedSkillVersionIds}。
	 * 普通智能体仍走 {@code resolveManagedAgent} live 路径。</p>
	 */
	private AgentRuntimeConfiguration prepareRuntimeConfiguration(AgentRequest request, Span rootSpan,
			AgentExecutionState state) {
		long configStart = System.nanoTime();
		emitRuntimeProgress(request, "CONFIG_LOADING", AgentRuntimeProgressService.STATUS_RUNNING);
		DataAgent managedDataAgentConfig;
		EmployeeReleaseSnapshot releaseSnapshot;
		if (isDigitalEmployeeOwner(request)) {
			releaseSnapshot = pinReleaseSnapshotForRuntime(request, rootSpan);
			managedDataAgentConfig = buildEmployeeRuntimeAgent(request, releaseSnapshot);
			pinEmployeeSkillVersions(request, releaseSnapshot);
		}
		else {
			managedDataAgentConfig = resolveManagedAgent(request.getAgentId());
			releaseSnapshot = null;
		}
		applyAgentRuntimeLimits(request, managedDataAgentConfig);
		applyAgentNameSnapshot(request, managedDataAgentConfig);
		agentTemporalService.initialize(request, managedDataAgentConfig);
		answerTraceExplainStore.openScope(request);
		String agentType = resolveAgentType(managedDataAgentConfig);
		if (request.isCollaboratorChild() && AgentTypeConstant.isOrchestrator(agentType)) {
			throw new RouteUnavailableException("ROUTE_SCOPE_VIOLATION");
		}
		ModelConfigDTO modelConfig = resolveReleasePinnedModelConfig(request, managedDataAgentConfig, releaseSnapshot);
		validateModelConfig(modelConfig);
		recordModelConfig(rootSpan, modelConfig);
		request.setRuntimeDeadline(AgentRuntimeDeadline.startAt(state.totalStart,
				resolveRuntimeTimeout(request, managedDataAgentConfig)));
		request.setRuntimeFinishBuffer(dataAgentProperties.getRuntime().getFinishBuffer());
		state.configMs = elapsedMs(configStart);
		emitRuntimeProgress(request, "CONFIG_READY", AgentRuntimeProgressService.STATUS_SUCCESS, state.configMs);
		return new AgentRuntimeConfiguration(managedDataAgentConfig, agentType, modelConfig, releaseSnapshot);
	}

	private boolean isDigitalEmployeeOwner(AgentRequest request) {
		return OWNER_TYPE_DIGITAL_EMPLOYEE.equals(request.getOwnerType()) && request.getOwnerId() != null;
	}

	/**
	 * MODEL_ONLY 沙箱：数字员工未钉死 Release（灰度未开 / Principal 未就绪 / 无生产部署）。
	 * 只允许纯模型对话，不装 NL2SQL/MCP、不执行技能。
	 */
	private boolean isEmployeeModelOnlySandbox(AgentRequest request) {
		return isDigitalEmployeeOwner(request) && request.getReleaseId() == null;
	}

	/**
	 * 从员工 Release 快照或草稿装配运行时合成 DataAgent。id 钉在员工主键上，
	 * 禁止回源 {@code data_agent} 表（员工 id 与 DataAgent id 可能撞车）。
	 */
	private DataAgent buildEmployeeRuntimeAgent(AgentRequest request, EmployeeReleaseSnapshot snapshot) {
		String tenantId = resolveReleaseTenantId(request);
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.fail("数字员工运行配置失败（失败关闭）：请求未携带且当前上下文无法解析租户, "
					+ "employeeId=" + request.getOwnerId());
		}
		DigitalEmployee employee = digitalEmployeeMapper.findByIdAndTenantId(request.getOwnerId(), tenantId.trim());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + request.getOwnerId());
		}
		String name = snapshot != null && StringUtils.hasText(snapshot.employeeName()) ? snapshot.employeeName()
				: employee.getEmployeeName();
		String prompt = snapshot != null && StringUtils.hasText(snapshot.systemInstruction())
				? snapshot.systemInstruction() : employee.getSystemInstruction();
		Long modelId = snapshot != null && snapshot.modelConfigId() != null ? snapshot.modelConfigId()
				: employee.getModelConfigId();
		DataAgent agent = new DataAgent();
		agent.setId(employee.getId());
		agent.setTenantId(employee.getTenantId());
		agent.setName(name);
		agent.setDescription(employee.getDescription());
		agent.setPrompt(prompt);
		agent.setChatModelConfigId(modelId);
		agent.setStatus("published");
		agent.setAgentType(AgentTypeConstant.DATA_ANALYSIS);
		request.setAgentId(String.valueOf(employee.getId()));
		return agent;
	}

	/**
	 * 数字员工技能钉死：调用方已写入（MODEL_ONLY 空列表）则不覆盖；否则按快照冻结版本填充。
	 * 必须写成非 null 列表，路由层据此跳过 DataAgent 绑定表回源。
	 */
	private void pinEmployeeSkillVersions(AgentRequest request, EmployeeReleaseSnapshot snapshot) {
		if (request.getPinnedSkillVersionIds() != null) {
			return;
		}
		if (snapshot == null || snapshot.capabilities() == null || snapshot.capabilities().isEmpty()) {
			request.setPinnedSkillVersionIds(List.of());
			return;
		}
		request.setPinnedSkillVersionIds(snapshot.capabilities().stream()
			.filter(capability -> capability != null && capability.skillVersionId() != null)
			.map(EmployeeReleaseSnapshot.CapabilityRef::skillVersionId)
			.toList());
	}

	/**
	 * 任务级 Release 钉死（任务14）：请求携带员工 Release 语境（DIGITAL_EMPLOYEE owner + releaseId）
	 * 时解析 PUBLISHED 快照作为运行配置事实源；其余链路（CALLER / 纯模型任务 / 普通智能体）返回
	 * null 保持 live 原路径，不触碰现网行为（与能力网关 resolveEmployeeSnapshot 同一判定口径）。
	 *
	 * <p>锚点选择：任务链路（能对到任务台账行）以 agent_task_version 冻结的 employeeReleaseId
	 * 为锚（见 {@link #resolveTaskVersionFrozenReleaseId}）；其余沿用请求携带的 releaseId。
	 * 解析器内部失败关闭（非 PUBLISHED / 跨租户 / SHA-256 防篡改 / 快照损坏均拒绝）。</p>
	 */
	private EmployeeReleaseSnapshot pinReleaseSnapshotForRuntime(AgentRequest request, Span rootSpan) {
		if (request.getReleaseId() == null || !OWNER_TYPE_DIGITAL_EMPLOYEE.equals(request.getOwnerType())
				|| request.getOwnerId() == null) {
			return null;
		}
		String tenantId = resolveReleaseTenantId(request);
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.fail("数字员工 Release 运行配置钉死失败（失败关闭）：请求未携带且当前上下文"
					+ "无法解析租户, agentId=" + request.getAgentId() + ", releaseId=" + request.getReleaseId());
		}
		Long pinnedReleaseId = resolveTaskVersionFrozenReleaseId(request, request.getReleaseId());
		EmployeeReleaseSnapshot snapshot = employeeReleaseSnapshotResolver.resolveById(tenantId,
				request.getOwnerId(), pinnedReleaseId);
		if (rootSpan != null) {
			rootSpan.setAttribute("dataagent.release.pinned_release_id", snapshot.releaseId());
			rootSpan.setAttribute("dataagent.release.version_anchor", defaultText(snapshot.versionAnchor()));
		}
		log.info("运行配置已按数字员工 Release 快照钉死. agentId={}, employeeId={}, releaseId={}, versionAnchor={}",
				request.getAgentId(), request.getOwnerId(), snapshot.releaseId(), snapshot.versionAnchor());
		return snapshot;
	}

	/**
	 * Release 钉死的租户解析：请求快照优先，授权上下文回退（异步委托链路仍可解析），
	 * 读取异常按无租户处理，由调用方失败关闭（与能力网关 resolvedTenantId 同模式）。
	 */
	private String resolveReleaseTenantId(AgentRequest request) {
		if (StringUtils.hasText(request.getTenantIdSnapshot())) {
			return request.getTenantIdSnapshot().trim();
		}
		try {
			if (authenticationContext != null && !authenticationContext.anonymous()) {
				return authenticationContext.tenantId();
			}
		}
		catch (RuntimeException ex) {
			log.debug("运行时读取授权上下文租户失败. errorType={}", ex.getClass().getSimpleName());
		}
		return null;
	}

	/**
	 * TaskVersion 反向校验（任务14，防“任务冻结版本与实际 Release 漂移”）：任务链路实际执行的
	 * Release 必须与 agent_task_version 冻结的 employeeReleaseId 完全一致——任务定义改绑
	 * Release 后，旧任务不得按新 Release 执行；不一致按失败关闭显式上抛。
	 *
	 * <p>比对锚说明：agent_task_version.spec_hash 是任务参数+提示词快照的指纹，与 Release
	 * 快照 specHash 不同源、不存在可比列；本方法以冻结 employeeReleaseId 为唯一漂移判据，
	 * 解析产物的 versionAnchor()（Release specHash）随 span 属性落审计供事后对账。
	 * 冻结列为空（PR-6 之前的存量版本行，无锚可比对而非比对不一致）告警放行；
	 * 非任务链路（无权威 Run / 台账对不上，如数字员工对话）直接沿用请求携带的锚。</p>
	 *
	 * @return 实际钉死使用的 releaseId（任务链路为版本冻结锚，其余为请求锚）
	 */
	private Long resolveTaskVersionFrozenReleaseId(AgentRequest request, Long requestReleaseId) {
		Long runtimeRunId = resolveAuthoritativeRunId(request);
		if (runtimeRunId == null) {
			return requestReleaseId;
		}
		AgentTaskRun taskRun = taskRunMapper.findByRuntimeRunId(runtimeRunId);
		if (taskRun == null) {
			return requestReleaseId;
		}
		AgentTaskVersion taskVersion = taskRun.getTaskVersionId() == null ? null
				: taskVersionMapper.findByTenantAndId(taskRun.getTenantId(), taskRun.getTaskVersionId());
		if (taskVersion == null || taskVersion.getEmployeeReleaseId() == null) {
			log.warn("任务版本冻结 Release 锚缺失, 跳过漂移校验（存量数据无锚可比对）. taskRunId={}, "
					+ "taskVersionId={}", taskRun.getId(), taskRun.getTaskVersionId());
			return requestReleaseId;
		}
		if (!taskVersion.getEmployeeReleaseId().equals(requestReleaseId)) {
			throw CheckedException.fail("任务级 Release 钉死失败（失败关闭）：任务版本冻结的 Release 与本次执行的"
					+ " Release 不一致（漂移）, taskRunId=" + taskRun.getId() + ", taskVersionId="
					+ taskRun.getTaskVersionId() + ", frozenEmployeeReleaseId=" + taskVersion.getEmployeeReleaseId()
					+ ", runtimeReleaseId=" + requestReleaseId);
		}
		return taskVersion.getEmployeeReleaseId();
	}

	/**
	 * Release 钉死下的 CHAT 模型解析：用户显式选择的 chatModelConfigId 保持优先（前端切模型
	 * 不回归）；否则按快照冻结的 modelConfigId 直接解析运行配置——冻结锚不是“用户选择”，
	 * 不做 agent 可选择性校验以免误拒，模型被删除或非 CHAT 类型时由数据服务失败关闭。
	 * 无快照 / 快照未冻结模型时回落 live 解析路径。
	 */
	private ModelConfigDTO resolveReleasePinnedModelConfig(AgentRequest request, DataAgent dataAgent,
			EmployeeReleaseSnapshot releaseSnapshot) {
		if (isDigitalEmployeeOwner(request)) {
			return employeeModelConfigService.resolveChatModelConfig(request.getOwnerId(),
					resolveReleaseTenantId(request), request.getChatModelConfigId(), releaseSnapshot);
		}
		if (releaseSnapshot == null || releaseSnapshot.modelConfigId() == null
				|| request.getChatModelConfigId() != null) {
			return resolveChatModelConfig(request.getChatModelConfigId(), dataAgent);
		}
		return modelConfigDataService.getRuntimeConfigById(releaseSnapshot.modelConfigId(), ModelType.CHAT);
	}

	/** 阶段：消费上一轮挂起的澄清 / 确认结果，据此恢复编排、取消本轮或改写本轮问题。 */
	private PhaseResult<PendingResolution> consumePendingRouteDecision(AgentRequest request,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, AgentRuntimeConfiguration configuration,
			AgentExecutionState state) {
		PendingResolution pendingResolution = routePendingService.consume(request);
		if (pendingResolution == null) {
			return PhaseResult.proceed(null);
		}
		if (pendingResolution.hasOrchestrationContinuation()) {
			return PhaseResult.terminated(resumeOrchestrationInteraction(request, eventPublisher, rootSpan,
					pendingResolution, configuration.dataAgent(), configuration.modelConfig(), state));
		}
		if (pendingResolution.cancelled()) {
			finishPendingExecution(request, RoutePendingService.EXECUTION_CANCELLED);
			return PhaseResult.terminated(completeDirectAnswer(request, eventPublisher, rootSpan, "已取消本次操作。",
					"CONFIRM_CANCELLED", state.totalStart));
		}
		if (StringUtils.hasText(pendingResolution.effectiveQuery())) {
			request.setEffectiveRoutingQuery(pendingResolution.effectiveQuery());
			request.setQuery(pendingResolution.effectiveQuery());
		}
		request.setRouteClarificationRound(pendingResolution.round());
		return PhaseResult.proceed(pendingResolution);
	}

	/** 阶段：判定本轮是否走编排者链路，加载编排策略并按澄清词典初始化时间区间。 */
	private OrchestrationScope resolveOrchestrationScope(AgentRequest request,
			AgentRuntimeConfiguration configuration) {
		boolean orchestratorAgent = AgentTypeConstant.isOrchestrator(configuration.agentType());
		AgentOrchestrationPolicy orchestrationPolicy = orchestratorAgent
				? orchestrationRuntimeSupport.loadPolicy(configuration.dataAgent()) : null;
		agentTemporalService.initializeInterval(request, clarificationConfig(orchestrationPolicy));
		return new OrchestrationScope(orchestratorAgent, orchestrationPolicy);
	}

	private Map<String, Object> clarificationConfig(AgentOrchestrationPolicy orchestrationPolicy) {
		return orchestrationPolicy == null || orchestrationPolicy.getClarificationConfig() == null ? Map.of()
				: orchestrationPolicy.getClarificationConfig();
	}

	/** 阶段：评估问题是否需要业务澄清，风险过高且没有待恢复交互时挂起本轮等待用户补充。 */
	private Optional<String> assessQueryClarification(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, OrchestrationScope scope, PendingResolution pendingResolution, AgentExecutionState state) {
		boolean clarifyCheckEnabled = scope.orchestrator() || request.isClarifyCheckEnabled()
				|| StringUtils.hasText(request.getHumanFeedbackContent())
				|| pendingResolution != null;
		boolean skipAttachmentSlots = turnFusionService.skipAttachmentSlotClarify(request);
		QueryClarifyAssessment clarifyAssessment = scope.orchestrator()
				? queryClarifyService.assess(request.getQuery(), request.getHumanFeedbackContent(), true,
						clarificationConfig(scope.policy()), request.getTemporalInterval(), skipAttachmentSlots)
				: queryClarifyService.assess(request.getQuery(), request.getHumanFeedbackContent(), clarifyCheckEnabled,
						null, request.getTemporalInterval(), skipAttachmentSlots);
		answerTraceExplainStore.recordClarifyAssessment(request, clarifyAssessment);
		rootSpan.setAttribute("dataagent.query_clarify.enabled", clarifyCheckEnabled);
		rootSpan.setAttribute("dataagent.query_clarify.risk_level", clarifyAssessment.riskLevel().value());
		if (!clarifyAssessment.shouldBlockExecution() || pendingResolution != null) {
			return Optional.empty();
		}
		state.toolMetrics = pendingInteractionMetrics(request);
		recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.WAITING_CLARIFICATION);
		return Optional.of(blockForClarification(request, eventPublisher, rootSpan, clarifyAssessment, null, state));
	}

	/**
	 * 沿用上一轮只读技能且本轮问句没有 URL 时，从会话更早的用户原文补 snapshot 再抽键。
	 */
	private void carrySessionLinkKeys(AgentRequest request, RouteDecision route) {
		if (request == null || request.isIsolatedMemory() || route == null
				|| !"ANALYSIS_SESSION_CONTINUATION".equals(route.reasonCode())) {
			return;
		}
		if (hasTrustedLinkKeys(request) || !LinkKeyExtractor.extractUrls(request.getQuery()).isEmpty()) {
			return;
		}
		if (!StringUtils.hasText(request.getOriginalQuerySnapshot())) {
			Long sessionId;
			try {
				sessionId = parseRequiredThreadId(request.getThreadId());
			}
			catch (RuntimeException ex) {
				return;
			}
			String snapshot = SessionLinkCarryover.latestUserTextWithUrl(chatMessageService.findBySessionId(sessionId));
			if (!StringUtils.hasText(snapshot)) {
				return;
			}
			request.setOriginalQuerySnapshot(snapshot);
		}
		appLinkResolver.resolve(request);
	}

	private boolean hasTrustedLinkKeys(AgentRequest request) {
		return request.getGroundedFacts() != null && !request.getGroundedFacts().trustedKeys().isEmpty();
	}

	/** 阶段：混合路由，得到本轮的技能 / 协作者路由决策；已确认过的路由只做复核。 */
	private RouteDecision routeBusinessRequest(AgentRequest request, Span rootSpan,
			AgentRuntimeConfiguration configuration, PendingResolution pendingResolution, AgentExecutionState state) {
		emitRuntimeProgress(request, "ROUTING", AgentRuntimeProgressService.STATUS_RUNNING);
		long routeStart = System.nanoTime();
		if (!request.getRuntimeDeadline().canStart(dataAgentProperties.getRuntime().getFinishBuffer())) {
			throw new IllegalStateException("Agent runtime deadline exhausted before Skill routing");
		}
		RouteDecision route;
		if (request.getResumedConfirmedRoute() != null) {
			route = routeCoordinator.validateConfirmed(request, configuration.dataAgent(),
					request.getResumedConfirmedRoute());
		}
		else if (pendingResolution != null && pendingResolution.confirmedRoute() != null) {
			route = routeCoordinator.validateConfirmed(request, configuration.dataAgent(),
					pendingResolution.confirmedRoute());
		}
		else {
			route = routeCoordinator.route(request, configuration.dataAgent());
		}
		state.routeMs = elapsedMs(routeStart);
		recordRoute(request, rootSpan, route, state.routeMs);
		emitRuntimeProgress(request, "ROUTING_DONE", AgentRuntimeProgressService.STATUS_SUCCESS, state.routeMs);
		return route;
	}

	/** 阶段：按路由决策分派——直接回答、澄清、确认、路由不可用，或落到技能 / 协作者目标。 */
	private PhaseResult<RoutedTarget> dispatchRouteDecision(AgentRequest request,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, AgentRuntimeConfiguration configuration,
			OrchestrationScope scope, RouteDecision route, AgentExecutionState state) {
		if (route.decision() == RouteDecisionType.DIRECT) {
			return PhaseResult.terminated(completeDirectAnswer(request, eventPublisher, rootSpan, route.directText(),
					"DIRECT_DONE", state.totalStart));
		}
		if (route.decision() == RouteDecisionType.CLARIFY) {
			return PhaseResult.terminated(clarifyRoutedRequest(request, eventPublisher, rootSpan, route, state));
		}
		if (route.decision() == RouteDecisionType.CONFIRM_REQUIRED) {
			return PhaseResult.terminated(blockForConfirmation(request, eventPublisher, rootSpan, route, state));
		}
		if (route.decision() == RouteDecisionType.ROUTE_UNAVAILABLE) {
			throw new RouteUnavailableException(route.reasonCode());
		}
		if (route.decision() == RouteDecisionType.NO_MATCH) {
			if (isDigitalEmployeeOwner(request) && request.getPinnedSkillVersionIds() != null
					&& request.getPinnedSkillVersionIds().isEmpty()) {
				return PhaseResult.proceed(RoutedTarget.none());
			}
			return PhaseResult.terminated(answerUnmatchedRoute(request, eventPublisher, rootSpan, configuration,
					state));
		}
		if (route.decision() == RouteDecisionType.SELECT
				|| route.decision() == RouteDecisionType.MULTI_SELECT) {
			if (isEmployeeModelOnlySandbox(request)) {
				return PhaseResult.proceed(RoutedTarget.none());
			}
			return PhaseResult.proceed(resolveRoutedTarget(request, scope, route));
		}
		return PhaseResult.proceed(RoutedTarget.none());
	}

	/** CLARIFY 决策的处理：协作者子请求要求业务澄清，主链路超过澄清轮次上限则直接收敛。 */
	private String clarifyRoutedRequest(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			RouteDecision route, AgentExecutionState state) {
		if (request.isCollaboratorChild()) {
			QueryClarifyAssessment childClarification = requireCollaboratorBusinessClarification(request, route);
			state.toolMetrics = pendingInteractionMetrics(request);
			recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.WAITING_CLARIFICATION);
			return blockForRouteClarification(request, eventPublisher, rootSpan,
					businessClarification(childClarification), state);
		}
		if (request.getRouteClarificationRound() != null && request.getRouteClarificationRound() >= 2) {
			return completeDirectAnswer(request, eventPublisher, rootSpan,
					"当前信息仍不足以安全确定业务范围，请重新描述问题。", "ROUTE_CLARIFY_LIMIT", state.totalStart);
		}
		state.toolMetrics = pendingInteractionMetrics(request);
		recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.WAITING_CLARIFICATION);
		return blockForRouteClarification(request, eventPublisher, rootSpan, route, state);
	}

	/** NO_MATCH 决策的处理：协作者子请求直接失败，其余按智能体类型给出对应引导话术。 */
	private String answerUnmatchedRoute(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			AgentRuntimeConfiguration configuration, AgentExecutionState state) {
		if (request.isCollaboratorChild()) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_NO_MATCH");
		}
		if (AgentTypeConstant.KNOWLEDGE_BASE.equals(AgentTypeConstant.normalize(configuration.agentType()))) {
			return completeDirectAnswer(request, eventPublisher, rootSpan,
					"当前问题未匹配到可用的知识问答能力，请换一种问法。",
					"KNOWLEDGE_ROUTE_NO_MATCH", state.totalStart);
		}
		if (request.supportsTextCommands()) {
			String imCopy = unmatchedRouteCopy.build(request);
			if (StringUtils.hasText(imCopy)) {
				return completeDirectAnswer(request, eventPublisher, rootSpan, imCopy, "ROUTE_NO_MATCH",
						state.totalStart);
			}
		}
		if (AgentTypeConstant.isOrchestrator(configuration.agentType())) {
			return completeDirectAnswer(request, eventPublisher, rootSpan,
						routeClarification(),
						"ROUTE_CLARIFY", state.totalStart);
		}
		return completeDirectAnswer(request, eventPublisher, rootSpan,
				"当前问题暂未匹配到可用的业务能力，请补充业务对象、时间范围或期望结果。",
				"ROUTE_NO_MATCH", state.totalStart);
	}

	/** SELECT / MULTI_SELECT 决策的目标解析：要么全是协作者，要么是单个技能，混合目标不允许。 */
	private RoutedTarget resolveRoutedTarget(AgentRequest request, OrchestrationScope scope, RouteDecision route) {
		boolean collaboratorOnly = route.selections().stream().allMatch(selection -> selection.target() != null
				&& selection.target().targetType() == RouteTargetType.COLLABORATOR);
		if (collaboratorOnly) {
			if (!scope.orchestrator()) {
				throw new RouteUnavailableException("ROUTE_SCOPE_VIOLATION");
			}
			return new RoutedTarget(null, route);
		}
		if (route.selections().size() == 1
				&& route.selections().get(0).target().targetType() == RouteTargetType.SKILL) {
			if (scope.orchestrator()) {
				throw new RouteUnavailableException("ROUTE_SCOPE_VIOLATION");
			}
			return new RoutedTarget(resolveSelectedSkill(request, route.selections().get(0)), null);
		}
		throw new RouteUnavailableException("ROUTE_SELECTION_MIXED_TARGETS");
	}

	/** 阶段：执行命中的技能；技能自行处理完本轮时直接产出最终答案，否则继续走 ReAct 运行时。 */
	private Optional<String> executeRoutedSkill(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, AgentRuntimeConfiguration configuration, RoutedTarget routedTarget,
			AgentExecutionState state) {
		if (isEmployeeModelOnlySandbox(request)) {
			return Optional.empty();
		}
		SelectedSkillRoute selectedSkill = routedTarget.selectedSkill();
		if (selectedSkill != null) {
			if (turnFusionService.shouldDeferSkillForUnreadImage(request, selectedSkill.executionMode())) {
				return Optional.empty();
			}
			long skillStart = System.nanoTime();
			SkillExecutionResult skillResult = skillExecutorRegistry.required(selectedSkill.executionMode())
				.execute(new SkillExecutionContext(request, configuration.dataAgent(), configuration.modelConfig(),
					selectedSkill.selection(), selectedSkill.skill(), selectedSkill.version(),
					skillVersionResourceLoader.load(selectedSkill.version())));
			applySkillDuration(request, selectedSkill.executionMode(), elapsedMs(skillStart));
			if (skillResult.handled()) {
				emitSkillUi(eventPublisher, request, skillResult.uiMessage(), state.uiCollector);
				if (skillResult.outcome() == SkillExecutionOutcome.FAILED) {
					return Optional.of(completeFailedDirectAnswer(request, eventPublisher, rootSpan,
							skillResult.answer(), skillResult.stageCode(), state.totalStart));
				}
				if (skillResult.outcome() == SkillExecutionOutcome.WAITING) {
					return Optional.of(waitForSkillAnswer(request, rootSpan, skillResult.answer(),
							skillResult.stageCode()));
				}
				return Optional.of(completeDirectAnswer(request, null, rootSpan, skillResult.answer(),
						skillResult.stageCode(), state.totalStart));
			}
		}
		if (selectedSkill != null && selectedSkill.executionMode() == SkillExecutionMode.DETERMINISTIC) {
			throw new IllegalStateException("Deterministic Skill executor must handle the routed request");
		}
		return Optional.empty();
	}

	/** 阶段：装载工作记忆，并在装载完成后检查取消信号。 */
	private PhaseResult<PreparedMemory> prepareWorkingMemory(AgentRequest request, Span rootSpan,
			AgentExecutionState state) {
		long memoryStart = System.nanoTime();
		emitRuntimeProgress(request, "MEMORY_LOADING", AgentRuntimeProgressService.STATUS_RUNNING);
		PreparedMemory preparedMemory = prepareMemory(request);
		state.memoryMs = elapsedMs(memoryStart);
		emitRuntimeProgress(request, "MEMORY_READY", AgentRuntimeProgressService.STATUS_SUCCESS, state.memoryMs);
		if (runtimeRegistry.isCancelled(request.getThreadId(), request.getRuntimeRequestId())) {
			rootSpan.setStatus(StatusCode.OK, "cancelled");
			chatTurnService.cancelTurn(request);
			emitRuntimeProgress(request, "CANCELLED", AgentRuntimeProgressService.STATUS_CANCELLED,
					elapsedMs(state.totalStart));
			return PhaseResult.terminated("");
		}
		emitRuntimeProgress(request, "CLARIFY_DONE", AgentRuntimeProgressService.STATUS_SUCCESS, state.clarifyMs);
		return PhaseResult.proceed(preparedMemory);
	}

	/** 阶段：编排者智能体的轻量协作编排；协作者挂起交互时把交互上抛给父会话。 */
	private Optional<String> runCollaboratorOrchestration(AgentRequest request,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, AgentRuntimeConfiguration configuration,
			OrchestrationScope scope, RoutedTarget routedTarget, AgentExecutionState state) {
		if (!scope.orchestrator() || routedTarget.collaboratorRoute() == null || request.isIsolatedMemory()) {
			return Optional.empty();
		}
		OrchestrationExecutionResult orchestrationResult = executeLightweightOrchestration(request,
				configuration.dataAgent(), configuration.modelConfig(),
				scope.policy(), routedTarget.collaboratorRoute(), eventPublisher);
		if (orchestrationResult.waitingResult() != null) {
			state.toolMetrics = pendingInteractionMetrics(request);
			recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.WAITING_CLARIFICATION);
			return Optional.of(publishParentCollaboratorPendingInteraction(request, eventPublisher, rootSpan,
					orchestrationResult.waitingResult().answer(),
					orchestrationResult.waitingResult().pendingInteraction(), state.totalStart));
		}
		String answer = orchestrationResult.answer();
		emitRuntimeProgress(request, "ROUTING_DONE", AgentRuntimeProgressService.STATUS_SUCCESS);
		rootSpan.setStatus(StatusCode.OK);
		emitRuntimeProgress(request, "ANSWER_COMPOSING", AgentRuntimeProgressService.STATUS_RUNNING);
		long persistStart = System.nanoTime();
		answerTraceExplainStore.recordFinalAnswer(answer);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeAnswer = finalizePublicAnswer(request, answer);
		mirrorExplainSummary(rootSpan, request);
		state.persistMs = elapsedMs(persistStart);
		chatTurnService.completeTurn(request, safeAnswer, 0L, 0, 0, explainSnapshot);
		emitRuntimeProgress(request, "ANSWER_COMPOSING", AgentRuntimeProgressService.STATUS_SUCCESS,
				state.persistMs);
		emitRuntimeProgress(request, "DONE", AgentRuntimeProgressService.STATUS_SUCCESS,
				elapsedMs(state.totalStart));
		state.successfulAnswer = safeAnswer;
		return Optional.of(safeAnswer);
	}

	/** 阶段：ReAct 运行时主路径——装配工具与模型、运行智能体、落库并分类终态。 */
	private String runReactRuntime(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			AgentRuntimeConfiguration configuration, RoutedTarget routedTarget, PreparedMemory preparedMemory,
			AgentExecutionState state) {
		turnFusionService.restorePixels(request, configuration.modelConfig());
		emitRuntimeProgress(request, "ROUTING_DONE", AgentRuntimeProgressService.STATUS_SUCCESS);
		Map<String, ToolCallback> toolCallbacks = resolveRuntimeToolCallbacks(request, configuration, routedTarget);
		ReactRuntimeContext runtimeContext = buildReactRuntimeContext(request, eventPublisher, rootSpan, configuration,
				toolCallbacks, preparedMemory, state);
		Optional<Msg> response = runManagedAgent(request, rootSpan, configuration, runtimeContext, state,
				eventPublisher);
		if (response.isEmpty()) {
			return "";
		}
		return completeAgentTurn(request, rootSpan, runtimeContext, response.get(), state);
	}

	/** 阶段：解析本轮可用的工具回调——技能 ReAct 用技能工具目录，其余用智能体工具箱。 */
	private Map<String, ToolCallback> resolveRuntimeToolCallbacks(AgentRequest request,
			AgentRuntimeConfiguration configuration, RoutedTarget routedTarget) {
		if (isEmployeeModelOnlySandbox(request)) {
			return Map.of();
		}
		SelectedSkillRoute selectedSkill = routedTarget.selectedSkill();
		Map<String, ToolCallback> toolCallbacks = selectedSkill != null
						&& selectedSkill.executionMode() == SkillExecutionMode.REACT
						? skillRuntimeToolCatalogService.getToolCallbacks(request, selectedSkill.selection(),
								selectedSkill.skill(), selectedSkill.version())
						: agentScopeToolkitFactory.getToolCallbacks(request.getAgentId(),
								configuration.agentType());
		// PR-4 工具列表部分授权：ENFORCE 租户未授权工具对模型不可见（全部 deny 时为空集，模型可纯文本对话）；
		// SHADOW（默认）原样返回，现网行为零变化（差异观测由执行期 CapabilityGateway Hook 线承担）。
		Map<String, ToolCallback> authorizedCallbacks = toolkitAuthorizationFilter
			.partiallyAuthorize(request, toolCallbacks);
		String analysisIntent = resolveV2AnalysisIntent(request);
		if (request != null) {
			request.setV2AnalysisIntent(analysisIntent);
		}
		return V2ToolkitFilter.filter(authorizedCallbacks == null ? Map.of() : authorizedCallbacks, analysisIntent);
	}

	private String resolveV2AnalysisIntent(AgentRequest request) {
		AnalysisConfig config = routedAnalysisConfig(request);
		boolean reportMode = isReportMode(request);
		if ((config == null || !config.present()) && !reportMode) {
			return null;
		}
		boolean hasAttachment = request != null && request.getAttachments() != null
				&& !request.getAttachments().isEmpty();
		AnalysisTurnIntentClassifier classifier = new AnalysisTurnIntentClassifier();
		Set<String> keys = classifier.extractJoinKeys(request == null ? null : request.getQuery(), config, Set.of());
		AnalysisTurnDecision decision = classifier.classify(request == null ? null : request.getQuery(), hasAttachment,
				config, keys);
		return V2ToolkitFilter.resolve(config, reportMode, decision);
	}

	/** 阶段：构建模型与运行时扩展——预算指标、上下文压缩、长期记忆召回与工具钩子。 */
	private ReactRuntimeContext buildReactRuntimeContext(AgentRequest request,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, AgentRuntimeConfiguration configuration,
			Map<String, ToolCallback> toolCallbacks, PreparedMemory preparedMemory, AgentExecutionState state) {
		long initStart = System.nanoTime();
		ModelConfigDTO modelConfig = configuration.modelConfig();
		emitRuntimeProgress(request, "AGENT_INITIALIZING", AgentRuntimeProgressService.STATUS_RUNNING);
		AgentRuntimeToolMetrics runtimeMetrics = new AgentRuntimeToolMetrics(
				dataAgentProperties.getRuntime().getNoProgressMaxCompletedIdenticalCalls(),
				request.getMaxModelCalls(), request.getMaxToolCalls(), request.getMaxPromptTokens());
		runtimeMetrics.configureEmptySearchNoProgress(
				dataAgentProperties.getRuntime().isEmptySearchNoProgressEnabled(),
				dataAgentProperties.getRuntime().getEmptySearchNoProgressMaxConsecutive());
		Model model = agentScopeModelFactory.create(dynamicModelFactory.createChatModel(modelConfig), modelConfig,
				toolCallbacks,
				tokenUsageService.buildContext(request, modelConfig, AgentTokenUsageService.SOURCE_AGENT_REACT),
				runtimeMetrics);
		AgentMemoryRecallResultDTO memoryRecall = recallLongTermMemorySafely(request);
		request.setMemoryRecallResult(memoryRecall);
		recordLongTermMemorySpan(rootSpan, memoryRecall);
		ManagedAgent managedAgent = managedAgentRegistry.getRequired(configuration.agentType());
		state.runtimeExtensions = agentRuntimeExtensionFactory.create(request, eventPublisher, toolCallbacks,
				preparedMemory, runtimeMetrics);
		state.toolMetrics = toolMetrics(state.runtimeExtensions);
		state.initMs = elapsedMs(initStart);
		emitRuntimeProgress(request, "AGENT_READY", AgentRuntimeProgressService.STATUS_SUCCESS, state.initMs);
		return new ReactRuntimeContext(model, runtimeMetrics, managedAgent, memoryRecall,
				modelConfig.getReasoningProtocol());
	}

	/**
	 * 阶段：运行托管智能体的 ReAct 主循环。返回空表示本轮已被取消，调用方直接返回空答案。
	 * 全部 dataagent ReAct（Web / 员工流 / executeAgentOnce / IM / NL2SQL / 分析意图 / 协作者子调用）
	 * 一律走 {@link #runHarnessAgentV2}；FLOW 不得进入本方法。
	 */
	private Optional<Msg> runManagedAgent(AgentRequest request, Span rootSpan, AgentRuntimeConfiguration configuration,
			ReactRuntimeContext runtimeContext, AgentExecutionState state, AgentRuntimeEventPublisher eventPublisher) {
		Msg response;
		long reactStart = System.nanoTime();
		try {
			emitRuntimeProgress(request, "THINKING", AgentRuntimeProgressService.STATUS_RUNNING);
			if (request != null && request.getRoutedSkillExecutionMode() == SkillExecutionMode.FLOW) {
				throw new IllegalStateException("FLOW skill must not enter ReAct runtime");
			}
			if (harnessAgentFactory() == null) {
				throw new IllegalStateException(
						"HarnessAgentFactory is required; ReAct cannot fall back to ReActAgent.call");
			}
			AgentRunContext runContext = new AgentRunContext(request.getAgentId(), request.getThreadId(),
					runtimeContext.model(), resolveRuntimeSystemPrompt(request, configuration),
					buildFinalUserPrompt(request, runtimeContext.memoryRecall().promptBlock()),
					buildUserContentBlocks(request), resolveRuntimeTimeout(request, configuration.dataAgent()),
					state.runtimeExtensions);
			response = runHarnessAgentV2(request, runContext, runtimeContext.managedAgent(), eventPublisher, state,
					runtimeContext.reasoningProtocol());
			state.reactMs = elapsedMs(reactStart);
		}
		catch (RuntimeException ex) {
			state.reactMs = elapsedMs(reactStart);
			if (runtimeRegistry.isCancelled(request.getThreadId(), request.getRuntimeRequestId())
					&& isInterruptedCancellation(ex)) {
				Thread.interrupted();
				rootSpan.setStatus(StatusCode.OK, "cancelled");
				chatTurnService.cancelTurn(request);
				emitRuntimeProgress(request, "CANCELLED", AgentRuntimeProgressService.STATUS_CANCELLED,
						elapsedMs(state.totalStart));
				log.info("Agent execution interrupted by cancellation, threadId={}, runtimeRequestId={}",
						request.getThreadId(), request.getRuntimeRequestId());
				return Optional.empty();
			}
			throw ex;
		}
		emitRuntimeProgress(request, "THINKING", AgentRuntimeProgressService.STATUS_SUCCESS, state.reactMs);
		if (runtimeRegistry.isCancelled(request.getThreadId(), request.getRuntimeRequestId())) {
			rootSpan.setStatus(StatusCode.OK, "cancelled");
			chatTurnService.cancelTurn(request);
			emitRuntimeProgress(request, "CANCELLED", AgentRuntimeProgressService.STATUS_CANCELLED,
					elapsedMs(state.totalStart));
			return Optional.empty();
		}
		return Optional.of(response);
	}

	private HarnessAgentFactory harnessAgentFactory() {
		return harnessAgentFactoryProvider == null ? null : harnessAgentFactoryProvider.getIfAvailable();
	}

	private V2AgentStateStore v2AgentStateStore() {
		return v2AgentStateStoreProvider == null ? null : v2AgentStateStoreProvider.getIfAvailable();
	}

	private Msg runHarnessAgentV2(AgentRequest request, AgentRunContext runContext, ManagedAgent managedAgent,
			AgentRuntimeEventPublisher eventPublisher, AgentExecutionState state, String reasoningProtocol) {
		HarnessAgentFactory factory = harnessAgentFactory();
		AgentRuntimeExtensions extensions = runContext.extensions();
		String sysPrompt = runContext.systemPrompt();
		if (managedAgent instanceof PromptBackedReActAgent promptAgent) {
			sysPrompt = promptAgent.resolveSystemPrompt(runContext);
		}
		else if (extensions != null && StringUtils.hasText(extensions.skillInstructions())) {
			sysPrompt = (sysPrompt == null ? "" : sysPrompt) + System.lineSeparator() + System.lineSeparator()
					+ extensions.skillInstructions();
		}
		// 不把 AutoContext Memory saveTo as2:：短记忆只由 V2AgentStateStore 持有，压缩交给 Harness。
		// 工具上下文走 2.0 builder.toolExecutionContext（本轮 graphRequest / 技能快照）。
		HarnessAgent agent = factory.create(runContext.model(), sysPrompt,
				extensions == null ? null : extensions.toolkit(), harnessHooks(extensions),
				extensions == null ? 0 : extensions.maxIterations(), null, factory.snapshotFor(request),
				extensions == null ? null : extensions.toolExecutionContext(),
				extensions == null ? null : extensions.toolExecutionConfig());
		V2EventToAgentResponseMapper.StreamMapState mapState = new V2EventToAgentResponseMapper.StreamMapState();
		AtomicReference<Msg> result = new AtomicReference<>();
		StringBuilder fallbackText = new StringBuilder();
		UserMessage userMessage = factory.userMessage(runContext.userPrompt(), runContext.userContentBlocks());
		Duration timeout = runContext.timeout();
		try {
			Flux<AgentEvent> events = agent
				.streamEvents(userMessage, factory.runtimeContext(request, reasoningProtocol))
				.doOnNext(event -> consumeHarnessEvent(request, event, mapState, eventPublisher, agent, result,
						fallbackText));
			if (timeout == null || timeout.isZero() || timeout.isNegative()) {
				events.blockLast();
			}
			else {
				events.blockLast(timeout);
			}
			Msg response = result.get();
			if (response != null) {
				return response;
			}
			return Msg.builder()
				.name("assistant")
				.role(MsgRole.ASSISTANT)
				.textContent(fallbackText.toString())
				.build();
		}
		catch (AgentBudgetExceededException ex) {
			// 预算到点（墙钟/token/费用/轮次）但已有流式正文：优雅收口为部分答案，走成功路径落库。
			// 取消路径不在此处理：CancellationException 不是预算异常，仍由 runManagedAgent 按取消语义收口。
			String partialText = fallbackText.toString();
			if (!StringUtils.hasText(partialText)) {
				throw ex;
			}
			state.budgetPartial = true;
			log.warn("Agent budget exceeded with partial answer, threadId={}, runtimeRequestId={}, phase={}, "
					+ "completedRounds={}, elapsedMs={}, textLength={}", request.getThreadId(),
					request.getRuntimeRequestId(), ex.getPhase(), ex.getCompletedRounds(), ex.getElapsedMs(),
					partialText.length());
			runtimeProgressService.emit(request, "HINT", AgentRuntimeProgressService.STATUS_SUCCESS);
			return Msg.builder()
				.name("assistant")
				.role(MsgRole.ASSISTANT)
				.textContent(partialText + BUDGET_PARTIAL_ANSWER_SUFFIX)
				.build();
		}
		finally {
			agent.close();
		}
	}

	/**
	 * Harness SSE 只走 {@code V2EventToAgentResponseMapper}。
	 * HumanFeedbackHook 保留（反馈文本已合并进 user prompt，Hook 作双保险）。
	 */
	static List<Hook> harnessHooks(AgentRuntimeExtensions extensions) {
		if (extensions == null || extensions.hooks() == null || extensions.hooks().isEmpty()) {
			return List.of();
		}
		List<Hook> filtered = new ArrayList<>();
		for (Hook hook : extensions.hooks()) {
			if (hook instanceof AgentScopeStreamingHook || hook instanceof AutoContextHook) {
				continue;
			}
			filtered.add(hook);
		}
		return List.copyOf(filtered);
	}

	private void consumeHarnessEvent(AgentRequest request, AgentEvent event,
			V2EventToAgentResponseMapper.StreamMapState mapState, AgentRuntimeEventPublisher eventPublisher,
			HarnessAgent agent, AtomicReference<Msg> result, StringBuilder fallbackText) {
		if (!runtimeRegistry.isActive(request.getThreadId(), request.getRuntimeRequestId())) {
			agent.interrupt();
			throw new CancellationException("cancelled");
		}
		if (event instanceof AgentResultEvent resultEvent && resultEvent.getResult() != null) {
			result.set(resultEvent.getResult());
		}
		if (event instanceof ToolResultEndEvent toolResult && shouldEmitHarnessSearchResultSet(toolResult)) {
			agentRuntimeExtensionFactory.emitSearchResultSet(request, eventPublisher);
		}
		for (ServerSentEvent<AgentResponse> sse : harnessAgentFactory().eventMapper().map(event, request, mapState)) {
			publishHarnessMappedEvent(request, sse, eventPublisher, fallbackText);
		}
	}

	private static boolean shouldEmitHarnessSearchResultSet(ToolResultEndEvent event) {
		return event != null && event.getState() == ToolResultState.SUCCESS
				&& AgentModelToolName.DATASOURCE_SKILL_SEARCH.equals(event.getToolCallName());
	}

	private void publishHarnessMappedEvent(AgentRequest request, ServerSentEvent<AgentResponse> sse,
			AgentRuntimeEventPublisher eventPublisher, StringBuilder fallbackText) {
		String name = sse.event();
		if (STREAM_EVENT_MESSAGE.equals(name) && sse.data() != null) {
			if (StringUtils.hasText(sse.data().getText())) {
				fallbackText.append(sse.data().getText());
			}
			if (eventPublisher != null) {
				eventPublisher.publish(sse.data());
			}
			return;
		}
		if (AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS.equals(name) && sse.data() != null) {
			Map<String, Object> metadata = sse.data().getMetadata();
			String stage = metadata == null ? "AGENT_PROGRESS"
					: String.valueOf(metadata.getOrDefault("stageCode", "AGENT_PROGRESS"));
			String status = metadata == null ? AgentRuntimeProgressService.STATUS_RUNNING
					: String.valueOf(metadata.getOrDefault("status", AgentRuntimeProgressService.STATUS_RUNNING));
			String displayName = metadata == null ? null : stringMetadata(metadata.get("displayName"));
			String toolName = metadata == null ? null : stringMetadata(metadata.get("toolName"));
			if ("TOOL_RUNNING".equals(stage)) {
				runtimeProgressService.emitToolRunning(request, firstText(toolName, displayName), displayName);
			}
			else if ("TOOL_FINISHED".equals(stage)) {
				runtimeProgressService.emitToolFinished(request, firstText(toolName, displayName), displayName, status,
						0L);
			}
			else {
				runtimeProgressService.emit(request, stage, status);
			}
			return;
		}
		if (STREAM_EVENT_ERROR.equals(name)) {
			String text = sse.data() == null || !StringUtils.hasText(sse.data().getText()) ? "智能体运行失败"
					: sse.data().getText();
			throw new IllegalStateException(text);
		}
	}

	private static String stringMetadata(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		return StringUtils.hasText(text) ? text : null;
	}

	/** 阶段：落库与终态分类——解释快照，失败终态转异常，成功则完成本轮。短记忆由 as2: StateStore 承担。 */
	private String completeAgentTurn(AgentRequest request, Span rootSpan, ReactRuntimeContext runtimeContext,
			Msg response, AgentExecutionState state) {
		emitRuntimeProgress(request, "ANSWER_COMPOSING", AgentRuntimeProgressService.STATUS_RUNNING);
		long persistStart = System.nanoTime();
		rootSpan.setStatus(StatusCode.OK);
		TerminalResult terminalResult = TERMINAL_CLASSIFIER.classify(response);
		if (terminalResult.outcome() == AgentRuntimeTerminalOutcome.BUSINESS_FAILED
				|| terminalResult.outcome() == AgentRuntimeTerminalOutcome.TOOL_FAILED) {
			recordTerminalOutcome(state.toolMetrics, terminalResult.outcome());
			if ("TOOL_CALL_LIMIT_EXCEEDED".equals(terminalResult.errorCode())) {
				throw runtimeContext.runtimeMetrics().toolBudgetExceededException();
			}
			throw new AgentRuntimeToolFailureException(terminalResult.failureMessage());
		}
		if (terminalResult.outcome() == AgentRuntimeTerminalOutcome.MODEL_PROTOCOL_ERROR) {
			if (canUseSearchWrapUpAsAnswer(state.toolMetrics, terminalResult)) {
				terminalResult = new TerminalResult(AgentRuntimeTerminalOutcome.SUCCESS,
						state.toolMetrics.searchExecutionNoProgressMessage(), null, null);
			}
			else {
				recordTerminalOutcome(state.toolMetrics, terminalResult.outcome());
				throw new AgentRuntimeProtocolException(terminalResult.failureMessage(), terminalResult.errorCode());
			}
		}
		if (terminalResult.outcome() == AgentRuntimeTerminalOutcome.RUNTIME_FAILED) {
			recordTerminalOutcome(state.toolMetrics, terminalResult.outcome());
			if ("BUDGET_EXCEEDED".equals(terminalResult.errorCode())) {
				throw runtimeContext.runtimeMetrics().modelBudgetExceededException(1L);
			}
			throw new IllegalStateException(firstText(terminalResult.failureMessage(),
					AgentRuntimeErrorCode.UNKNOWN.getLabel()));
		}
		String answer = terminalResult.answer();
		answerTraceExplainStore.recordFinalAnswer(answer);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeAnswer = finalizePublicAnswer(request, answer);
		mirrorExplainSummary(rootSpan, request);
		extractLongTermMemorySafely(request, safeAnswer);
		state.persistMs = elapsedMs(persistStart);
		chatTurnService.completeTurn(request, safeAnswer, 0L, toolCount(state.toolMetrics),
				toolFailCount(state.toolMetrics), explainSnapshot);
		recordTerminalOutcome(state.toolMetrics, AgentRuntimeTerminalOutcome.SUCCESS);
		emitRuntimeProgress(request, "ANSWER_COMPOSING", AgentRuntimeProgressService.STATUS_SUCCESS,
				state.persistMs);
		emitRuntimeProgress(request, "DONE", AgentRuntimeProgressService.STATUS_SUCCESS,
				elapsedMs(state.totalStart));
		state.successfulAnswer = safeAnswer;
		return safeAnswer;
	}

	private AgentRuntimeToolMetrics toolMetrics(AgentRuntimeExtensions runtimeExtensions) {
		if (runtimeExtensions == null || runtimeExtensions.attributes() == null) {
			return null;
		}
		Object value = runtimeExtensions.attributes().get("toolMetrics");
		return value instanceof AgentRuntimeToolMetrics metrics ? metrics : null;
	}

	private AgentRuntimeToolMetrics pendingInteractionMetrics(AgentRequest request) {
		if (request == null) {
			return null;
		}
		AgentRuntimeToolMetrics metrics = new AgentRuntimeToolMetrics(
				dataAgentProperties.getRuntime().getNoProgressMaxCompletedIdenticalCalls(), request.getMaxModelCalls(),
				request.getMaxToolCalls(), request.getMaxPromptTokens());
		metrics.configureEmptySearchNoProgress(dataAgentProperties.getRuntime().isEmptySearchNoProgressEnabled(),
				dataAgentProperties.getRuntime().getEmptySearchNoProgressMaxConsecutive());
		return metrics;
	}

	private int toolCount(AgentRuntimeToolMetrics toolMetrics) {
		return toolMetrics == null ? 0 : toolMetrics.toolCount();
	}

	private int toolFailCount(AgentRuntimeToolMetrics toolMetrics) {
		return toolMetrics == null ? 0 : toolMetrics.toolFailCount();
	}

	@Override
	public String executeAgentOnce(AgentRequest request,
			Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector) {
		return executeSingleTurn(request, uiCollector);
	}

	private void recordTerminalOutcome(AgentRuntimeToolMetrics toolMetrics, AgentRuntimeTerminalOutcome outcome) {
		if (toolMetrics != null) {
			toolMetrics.recordTerminalOutcome(outcome);
		}
	}

	private AgentRuntimeTerminalOutcome terminalOutcome(AgentRuntimeToolMetrics toolMetrics) {
		return toolMetrics == null ? AgentRuntimeTerminalOutcome.IN_PROGRESS : toolMetrics.terminalOutcome();
	}

	private void recordRoute(AgentRequest request, Span rootSpan, RouteDecision route, long routeMs) {
		request.setRoutedSkillCode(null);
		request.setRoutedSkillId(null);
		request.setRoutedSkillVersionId(null);
		request.setRoutedSkillResources(null);
		request.setRoutedSkillInstructions(null);
		request.setSkillBusinessContext(null);
		request.setRoutedSkillReportProfile(null);
		request.setRoutedSkillExecutionMode(null);
		request.setRouteResult(route);
		request.setRouteDecision(route == null ? RouteDecisionType.ROUTE_UNAVAILABLE.name() : route.decision().name());
		request.setRouteReasonCode(route == null ? "NO_DECISION" : route.reasonCode());
		request.setRouteDurationMs(routeMs);
		rootSpan.setAttribute("dataagent.skill_route.decision", request.getRouteDecision());
		rootSpan.setAttribute("dataagent.skill_route.reason_code", defaultText(request.getRouteReasonCode()));
		rootSpan.setAttribute("dataagent.skill_route.target_count", route == null ? 0 : route.selections().size());
		rootSpan.setAttribute("dataagent.skill_route.duration_ms", routeMs);
	}

	private SelectedSkillRoute resolveSelectedSkill(AgentRequest request, RouteSelection selection) {
		if (selection == null || selection.target() == null
				|| selection.target().targetType() != RouteTargetType.SKILL) {
			throw new RouteUnavailableException("SKILL_SELECTION_INVALID");
		}
		DataAgentSkill skill = skillMapper.selectById(selection.target().targetId());
		DataAgentSkillVersion version = skillVersionMapper.selectById(selection.target().targetVersionId());
		if (skill == null || version == null || skill.getId() == null || version.getId() == null
				|| !Objects.equals(skill.getId(), version.getSkillId())
				|| !Objects.equals(request.getTenantIdSnapshot(), skill.getTenantId())
				|| !Objects.equals(request.getTenantIdSnapshot(), version.getTenantId())
				|| !"PUBLISHED".equals(skill.getStatus()) || !"PUBLISHED".equals(version.getStatus())
				|| !StringUtils.hasText(version.getExecutionMode()) || !StringUtils.hasText(version.getSkillKind())) {
			throw new IllegalStateException("Routed Skill must reference its published version");
		}
		SkillExecutionMode executionMode;
		try {
			executionMode = SkillExecutionMode.valueOf(version.getExecutionMode());
		}
		catch (IllegalArgumentException ex) {
			throw new RouteUnavailableException("SKILL_EXECUTION_MODE_INVALID");
		}
		var resources = skillVersionResourceLoader.load(version);
		if (!Objects.equals(resources.skillId(), skill.getId())
				|| !Objects.equals(resources.skillVersionId(), version.getId())) {
			throw new IllegalStateException("Routed Skill resource snapshot identity is invalid");
		}
		request.setRoutedSkillCode(skill.getSkillCode());
		request.setRoutedSkillId(skill.getId());
		request.setRoutedSkillVersionId(version.getId());
		request.setRoutedSkillResources(resources);
		request.setRoutedSkillInstructions(version.getSkillMarkdown());
		request.setRoutedSkillReportProfile(SkillReportProfileParser.parse(version.getSkillMarkdown()));
		request.setRoutedSkillExecutionMode(executionMode);
		if (executionMode == SkillExecutionMode.REACT) {
			applySkillReactLimits(request, version);
		}
		return new SelectedSkillRoute(selection, skill, version, executionMode);
	}

	private void applySkillDuration(AgentRequest request, SkillExecutionMode mode, long durationMs) {
		if (mode == SkillExecutionMode.KNOWLEDGE) {
			request.setKnowledgeDurationMs(durationMs);
		}
		else if (mode == SkillExecutionMode.FLOW) {
			request.setFlowDurationMs(durationMs);
		}
		else if (mode == SkillExecutionMode.REACT) {
			request.setReactDurationMs(durationMs);
		}
	}

	private void emitSkillUi(AgentRuntimeEventPublisher eventPublisher, AgentRequest request,
			com.sn68.agent.dataagent.ui.AgentUiMessage uiMessage,
			Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector) {
		if (uiMessage == null) {
			return;
		}
		if (uiCollector != null) {
			uiCollector.accept(uiMessage);
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("uiSchemaVersion", "agent-ui/v2");
		metadata.put("uiSource", "skill-runtime");
		metadata.put("messageType", AgentSessionConstant.MESSAGE_TYPE_SKILL_FLOW);
		metadata.put("agentUi", uiMessage);
		Map<String, Object> publicMetadata = sanitizePublicMetadata(metadata, request);
		try {
			chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(parseRequiredThreadId(request.getThreadId()))
				.role("assistant")
				.content(defaultText(uiMessage.content() == null ? null : uiMessage.content().text()))
				.messageType(AgentSessionConstant.MESSAGE_TYPE_SKILL_FLOW)
				.metadata(objectMapper.writeValueAsString(publicMetadata))
				.build(), parseRequiredAgentId(request.getAgentId()));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to persist Skill UI message", ex);
		}
		if (eventPublisher == null) {
			return;
		}
		eventPublisher.publish(AgentResponse.builder()
			.agentId(request.getAgentId())
			.threadId(request.getThreadId())
			.nodeName("SkillRuntime")
			.textType(TextType.JSON)
			.text(defaultText(uiMessage.content() == null ? null : uiMessage.content().text()))
			.metadata(publicMetadata)
			.build());
	}

	private String resumeOrchestrationInteraction(AgentRequest parentRequest, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, PendingResolution pendingResolution, DataAgent orchestrator, ModelConfigDTO modelConfig,
			AgentExecutionState state) {
		long totalStart = state.totalStart;
		RoutePendingService.OrchestrationContinuation continuation = pendingResolution.orchestrationContinuation();
		OrchestrationRuntimeSupport.ResumedCollaborator resumed = orchestrationRuntimeSupport.requireResumedCollaborator(
				parentRequest, continuation);
		AgentRequest childRequest = resumed.childRequest();
		childRequest.setRouteClarificationRound(pendingResolution.round());
		RouteDecision routeSnapshot = continuation.orchestrationRouteSnapshot();
		if (pendingResolution.cancelled()) {
			RuntimeTiming timing = new RuntimeTiming(0L, 0, 0, 0L);
			orchestrationRuntimeSupport.markStepCancelled(resumed.step(), childRequest, timing);
			orchestrationRuntimeSupport.markRunCompleted(resumed.run(), OrchestrationStatus.CANCELLED, "已取消本次操作。",
					0L, 0L, 0L, elapsedMs(totalStart), 1);
			finishPendingExecution(parentRequest, RoutePendingService.EXECUTION_CANCELLED);
			return completeDirectAnswer(parentRequest, eventPublisher, rootSpan, "已取消本次操作。",
					"ORCHESTRATION_CANCELLED", totalStart);
		}
		if (continuation.executionPolicy() == null) {
			return failLegacyOrchestrationContinuation(parentRequest, eventPublisher, rootSpan, resumed, childRequest,
					totalStart);
		}
		ResumedOrchestrationPlan resumePlan;
		try {
			resumePlan = validateResumedOrchestration(parentRequest, resumed,
					continuation, routeSnapshot, orchestrator, modelConfig);
		}
		catch (RuntimeException ex) {
			RuntimeTiming timing = new RuntimeTiming(0L, 0, 0, 0L);
			orchestrationRuntimeSupport.markStepFailed(resumed.step(), ex, childRequest, timing);
			orchestrationRuntimeSupport.markRunFailed(resumed.run(), ex, 0L, timing.totalMs(), 0L,
					elapsedMs(totalStart), 1);
			throw ex;
		}
		RuntimeTiming snapshotTiming = new RuntimeTiming(0L, 0, 0, 0L);
		boolean resumedStepUnavailable;
		try {
			resumedStepUnavailable = markUnavailableSnapshotSteps(resumed, resumePlan, childRequest, snapshotTiming);
		}
		catch (RuntimeException ex) {
			orchestrationRuntimeSupport.markStepFailed(resumed.step(), ex, childRequest, snapshotTiming);
			orchestrationRuntimeSupport.markRunFailed(resumed.run(), ex, 0L, snapshotTiming.totalMs(), 0L,
					elapsedMs(totalStart), 1);
			throw ex;
		}
		if (resumedStepUnavailable || isFailFast(resumePlan.policy())
				&& !resumePlan.invalidStepResults().isEmpty()) {
			return continueResumedOrchestration(parentRequest, eventPublisher, rootSpan, resumed, resumePlan, snapshotTiming,
					totalStart);
		}
		long childStart = System.nanoTime();
		AgentRunResult childResult;
		RuntimeTiming childTiming;
		try {
			childResult = executeAgent(childRequest, null, state.uiCollector);
			childTiming = resolveLastRuntimeTiming(elapsedMs(childStart));
		}
		catch (RuntimeException ex) {
			RuntimeTiming timing = resolveLastRuntimeTiming(elapsedMs(childStart));
			log.warn("Resumed collaborator execution failed. agentId={}, runtimeRequestId={}, stepId={}",
					childRequest.getAgentId(), childRequest.getRuntimeRequestId(),
					resumed.step() == null ? null : resumed.step().getId(), ex);
			orchestrationRuntimeSupport.markStepFailed(resumed.step(), ex, childRequest, timing);
			return continueResumedOrchestration(parentRequest, eventPublisher, rootSpan, resumed, resumePlan, timing,
					totalStart);
		}
		finally {
			// executeAgent 内部的 markRunning 走的是 getOrCreateState，会为子请求键凭空建出注册表条目，
			// 而 clearRunning 只把线程置空、不删条目。executeAgent 的另两个调用点各自有 register/finish 配对
			// （流式入口、协作者派发），只有这条续跑路径两头都没有，条目会永久残留在注册表里。
			// 此处执行已经返回（含挂起等待人工交互的分支——那时线程也已让出），没有在途执行需要保留状态。
			runtimeRegistry.finish(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
		}
		String childAnswer = childResult.answer();
		PendingInteraction pendingInteraction = childResult.pendingInteraction();
		if (pendingInteraction != null) {
			orchestrationRuntimeSupport.markStepWaiting(resumed.step(), childRequest, childTiming);
			orchestrationRuntimeSupport.markRunWaiting(resumed.run(), routeDuration(resumed.run()), childTiming.totalMs(), 1);
			return publishParentCollaboratorPendingInteraction(parentRequest, eventPublisher, rootSpan, childAnswer,
					pendingInteraction, totalStart);
		}
		orchestrationRuntimeSupport.markStepSuccess(resumed.step(), childAnswer, childRequest, childTiming);
		attachCollaboratorSnapshotsForReport(parentRequest, childRequest);
		return continueResumedOrchestration(parentRequest, eventPublisher, rootSpan, resumed, resumePlan, childTiming,
				totalStart);
	}

	private String failLegacyOrchestrationContinuation(AgentRequest parentRequest,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, OrchestrationRuntimeSupport.ResumedCollaborator resumed,
			AgentRequest childRequest, long totalStart) {
		IllegalStateException failure = new IllegalStateException("ORCHESTRATION_CONTINUATION_POLICY_SNAPSHOT_MISSING");
		RuntimeTiming timing = new RuntimeTiming(0L, 0, 0, 0L);
		orchestrationRuntimeSupport.markStepFailed(resumed.step(), failure, childRequest, timing);
		orchestrationRuntimeSupport.markRunFailed(resumed.run(), failure, 0L, timing.totalMs(), 0L,
				elapsedMs(totalStart), 1);
		return completeFailedDirectAnswer(parentRequest, eventPublisher, rootSpan,
				"本次编排澄清已失效，请重新发起问题。", "ORCHESTRATION_CONTINUATION_EXPIRED", totalStart);
	}

	private ResumedOrchestrationPlan validateResumedOrchestration(AgentRequest parentRequest,
			OrchestrationRuntimeSupport.ResumedCollaborator resumed,
			RoutePendingService.OrchestrationContinuation continuation, RouteDecision routeSnapshot,
			DataAgent orchestrator, ModelConfigDTO modelConfig) {
		AgentOrchestrationPolicy policy = orchestrationRuntimeSupport.policyFromContinuation(continuation);
		OrchestrationContext context = orchestrationRuntimeSupport.contextForExistingRun(orchestrator, modelConfig, policy,
				resumed.run());
		orchestrationRuntimeSupport.validateResumedStepSnapshot(resumed, continuation);
		OrchestrationRuntimeSupport.SnapshotRouteRestore restored = orchestrationRuntimeSupport.restoreRoutesFromSnapshot(
				parentRequest, context, routeSnapshot);
		List<CollaboratorRoute> routes = List.copyOf(restored.routes());
		if (routes.isEmpty()) {
			throw new IllegalStateException("Orchestration continuation has no executable collaborators");
		}
		return new ResumedOrchestrationPlan(policy, routes, orchestrator, modelConfig, restored.invalidStepResults());
	}

	private boolean markUnavailableSnapshotSteps(OrchestrationRuntimeSupport.ResumedCollaborator resumed,
			ResumedOrchestrationPlan resumePlan, AgentRequest childRequest, RuntimeTiming timing) {
		boolean resumedStepUnavailable = false;
		for (CollaboratorExecutionResult result : resumePlan.invalidStepResults().values()) {
			CollaboratorRoute route = result.route();
			if (route == null || !StringUtils.hasText(route.stepId())) {
				continue;
			}
			if (Objects.equals(route.stepId(), resumed.step().getRouteStepId())) {
				orchestrationRuntimeSupport.markStepFailed(resumed.step(), result.error(), childRequest, timing);
				resumedStepUnavailable = true;
				continue;
			}
			AgentOrchestrationStep failedStep = orchestrationRuntimeSupport.createStep(resumed.run(), route);
			orchestrationRuntimeSupport.markStepFailed(failedStep, result.error());
		}
		return resumedStepUnavailable;
	}

	private String continueResumedOrchestration(AgentRequest parentRequest, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, OrchestrationRuntimeSupport.ResumedCollaborator resumed, ResumedOrchestrationPlan resumePlan,
			RuntimeTiming childTiming, long totalStart) {
		AgentOrchestrationPolicy policy = resumePlan.policy();
		List<CollaboratorRoute> routes = resumePlan.routes();
		boolean exposeTrace = Boolean.TRUE.equals(policy.getExposeTrace());
		long routeMs = routeDuration(resumed.run());
		try {
			Map<String, CollaboratorExecutionResult> restored = new LinkedHashMap<>(
					orchestrationRuntimeSupport.loadTerminalResults(resumed.run(), routes));
			resumePlan.invalidStepResults().forEach(restored::putIfAbsent);
			emitOrchestrationProgress(exposeTrace, parentRequest, "ORCHESTRATION_COLLABORATING",
					AgentRuntimeProgressService.STATUS_RUNNING);
			long collaboratorStart = System.nanoTime();
			List<CollaboratorExecutionResult> results = executeCollaborators(parentRequest, resumed.run(), routes,
					exposeTrace, restored, isFailFast(policy), eventPublisher);
			long collaboratorMs = childTiming.totalMs() + elapsedMs(collaboratorStart);
			List<CollaboratorExecutionResult> waitingResults = results.stream()
				.filter(CollaboratorExecutionResult::waiting)
				.toList();
			if (!waitingResults.isEmpty()) {
				if (waitingResults.size() != 1) {
					throw new IllegalStateException("Only one collaborator interaction can wait at a time");
				}
				orchestrationRuntimeSupport.markRunWaiting(resumed.run(), routeMs, collaboratorMs, routes.size());
				CollaboratorExecutionResult waiting = waitingResults.get(0);
				return publishParentCollaboratorPendingInteraction(parentRequest, eventPublisher, rootSpan, waiting.answer(),
						waiting.pendingInteraction(), totalStart);
			}
			List<CollaboratorExecutionResult> failures = results.stream().filter(result -> !result.success()).toList();
			if (isFailFast(policy) && !failures.isEmpty()) {
				throw propagateCollaboratorFailure(failures.get(0));
			}
			emitOrchestrationProgress(exposeTrace, parentRequest, "ORCHESTRATION_MERGING",
					AgentRuntimeProgressService.STATUS_RUNNING);
			long summaryMs = 0L;
			String answer;
			List<CollaboratorExecutionResult> successes = results.stream()
				.filter(CollaboratorExecutionResult::success)
				.toList();
			if (shouldSummarizeOrchestration(parentRequest, successes)) {
				long summaryStart = System.nanoTime();
				answer = summarizeOrchestration(resumePlan.orchestrator(), parentRequest, resumePlan.modelConfig(),
						results, true);
				summaryMs = elapsedMs(summaryStart);
			}
			else {
				answer = buildDeterministicAnswer(parentRequest, results);
			}
			String status = failures.isEmpty() ? OrchestrationStatus.SUCCESS
					: (successes.isEmpty() ? OrchestrationStatus.FAILED : OrchestrationStatus.PARTIAL_SUCCESS);
			orchestrationRuntimeSupport.markRunCompleted(resumed.run(), status, answer, routeMs, collaboratorMs,
					summaryMs, elapsedMs(totalStart), routes.size());
			emitOrchestrationProgress(exposeTrace, parentRequest, "ORCHESTRATION_MERGING",
					AgentRuntimeProgressService.STATUS_SUCCESS, summaryMs);
			return completeDirectAnswer(parentRequest, eventPublisher, rootSpan, answer, "ORCHESTRATION_RESUMED_DONE",
					totalStart);
		}
		catch (RuntimeException ex) {
			orchestrationRuntimeSupport.markRunFailed(resumed.run(), ex, routeMs, childTiming.totalMs(), 0L,
					elapsedMs(totalStart), 1);
			throw ex;
		}
	}

	private long routeDuration(AgentOrchestrationRun run) {
		return run == null || run.getRouteMs() == null ? 0L : run.getRouteMs();
	}

	private String completeDirectAnswer(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			String answer, String stageCode, long totalStart) {
		String normalized = defaultText(answer);
		if (eventPublisher != null && StringUtils.hasText(normalized)) {
			eventPublisher.publish(AgentResponse.builder()
				.agentId(request.getAgentId())
				.threadId(request.getThreadId())
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text(normalized)
				.metadata(buildFinalAnswerMetadata(request))
				.build());
		}
		rootSpan.setStatus(StatusCode.OK);
		answerTraceExplainStore.recordFinalAnswer(normalized);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeAnswer = sanitizePublicText(normalized, request);
		chatTurnService.completeTurn(request, safeAnswer, 0L, 0, 0, explainSnapshot);
		finishPendingExecution(request, RoutePendingService.EXECUTION_SUCCEEDED);
		emitRuntimeProgress(request, stageCode, AgentRuntimeProgressService.STATUS_SUCCESS);
		emitRuntimeProgress(request, "DONE", AgentRuntimeProgressService.STATUS_SUCCESS, elapsedMs(totalStart));
		return safeAnswer;
	}

	private void finishPendingExecution(AgentRequest request, String executionState) {
		if (request == null || !StringUtils.hasText(request.getRuntimeRequestId())) {
			return;
		}
		try {
			routePendingService.finishExecution(request, executionState, request.getRuntimeRequestId());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to persist route pending execution state. agentId={}, threadId={}, runtimeRequestId={}, state={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), executionState, ex);
		}
	}

	private String completeFailedDirectAnswer(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, String answer, String stageCode, long totalStart) {
		String normalized = defaultText(answer);
		if (eventPublisher != null && StringUtils.hasText(normalized)) {
			eventPublisher.publish(AgentResponse.builder()
				.agentId(request.getAgentId())
				.threadId(request.getThreadId())
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text(normalized)
				.metadata(buildFinalAnswerMetadata(request))
				.build());
		}
		IllegalStateException failure = new IllegalStateException(firstText(normalized, "Skill execution failed"));
		rootSpan.setStatus(StatusCode.ERROR, failure.getMessage());
		answerTraceExplainStore.recordFinalAnswer(normalized);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeAnswer = sanitizePublicText(normalized, request);
		chatTurnService.completeFailedTurn(request, safeAnswer, failure, explainSnapshot);
		finishPendingExecution(request, RoutePendingService.EXECUTION_FAILED);
		emitRuntimeProgress(request, stageCode, AgentRuntimeProgressService.STATUS_FAILED);
		emitRuntimeProgress(request, "FAILED", AgentRuntimeProgressService.STATUS_FAILED, elapsedMs(totalStart));
		dispatchAfterAgentFailed(request, failure);
		return safeAnswer;
	}

	private String waitForSkillAnswer(AgentRequest request, Span rootSpan, String answer, String stageCode) {
		String normalized = defaultText(answer);
		answerTraceExplainStore.recordFinalAnswer(normalized);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeAnswer = sanitizePublicText(normalized, request);
		chatTurnService.waitForClarification(request, safeAnswer, explainSnapshot);
		finishPendingExecution(request, RoutePendingService.EXECUTION_FORWARDED);
		mirrorExplainSummary(rootSpan, request);
		rootSpan.setStatus(StatusCode.OK, "skill waiting for input");
		emitRuntimeProgress(request, stageCode, AgentRuntimeProgressService.STATUS_WAITING);
		emitRuntimeProgress(request, "WAITING", AgentRuntimeProgressService.STATUS_WAITING);
		return safeAnswer;
	}

	private void emitRuntimeProgress(AgentRequest request, String stageCode, String status) {
		runtimeProgressService.emit(request, stageCode, status);
	}

	private void emitRuntimeProgress(AgentRequest request, String stageCode, String status, long durationMs) {
		runtimeProgressService.emit(request, stageCode, status, durationMs);
	}

	private void rejectEmployeeStreamBypass(AgentRequest request, DataChatSession session) {
		if (request != null && request.isEmployeeFacadeStream()) {
			return;
		}
		boolean employeeOwner = request != null
				&& "DIGITAL_EMPLOYEE".equalsIgnoreCase(request.getOwnerType());
		boolean employeeSession = session != null
				&& ChatSessionChannelDict.EMPLOYEE.getValue().equalsIgnoreCase(session.getChannelType());
		if (employeeOwner || employeeSession) {
			throw CheckedException.badRequest(ChannelSessionErrorDict.EMPLOYEE_FACADE_REQUIRED.getValue(),
					ChannelSessionErrorDict.EMPLOYEE_FACADE_REQUIRED.getLabel());
		}
	}

	private void validateStreamSessionAccess(AgentRequest request, DataChatSession session) {
		if (request != null && request.isEmployeeFacadeStream()) {
			return;
		}
		if (session == null) {
			return;
		}
		validateSessionRuntimeAccess(request, session);
	}

	private void validateSessionRuntimeAccess(AgentRequest request, DataChatSession session) {
		if (isWebTakeover(request)) {
			if (!thinkingPermissionService.canViewAnyDiagnostics()) {
				throw CheckedException.badRequest(ChannelSessionErrorDict.TAKEOVER_FORBIDDEN.getValue(),
						ChannelSessionErrorDict.TAKEOVER_FORBIDDEN.getLabel());
			}
			return;
		}
		if (!isWebChannel(session)) {
			throw CheckedException.badRequest(ChannelSessionErrorDict.WEB_SESSION_REQUIRED.getValue(),
					ChannelSessionErrorDict.WEB_SESSION_REQUIRED.getLabel());
		}
		requireWebSessionOwner(request, session);
	}

	private boolean isWebTakeover(AgentRequest request) {
		return request != null
				&& AgentRequestSourceDict.WEB_TAKEOVER.getValue().equalsIgnoreCase(request.getRequestSource());
	}

	private boolean isWebChannel(DataChatSession session) {
		return session != null
				&& ChatSessionChannelDict.WEB.getValue().equalsIgnoreCase(session.getChannelType());
	}

	private void requireWebSessionOwner(AgentRequest request, DataChatSession session) {
		String currentUserId = request == null ? null : request.getUserIdSnapshot();
		if (!StringUtils.hasText(currentUserId)) {
			throw CheckedException.forbidden(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel());
		}
		String current = currentUserId.trim();
		Long ownerUserId = session.getUserId();
		if (ownerUserId != null) {
			if (!current.equals(String.valueOf(ownerUserId))) {
				throw CheckedException.forbidden(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel());
			}
			return;
		}
		if (StringUtils.hasText(session.getCreateBy()) && current.equals(session.getCreateBy().trim())) {
			return;
		}
		throw CheckedException.forbidden(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel());
	}

	private void dispatchAfterAgentSuccess(AgentRequest request, String answer, AgentRuntimeToolMetrics toolMetrics) {
		try {
			runtimeHookDispatcher.dispatchAfterAgentSuccess(parseAgentId(request == null ? null : request.getAgentId()),
					request == null ? null : request.getThreadId(), request == null ? null : request.getRuntimeRequestId(),
					runtimeInput(request), runtimeOutput(request, answer, toolMetrics));
		}
		catch (Exception ex) {
			log.warn("Failed to dispatch agent success runtime hook. agentId={}, runtimeRequestId={}",
					request == null ? null : request.getAgentId(),
					request == null ? null : request.getRuntimeRequestId(), ex);
		}
	}

	private void dispatchAfterAgentFailed(AgentRequest request, RuntimeException error) {
		try {
			runtimeHookDispatcher.dispatchAfterAgentFailed(parseAgentId(request == null ? null : request.getAgentId()),
					request == null ? null : request.getThreadId(), request == null ? null : request.getRuntimeRequestId(),
					runtimeInput(request), runtimeFailureOutput(error));
		}
		catch (Exception hookEx) {
			log.warn("Failed to dispatch agent failure runtime hook. agentId={}, runtimeRequestId={}",
					request == null ? null : request.getAgentId(),
					request == null ? null : request.getRuntimeRequestId(), hookEx);
		}
	}

	private Map<String, Object> runtimeInput(AgentRequest request) {
		Map<String, Object> input = new LinkedHashMap<>();
		if (request == null) {
			return input;
		}
		input.put("query", request.getQuery());
		input.put("agentId", request.getAgentId());
		input.put("sessionId", request.getThreadId());
		input.put("runtimeRequestId", request.getRuntimeRequestId());
		input.put("responseMode", request.getResponseMode());
		input.put("requestSource", request.getRequestSource());
		input.put("executionIntent", request.getExecutionIntent());
		input.put("ownerType", request.getOwnerType());
		input.put("ownerId", request.getOwnerId());
		input.put("tenantIdSnapshot", request.getTenantIdSnapshot());
		input.put("tenantCodeSnapshot", request.getTenantCodeSnapshot());
		input.put("userIdSnapshot", request.getUserIdSnapshot());
		input.put("clientIdSnapshot", request.getClientIdSnapshot());
		return input;
	}

	private Map<String, Object> runtimeOutput(AgentRequest request, String answer, AgentRuntimeToolMetrics toolMetrics) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("message", answer);
		output.put("answer", answer);
		output.put("toolCount", toolCount(toolMetrics));
		output.put("toolFailCount", toolFailCount(toolMetrics));
		output.put("routeMs", request == null ? null : request.getRouteDurationMs());
		output.put("reactDurationMs", request == null ? null : request.getReactDurationMs());
		if (toolMetrics != null) {
			output.put("terminalOutcome", toolMetrics.terminalOutcome().name());
			output.put("protocolRepairCount", toolMetrics.protocolRepairCount());
			output.put("clarificationReason", toolMetrics.clarificationReason());
			output.put("toolDurationMs", toolMetrics.toolDurationMs());
			output.put("modelCallCount", toolMetrics.modelCallCount());
			output.put("modelRetryCount", toolMetrics.modelRetryCount());
			output.put("modelDurationMs", toolMetrics.modelDurationMs());
			output.put("promptTokens", toolMetrics.promptTokens());
			output.put("peakPromptTokens", toolMetrics.peakPromptTokens());
			output.put("completionTokens", toolMetrics.completionTokens());
			output.put("reactIterationCount", toolMetrics.reactIterationCount());
			output.put("maxIterationsReached", toolMetrics.maxIterationsReached());
			output.put("lastToolFailure", toolMetrics.lastFailureMessage());
		}
		return output;
	}

	private Map<String, Object> runtimeFailureOutput(RuntimeException error) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("message", error == null ? "Agent runtime failed." : error.getMessage());
		output.put("errorType", error == null ? null : error.getClass().getSimpleName());
		return output;
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

	private PreparedMemory prepareMemory(AgentRequest request) {
		if (request != null && request.isIsolatedMemory()) {
			return new PreparedMemory(new InMemoryMemory(), false);
		}
		return agentScopeMemoryFactory.create(request);
	}

	private OrchestrationExecutionResult executeLightweightOrchestration(AgentRequest request, DataAgent orchestrator, ModelConfigDTO modelConfig,
			AgentOrchestrationPolicy policy, RouteDecision routeDecision,
			AgentRuntimeEventPublisher eventPublisher) {
		long totalStart = System.nanoTime();
		long routeMs = 0L;
		long collaboratorMs = 0L;
		long summaryMs = 0L;
		int collaboratorCount = 0;
		OrchestrationContext context = orchestrationRuntimeSupport.prepareOrCreateContext(request, orchestrator,
				modelConfig, policy);
		AgentOrchestrationRun run = context.run();
		boolean exposeTrace = context.policy() != null && Boolean.TRUE.equals(context.policy().getExposeTrace());
		try {
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_ROUTING",
					AgentRuntimeProgressService.STATUS_RUNNING);
			List<CollaboratorRoute> selectedRoutes = orchestrationRuntimeSupport.routesFromSelections(request, context,
					routeDecision);
			routeMs = request.getRouteDurationMs() == null ? routeDecision.timing().totalMs()
					: request.getRouteDurationMs();
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_ROUTING",
					AgentRuntimeProgressService.STATUS_SUCCESS, routeMs);
			Integer configuredMaxRoutes = context.policy() == null ? null
					: context.policy().getMaxCollaboratorsPerRun();
			if (configuredMaxRoutes == null || configuredMaxRoutes < 1) {
				throw new IllegalStateException("编排策略缺少有效的maxCollaboratorsPerRun");
			}
			int maxRoutes = configuredMaxRoutes;
			List<CollaboratorRoute> routes = orchestrationRuntimeSupport.normalizeRoutes(selectedRoutes, maxRoutes);
			collaboratorCount = routes.size();
			if (routes.isEmpty()) {
				throw CheckedException.badRequest("编排路由未返回可执行协作者");
			}
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_COLLABORATING",
					AgentRuntimeProgressService.STATUS_RUNNING);
			long collaboratorStart = System.nanoTime();
			List<CollaboratorExecutionResult> results = executeCollaborators(request, run, routes, exposeTrace,
					isFailFast(context.policy()), eventPublisher);
			collaboratorMs = elapsedMs(collaboratorStart);
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_COLLABORATING",
					AgentRuntimeProgressService.STATUS_SUCCESS, collaboratorMs);
			List<CollaboratorExecutionResult> waitingResults = results.stream()
				.filter(CollaboratorExecutionResult::waiting)
				.toList();
			if (!waitingResults.isEmpty()) {
				if (waitingResults.size() != 1) {
					throw new IllegalStateException("Only one collaborator interaction can wait at a time");
				}
				orchestrationRuntimeSupport.markRunWaiting(run, routeMs, collaboratorMs, collaboratorCount);
				return OrchestrationExecutionResult.waiting(waitingResults.get(0));
			}
			boolean failFast = isFailFast(context.policy());
			List<CollaboratorExecutionResult> failures = results.stream().filter(result -> !result.success()).toList();
			if (failFast && !failures.isEmpty()) {
				throw propagateCollaboratorFailure(failures.get(0));
			}
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_MERGING",
					AgentRuntimeProgressService.STATUS_RUNNING);
			String answer;
			List<CollaboratorExecutionResult> successes = results.stream()
				.filter(CollaboratorExecutionResult::success).toList();
			if (shouldSummarizeOrchestration(request, successes)) {
				long summaryStart = System.nanoTime();
				answer = summarizeOrchestration(orchestrator, request, modelConfig, results, true);
				summaryMs = elapsedMs(summaryStart);
			}
			else {
				answer = buildDeterministicAnswer(request, results);
			}
			long totalMs = elapsedMs(totalStart);
			String status = failures.isEmpty() ? OrchestrationStatus.SUCCESS
					: (successes.isEmpty() ? OrchestrationStatus.FAILED : OrchestrationStatus.PARTIAL_SUCCESS);
			orchestrationRuntimeSupport.markRunCompleted(run, status, answer, routeMs, collaboratorMs,
					summaryMs, totalMs, collaboratorCount);
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_MERGING",
					AgentRuntimeProgressService.STATUS_SUCCESS, summaryMs);
			log.info(
					"Lightweight orchestration timing. agentId={}, threadId={}, runtimeRequestId={}, source={}, collaboratorCount={}, routeMs={}, collaboratorMs={}, summaryMs={}, totalMs={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), routeDecision.reasonCode(),
					collaboratorCount, routeMs, collaboratorMs, summaryMs, totalMs);
			return OrchestrationExecutionResult.completed(answer);
		}
		catch (RuntimeException ex) {
			orchestrationRuntimeSupport.markRunFailed(run, ex, routeMs, collaboratorMs, summaryMs,
					elapsedMs(totalStart), collaboratorCount);
			emitOrchestrationProgress(exposeTrace, request, "ORCHESTRATION_FAILED",
					AgentRuntimeProgressService.STATUS_FAILED, elapsedMs(totalStart));
			throw ex;
		}
	}

	private List<CollaboratorExecutionResult> executeCollaborators(AgentRequest parentRequest,
			AgentOrchestrationRun run, List<CollaboratorRoute> routes, boolean exposeTrace,
			boolean failFast, AgentRuntimeEventPublisher eventPublisher) {
		return executeCollaborators(parentRequest, run, routes, exposeTrace, Map.of(), failFast, eventPublisher);
	}

	private List<CollaboratorExecutionResult> executeCollaborators(AgentRequest parentRequest,
			AgentOrchestrationRun run, List<CollaboratorRoute> routes, boolean exposeTrace,
			Map<String, CollaboratorExecutionResult> restoredResults, boolean failFast,
			AgentRuntimeEventPublisher eventPublisher) {
		DataAgentAsyncContextBridge.Snapshot asyncContext = asyncContextBridge.capture();
		validateCollaboratorDependencies(routes);
		List<CollaboratorRoute> pending = new ArrayList<>(routes);
		Map<String, Boolean> completedSteps = new HashMap<>();
		Map<String, CollaboratorExecutionResult> completedResults = new HashMap<>();
		Map<CollaboratorRoute, CollaboratorExecutionResult> results = new LinkedHashMap<>();
		for (CollaboratorRoute route : routes) {
			if (route == null || !StringUtils.hasText(route.stepId()) || restoredResults == null) {
				continue;
			}
			CollaboratorExecutionResult restored = restoredResults.get(route.stepId());
			if (restored == null) {
				continue;
			}
			results.put(route, restored);
			completedSteps.put(route.stepId(), restored.success());
			completedResults.put(route.stepId(), restored);
		}
		pending.removeIf(results::containsKey);
		if (failFast) {
			CollaboratorExecutionResult restoredFailure = results.values().stream().filter(result -> !result.success())
				.findFirst().orElse(null);
			if (restoredFailure != null) {
				throw propagateCollaboratorFailure(restoredFailure);
			}
		}
		if (!pending.isEmpty() && isEventDrivenSchedulerEnabled()) {
			List<CollaboratorExecutionResult> scheduled = eventDrivenCollaboratorEngine.tryExecute(run, routes,
					completedResults, failFast,
					scheduledCollaboratorRunner(parentRequest, run, exposeTrace, asyncContext, eventPublisher));
			if (scheduled != null) {
				return scheduled;
			}
			log.info("权威运行镜像不可用或路由缺少步骤标识，编排回落整批执行. runId={}", run == null ? null : run.getId());
		}
		while (!pending.isEmpty()) {
			List<CollaboratorRoute> blocked = pending.stream()
				.filter(route -> route.dependsOn().stream()
					.anyMatch(dependency -> Boolean.FALSE.equals(completedSteps.get(dependency))))
				.toList();
			for (CollaboratorRoute route : blocked) {
				AgentOrchestrationStep step = orchestrationRuntimeSupport.createStep(run, route);
				RuntimeException error = new IllegalStateException("前置步骤执行失败，当前协作者未执行");
				orchestrationRuntimeSupport.markStepFailed(step, error);
				results.put(route, new CollaboratorExecutionResult(route, null, error, 0L));
				completedSteps.put(route.stepId(), false);
			}
			pending.removeAll(blocked);
			if (pending.isEmpty()) {
				break;
			}
			if (!blocked.isEmpty()) {
				continue;
			}
			List<CollaboratorRoute> ready = pending.stream()
				.filter(route -> route.dependsOn().stream()
					.allMatch(dependency -> Boolean.TRUE.equals(completedSteps.get(dependency))))
				.toList();
			if (ready.isEmpty()) {
				throw new IllegalStateException("编排计划存在未完成或失败的步骤依赖");
			}
			List<CollaboratorRoute> interactiveRoutes = ready.stream().filter(this::isInteractiveCollaborator).toList();
			if (!interactiveRoutes.isEmpty()) {
				ready = List.of(interactiveRoutes.get(0));
			}
			List<CollaboratorExecutionResult> batchResults = executeCollaboratorBatch(parentRequest, run, ready, exposeTrace,
					asyncContext, completedResults, eventPublisher);
			for (CollaboratorExecutionResult result : batchResults) {
				results.put(result.route(), result);
				if (result.waiting()) {
					return routes.stream().map(results::get).filter(Objects::nonNull).toList();
				}
				if (StringUtils.hasText(result.route().stepId())) {
					completedSteps.put(result.route().stepId(), result.success());
					completedResults.put(result.route().stepId(), result);
				}
			}
			pending.removeAll(ready);
			if (failFast) {
				CollaboratorExecutionResult failure = batchResults.stream().filter(result -> !result.success())
					.findFirst().orElse(null);
				if (failure != null) {
					throw propagateCollaboratorFailure(failure);
				}
			}
		}
		return routes.stream().map(results::get).toList();
	}

	private void validateCollaboratorDependencies(List<CollaboratorRoute> routes) {
		Set<String> stepIds = new HashSet<>();
		for (CollaboratorRoute route : routes) {
			if (route == null || !route.dependsOn().isEmpty() && !StringUtils.hasText(route.stepId())) {
				throw new IllegalStateException("编排路由缺少有效步骤标识");
			}
			if (StringUtils.hasText(route.stepId()) && !stepIds.add(route.stepId())) {
				throw new IllegalStateException("编排路由包含重复步骤标识");
			}
		}
		for (CollaboratorRoute route : routes) {
			if (route.dependsOn().stream().anyMatch(dependency -> !stepIds.contains(dependency))) {
				throw new IllegalStateException("编排路由包含未知步骤依赖");
			}
		}
		Set<String> resolvedSteps = new HashSet<>();
		while (resolvedSteps.size() < stepIds.size()) {
			boolean progressed = false;
			for (CollaboratorRoute route : routes) {
				if (StringUtils.hasText(route.stepId()) && !resolvedSteps.contains(route.stepId())
						&& resolvedSteps.containsAll(route.dependsOn())) {
					resolvedSteps.add(route.stepId());
					progressed = true;
				}
			}
			if (!progressed) {
				throw new IllegalStateException("编排计划存在未完成或失败的步骤依赖");
			}
		}
	}

	private List<CollaboratorExecutionResult> executeCollaboratorBatch(AgentRequest parentRequest,
			AgentOrchestrationRun run, List<CollaboratorRoute> routes, boolean exposeTrace,
			DataAgentAsyncContextBridge.Snapshot asyncContext,
			Map<String, CollaboratorExecutionResult> completedResults, AgentRuntimeEventPublisher eventPublisher) {
		List<CollaboratorTask> tasks = new ArrayList<>();
		for (CollaboratorRoute route : routes) {
			AgentOrchestrationStep step = orchestrationRuntimeSupport.createStep(run, route);
			List<CollaboratorExecutionResult> dependencyResults = route.dependsOn().stream()
				.map(completedResults::get)
				.filter(Objects::nonNull)
				.toList();
			AgentRequest childRequest = orchestrationRuntimeSupport.buildCollaboratorRequest(parentRequest, route,
					dependencyResults);
			childRequest.setRootRuntimeRequestId(firstText(parentRequest == null ? null : parentRequest.getRootRuntimeRequestId(),
					parentRequest == null ? null : parentRequest.getRuntimeRequestId()));
			childRequest.setParentRuntimeRequestId(parentRequest == null ? null : parentRequest.getRuntimeRequestId());
			childRequest.setOrchestrationRunId(run == null ? null : run.getId());
			childRequest.setOrchestrationStepId(step == null ? null : step.getId());
			if (exposeTrace) {
				runtimeProgressService.registerOrchestrationChild(parentRequest, childRequest,
						run == null ? null : run.getId(), step == null ? null : step.getId(),
						route.dataAgent() == null ? null : route.dataAgent().getName(),
						route.collaborator() == null ? null : route.collaborator().getRoleName());
			}
			long submittedAt = System.nanoTime();
			// 提交前先挂到父请求下：协作者有自己的 threadId/runtimeRequestId，不建立关联的话
			// 用户停止编排者会话后，这些子请求收不到取消，会继续调用模型直到自己的 deadline。
			runtimeRegistry.registerChild(parentRequest == null ? null : parentRequest.getThreadId(),
					parentRequest == null ? null : parentRequest.getRuntimeRequestId(), childRequest.getThreadId(),
					childRequest.getRuntimeRequestId());
			try {
				CompletableFuture<CollaboratorExecutionResult> future = CompletableFuture.supplyAsync(
						() -> asyncContextBridge.supplyWith(asyncContext,
								() -> executeCollaborator(parentRequest, route, step, childRequest, eventPublisher)), orchestrationExecutor);
				tasks.add(new CollaboratorTask(route, step, childRequest, future, submittedAt));
			}
			catch (RuntimeException ex) {
				log.warn("Failed to submit collaborator task to the orchestration executor. agentId={}, runtimeRequestId={}, stepId={}",
						childRequest.getAgentId(), childRequest.getRuntimeRequestId(), step == null ? null : step.getId(),
						ex);
				orchestrationRuntimeSupport.markStepFailed(step, ex, childRequest,
						new RuntimeTiming(0L, 0, 0, elapsedMs(submittedAt)));
				runtimeProgressService.unregister(childRequest);
				// 任务没跑起来就没人调用 executeCollaborator 的 finally，这里补上注销，避免关联表残留。
				runtimeRegistry.finish(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
				tasks.add(new CollaboratorTask(route, step, childRequest,
						CompletableFuture.completedFuture(new CollaboratorExecutionResult(route, null, ex,
								elapsedMs(submittedAt))), submittedAt));
			}
		}
		return tasks.stream().map(task -> joinCollaboratorResult(parentRequest, task)).toList();
	}

	private CollaboratorExecutionResult executeCollaborator(AgentRequest parentRequest, CollaboratorRoute route,
			AgentOrchestrationStep step, AgentRequest childRequest, AgentRuntimeEventPublisher eventPublisher) {
		long start = System.nanoTime();
		try {
			// 协作者跑在编排线程池上，同步 UI 收集器是调用线程独有的，不随线程切换传递。
			AgentRunResult runResult = executeAgent(childRequest, null, null);
			String answer = runResult.answer();
			long durationMs = elapsedMs(start);
			RuntimeTiming timing = resolveLastRuntimeTiming(durationMs);
			// 级联取消会让协作者在中途返回空答案，这条路径不能记成 SUCCESS，否则编排轨迹里
			// 一个被取消的步骤会显示为「成功但没有结果」。
			if (isCollaboratorCancelled(childRequest)) {
				return cancelledCollaboratorResult(route, step, childRequest, timing);
			}
			PendingInteraction pendingInteraction = runResult.pendingInteraction();
			if (pendingInteraction != null) {
				orchestrationRuntimeSupport.markStepWaiting(step, childRequest, timing);
				emitRuntimeProgress(childRequest, "COLLABORATOR_WAITING", AgentRuntimeProgressService.STATUS_WAITING,
						timing.totalMs());
				return new CollaboratorExecutionResult(route, answer, null, timing.totalMs(), childRequest,
						pendingInteraction);
			}
			List<String> alignmentNames = collaboratorAlignmentNames(childRequest);
			if (timing.toolFailCount() > 0 && !hasCollaboratorSearchSnapshot(childRequest)) {
				answer = incompleteCollaboratorAnswer(route);
			}
			else {
				answer = sanitizePublicText(answer, childRequest);
			}
			Map<String, Object> structuredOutput = orchestrationRuntimeSupport.extractStructuredOutput(route, answer);
			orchestrationRuntimeSupport.markStepSuccess(step, answer, childRequest, timing);
			attachCollaboratorSnapshots(parentRequest, childRequest);
			emitRuntimeProgress(childRequest, "COLLABORATOR_FINISHED", AgentRuntimeProgressService.STATUS_SUCCESS,
					timing.totalMs());
			log.info(
					"Collaborator execution finished. agentId={}, runtimeRequestId={}, collaboratorAgentId={}, success=true, durationMs={}, reactMs={}, toolCount={}, toolFailCount={}",
					childRequest.getAgentId(), childRequest.getRuntimeRequestId(), route.collaboratorAgentId(), durationMs,
					timing.reactMs(), timing.toolCount(), timing.toolFailCount());
			return new CollaboratorExecutionResult(route, answer, null, timing.totalMs(), null, null,
					structuredOutput, alignmentNames);
		}
		catch (RuntimeException ex) {
			long durationMs = elapsedMs(start);
			RuntimeTiming timing = resolveLastRuntimeTiming(durationMs);
			if (isCollaboratorCancelled(childRequest)) {
				return cancelledCollaboratorResult(route, step, childRequest, timing);
			}
			orchestrationRuntimeSupport.markStepFailed(step, ex, childRequest, timing);
			emitRuntimeProgress(childRequest, "COLLABORATOR_FINISHED", AgentRuntimeProgressService.STATUS_FAILED,
					timing.totalMs());
			log.warn(
					"Collaborator execution finished. agentId={}, runtimeRequestId={}, collaboratorAgentId={}, success=false, durationMs={}, reactMs={}, toolCount={}, toolFailCount={}, error={}",
					childRequest.getAgentId(), childRequest.getRuntimeRequestId(), route.collaboratorAgentId(), durationMs,
					timing.reactMs(), timing.toolCount(), timing.toolFailCount(), ex.getMessage());
			return new CollaboratorExecutionResult(route, null, ex, timing.totalMs());
		}
		finally {
			runtimeProgressService.unregister(childRequest);
			LAST_RUNTIME_TIMING.remove();
			runtimeRegistry.finish(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
			clearDatasourceRuntimeCache(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
			// 级联取消是通过中断协作者线程实现的，线程归还编排线程池前必须清掉中断位，
			// 否则同一条线程上的下一个协作者任务会被上一次的中断误伤。
			Thread.interrupted();
		}
	}

	private boolean isCollaboratorCancelled(AgentRequest childRequest) {
		return runtimeRegistry.isCancelled(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
	}

	private CollaboratorExecutionResult cancelledCollaboratorResult(CollaboratorRoute route,
			AgentOrchestrationStep step, AgentRequest childRequest, RuntimeTiming timing) {
		// 与主链路取消一致（runManagedAgent 的取消分支），落库前先清掉中断位。
		Thread.interrupted();
		orchestrationRuntimeSupport.markStepCancelled(step, childRequest, timing);
		emitRuntimeProgress(childRequest, "COLLABORATOR_FINISHED", AgentRuntimeProgressService.STATUS_CANCELLED,
				timing.totalMs());
		log.info(
				"Collaborator execution cancelled. agentId={}, runtimeRequestId={}, collaboratorAgentId={}, durationMs={}",
				childRequest.getAgentId(), childRequest.getRuntimeRequestId(), route.collaboratorAgentId(),
				timing.totalMs());
		return new CollaboratorExecutionResult(route, null, new IllegalStateException("协作者已被取消"), timing.totalMs());
	}

	private boolean isEventDrivenSchedulerEnabled() {
		DataAgentProperties.Orchestration orchestration = dataAgentProperties == null ? null
				: dataAgentProperties.getOrchestration();
		String scheduler = orchestration == null ? null : orchestration.getScheduler();
		return !"LEGACY".equalsIgnoreCase(scheduler == null ? "" : scheduler.trim());
	}

	/**
	 * 事件驱动引擎的步骤执行体：复用整批模式的单路由提交与执行逻辑（遥测步骤、子请求构建、
	 * 级联取消注册、协作者守卫），由 {@link EventDrivenCollaboratorEngine} 在调度器工作线程上回调。
	 */
	private CollaboratorStepRunner scheduledCollaboratorRunner(AgentRequest parentRequest, AgentOrchestrationRun run,
			boolean exposeTrace, DataAgentAsyncContextBridge.Snapshot asyncContext,
			AgentRuntimeEventPublisher eventPublisher) {
		// 遥测步骤序号取自现有行数，并发创建会重号；引擎工作线程共用一把锁保持与整批模式一致的串行创建
		Object stepCreationLock = new Object();
		return new CollaboratorStepRunner() {

			@Override
			public CollaboratorExecutionResult runRoute(CollaboratorRoute route,
					List<CollaboratorExecutionResult> dependencyResults, InFlightHandle handle) {
				return asyncContextBridge.supplyWith(asyncContext, () -> runScheduledCollaborator(parentRequest, run,
						route, dependencyResults, exposeTrace, handle, stepCreationLock, eventPublisher));
			}

			@Override
			public CollaboratorExecutionResult markUnexecuted(CollaboratorRoute route, String errorMessage) {
				AgentOrchestrationStep step;
				synchronized (stepCreationLock) {
					step = orchestrationRuntimeSupport.createStep(run, route);
				}
				RuntimeException error = new IllegalStateException(errorMessage);
				orchestrationRuntimeSupport.markStepFailed(step, error);
				return new CollaboratorExecutionResult(route, null, error, 0L);
			}

			@Override
			public CollaboratorExecutionResult abandonTimedOut(InFlightHandle handle) {
				return abandonTimedOutScheduledCollaborator(handle);
			}

			@Override
			public void abandon(InFlightHandle handle) {
				AgentRequest childRequest = handle == null ? null : handle.childRequest();
				if (childRequest != null) {
					runtimeRegistry.markCancelled(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
				}
			}

			@Override
			public void ensureParentBudget() {
				ensureOrchestratorWaitBudget(parentRequest);
			}

			@Override
			public boolean interactive(CollaboratorRoute route) {
				return isInteractiveCollaborator(route);
			}

		};
	}

	/**
	 * 单条协作者路由的提交 + 执行（事件驱动模式）：构建阶段异常包装为 EngineFatal（等价整批模式
	 * 父线程构建失败即编排失败），执行阶段交给既有 executeCollaborator 守卫。
	 */
	private CollaboratorExecutionResult runScheduledCollaborator(AgentRequest parentRequest, AgentOrchestrationRun run,
			CollaboratorRoute route, List<CollaboratorExecutionResult> dependencyResults, boolean exposeTrace,
			InFlightHandle handle, Object stepCreationLock, AgentRuntimeEventPublisher eventPublisher) {
		AgentOrchestrationStep step;
		AgentRequest childRequest;
		try {
			synchronized (stepCreationLock) {
				step = orchestrationRuntimeSupport.createStep(run, route);
			}
			childRequest = orchestrationRuntimeSupport.buildCollaboratorRequest(parentRequest, route,
					dependencyResults);
			childRequest.setRootRuntimeRequestId(firstText(parentRequest == null ? null
					: parentRequest.getRootRuntimeRequestId(),
					parentRequest == null ? null : parentRequest.getRuntimeRequestId()));
			childRequest.setParentRuntimeRequestId(parentRequest == null ? null : parentRequest.getRuntimeRequestId());
			childRequest.setOrchestrationRunId(run == null ? null : run.getId());
			childRequest.setOrchestrationStepId(step == null ? null : step.getId());
			if (exposeTrace) {
				runtimeProgressService.registerOrchestrationChild(parentRequest, childRequest,
						run == null ? null : run.getId(), step == null ? null : step.getId(),
						route.dataAgent() == null ? null : route.dataAgent().getName(),
						route.collaborator() == null ? null : route.collaborator().getRoleName());
			}
			// 执行前先挂到父请求下：编排者会话停止时级联取消必须能触达该子请求
			runtimeRegistry.registerChild(parentRequest == null ? null : parentRequest.getThreadId(),
					parentRequest == null ? null : parentRequest.getRuntimeRequestId(), childRequest.getThreadId(),
					childRequest.getRuntimeRequestId());
			handle.attach(step, childRequest, collaboratorWaitTimeoutMs(parentRequest, childRequest));
		}
		catch (RuntimeException buildFailure) {
			throw new EngineFatalException(buildFailure);
		}
		return executeCollaborator(parentRequest, route, step, childRequest, eventPublisher);
	}

	/**
	 * 事件驱动模式的超时放弃：与整批模式 joinCollaboratorResult 的超时分支一致——级联取消子请求、
	 * 中断执行线程、遥测记 TIMED_OUT；被放弃的执行线程按取消分支自行收尾。
	 */
	private CollaboratorExecutionResult abandonTimedOutScheduledCollaborator(InFlightHandle handle) {
		AgentRequest childRequest = handle.childRequest();
		long durationMs = elapsedMs(handle.startedAtNanos());
		log.warn("Collaborator execution timed out. agentId={}, runtimeRequestId={}, stepId={}, durationMs={}",
				childRequest == null ? null : childRequest.getAgentId(),
				childRequest == null ? null : childRequest.getRuntimeRequestId(),
				handle.telemetryStep() == null ? null : handle.telemetryStep().getId(), durationMs);
		if (childRequest != null) {
			runtimeRegistry.markCancelled(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
		}
		handle.workerThread().interrupt();
		orchestrationRuntimeSupport.markStepTimedOut(handle.telemetryStep(), childRequest, durationMs);
		emitRuntimeProgress(childRequest, "COLLABORATOR_TIMED_OUT", AgentRuntimeProgressService.STATUS_FAILED,
				durationMs);
		runtimeProgressService.unregister(childRequest);
		return new CollaboratorExecutionResult(handle.route(), null, new IllegalStateException("协作者执行超时"),
				durationMs);
	}

	/**
	 * 事件驱动模式的编排者预算看护：剩余时间耗尽时与整批模式抛出同一异常。
	 */
	private void ensureOrchestratorWaitBudget(AgentRequest parentRequest) {
		if (parentRequest == null || parentRequest.getRuntimeDeadline() == null) {
			return;
		}
		Duration remaining = parentRequest.getRuntimeDeadline().timeoutFor(null,
				parentRequest.getRuntimeFinishBuffer());
		if (remaining == null || remaining.isZero() || remaining.isNegative()) {
			throw new IllegalStateException("编排者没有剩余时间等待协作者");
		}
	}

	private CollaboratorExecutionResult joinCollaboratorResult(AgentRequest parentRequest, CollaboratorTask task) {
		try {
			long timeoutMs = collaboratorWaitTimeoutMs(parentRequest, task.childRequest());
			return task.future().get(timeoutMs, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			abandonCollaborator(task);
			long durationMs = elapsedMs(task.submittedAt());
			log.warn("Collaborator execution timed out. agentId={}, runtimeRequestId={}, stepId={}, durationMs={}",
					task.childRequest() == null ? null : task.childRequest().getAgentId(),
					task.childRequest() == null ? null : task.childRequest().getRuntimeRequestId(),
					task.step() == null ? null : task.step().getId(), durationMs);
			orchestrationRuntimeSupport.markStepTimedOut(task.step(), task.childRequest(), durationMs);
			emitRuntimeProgress(task.childRequest(), "COLLABORATOR_TIMED_OUT",
					AgentRuntimeProgressService.STATUS_FAILED, durationMs);
			runtimeProgressService.unregister(task.childRequest());
			return new CollaboratorExecutionResult(task.route(), null,
					new IllegalStateException("协作者执行超时", ex), durationMs);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			abandonCollaborator(task);
			throw new IllegalStateException("编排协作者等待被中断", ex);
		}
		catch (ExecutionException | CompletionException ex) {
			Throwable cause = ex.getCause() == null ? ex : ex.getCause();
			// executeCollaborator logs the failures it catches itself; anything reaching here escaped that guard.
			log.warn("Collaborator execution failed outside the collaborator guard. agentId={}, runtimeRequestId={}, stepId={}",
					task.childRequest() == null ? null : task.childRequest().getAgentId(),
					task.childRequest() == null ? null : task.childRequest().getRuntimeRequestId(),
					task.step() == null ? null : task.step().getId(), cause);
			return new CollaboratorExecutionResult(task.route(), null, cause, elapsedMs(task.submittedAt()));
		}
	}

	/**
	 * 编排者不再等待某个协作者时同时取消它。{@code CompletableFuture#cancel} 对已经在
	 * {@code supplyAsync} 中运行的任务不会中断线程，只有走注册表取消才能真正让它停下来，
	 * 否则被放弃的协作者会继续调用模型并继续占用编排线程。
	 */
	private void abandonCollaborator(CollaboratorTask task) {
		task.future().cancel(true);
		AgentRequest childRequest = task.childRequest();
		if (childRequest != null) {
			runtimeRegistry.markCancelled(childRequest.getThreadId(), childRequest.getRuntimeRequestId());
		}
	}

	private long collaboratorWaitTimeoutMs(AgentRequest parentRequest, AgentRequest childRequest) {
		Duration timeout = childRequest == null ? null : childRequest.getRuntimeTimeout();
		if (parentRequest != null && parentRequest.getRuntimeDeadline() != null) {
			Duration remaining = parentRequest.getRuntimeDeadline().timeoutFor(timeout,
					parentRequest.getRuntimeFinishBuffer());
			timeout = remaining;
		}
		if (timeout != null && timeout.compareTo(ORCHESTRATION_SUMMARY_RESERVE) > 0) {
			timeout = timeout.minus(ORCHESTRATION_SUMMARY_RESERVE);
		}
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalStateException("编排者没有剩余时间等待协作者");
		}
		return Math.max(1L, timeout.toMillis());
	}

	private boolean isInteractiveCollaborator(CollaboratorRoute route) {
		String delegationMode = route == null ? null : route.delegationMode();
		if (!StringUtils.hasText(delegationMode) && route != null && route.collaborator() != null) {
			delegationMode = route.collaborator().getDelegationMode();
		}
		return DelegationMode.INTERACTIVE == DelegationMode.resolve(delegationMode);
	}

	private boolean isInteractiveCollaborator(AgentRequest request) {
		return request != null
				&& DelegationMode.INTERACTIVE == DelegationMode.resolve(request.getCollaboratorDelegationMode());
	}

	private void emitOrchestrationProgress(boolean exposeTrace, AgentRequest request, String stageCode, String status) {
		if (exposeTrace) {
			emitRuntimeProgress(request, stageCode, status);
		}
	}

	private void emitOrchestrationProgress(boolean exposeTrace, AgentRequest request, String stageCode, String status,
			long durationMs) {
		if (exposeTrace) {
			emitRuntimeProgress(request, stageCode, status, durationMs);
		}
	}

	private record CollaboratorTask(CollaboratorRoute route, AgentOrchestrationStep step, AgentRequest childRequest,
			CompletableFuture<CollaboratorExecutionResult> future, long submittedAt) {
	}

	private record ResumedOrchestrationPlan(AgentOrchestrationPolicy policy, List<CollaboratorRoute> routes,
			DataAgent orchestrator, ModelConfigDTO modelConfig,
			Map<String, CollaboratorExecutionResult> invalidStepResults) {
	}

	private record OrchestrationExecutionResult(String answer, CollaboratorExecutionResult waitingResult) {

		private static OrchestrationExecutionResult completed(String answer) {
			return new OrchestrationExecutionResult(answer, null);
		}

		private static OrchestrationExecutionResult waiting(CollaboratorExecutionResult waitingResult) {
			return new OrchestrationExecutionResult(null, waitingResult);
		}

	}

	private record SelectedSkillRoute(RouteSelection selection, DataAgentSkill skill,
			DataAgentSkillVersion version, SkillExecutionMode executionMode) {
	}

	/**
	 * 单轮 executeAgent 执行的共享可变状态。各阶段把耗时与工具指标写在这里，
	 * executeAgent 的失败分支与 finally 汇总日志再读取，取代原先贯穿整个方法的一组局部变量。
	 */
	private static final class AgentExecutionState {

		private final long totalStart;

		/** 同步调用方传入的 UI 消息收集器；流式与协作者入口没有收集器，为 null。 */
		private final Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector;

		/** 协作者子请求挂起的业务交互，由 holdCollaboratorPendingInteraction 写入，随运行结果返回给编排者。 */
		private PendingInteraction pendingInteraction;

		private long memoryMs;

		/** 澄清阶段耗时。当前链路未对其计时，保持 0 以维持既有运行日志字段。 */
		private long clarifyMs;

		private long configMs;

		private long routeMs;

		private long initMs;

		private long reactMs;

		private long persistMs;

		/** 预算到点后以流式正文部分答案收口的轮次标记，供 timing 日志区分优雅收口与整轮失败。 */
		private boolean budgetPartial;

		private AgentRuntimeToolMetrics toolMetrics;

		private AgentRuntimeExtensions runtimeExtensions;

		private String successfulAnswer;

		private AgentExecutionState(long totalStart,
				Consumer<com.sn68.agent.dataagent.ui.AgentUiMessage> uiCollector) {
			this.totalStart = totalStart;
			this.uiCollector = uiCollector;
		}

	}

	/**
	 * 单轮运行结果。{@code pendingInteraction} 只在协作者子请求挂起业务交互时非空，
	 * 由编排者读取后决定是否把该交互上抛给父会话。
	 */
	private record AgentRunResult(String answer, PendingInteraction pendingInteraction) {
	}

	/**
	 * 阶段执行结果。{@code terminated} 为真表示该阶段已产出本轮最终答案，流水线应立即返回
	 * {@code answer}；否则 {@code value} 是该阶段交给后续阶段的中间产物。
	 */
	private record PhaseResult<T>(boolean terminated, String answer, T value) {

		private static <T> PhaseResult<T> proceed(T value) {
			return new PhaseResult<>(false, null, value);
		}

		private static <T> PhaseResult<T> terminated(String answer) {
			return new PhaseResult<>(true, answer, null);
		}

	}

	/** 本轮运行已解析的智能体配置。 */
	/** 运行时配置装配产物；releaseSnapshot 为任务级 Release 钉死（任务14）解析的快照视图，非数字员工链路为 null。 */
	private record AgentRuntimeConfiguration(DataAgent dataAgent, String agentType, ModelConfigDTO modelConfig,
			EmployeeReleaseSnapshot releaseSnapshot) {
	}

	/** 编排链路判定结果：是否编排者智能体，以及对应的编排策略（非编排者为 null）。 */
	private record OrchestrationScope(boolean orchestrator, AgentOrchestrationPolicy policy) {
	}

	/** 路由分派命中的执行目标：技能与协作者路由互斥，两者也可能都为空。 */
	private record RoutedTarget(SelectedSkillRoute selectedSkill, RouteDecision collaboratorRoute) {

		private static RoutedTarget none() {
			return new RoutedTarget(null, null);
		}

	}

	/** ReAct 运行时装配产物，供智能体运行与终态分类阶段使用。 */
	private record ReactRuntimeContext(Model model, AgentRuntimeToolMetrics runtimeMetrics, ManagedAgent managedAgent,
			AgentMemoryRecallResultDTO memoryRecall, String reasoningProtocol) {
	}

	private RuntimeTiming resolveLastRuntimeTiming(long fallbackTotalMs) {
		RuntimeTiming timing = LAST_RUNTIME_TIMING.get();
		if (timing == null) {
			return new RuntimeTiming(0L, 0, 0, fallbackTotalMs);
		}
		if (timing.totalMs() > 0L) {
			return timing;
		}
		return new RuntimeTiming(timing.reactMs(), timing.toolCount(), timing.toolFailCount(), fallbackTotalMs);
	}

	private RuntimeException propagateCollaboratorFailure(CollaboratorExecutionResult failure) {
		Throwable error = failure == null ? null : failure.error();
		if (error instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		return new IllegalStateException(error == null ? "协作者执行失败" : error.getMessage(), error);
	}

	private void applyAgentRuntimeLimits(AgentRequest request, DataAgent dataAgent) {
		DataAgentProperties.Runtime runtime = dataAgentProperties.getRuntime();
		ReactRuntimeBudgetPolicy.Budget budget = ReactRuntimeBudgetPolicy.resolve(runtime,
				dataAgent == null ? null : dataAgent.getReactMaxIterations(),
				dataAgent == null ? null : dataAgent.getMaxModelCalls(),
				dataAgent == null ? null : dataAgent.getMaxToolCalls(),
				dataAgent == null ? null : dataAgent.getMaxPromptTokens());
		applyReactBudget(request, budget);
	}

	private void applySkillReactLimits(AgentRequest request, DataAgentSkillVersion version) {
		ReactRuntimeBudgetPolicy.Budget current = new ReactRuntimeBudgetPolicy.Budget(
				request.getReactMaxIterations() == null ? 1 : request.getReactMaxIterations(),
				request.getMaxModelCalls() == null ? 0 : request.getMaxModelCalls(),
				request.getMaxToolCalls() == null ? 0 : request.getMaxToolCalls(),
				request.getMaxPromptTokens() == null ? 0L : request.getMaxPromptTokens());
		ReactRuntimeBudgetPolicy.Budget budget = ReactRuntimeBudgetPolicy.tighten(current,
				readReactConfig(version == null ? null : version.getReactConfig()));
		applyReactBudget(request, budget);
	}

	private void applyReactBudget(AgentRequest request, ReactRuntimeBudgetPolicy.Budget budget) {
		request.setReactMaxIterations(budget.maxIterations());
		request.setMaxModelCalls(budget.maxModelCalls());
		request.setMaxToolCalls(budget.maxToolCalls());
		request.setMaxPromptTokens(budget.maxPromptTokens());
	}

	private Map<String, Object> readReactConfig(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
			});
		}
		catch (Exception ex) {
			log.warn("Ignoring invalid published ReAct budget configuration. message={}", ex.getMessage());
			return Map.of();
		}
	}

	private String summarizeOrchestration(DataAgent orchestrator, AgentRequest request, ModelConfigDTO modelConfig,
			List<CollaboratorExecutionResult> results, boolean forUserPresentation) {
		List<CollaboratorExecutionResult> successes = results.stream().filter(CollaboratorExecutionResult::success).toList();
		if (successes.isEmpty()) {
			return buildFailureAnswer(results);
		}
		String prompt = orchestrationRuntimeSupport.buildSummaryPrompt(orchestrator,
				orchestrationRuntimeSupport.effectiveQuery(request), results, forUserPresentation);
		prompt = agentTemporalService.promptBlock(request == null ? null : request.getTemporalContext())
				+ System.lineSeparator() + prompt;
		if (isReportMode(request)) {
			prompt = prompt + System.lineSeparator() + System.lineSeparator() + REPORT_DATA_SUFFICIENCY_PROMPT;
		}
		String summary = tokenUsageService.callAndRecord(dynamicModelFactory.createChatModel(modelConfig), prompt,
				tokenUsageService.buildContext(request, modelConfig, AgentTokenUsageService.SOURCE_ORCHESTRATION_SUMMARY));
		if (StringUtils.hasText(summary) && !(forUserPresentation && looksLikeJson(summary))) {
			return summary.trim();
		}
		return forUserPresentation ? redactJsonAnswers(results) : buildDeterministicAnswer(request, results);
	}

	private boolean shouldSummarizeOrchestration(AgentRequest request, List<CollaboratorExecutionResult> successes) {
		if (successes == null || successes.isEmpty()) {
			return false;
		}
		return successes.size() >= 2 || isReportMode(request) || needsJsonPresentation(successes);
	}

	private boolean needsJsonPresentation(List<CollaboratorExecutionResult> successes) {
		return successes != null && successes.stream().anyMatch(result -> looksLikeJson(result.answer()));
	}

	private boolean looksLikeJson(String answer) {
		if (!StringUtils.hasText(answer)) {
			return false;
		}
		String text = answer.trim();
		if (text.regionMatches(true, 0, "```json", 0, 7) || text.startsWith("```JSON")) {
			return true;
		}
		return text.startsWith("{") || text.startsWith("[");
	}

	private String redactJsonAnswers(List<CollaboratorExecutionResult> results) {
		List<CollaboratorExecutionResult> successes = results == null ? List.of()
				: results.stream().filter(CollaboratorExecutionResult::success).toList();
		if (successes.isEmpty()) {
			return buildFailureAnswer(results);
		}
		StringBuilder builder = new StringBuilder();
		for (CollaboratorExecutionResult result : successes) {
			if (!builder.isEmpty()) {
				builder.append("\n\n");
			}
			String title = result.route() == null || result.route().collaborator() == null ? null
					: result.route().collaborator().getRoleName();
			if (StringUtils.hasText(title)) {
				builder.append("**").append(title).append("**\n");
			}
			if (looksLikeJson(result.answer())) {
				builder.append("已查到结构化结果，未生成可读汇总。");
			}
			else {
				builder.append(result.answer().trim());
			}
		}
		List<CollaboratorExecutionResult> failures = results.stream().filter(result -> !result.success()).toList();
		if (!failures.isEmpty()) {
			builder.append("\n\n未完成部分：")
				.append(joinRoles(failures))
				.append("。");
		}
		return builder.toString();
	}

	private String buildDeterministicAnswer(AgentRequest request, List<CollaboratorExecutionResult> results) {
		List<CollaboratorExecutionResult> successes = results == null ? List.of()
				: results.stream().filter(CollaboratorExecutionResult::success).toList();
		if (successes.isEmpty()) {
			return buildFailureAnswer(results);
		}
		List<CollaboratorExecutionResult> failures = results.stream().filter(result -> !result.success()).toList();
		if (successes.size() == 1 && failures.isEmpty()) {
			return AttachmentAnswerGuard.apply(request, successes.get(0).answer());
		}
		String answer = buildLightweightSummary(successes);
		if (failures.isEmpty()) {
			return AttachmentAnswerGuard.apply(request, answer);
		}
		String missing = failures.stream().map(this::collaboratorRole).distinct()
				.collect(java.util.stream.Collectors.joining("、"));
		return AttachmentAnswerGuard.apply(request, answer + "\n\n未完成部分：" + missing + "。");
	}

	private String buildLightweightSummary(List<CollaboratorExecutionResult> successes) {
		if (successes.size() == 1) {
			return successes.get(0).answer();
		}
		StringBuilder builder = new StringBuilder();
		for (CollaboratorExecutionResult result : successes) {
			if (!builder.isEmpty()) {
				builder.append("\n\n");
			}
			String title = result.route() == null || result.route().collaborator() == null ? null
					: result.route().collaborator().getRoleName();
			if (StringUtils.hasText(title)) {
				builder.append("**").append(title).append("**\n");
			}
			builder.append(result.answer().trim());
		}
		return builder.toString();
	}

	private String buildFailureAnswer(List<CollaboratorExecutionResult> results) {
		StringBuilder builder = new StringBuilder("该部分执行失败，暂时无法完成本次编排。");
		List<CollaboratorExecutionResult> failures = results == null ? List.of()
				: results.stream().filter(result -> !result.success()).toList();
		List<CollaboratorExecutionResult> skipped = failures.stream().filter(this::skippedForUpstreamFailure).toList();
		List<CollaboratorExecutionResult> executedFailures = failures.stream()
			.filter(result -> !skippedForUpstreamFailure(result))
			.toList();
		if (!executedFailures.isEmpty()) {
			builder.append("失败协作者：");
			builder.append(joinRoles(executedFailures));
			builder.append("。");
		}
		if (!skipped.isEmpty()) {
			builder.append("未执行（上游失败）：");
			builder.append(joinRoles(skipped));
			builder.append("。");
		}
		return builder.toString();
	}

	private boolean skippedForUpstreamFailure(CollaboratorExecutionResult result) {
		String message = result == null ? null : result.errorMessage();
		return StringUtils.hasText(message) && message.contains("前置步骤执行失败");
	}

	private String joinRoles(List<CollaboratorExecutionResult> results) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < results.size(); i++) {
			if (i > 0) {
				builder.append("、");
			}
			builder.append(collaboratorRole(results.get(i)));
		}
		return builder.toString();
	}

	private String collaboratorRole(CollaboratorExecutionResult result) {
		if (result == null || result.route() == null || result.route().collaborator() == null) {
			return "未知协作者";
		}
		if (StringUtils.hasText(result.route().collaborator().getRoleName())) {
			return result.route().collaborator().getRoleName();
		}
		if (result.route().dataAgent() != null && StringUtils.hasText(result.route().dataAgent().getName())) {
			return result.route().dataAgent().getName();
		}
		return "协作者";
	}

	private boolean isFailFast(AgentOrchestrationPolicy policy) {
		return policy != null && "fail_fast".equalsIgnoreCase(policy.getFailureStrategy());
	}

	private java.time.Duration resolveRuntimeTimeout(AgentRequest request, DataAgent dataAgent) {
		if (request != null && request.getRuntimeTimeout() != null) {
			return request.getRuntimeTimeout();
		}
		if (dataAgent != null && dataAgent.getRuntimeTimeoutSeconds() != null && dataAgent.getRuntimeTimeoutSeconds() > 0) {
			return java.time.Duration.ofSeconds(dataAgent.getRuntimeTimeoutSeconds());
		}
		return dataAgentProperties.getRuntime().getTotalTimeout();
	}

	private long elapsedMs(long startNanos) {
		return java.time.Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
	}

	private void clearDatasourceRuntimeCache(String threadId, String runtimeRequestId) {
		datasourceRuntimeContextCache.clear(threadId, runtimeRequestId);
	}

	private Span startRuntimeSpan(AgentRequest request) {
		Span span = tracer.spanBuilder(ROOT_SPAN_NAME).startSpan();
		span.setAttribute(SessionTraceStore.ATTR_THREAD_ID, request.getThreadId());
		span.setAttribute(SessionTraceStore.ATTR_RUNTIME_REQUEST_ID, request.getRuntimeRequestId());
		span.setAttribute(SessionTraceStore.ATTR_AGENT_ID, request.getAgentId() == null ? "" : request.getAgentId());
		span.setAttribute("dataagent.runtime.human_feedback", request.isHumanFeedback());
		return span;
	}

	private void recordRuntimeFailure(Span rootSpan, Throwable throwable) {
		if (rootSpan == null) {
			return;
		}
		rootSpan.setStatus(StatusCode.ERROR,
				throwable.getMessage() == null ? "runtime failed" : throwable.getMessage());
		rootSpan.recordException(throwable);
	}

	private String blockForClarification(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			QueryClarifyAssessment clarifyAssessment, PreparedMemory preparedMemory, AgentExecutionState state) {
		if (request.isCollaboratorChild()) {
			requireResumableInteractiveCollaborator(request);
		}
		String clarifyText = clarifyAssessment.userMessage();
		appendClarifyTurn(preparedMemory, request, clarifyText);
		persistNativeMemorySafely(request, preparedMemory == null ? null : preparedMemory.memory());
		answerTraceExplainStore.recordFinalAnswer(clarifyText);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		String safeClarifyText = sanitizePublicText(clarifyText, request);
		mirrorExplainSummary(rootSpan, request);
		RouteClarification clarification = businessClarification(clarifyAssessment);
		PendingInteraction pending = routePendingService.create(request,
				RoutePendingService.TYPE_BUSINESS_CLARIFICATION, request.getQuery(),
				(request.getRouteClarificationRound() == null ? 0 : request.getRouteClarificationRound()) + 1,
				clarification, null);
		if (request.isCollaboratorChild()) {
			return holdCollaboratorPendingInteraction(request, rootSpan, safeClarifyText, pending, state);
		}
		chatTurnService.waitForClarification(request, safeClarifyText, explainSnapshot);
		rootSpan.setStatus(StatusCode.OK, "clarify required");
		emitRuntimeProgress(request, "WAITING", AgentRuntimeProgressService.STATUS_WAITING);
		if (eventPublisher != null) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("businessClarification", pending.metadata());
			metadata.put(CONTENT_FORMAT_KEY, CONTENT_FORMAT_MARKDOWN);
			eventPublisher.publish(AgentResponse.builder()
				.agentId(request.getAgentId())
				.threadId(request.getThreadId())
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text(safeClarifyText)
				.metadata(metadata)
				.build());
		}
		return safeClarifyText;
	}

	private RouteClarification businessClarification(QueryClarifyAssessment assessment) {
		List<RouteClarificationOption> options = new ArrayList<>();
		if (assessment != null && assessment.suggestedAssumptions() != null) {
			for (String assumption : assessment.suggestedAssumptions()) {
				if (StringUtils.hasText(assumption)) {
					options.add(new RouteClarificationOption(null, assumption, assumption, null));
				}
			}
		}
		String prompt = assessment == null ? "请补充业务信息。"
				: assessment.followUpQuestions().stream().findFirst().orElse(assessment.userMessage());
		return new RouteClarification(prompt, "请补充业务信息", options, true, "high");
	}

	private QueryClarifyAssessment requireCollaboratorBusinessClarification(AgentRequest request, RouteDecision route) {
		requireResumableInteractiveCollaborator(request);
		if (route == null || !"AMBIGUOUS_CANDIDATES".equals(route.reasonCode())) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_NOT_CLARIFIABLE");
		}
		if (request.getRouteClarificationRound() != null && request.getRouteClarificationRound() >= 1) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_CLARIFICATION_EXHAUSTED");
		}
		QueryClarifyAssessment assessment = queryClarifyService.assess(request.getQuery(), request.getHumanFeedbackContent(),
				true, null, request.getTemporalInterval());
		answerTraceExplainStore.recordClarifyAssessment(request, assessment);
		if (assessment.missingDimensions() == null || assessment.missingDimensions().isEmpty()) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_AMBIGUITY_UNRESOLVED");
		}
		return assessment;
	}

	private void requireResumableInteractiveCollaborator(AgentRequest request) {
		if (!isInteractiveCollaborator(request)) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_INTERACTION_NOT_ALLOWED");
		}
		if (request.getOrchestrationRunId() == null || request.getOrchestrationStepId() == null
				|| !StringUtils.hasText(request.getParentAgentId()) || !StringUtils.hasText(request.getParentThreadId())) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_CLARIFICATION_NOT_RESUMABLE");
		}
		if (request.getOrchestrationRouteSnapshot() == null
				|| request.getOrchestrationRouteSnapshot().plan().steps().isEmpty()) {
			throw new RouteUnavailableException("COLLABORATOR_ROUTE_CLARIFICATION_NOT_RESUMABLE");
		}
	}

	private String blockForRouteClarification(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, RouteDecision decision, AgentExecutionState state) {
		return blockForRouteClarification(request, eventPublisher, rootSpan,
				decision == null ? null : decision.clarification(), decision, state);
	}

	private String blockForRouteClarification(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, RouteClarification clarification, AgentExecutionState state) {
		return blockForRouteClarification(request, eventPublisher, rootSpan, clarification, null, state);
	}

	private String blockForRouteClarification(AgentRequest request, AgentRuntimeEventPublisher eventPublisher,
			Span rootSpan, RouteClarification clarification, RouteDecision routeSnapshot,
			AgentExecutionState state) {
		if (request.isCollaboratorChild()) {
			requireResumableInteractiveCollaborator(request);
		}
		if (clarification == null) {
			clarification = new RouteClarification(routeClarification(), "请补充业务信息", List.of(), true, "medium");
		}
		PendingInteraction pending = routePendingService.create(request, RoutePendingService.TYPE_ROUTE_CLARIFICATION,
				request.getOriginalQuerySnapshot() == null ? request.getQuery() : request.getOriginalQuerySnapshot(),
				(request.getRouteClarificationRound() == null ? 0 : request.getRouteClarificationRound()) + 1,
				clarification, routeSnapshot);
		return publishPendingInteraction(eventPublisher, request, rootSpan,
				routeClarificationReply(request, clarification), pending, "ROUTE_CLARIFY", state);
	}

	/**
	 * 文本通道没有点选卡片，不能只发「请点选下面一项」；用绑定技能描述引导用户直接说要办的事。
	 * Web 仍使用澄清 prompt，选项走 pending 卡片。
	 */
	private String routeClarificationReply(AgentRequest request, RouteClarification clarification) {
		if (request != null && request.supportsTextCommands()) {
			String imCopy = unmatchedRouteCopy.build(request);
			if (StringUtils.hasText(imCopy)) {
				return imCopy;
			}
		}
		return clarification == null ? routeClarification() : clarification.prompt();
	}

	private String blockForConfirmation(AgentRequest request, AgentRuntimeEventPublisher eventPublisher, Span rootSpan,
			RouteDecision decision, AgentExecutionState state) {
		if (request.isCollaboratorChild()) {
			requireResumableInteractiveCollaborator(request);
		}
		RouteClarification clarification = decision == null ? null : decision.clarification();
		if (clarification == null) {
			clarification = new RouteClarification("本次操作可能产生业务变更，请确认后继续。", "请确认本次操作", List.of(), false,
					highRisk(decision) ? "high" : "medium");
		}
		PendingInteraction pending = routePendingService.create(request, RoutePendingService.TYPE_CONFIRMATION,
				request.getQuery(), request.getRouteClarificationRound() == null ? 0 : request.getRouteClarificationRound(),
				clarification, decision);
		return publishPendingInteraction(eventPublisher, request, rootSpan, clarification.prompt(), pending,
				"CONFIRM_REQUIRED", state);
	}

	private String publishPendingInteraction(AgentRuntimeEventPublisher eventPublisher, AgentRequest request,
			Span rootSpan, String text, PendingInteraction pending, String stageCode, AgentExecutionState state) {
		String safeText = sanitizePublicText(text, request);
		answerTraceExplainStore.recordFinalAnswer(safeText);
		if (request.isCollaboratorChild()) {
			return holdCollaboratorPendingInteraction(request, rootSpan, safeText, pending, state);
		}
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(request);
		chatTurnService.waitForClarification(request, safeText, explainSnapshot);
		finishPendingExecution(request, RoutePendingService.EXECUTION_FORWARDED);
		mirrorExplainSummary(rootSpan, request);
		rootSpan.setStatus(StatusCode.OK, "waiting for business interaction");
		emitRuntimeProgress(request, "WAITING", AgentRuntimeProgressService.STATUS_WAITING);
		if (eventPublisher != null) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			Map<String, Object> interaction = new LinkedHashMap<>(pending.metadata());
			String interactionType = String.valueOf(interaction.get("schemaVersion"));
			metadata.put(interactionType.startsWith("confirm") ? "confirmation" : "businessClarification", interaction);
			metadata.put(CONTENT_FORMAT_KEY, CONTENT_FORMAT_MARKDOWN);
			eventPublisher.publish(AgentResponse.builder().agentId(request.getAgentId()).threadId(request.getThreadId())
					.nodeName(RUNTIME_NODE_NAME).textType(TextType.TEXT).text(safeText).metadata(metadata).build());
		}
		return safeText;
	}

	private String holdCollaboratorPendingInteraction(AgentRequest request, Span rootSpan, String text,
			PendingInteraction pending, AgentExecutionState state) {
		state.pendingInteraction = pending;
		mirrorExplainSummary(rootSpan, request);
		rootSpan.setStatus(StatusCode.OK, "waiting for collaborator interaction");
		emitRuntimeProgress(request, "WAITING", AgentRuntimeProgressService.STATUS_WAITING);
		return text;
	}

	private String publishParentCollaboratorPendingInteraction(AgentRequest parentRequest,
			AgentRuntimeEventPublisher eventPublisher, Span rootSpan, String text, PendingInteraction pending,
			long totalStart) {
		if (pending == null) {
			throw new IllegalStateException("Collaborator pending interaction is missing");
		}
		String safeText = sanitizePublicText(text, parentRequest);
		answerTraceExplainStore.recordFinalAnswer(safeText);
		DataChatMessage explainSnapshot = persistAnswerExplainSnapshot(parentRequest);
		chatTurnService.waitForClarification(parentRequest, safeText, explainSnapshot);
		finishPendingExecution(parentRequest, RoutePendingService.EXECUTION_FORWARDED);
		mirrorExplainSummary(rootSpan, parentRequest);
		rootSpan.setStatus(StatusCode.OK, "waiting for collaborator interaction");
		emitRuntimeProgress(parentRequest, "WAITING", AgentRuntimeProgressService.STATUS_WAITING);
		if (eventPublisher != null) {
			Map<String, Object> metadata = new LinkedHashMap<>();
			Map<String, Object> interaction = new LinkedHashMap<>(pending.metadata());
			String schemaVersion = String.valueOf(interaction.get("schemaVersion"));
			metadata.put(schemaVersion.startsWith("confirm") ? "confirmation" : "businessClarification", interaction);
			metadata.put(CONTENT_FORMAT_KEY, CONTENT_FORMAT_MARKDOWN);
			eventPublisher.publish(AgentResponse.builder()
				.agentId(parentRequest.getAgentId())
				.threadId(parentRequest.getThreadId())
				.nodeName(RUNTIME_NODE_NAME)
				.textType(TextType.TEXT)
				.text(safeText)
				.metadata(metadata)
				.build());
		}
		return safeText;
	}

	private boolean highRisk(RouteDecision decision) {
		return decision != null && decision.selections().stream().anyMatch(selection -> selection.risk() != null
				&& selection.risk() != com.sn68.agent.dataagent.routing.model.RouteRisk.READ_ONLY);
	}

	private String routeClarification() {
		return "请补充业务对象、时间范围、数据口径或希望执行的操作，我会据此继续判断。";
	}

	private void mirrorExplainSummary(Span rootSpan, AgentRequest request) {
		if (rootSpan == null || request == null) {
			return;
		}
		try {
			answerTraceExplainStore.getMirrorSummary(request.getThreadId(), request.getRuntimeRequestId())
				.ifPresent(summary -> {
					rootSpan.setAttribute("dataagent.answer.explain.available", true);
					rootSpan.setAttribute("dataagent.answer.explain.tool_step_count", summary.getToolStepCount());
					rootSpan.setAttribute("dataagent.answer.explain.semantic_hit_count", summary.getSemanticHitCount());
					rootSpan.setAttribute("dataagent.answer.explain.knowledge_hit_count", summary.getKnowledgeHitCount());
					if (StringUtils.hasText(summary.getDatasource())) {
						rootSpan.setAttribute("dataagent.answer.explain.datasource", summary.getDatasource());
					}
				});
		}
		catch (RuntimeException | LinkageError error) {
			rootSpan.setAttribute("dataagent.answer.explain.available", false);
			log.warn("Skip answer explain mirror. threadId={}, runtimeRequestId={}", request.getThreadId(),
					request.getRuntimeRequestId(), error);
		}
	}

	private RuntimeException normalizeRuntimeFailure(Throwable error) {
		if (error instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		return new IllegalStateException("Agent runtime linkage failure", error);
	}

	private void emitStreamEvent(Sinks.Many<ServerSentEvent<AgentResponse>> sink,
			ServerSentEvent<AgentResponse> event) {
		if (sink == null || event == null) {
			return;
		}
		synchronized (sink) {
			sink.tryEmitNext(event);
		}
	}

	private void completeStream(Sinks.Many<ServerSentEvent<AgentResponse>> sink) {
		if (sink == null) {
			return;
		}
		synchronized (sink) {
			sink.tryEmitComplete();
		}
	}

	private DataChatMessage persistAnswerExplainSnapshot(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		Long sessionId = parseThreadIdOrNull(request.getThreadId());
		if (sessionId == null) {
			return null;
		}
		return answerTraceExplainStore.getExplain(request.getThreadId(), request.getRuntimeRequestId()).map(explain -> {
			try {
				if (chatSessionService.findBySessionId(sessionId) == null) {
					return null;
				}
				return chatMessageService.saveMessage(DataChatMessage.builder()
					.sessionId(sessionId)
					.role("system")
					.content(objectMapper.writeValueAsString(explain))
					.messageType(ANSWER_EXPLAIN_MESSAGE_TYPE)
					.metadata(buildAnswerExplainMetadata(request))
					.build());
			}
			catch (Exception ex) {
				log.warn("Failed to persist answer explain snapshot. sessionId={}, runtimeRequestId={}",
						request.getThreadId(), request.getRuntimeRequestId(), ex);
				return null;
			}
		}).orElse(null);
	}

	private String buildAnswerExplainMetadata(AgentRequest request) throws Exception {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("kind", "answer-explain");
		metadata.put("runtimeRequestId", request.getRuntimeRequestId());
		metadata.put("visibility", "system-hidden");
		return objectMapper.writeValueAsString(metadata);
	}

	private DataChatMessage persistThinkingTraceSnapshot(AgentRequest request, ThinkingTraceCollector collector) {
		if (request == null) {
			return null;
		}
		String content = collector != null && collector.hasEntries() ? collector.toHtml(outputSanitizer)
				: buildThinkingTraceFallbackHtml(request);
		if (!StringUtils.hasText(content)) {
			return null;
		}
		Long sessionId = parseThreadIdOrNull(request.getThreadId());
		if (sessionId == null) {
			return null;
		}
		try {
			if (chatSessionService.findBySessionId(sessionId) == null) {
				return null;
			}
			return chatMessageService.saveMessage(DataChatMessage.builder()
				.sessionId(sessionId)
				.role("system")
				.content(content)
				.messageType(THINKING_MESSAGE_TYPE)
				.metadata(buildThinkingTraceMetadata(request))
				.build());
		}
		catch (Exception ex) {
			log.warn("Failed to persist thinking trace snapshot. sessionId={}, runtimeRequestId={}",
					request.getThreadId(), request.getRuntimeRequestId(), ex);
			return null;
		}
	}

	private String buildThinkingTraceFallbackHtml(AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		return answerTraceExplainStore.getExplain(request.getThreadId(), request.getRuntimeRequestId())
			.map(explain -> ThinkingTraceCollector.toHtmlFromExplain(explain, outputSanitizer))
			.filter(StringUtils::hasText)
			.orElse(null);
	}

	private String buildThinkingTraceMetadata(AgentRequest request) throws Exception {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("kind", "thinking-trace");
		metadata.put("runtimeRequestId", request.getRuntimeRequestId());
		metadata.put("visibility", "system-hidden");
		return objectMapper.writeValueAsString(metadata);
	}

	private void validateModelConfig(ModelConfigDTO modelConfig) {
		if (modelConfig == null) {
			throw new IllegalStateException("当前未配置可用的 CHAT 模型，请先在控制台完成配置。");
		}
		if (!StringUtils.hasText(modelConfig.getApiKey())) {
			throw new IllegalStateException("当前活动 CHAT 模型的 apiKey 为空。");
		}
		if (!StringUtils.hasText(modelConfig.getModelName())) {
			throw new IllegalStateException("当前活动 CHAT 模型的 modelName 为空。");
		}
	}

	private void validateVisionModel(AgentRequest request, ModelConfigDTO modelConfig) {
		if (request == null || request.getTurnArtifact() == null || !request.getTurnArtifact().hasPixelImage()) {
			return;
		}
		if (modelConfig == null || !Boolean.TRUE.equals(modelConfig.getSupportVision())) {
			throw CheckedException.badRequest("当前模型不支持图片输入，请切换支持视觉的模型。");
		}
	}

	private List<ContentBlock> buildUserContentBlocks(AgentRequest request) {
		return turnFusionService.buildUserContentBlocks(request);
	}

	private ModelConfigDTO resolveChatModelConfig(Long chatModelConfigId, DataAgent dataAgent) {
		ModelConfigDTO modelConfig = agentModelConfigService.resolveChatModelConfig(dataAgent, chatModelConfigId);
		if (chatModelConfigId != null) {
			log.info("Using selected CHAT model config. chatModelConfigId={}", chatModelConfigId);
		}
		else if (dataAgent != null && dataAgent.getChatModelConfigId() != null) {
			log.info("Using agent default CHAT model config. agentId={}, chatModelConfigId={}", dataAgent.getId(),
					dataAgent.getChatModelConfigId());
		}
		else {
			log.info("Using active CHAT model config because no request or agent default model was configured.");
		}
		return modelConfig;
	}

	private String resolveAgentType(DataAgent dataAgent) {
		return AgentTypeConstant.normalize(dataAgent == null ? null : dataAgent.getAgentType());
	}

	private void recordModelConfig(Span rootSpan, ModelConfigDTO modelConfig) {
		if (rootSpan == null || modelConfig == null) {
			return;
		}
		if (modelConfig.getId() != null) {
			rootSpan.setAttribute("dataagent.model.config_id", modelConfig.getId());
		}
		rootSpan.setAttribute("dataagent.model.provider",
				modelConfig.getProvider() == null ? "" : modelConfig.getProvider());
		rootSpan.setAttribute("dataagent.model.name",
				modelConfig.getModelName() == null ? "" : modelConfig.getModelName());
		rootSpan.setAttribute("dataagent.model.base_url",
				modelConfig.getBaseUrl() == null ? "" : modelConfig.getBaseUrl());
	}

	private DataAgent resolveManagedAgent(String requestAgentId) {
		Long agentId = parseAgentId(requestAgentId);
		if (agentId == null) {
			return null;
		}
		DataAgent dataAgent = agentService.findById(agentId);
		validateAgentStatus(dataAgent, requestAgentId);
		return dataAgent;
	}

	private void applyAgentNameSnapshot(AgentRequest request, DataAgent dataAgent) {
		if (request != null && dataAgent != null && StringUtils.hasText(dataAgent.getName())) {
			request.setAgentNameSnapshot(dataAgent.getName().trim());
		}
	}

	private String resolveManagedSystemPrompt(DataAgent dataAgent, String requestAgentId) {
		if (dataAgent == null || !StringUtils.hasText(dataAgent.getPrompt())) {
			log.info("No agent prompt found, keep CommonAgent system prompt empty. agentId={}", requestAgentId);
			return "";
		}
		log.info("Using agent prompt from base setting. agentId={}", requestAgentId);
		return dataAgent.getPrompt();
	}

	/**
	 * DRY_RUN overlay 门：有 overlay 且 DRY_RUN 即生效（EVAL / SHADOW 同源）。
	 * 非 DRY_RUN（含 LIVE）忽略 overlay，仍用 live / release 提示词。
	 */
	static String resolveEvalOrLiveSystemPrompt(AgentRequest request, String liveOrReleasePrompt) {
		if (request != null && ExecutionIntentContext.INTENT_DRY_RUN.equalsIgnoreCase(request.getExecutionIntent())
				&& StringUtils.hasText(request.getEvalSystemInstructionOverride())) {
			return request.getEvalSystemInstructionOverride();
		}
		return liveOrReleasePrompt;
	}

	/**
	 * 系统提示词钉死（任务14）：Release 快照冻结的 systemInstruction 优先——Seal 后 live 草稿
	 * 修改不影响已冻结运行；快照未冻结提示词语义时回落 live 配置（与原路径一致）。
	 */
	private String resolveRuntimeSystemPrompt(AgentRequest request, AgentRuntimeConfiguration configuration) {
		String overlay = resolveEvalOrLiveSystemPrompt(request, null);
		if (StringUtils.hasText(overlay)) {
			log.info("Using DRY_RUN system instruction overlay. agentId={}, releaseId={}, requestSource={}",
					request.getAgentId(), request.getReleaseId(), request.getRequestSource());
			return appendExtractCardPrompt(request, overlay);
		}
		EmployeeReleaseSnapshot releaseSnapshot = configuration.releaseSnapshot();
		if (releaseSnapshot != null && StringUtils.hasText(releaseSnapshot.systemInstruction())) {
			log.info("Using system instruction from pinned employee release snapshot. agentId={}, releaseId={}, "
					+ "versionAnchor={}", request.getAgentId(), releaseSnapshot.releaseId(),
					releaseSnapshot.versionAnchor());
			return appendExtractCardPrompt(request, releaseSnapshot.systemInstruction());
		}
		return appendExtractCardPrompt(request,
				resolveManagedSystemPrompt(configuration.dataAgent(), request.getAgentId()));
	}

	private String appendExtractCardPrompt(AgentRequest request, String prompt) {
		if (request == null || (request.getExtractCard() == null && !request.isCollaboratorChild())) {
			return prompt;
		}
		String snippet = "当抽取卡片已有标识时禁止再向用户要编号；unreadReason 非空时必须说明未能读取图片内容。";
		if (!StringUtils.hasText(prompt)) {
			return snippet;
		}
		if (prompt.contains(snippet)) {
			return prompt;
		}
		return prompt + "\n" + snippet;
	}

	private String buildUserPrompt(AgentRequest request) {
		return request.getQuery() == null ? "" : request.getQuery();
	}

	private String buildFinalUserPrompt(AgentRequest request) {
		return buildFinalUserPrompt(request, "");
	}

	private String buildFinalUserPrompt(AgentRequest request, String longTermMemoryBlock) {
		String query = buildUserPromptWithFeedback(request);
		String temporalBlock = agentTemporalService.promptBlock(request == null ? null : request.getTemporalContext());
		query = temporalBlock + System.lineSeparator() + System.lineSeparator() + query;
		if (StringUtils.hasText(longTermMemoryBlock)) {
			query = StringUtils.hasText(query)
					? longTermMemoryBlock + System.lineSeparator() + System.lineSeparator() + "【当前用户问题】"
							+ System.lineSeparator() + query
					: longTermMemoryBlock;
		}
		String citation = turnFusionService.citationPrompt(request);
		if (StringUtils.hasText(citation)) {
			query = query + System.lineSeparator() + System.lineSeparator() + citation;
		}
		if (!isReportMode(request)) {
			return query;
		}
		return query + System.lineSeparator() + System.lineSeparator() + REPORT_DATA_SUFFICIENCY_PROMPT;
	}

	private String buildUserPromptWithFeedback(AgentRequest request) {
		String query = buildUserPrompt(request);
		if (request == null || !StringUtils.hasText(request.getHumanFeedbackContent())) {
			return query;
		}
		String feedback = request.getHumanFeedbackContent().trim();
		if (!StringUtils.hasText(query)) {
			return feedback;
		}
		return """
				原始问题：
				%s

				引导补充：
				%s

				请优先按引导补充理解本轮问题；如果补充修改了指标、排序或口径，以补充后的意图重新分析，不要直接复用上一轮结论。
				""".formatted(query, feedback).trim();
	}

	private boolean isReportMode(AgentRequest request) {
		return request != null && request.isReportIntentDetectionEnabled()
				&& reportIntentDetector.shouldGenerateReport(request.getQuery(), request.getResponseMode());
	}

	private void attachCollaboratorSnapshotsForReport(AgentRequest parentRequest, AgentRequest childRequest) {
		attachCollaboratorSnapshots(parentRequest, childRequest);
	}

	private void attachCollaboratorSnapshots(AgentRequest parentRequest, AgentRequest childRequest) {
		if (parentRequest == null || childRequest == null) {
			return;
		}
		answerTraceExplainStore.getExplain(childRequest.getThreadId(), childRequest.getRuntimeRequestId())
			.map(AnswerTraceExplainStore.AnswerTraceExplainView::getReportDataSnapshots)
			.filter(snapshots -> snapshots != null && !snapshots.isEmpty())
			.ifPresent(snapshots -> answerTraceExplainStore.attachSnapshots(parentRequest, snapshots));
	}

	private boolean hasCollaboratorSearchSnapshot(AgentRequest childRequest) {
		if (childRequest == null) {
			return false;
		}
		return answerTraceExplainStore.getExplain(childRequest.getThreadId(), childRequest.getRuntimeRequestId())
			.map(AnswerTraceExplainStore.AnswerTraceExplainView::getReportDataSnapshots)
			.filter(snapshots -> snapshots != null && !snapshots.isEmpty())
			.isPresent();
	}

	private List<String> collaboratorAlignmentNames(AgentRequest childRequest) {
		if (childRequest == null) {
			return List.of();
		}
		return answerTraceExplainStore.getExplain(childRequest.getThreadId(), childRequest.getRuntimeRequestId())
			.map(AnswerTraceExplainStore.AnswerTraceExplainView::getReportDataSnapshots)
			.map(PredecessorAlignmentNames::fromSnapshots)
			.orElse(List.of());
	}

	private String incompleteCollaboratorAnswer(CollaboratorRoute route) {
		String role = route == null || route.collaborator() == null ? null : route.collaborator().getRoleName();
		if (!StringUtils.hasText(role)) {
			role = "该部分";
		}
		return role + "数据未能查出，请稍后重试。";
	}

	private AgentMemoryRecallResultDTO recallLongTermMemorySafely(AgentRequest request) {
		CompletableFuture<AgentMemoryRecallResultDTO> future = null;
		try {
			future = submitLongTermMemoryRecall(request);
			return future.get(resolveLongTermMemoryRecallTimeoutMs(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			cancelLongTermMemoryRecall(future);
			log.warn("Long-term memory recall timeout. agentId={}, threadId={}, runtimeRequestId={}, timeoutMs={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(),
					resolveLongTermMemoryRecallTimeoutMs());
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			cancelLongTermMemoryRecall(future);
			log.warn("Long-term memory recall interrupted. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), ex);
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		catch (CancellationException ex) {
			log.warn("Long-term memory recall cancelled. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId());
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		catch (ExecutionException ex) {
			log.warn("Long-term memory recall skipped. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), ex.getCause());
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
		catch (RuntimeException ex) {
			log.warn("Long-term memory recall skipped. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), ex);
			return AgentMemoryRecallResultDTO.empty(true, false);
		}
	}

	/**
	 * 召回必须显式带租户，并跑在 TTL 包装的 dbOperationExecutor 上：裸 supplyAsync 会进
	 * commonPool，ThreadLocal 租户传不过去，requireTenantId 直接失败关闭。
	 */
	private CompletableFuture<AgentMemoryRecallResultDTO> submitLongTermMemoryRecall(AgentRequest request) {
		// 数字员工（ownerType=DIGITAL_EMPLOYEE）对话与无人值守任务同一召回桶：
		// 只召该员工 WORKSPACE 共享口径，不召操作人 EMPLOYEE_USER 偏好。这是有意隔离。
		Memory recentMemory = loadAs2RecentMemory(request);
		boolean unattended = OWNER_TYPE_DIGITAL_EMPLOYEE.equals(request.getOwnerType());
		String tenantId = resolveRecallTenantId(request);
		String query = buildUserPromptWithFeedback(request);
		return CompletableFuture.supplyAsync(() -> unattended
				? longTermMemoryRecallService.recallForDigitalEmployee(request.getAgentId(),
						String.valueOf(request.getOwnerId()), query, recentMemory, tenantId)
				: longTermMemoryRecallService.recall(request.getAgentId(), request.getUserIdSnapshot(), query,
						recentMemory, tenantId),
				dbOperationExecutor);
	}

	private String resolveRecallTenantId(AgentRequest request) {
		String snapshot = request == null ? null : request.getTenantIdSnapshot();
		if (isBindableScope(snapshot)) {
			return snapshot.trim();
		}
		try {
			String tenantId = authenticationContext == null ? null : authenticationContext.tenantId();
			if (isBindableScope(tenantId)) {
				return tenantId.trim();
			}
		}
		catch (RuntimeException ex) {
			log.warn("Long-term memory recall tenant fallback failed. agentId={}",
					request == null ? null : request.getAgentId(), ex);
		}
		throw CheckedException.forbidden("缺少租户上下文，无法召回记忆");
	}

	private static void cancelLongTermMemoryRecall(CompletableFuture<?> future) {
		if (future != null && !future.isDone()) {
			future.cancel(true);
		}
	}

	/**
	 * 从 as2: 装载近讯供长期记忆召回。缺租户/用户时不 bind {@code _}；store 缺失或 loadFrom 失败则空近讯，召回仍执行。
	 * isolatedMemory 保持空近讯（与原先 AutoContext 空记忆一致），抽取仍由 {@link #extractLongTermMemorySafely} 跳过。
	 */
	private Memory loadAs2RecentMemory(AgentRequest request) {
		InMemoryMemory empty = new InMemoryMemory();
		if (request == null || request.isIsolatedMemory()) {
			return empty;
		}
		V2AgentStateStore store = v2AgentStateStore();
		if (store == null) {
			return empty;
		}
		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot.from(request);
		if (!canBindV2Snapshot(snapshot)) {
			return empty;
		}
		try {
			InMemoryMemory memory = new InMemoryMemory();
			memory.loadFrom(store.bind(snapshot), snapshot.userId(), snapshot.sessionId());
			return memory;
		}
		catch (RuntimeException ex) {
			log.warn("as2: long-term memory recent texts skipped. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), ex);
			return empty;
		}
	}

	private static boolean canBindV2Snapshot(V2RuntimeSnapshot snapshot) {
		return snapshot != null && isBindableScope(snapshot.tenantId()) && isBindableScope(snapshot.userId())
				&& StringUtils.hasText(snapshot.sessionId());
	}

	private static boolean isBindableScope(String value) {
		return StringUtils.hasText(value) && !"_".equals(value.trim());
	}

	private long resolveLongTermMemoryRecallTimeoutMs() {
		if (dataAgentProperties == null || dataAgentProperties.getLongTermMemory() == null
				|| dataAgentProperties.getLongTermMemory().getRecallTimeoutMs() <= 0) {
			return 3000L;
		}
		return dataAgentProperties.getLongTermMemory().getRecallTimeoutMs();
	}

	private void recordLongTermMemorySpan(Span rootSpan, AgentMemoryRecallResultDTO result) {
		if (rootSpan == null || result == null) {
			return;
		}
		rootSpan.setAttribute("dataagent.long_term_memory.enabled", result.enabled());
		rootSpan.setAttribute("dataagent.long_term_memory.recall_enabled", result.recallEnabled());
		rootSpan.setAttribute("dataagent.long_term_memory.candidate_count", result.candidateCount());
		rootSpan.setAttribute("dataagent.long_term_memory.injected_count", result.injectedCount());
		rootSpan.setAttribute("dataagent.long_term_memory.estimated_tokens", result.estimatedTokens());
	}

	private void extractLongTermMemorySafely(AgentRequest request, String answer) {
		if (request != null && request.isIsolatedMemory()) {
			return;
		}
		try {
			longTermMemoryExtractionService.extractAndSaveAsync(request.getAgentId(), request.getUserIdSnapshot(),
					request.getThreadId(), request.getRuntimeRequestId(), resolvePersistedUserInput(request), answer,
					resolveAuthoritativeRunId(request));
		}
		catch (RuntimeException ex) {
			log.warn("Long-term memory extraction skipped. agentId={}, threadId={}, runtimeRequestId={}",
					request.getAgentId(), request.getThreadId(), request.getRuntimeRequestId(), ex);
		}
	}

	/**
	 * W7 记忆接线：取当次请求（根请求优先）对应的权威 Run ID，供记忆写入做「来源 Run 必须终态成功」
	 * 查证；迁移期未镜像的运行返回 null，记忆链路按参数校验放行。解析失败不阻断记忆抽取。
	 */
	private Long resolveAuthoritativeRunId(AgentRequest request) {
		if (request == null) {
			return null;
		}
		try {
			return runtimeRunService.findRunIdByRuntimeRequestId(
					firstText(request.getRootRuntimeRequestId(), request.getRuntimeRequestId()));
		}
		catch (RuntimeException ex) {
			log.debug("解析权威 Run ID 失败，记忆写入按迁移期语义放行. runtimeRequestId={}",
					request.getRuntimeRequestId(), ex);
			return null;
		}
	}

	private void persistNativeMemorySafely(AgentRequest request, io.agentscope.core.memory.Memory memory) {
		if (request != null && request.isIsolatedMemory()) {
			return;
		}
		if (memory == null || request == null || !StringUtils.hasText(request.getThreadId())) {
			return;
		}
		Long sessionId = parseThreadIdOrNull(request.getThreadId());
		if (sessionId == null) {
			return;
		}
		try {
			if (chatSessionService.findBySessionId(sessionId) == null) {
				return;
			}
			nativeSessionService.saveMemory(memory, request.getThreadId(), request.getUserIdSnapshot(),
					messages -> normalizeMemoryForPersistence(request, messages));
		}
		catch (RuntimeException ex) {
			log.warn("Failed to persist AgentScope native memory. threadId={}, runtimeRequestId={}",
					request.getThreadId(), request.getRuntimeRequestId(), ex);
		}
	}

	private void appendClarifyTurn(PreparedMemory preparedMemory, AgentRequest request, String clarifyText) {
		if (preparedMemory == null || request == null) {
			return;
		}
		Memory memory = preparedMemory.memory();
		if (memory == null) {
			return;
		}
		String userInput = resolvePersistedUserInput(request);
		if (StringUtils.hasText(userInput)) {
			memory.addMessage(Msg.builder().name("user").role(MsgRole.USER).textContent(userInput).build());
		}
		if (StringUtils.hasText(clarifyText)) {
			memory.addMessage(Msg.builder().name("assistant").role(MsgRole.ASSISTANT).textContent(clarifyText).build());
		}
	}

	private List<Msg> normalizeMemoryForPersistence(AgentRequest request, List<Msg> originalMessages) {
		String persistedUserInput = resolvePersistedUserInput(request);
		if (!StringUtils.hasText(persistedUserInput) || originalMessages == null || originalMessages.isEmpty()) {
			return originalMessages;
		}
		int replacementIndex = findLastUserMessageIndex(originalMessages);
		if (replacementIndex < 0) {
			return originalMessages;
		}
		List<Msg> normalizedMessages = new ArrayList<>(originalMessages);
		Msg replayMessage = originalMessages.get(replacementIndex);
		normalizedMessages.set(replacementIndex,
				Msg.builder()
					.id(replayMessage.getId())
					.name(resolveMsgName(replayMessage, "user"))
					.role(replayMessage.getRole())
					.content(normalizeUserContent(replayMessage.getContent(), persistedUserInput))
					.metadata(replayMessage.getMetadata())
					.timestamp(replayMessage.getTimestamp())
					.build());
		return normalizedMessages;
	}

	private int findLastUserMessageIndex(List<Msg> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			Msg message = messages.get(i);
			if (message != null && message.getRole() == MsgRole.USER) {
				return i;
			}
		}
		return -1;
	}

	private List<ContentBlock> normalizeUserContent(List<ContentBlock> content, String persistedUserInput) {
		List<ContentBlock> normalized = new ArrayList<>();
		boolean textAdded = false;
		if (content != null) {
			for (ContentBlock block : content) {
				if (block instanceof TextBlock) {
					if (!textAdded) {
						normalized.add(TextBlock.builder().text(persistedUserInput).build());
						textAdded = true;
					}
					continue;
				}
				if (block instanceof ImageBlock) {
					continue;
				}
				normalized.add(block);
			}
		}
		if (!textAdded) {
			normalized.add(0, TextBlock.builder().text(persistedUserInput).build());
		}
		return normalized;
	}

	private String resolveMsgName(Msg message, String fallback) {
		if (message != null && StringUtils.hasText(message.getName())) {
			return message.getName();
		}
		return fallback;
	}

	private String resolvePersistedUserInput(AgentRequest request) {
		if (request == null) {
			return null;
		}
		String query = request.getQuery();
		if (!StringUtils.hasText(request.getHumanFeedbackContent())) {
			return query;
		}
		String feedback = request.getHumanFeedbackContent().trim();
		if (!StringUtils.hasText(query)) {
			return feedback;
		}
		return """
				原始问题：
				%s

				用户补充：
				%s
				""".formatted(query, feedback).trim();
	}

	private void validateAgentStatus(DataAgent dataAgent, String requestAgentId) {
		if (dataAgent == null) {
			return;
		}
		String status = dataAgent.getStatus();
		if (!StringUtils.hasText(status) || AGENT_STATUS_PUBLISHED.equalsIgnoreCase(status)
				|| "draft".equalsIgnoreCase(status)) {
			return;
		}
		if (AGENT_STATUS_OFFLINE.equalsIgnoreCase(status)) {
			throw new IllegalStateException(
					"Agent %s is offline and cannot be run. Current status: %s".formatted(requestAgentId, status));
		}
		String resolvedStatus = StringUtils.hasText(status) ? status : "unknown";
		throw new IllegalStateException(
				"Agent %s cannot be run. Current status: %s".formatted(requestAgentId, resolvedStatus));
	}

	private Long parseAgentId(String agentId) {
		if (!StringUtils.hasText(agentId)) {
			return null;
		}
		try {
			return Long.valueOf(agentId);
		}
		catch (NumberFormatException ex) {
			log.warn("Agent id is not numeric, skip agent-specific prompt lookup. agentId={}", agentId);
			return null;
		}
	}

	private Long parseRequiredAgentId(String agentId) {
		if (!StringUtils.hasText(agentId)) {
			throw CheckedException.badRequest("agentId must not be empty");
		}
		try {
			return Long.valueOf(agentId);
		}
		catch (NumberFormatException ex) {
			throw CheckedException.badRequest("agentId must be numeric");
		}
	}

	private Long parseThreadIdOrNull(String threadId) {
		if (!StringUtils.hasText(threadId)) {
			return null;
		}
		try {
			return Long.valueOf(threadId);
		}
		catch (NumberFormatException ex) {
			log.warn("threadId is not numeric, skip chat-session persistence. threadId={}", threadId);
			return null;
		}
	}

	private Long parseRequiredThreadId(String threadId) {
		if (!StringUtils.hasText(threadId)) {
			throw CheckedException.badRequest("threadId must not be empty");
		}
		try {
			return Long.valueOf(threadId);
		}
		catch (NumberFormatException ex) {
			throw CheckedException.badRequest("threadId must be numeric");
		}
	}

	private String defaultText(String value) {
		return value == null ? "" : value;
	}

	private boolean isInterruptedCancellation(Throwable throwable) {
		Throwable current = Exceptions.unwrap(throwable);
		while (current != null) {
			if (current instanceof InterruptedException || current instanceof CancellationException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static final class ThinkingTraceCollector {

		private final AgentRequest request;

		private final List<TraceEntry> entries = new ArrayList<>();

		ThinkingTraceCollector(AgentRequest request) {
			this.request = request;
		}

		synchronized void record(AgentResponse response) {
			if (isRuntimeProgress(response)) {
				return;
			}
			if (!isThinkingResponse(response)) {
				return;
			}
			entries.add(new TraceEntry(response.getNodeName(), response.getTextType(), response.getText(),
					response.getMetadata()));
		}

		private boolean isRuntimeProgress(AgentResponse response) {
			if (response == null || response.getMetadata() == null) {
				return false;
			}
			return AgentRuntimeProgressService.EVENT_TYPE_RUNTIME_PROGRESS
				.equals(response.getMetadata().get("eventType"));
		}

		synchronized boolean hasEntries() {
			return !entries.isEmpty();
		}

		synchronized String toHtml(DataAgentOutputSanitizer sanitizer) {
			StringBuilder html = new StringBuilder();
			appendHeader(html, entries.size());
			for (TraceEntry entry : entries) {
				appendStepStart(html, resolveStepTitle(entry));
				html.append("<div><strong>节点：</strong>").append(escapeHtml(entry.nodeName())).append("</div>");
				html.append("<div><strong>类型：</strong>").append(escapeHtml(String.valueOf(entry.textType()))).append("</div>");
				if (StringUtils.hasText(entry.text())) {
					// 思考快照面向普通用户，只渲染脱敏后的文本；调试级 metadata 原文不进用户面。
					html.append("<pre>").append(escapeHtml(sanitizer.redactForThinking(entry.text(), null))).append("</pre>");
				}
				appendStepEnd(html);
			}
			appendFooter(html);
			return html.toString();
		}

		static String toHtmlFromExplain(AnswerTraceExplainStore.AnswerTraceExplainView explain,
				DataAgentOutputSanitizer sanitizer) {
			if (explain == null) {
				return null;
			}
			StringBuilder html = new StringBuilder();
			int stepCount = 1 + safeSize(explain.getToolSteps()) + (safeSize(explain.getWarnings()) > 0 ? 1 : 0);
			appendHeader(html, stepCount);
			appendStepStart(html, "执行摘要");
			appendLine(html, "问题", sanitizer.redactForThinking(explain.getQuestion(), explain));
			appendLine(html, "决策摘要", sanitizer.redactForThinking(explain.getDecisionReason(), explain));
			appendLine(html, "结果范围", sanitizer.redactForThinking(explain.getResultScope(), explain));
			appendLine(html, "数据源", sanitizer.redactForThinking(explain.getDatasource(), explain));
			appendLine(html, "表数量", String.valueOf(safeSize(explain.getUsedTables())));
			appendLine(html, "字段数量", String.valueOf(safeSize(explain.getUsedColumns())));
			appendLine(html, "语义命中", String.valueOf(safeSize(explain.getSemanticHits())));
			appendLine(html, "知识命中", String.valueOf(safeSize(explain.getKnowledgeHits())));
			appendStepEnd(html);
			if (explain.getToolSteps() != null) {
				for (AnswerTraceExplainStore.ToolStepView step : explain.getToolSteps()) {
					appendStepStart(html, StringUtils.hasText(step.getTitle()) ? step.getTitle() : "工具步骤");
					appendLine(html, "工具", sanitizer.toolDisplayNameForThinking(step.getToolName()));
					appendLine(html, "状态", step.getStatus());
					appendLine(html, "摘要", sanitizer.redactForThinking(step.getSummary(), explain));
					appendLine(html, "耗时", step.getDurationMs() == null ? null : step.getDurationMs() + "ms");
					appendStepEnd(html);
				}
			}
			if (explain.getWarnings() != null && !explain.getWarnings().isEmpty()) {
				appendStepStart(html, "Warnings");
				html.append("<ul>");
				for (String warning : explain.getWarnings()) {
					if (StringUtils.hasText(warning)) {
						html.append("<li>").append(escapeHtml(sanitizer.redactForThinking(warning, explain))).append("</li>");
					}
				}
				html.append("</ul>");
				appendStepEnd(html);
			}
			appendFooter(html);
			return html.toString();
		}

		private static void appendHeader(StringBuilder html, int count) {
			html.append("<details class=\"agent-thinking-block agent-thinking-group\" open>");
			html.append("<summary class=\"agent-response-summary\">");
			html.append("<span class=\"agent-response-title-text\">Thinking</span>");
			html.append("<span class=\"agent-thinking-count\">").append(count).append("</span>");
			html.append("</summary>");
			html.append("<div class=\"agent-thinking-content\">");
			html.append("<ol class=\"agent-thinking-steps\">");
		}

		private static void appendFooter(StringBuilder html) {
			html.append("</ol>");
			html.append("</div>");
			html.append("</details>");
		}

		private static void appendStepStart(StringBuilder html, String title) {
			html.append("<li class=\"agent-thinking-step\">");
			html.append("<div class=\"agent-thinking-step-body\">");
			html.append("<div class=\"agent-thinking-step-title\">").append(escapeHtml(title)).append("</div>");
			html.append("<div class=\"agent-thinking-step-content\">");
		}

		private static void appendStepEnd(StringBuilder html) {
			html.append("</div>");
			html.append("</div>");
			html.append("</li>");
		}

		private static void appendLine(StringBuilder html, String label, String value) {
			if (!StringUtils.hasText(value)) {
				return;
			}
			html.append("<div><strong>").append(escapeHtml(label)).append("：</strong>")
				.append(escapeHtml(value))
				.append("</div>");
		}

		private static int safeSize(List<?> values) {
			return values == null ? 0 : values.size();
		}

		private String resolveStepTitle(TraceEntry entry) {
			String nodeName = entry.nodeName();
			if (!StringUtils.hasText(nodeName)) {
				return "执行步骤";
			}
			if (nodeName.startsWith("tool:")) {
				return "工具调用";
			}
			if (REPORT_NODE_NAME.equals(nodeName)) {
				return "生成报告";
			}
			return nodeName;
		}

		private static boolean isThinkingResponse(AgentResponse response) {
			if (response == null || response.isComplete() || response.isError()) {
				return false;
			}
			if (response.getTextType() == TextType.RESULT_SET) {
				return false;
			}
			String nodeName = response.getNodeName();
			if (RUNTIME_NODE_NAME.equals(nodeName) || REPORT_NODE_NAME.equals(nodeName)) {
				return false;
			}
			return StringUtils.hasText(response.getText()) || response.getMetadata() != null;
		}

		private static String escapeHtml(String value) {
			if (value == null) {
				return "";
			}
			return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#39;");
		}

		private record TraceEntry(String nodeName, TextType textType, String text, Map<String, Object> metadata) {
		}

	}

	private static final class StreamTextTracker {

		private final Map<String, StringBuilder> accumulatedByNode = new LinkedHashMap<>();

		private final Map<String, String> lastTextByNode = new LinkedHashMap<>();

		private boolean structuredUiMessage;

		private boolean resultSet;

		synchronized void record(String nodeName, String text) {
			if (!StringUtils.hasText(text)) {
				return;
			}
			String normalizedNodeName = StringUtils.hasText(nodeName) ? nodeName : "";
			accumulatedByNode.computeIfAbsent(normalizedNodeName, key -> new StringBuilder()).append(text);
			lastTextByNode.put(normalizedNodeName, text);
		}

		synchronized void recordStructuredUiMessage() {
			structuredUiMessage = true;
		}

		synchronized boolean hasStructuredUiMessage() {
			return structuredUiMessage;
		}

		synchronized void recordResultSet() {
			resultSet = true;
		}

		synchronized boolean hasResultSet() {
			return resultSet;
		}

		synchronized boolean containsFinalAnswer(String candidate) {
			if (!StringUtils.hasText(candidate)) {
				return false;
			}
			String normalizedCandidate = normalize(candidate);
			if (!StringUtils.hasText(normalizedCandidate)) {
				return false;
			}
			for (StringBuilder accumulated : accumulatedByNode.values()) {
				String normalizedAccumulated = normalize(accumulated.toString());
				if (matchesFinalAnswer(normalizedAccumulated, normalizedCandidate)) {
					return true;
				}
			}
			for (String lastText : lastTextByNode.values()) {
				String normalizedLastText = normalize(lastText);
				if (matchesFinalAnswer(normalizedLastText, normalizedCandidate)) {
					return true;
				}
			}
			return false;
		}

		private boolean matchesFinalAnswer(String existingText, String candidate) {
			if (!StringUtils.hasText(existingText) || !StringUtils.hasText(candidate)) {
				return false;
			}
			return existingText.equals(candidate) || existingText.endsWith(candidate)
					|| candidate.endsWith(existingText);
		}

		private String normalize(String text) {
			if (!StringUtils.hasText(text)) {
				return "";
			}
			return text.replace("\r\n", "\n")
				.replace('\r', '\n')
				.replaceAll("[ \\t\\x0B\\f]+", " ")
				.replaceAll(" *\\n *", "\n")
				.trim();
		}

	}

}
