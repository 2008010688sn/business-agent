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
package com.sn68.agent.dataagent.agentscope.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sn68.agent.dataagent.dto.chat.ChatAttachmentDTO;
import com.sn68.agent.dataagent.multimodal.ExtractCard;
import com.sn68.agent.dataagent.multimodal.TurnArtifact;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryRecallResultDTO;
import com.sn68.agent.dataagent.flow.FlowAction;
import com.sn68.agent.dataagent.flow.FlowTextSearchIntent;
import com.sn68.agent.dataagent.im.ChannelInteractionCapability;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeDeadline;
import com.sn68.agent.dataagent.routing.model.ExplicitRouteTarget;
import com.sn68.agent.dataagent.routing.model.RoutePlan;
import com.sn68.agent.dataagent.routing.model.RouteScope;
import com.sn68.agent.dataagent.routing.model.RouteDecision;
import com.sn68.agent.dataagent.skill.SkillExecutionMode;
import com.sn68.agent.dataagent.skill.execution.SkillBusinessContext;
import com.sn68.agent.dataagent.skill.execution.SkillVersionResources;
import com.sn68.agent.dataagent.service.report.SkillReportProfile;
import com.sn68.agent.dataagent.temporal.AgentTemporalContext;
import com.sn68.agent.dataagent.temporal.TemporalInterval;
import com.sn68.agent.framework.commons.security.DataPermission;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent请求。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Schema(description = "智能体运行请求")
public class AgentRequest {

	@Schema(description = "智能体ID")
	private String agentId;

	@Schema(description = "会话ID")
	private String threadId;

	@Schema(description = "运行请求ID")
	private String runtimeRequestId;

	@JsonIgnore
	@Schema(hidden = true)
	private String rootRuntimeRequestId;

	@JsonIgnore
	@Schema(hidden = true)
	private String parentRuntimeRequestId;

	@JsonIgnore
	@Schema(hidden = true)
	private String parentAgentId;

	@JsonIgnore
	@Schema(hidden = true)
	private String parentThreadId;

	@Schema(description = "用户问题")
	private String query;

	@Schema(description = "当前页可选上下文，仅 hint；雪花 id 必须是字符串")
	private PageContext pageContext;

	@JsonIgnore
	@Schema(hidden = true)
	private GroundedFacts groundedFacts;

	@Schema(hidden = true)
	@JsonIgnore
	@Deprecated
	private ExplicitRouteTarget explicitRouteTarget;

	@Schema(description = "Business clarification or confirmation response")
	private ClarificationResponse clarificationResponse;

	@Schema(description = "FLOW instance id")
	private String flowInstanceId;

	@Schema(description = "Structured action for a waiting FLOW node")
	private FlowAction flowAction;

	@JsonIgnore
	@Schema(hidden = true)
	@Builder.Default
	private Set<ChannelInteractionCapability> interactionCapabilities = Set.of(ChannelInteractionCapability.STRUCTURED_ACTIONS);

	@Schema(description = "Client-supported interaction schemas")
	@Builder.Default
	private Set<String> clientInteractionSchemas = Set.of();

	@JsonIgnore
	@Schema(hidden = true)
	private FlowTextSearchIntent flowTextSearchIntent;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean flowTextDirectEdit;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean flowTextNoop;

	@Schema(description = "聊天附件")
	private List<ChatAttachmentDTO> attachments;

	@JsonIgnore
	@Schema(hidden = true)
	private TurnArtifact turnArtifact;

	@Schema(description = "回合融合产物 ID，仅观测")
	private String turnArtifactId;

	@JsonIgnore
	@Schema(hidden = true)
	private ExtractCard extractCard;

	@JsonIgnore
	@Schema(hidden = true)
	private String originalUserQuery;

	@Schema(description = "是否启用澄清检查")
	private boolean clarifyCheckEnabled;

	@Schema(description = "是否为人工反馈")
	private boolean humanFeedback;

	@Schema(description = "人工反馈内容")
	private String humanFeedbackContent;

	@Schema(description = "是否拒绝执行计划")
	private boolean rejectedPlan;

	@Schema(description = "对话模型配置ID")
	private Long chatModelConfigId;

	@Schema(description = "响应模式：normal-普通问答，report-分析报告")
	@Builder.Default
	private String responseMode = "normal";

	@JsonIgnore
	@Schema(hidden = true)
	@Builder.Default
	private boolean reportIntentDetectionEnabled = true;

	@JsonIgnore
	@Schema(hidden = true)
	private DataPermission dataPermissionSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String userIdSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String userNickNameSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String agentNameSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String requestSource;

	/**
	 * 执行意图（LIVE / DRY_RUN）。离线评估适配器设为 DRY_RUN，随 ToolContext 透传到
	 * CapabilityGateway，用于跨线程可靠拦截写能力与外部副作用（方案第十四章）。空值按 LIVE 处理。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String executionIntent;

	/**
	 * DRY_RUN 违规采集作用域键：评估执行方生成并注册采集器，关口按该键回投违规记录。
	 * 仅 DRY_RUN 评估链路填写。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String executionScopeKey;

	/**
	 * 运行主体类型（DIGITAL_EMPLOYEE / CALLER / PLATFORM）。PR-1 起与 {@link #executionIntent}
	 * 同一透传口径：由发起方从权威运行（agent_runtime_run）显式携带，运行时内部切线程也不会丢失，
	 * 供 CapabilityGateway 审批链路按 owner 维度隔离批复使用。空值表示调用方未携带主体。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String ownerType;

	/**
	 * 运行主体 ID。与 {@link #ownerType} 成对透传，任一缺失时按无主体作用域处理。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private Long ownerId;

	/**
	 * 本次运行钉死的发布版本 ID。数字员工为 {@code digital_employee_release.id}；
	 * 普通智能体不再使用该字段（只认 {@code DataAgent.status}）。空值表示未绑定 Release。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private Long releaseId;

	/**
	 * 评估 / Shadow DRY_RUN 才允许覆盖系统提示（候选 overlay）。生产对话不得携带；运行时仅在
	 * {@code executionIntent=DRY_RUN} 且本字段非空时生效，不要求 {@code requestSource=EVAL}。
	 * 非 DRY_RUN（含 LIVE）即使携带也忽略，仍用 live / release 提示词。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String evalSystemInstructionOverride;

	/**
	 * 数字员工运行时钉死的 SkillVersion ID 列表。非 null（含空列表）表示走快照/沙箱钉死路径，
	 * 禁止再按 DataAgent 绑定表回源；null 表示普通 DataAgent live 绑定。仅服务端写入。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private List<Long> pinnedSkillVersionIds;

	/**
	 * 数字员工 Facade 流式入口标记。仅服务端写入，客户端 JSON 无法设置。
	 * 为 true 时允许 EMPLOYEE 渠道与 DIGITAL_EMPLOYEE 主体进入 {@code streamSearch}；
	 * HTTP {@code /stream/search} 不会带此标记，从而拒绝员工旁路。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private boolean employeeFacadeStream;

	/**
	 * HTTP/IM {@code streamSearch} → {@code graphStreamProcess} 标记。仅服务端写入。
	 * {@code hitlEnabled} 为 true 时写工具走 ASK。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private boolean streamSearchRuntime;

	/**
	 * 本轮分析通道意图（FILE_ONLY / FILE_JOIN / ANALYSIS）。仅服务端写入，供 v2 toolkit 过滤与
	 * {@code V2ActingPermissionMiddleware} 使用。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String v2AnalysisIntent;

	@JsonIgnore
	@Schema(hidden = true)
	private String provider;

	@JsonIgnore
	@Schema(hidden = true)
	private String connectorCode;

	@JsonIgnore
	@Schema(hidden = true)
	private String conversationType;

	@JsonIgnore
	@Schema(hidden = true)
	private String externalUserId;

	@JsonIgnore
	@Schema(hidden = true)
	private String externalConversationId;

	@JsonIgnore
	@Schema(hidden = true)
	private String tenantIdSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String tenantCodeSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String clientIdSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private List<String> teamIdsSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private Long orchestrationRunId;

	/**
	 * 权威运行 ID（{@code agent_runtime_run.id}）。仅服务端写入，供能力网关审批与调用记录使用。
	 * 禁止用 {@link #orchestrationRunId}（遥测表 id）顶替。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private Long durableRunId;

	/** 本节点持有的 CHAT Run 租约 fence。仅服务端写入，旧写者追加事件时必须带上。 */
	@JsonIgnore
	@Schema(hidden = true)
	private Long fenceToken;

	/** 本节点 CHAT Run 租约持有者。仅服务端写入。 */
	@JsonIgnore
	@Schema(hidden = true)
	private String leaseOwner;

	@JsonIgnore
	@Schema(hidden = true)
	private Long orchestrationStepId;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean isolatedMemory;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean collaboratorChild;

	/**
	 * 协作者自己的任务句，供 TopN/排名守卫使用。不能用拼了前置答案的整段 prompt，否则账单子任务会误套父问句的 top10。
	 */
	@JsonIgnore
	@Schema(hidden = true)
	private String rankingIntentQuery;

	@JsonIgnore
	@Schema(hidden = true)
	private String collaboratorDelegationMode;

	@JsonIgnore
	@Schema(hidden = true)
	private Duration runtimeTimeout;

	@JsonIgnore
	@Schema(hidden = true)
	private AgentRuntimeDeadline runtimeDeadline;

	@JsonIgnore
	@Schema(hidden = true)
	private Duration runtimeFinishBuffer;

	@JsonIgnore
	@Schema(hidden = true)
	private Integer reactMaxIterations;

	@JsonIgnore
	@Schema(hidden = true)
	private Integer maxModelCalls;

	@JsonIgnore
	@Schema(hidden = true)
	private Integer maxToolCalls;

	@JsonIgnore
	@Schema(hidden = true)
	private Long maxPromptTokens;

	@JsonIgnore
	@Schema(hidden = true)
	private AgentTemporalContext temporalContext;

	@JsonIgnore
	@Schema(hidden = true)
	private TemporalInterval temporalInterval;

	@JsonIgnore
	@Schema(hidden = true)
	private String pendingInteractionId;

	@JsonIgnore
	@Schema(hidden = true)
	private Map<String, Object> pendingInteractionMetadata;

	@JsonIgnore
	@Schema(hidden = true)
	private String pendingInteractionText;

	@JsonIgnore
	@Schema(hidden = true)
	private RouteDecision orchestrationRouteSnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String orchestrationFailureStrategy;

	@JsonIgnore
	@Schema(hidden = true)
	private Boolean orchestrationExposeTrace;

	@JsonIgnore
	@Schema(hidden = true)
	private Map<String, Object> orchestrationDependencyInputs;

	@JsonIgnore
	@Schema(hidden = true)
	private AgentMemoryRecallResultDTO memoryRecallResult;

	@JsonIgnore
	@Schema(hidden = true)
	private String routedSkillCode;

	@JsonIgnore
	@Schema(hidden = true)
	private Long routedSkillId;

	@JsonIgnore
	@Schema(hidden = true)
	private Long routedSkillVersionId;

	@JsonIgnore
	@Schema(hidden = true)
	private SkillVersionResources routedSkillResources;

	@JsonIgnore
	@Schema(hidden = true)
	private String routedSkillInstructions;

	@JsonIgnore
	@Schema(hidden = true)
	private SkillBusinessContext skillBusinessContext;

	@JsonIgnore
	@Schema(hidden = true)
	private SkillReportProfile routedSkillReportProfile;

	@JsonIgnore
	@Schema(hidden = true)
	private SkillExecutionMode routedSkillExecutionMode;

	@JsonIgnore
	@Schema(hidden = true)
	private RouteDecision routeResult;

	@JsonIgnore
	@Schema(hidden = true)
	private RouteDecision resumedConfirmedRoute;

	@JsonIgnore
	@Schema(hidden = true)
	private String originalQuerySnapshot;

	@JsonIgnore
	@Schema(hidden = true)
	private String effectiveRoutingQuery;

	@JsonIgnore
	@Schema(hidden = true)
	private Integer routeClarificationRound;

	@JsonIgnore
	@Schema(hidden = true)
	private boolean routeConfirmed;

	@JsonIgnore
	@Schema(hidden = true)
	private RouteScope routeScope;

	@JsonIgnore
	@Schema(hidden = true)
	private RoutePlan routePlan;

	@JsonIgnore
	@Schema(hidden = true)
	private String routeDecision;

	@JsonIgnore
	@Schema(hidden = true)
	private String routeReasonCode;

	@JsonIgnore
	@Schema(hidden = true)
	private Long routeDurationMs;

	@JsonIgnore
	@Schema(hidden = true)
	private Long knowledgeDurationMs;

	@JsonIgnore
	@Schema(hidden = true)
	private Long flowDurationMs;

	@JsonIgnore
	@Schema(hidden = true)
	private Long reactDurationMs;

	public boolean supportsTextCommands() {
		return interactionCapabilities != null && interactionCapabilities.contains(ChannelInteractionCapability.TEXT_COMMANDS);
	}

}
