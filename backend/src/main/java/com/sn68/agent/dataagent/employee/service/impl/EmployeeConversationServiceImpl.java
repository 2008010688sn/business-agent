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
package com.sn68.agent.dataagent.employee.service.impl;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeProgressService;
import com.sn68.agent.dataagent.agentscope.runtime.ConversationAuthorizationGuard;
import com.sn68.agent.dataagent.agentscope.service.DataAgentService;
import com.sn68.agent.dataagent.agentscope.vo.AgentResponse;
import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeConversationResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.guard.EmployeeConversationGuard;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.EmployeeConversationService;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.enums.ChatSessionChannelDict;
import com.sn68.agent.dataagent.enums.TextType;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

/**
 * 数字员工对话 Facade 实现。
 *
 * <p>运行时身份恒为 {@code DIGITAL_EMPLOYEE}，会话与 agentId 都钉在员工主键上，不再把
 * live DataAgent（sourceAgentId）当作对话主体。执行模式分叉：
 * rollout 开启 + 员工 ENABLED + Principal READY + 生产环境已激活 PUBLISHED Release
 * → PRINCIPAL（签发委托 token 并以员工身份执行，技能来自 Release 快照）；
 * 其余一律 MODEL_ONLY（草稿提示词/模型、无业务工具沙箱）。
 * USE 判定在换 token 之前完成，主体是真人 CALLER（userIdSnapshot）；换票后执行期把
 * userIdSnapshot 改成 Principal，技能 SQL 才能对齐 live identity。
 * MODEL_ONLY 走 {@link com.sn68.agent.dataagent.employee.guard.EmployeeConversationGuard}
 * （READ_KNOWLEDGE）；PRINCIPAL 不走该 Guard，仍用 {@code ConversationAuthorizationGuard} 的 USE。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeConversationServiceImpl implements EmployeeConversationService {

	/** 执行模式：纯模型沙箱（rollout 未开启 / Principal 未就绪 / 无生产 Release）。 */
	private static final String EXECUTION_MODE_MODEL_ONLY = "MODEL_ONLY";

	/** 执行模式：以员工 Principal token 委托执行。 */
	private static final String EXECUTION_MODE_PRINCIPAL = "PRINCIPAL";

	/** 会话渠道标记（data_chat_session.channel_type，便于按数字员工域过滤会话）。 */
	private static final String SESSION_CHANNEL_TYPE = ChatSessionChannelDict.EMPLOYEE.getValue();

	/** 运行主体类型（AgentRequest.ownerType 透传口径）。 */
	private static final String OWNER_TYPE_EMPLOYEE = "DIGITAL_EMPLOYEE";

	/** 人工对话写入权威运行时的触发来源 / 运行模式（守护作业不拉起 CHAT）。 */
	private static final String TRIGGER_SOURCE_CHAT = "CHAT";

	private static final String RUN_MODE_CHAT = "CHAT";

	private static final String CONVERSATION_FAILED = "CONVERSATION_FAILED";

	private static final String STREAM_EVENT_MESSAGE = "message";

	private static final String STREAM_STAGE_EMPLOYEE_CONVERSATION = "EMPLOYEE_CONVERSATION";

	/** CHAT 截止缺省：20 分钟，对应 Runtime.chatDeadline 默认值。 */
	private static final long DEFAULT_CHAT_DEADLINE_SECONDS = Duration.ofMinutes(20).toSeconds();

	private final DigitalEmployeeMapper employeeMapper;

	private final EmployeeDeploymentService deploymentService;

	private final ConversationAuthorizationGuard conversationAuthorizationGuard;

	private final EmployeeConversationGuard employeeConversationGuard;

	private final EmployeeExecutionContextClient executionContextClient;

	private final DataAgentService dataAgentService;

	private final DataChatSessionService chatSessionService;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final DigitalEmployeeProperties properties;

	private final AuthenticationContext authenticationContext;

	private final RuntimeRunService runtimeRunService;

	private final DataAgentProperties dataAgentProperties;

	@Override
	public EmployeeConversationResp converse(Long employeeId, EmployeeConversationReq request) {
		ConversationPrep prep = prepareConversation(employeeId, request);
		try {
			String reply = executeConversation(prep.principalMode(), prep.employee(), prep.tenantId(),
					prep.agentRequest());
			succeedChatRunSafely(prep.tenantId(), prep.run(), reply);
			log.info("数字员工对话完成. employeeId={}, sessionId={}, executionMode={}, runtimeRunId={}", employeeId,
					prep.session().getId(), prep.executionMode(), prep.run() == null ? null : prep.run().id());
			return EmployeeConversationResp.builder()
				.sessionId(prep.session().getId())
				.reply(reply == null ? "" : reply)
				.executionMode(prep.executionMode())
				.runtimeRunId(prep.run() == null ? null : prep.run().id())
				.build();
		}
		catch (RuntimeException ex) {
			failChatRunSafely(prep.tenantId(), prep.run(), ex);
			throw ex;
		}
	}

	@Override
	public Flux<ServerSentEvent<AgentResponse>> converseStream(Long employeeId, EmployeeConversationReq request) {
		ConversationPrep prep = prepareConversation(employeeId, request);
		prep.agentRequest().setEmployeeFacadeStream(true);
		Flux<ServerSentEvent<AgentResponse>> body = attachRunLifecycle(
				executeConversationStream(prep.principalMode(), prep.employee(), prep.tenantId(), prep.agentRequest()),
				prep.tenantId(), prep.run());
		return Flux.concat(Flux.just(conversationStartedEvent(prep)), body);
	}

	private boolean isPrincipalMode(DigitalEmployee employee, Long activeReleaseId) {
		return properties.getRollout().isEnabled()
				&& EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())
				&& PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())
				&& StringUtils.hasText(employee.getIamPrincipalId())
				&& activeReleaseId != null;
	}

	private AgentRequest buildAgentRequest(DigitalEmployee employee, DataChatSession session,
			EmployeeConversationReq request, String tenantId, Long callerUserId, Long releaseId,
			boolean principalMode) {
		return AgentRequest.builder()
			.agentId(String.valueOf(employee.getId()))
			.threadId(String.valueOf(session.getId()))
			.query(request.getQuery().trim())
			.chatModelConfigId(request.getChatModelConfigId())
			.ownerType(OWNER_TYPE_EMPLOYEE)
			.ownerId(employee.getId())
			.releaseId(releaseId)
			.userIdSnapshot(String.valueOf(callerUserId))
			.tenantIdSnapshot(tenantId)
			.requestSource(TRIGGER_SOURCE_CHAT)
			.pinnedSkillVersionIds(principalMode ? null : List.of())
			.build();
	}

	private ConversationPrep prepareConversation(Long employeeId, EmployeeConversationReq request) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		if (request == null || !StringUtils.hasText(request.getQuery())) {
			throw CheckedException.badRequest("输入内容不能为空");
		}
		String tenantId = currentTenantId();
		Long callerUserId = requireCurrentUserId();
		DigitalEmployee employee = requireConversableEmployee(employeeId, tenantId);
		DataChatSession session = resolveSession(request.getSessionId(), employee, callerUserId);
		Long activeReleaseId = resolveActiveReleaseId(employeeId);
		boolean principalMode = isPrincipalMode(employee, activeReleaseId);
		String executionMode = principalMode ? EXECUTION_MODE_PRINCIPAL : EXECUTION_MODE_MODEL_ONLY;
		AgentRequest agentRequest = buildAgentRequest(employee, session, request, tenantId, callerUserId,
				principalMode ? activeReleaseId : null, principalMode);
		if (principalMode) {
			// 换票前按 CALLER 做 USE 判定；MODEL_ONLY Guard 会拒业务动作，Principal 不走该 Guard。
			conversationAuthorizationGuard.authorizeConversationUse(agentRequest);
			agentRequest.setUserIdSnapshot(employee.getIamPrincipalId());
		}
		else {
			employeeConversationGuard.requireAllowed(AuthorizationAction.READ_KNOWLEDGE);
		}
		RuntimeRunResp run = startChatRunSafely(tenantId, callerUserId, employee, session, agentRequest);
		bindRuntimeRequestId(agentRequest, run);
		return new ConversationPrep(employee, session, agentRequest, principalMode, executionMode, tenantId, run);
	}

	private String executeConversation(boolean principalMode, DigitalEmployee employee, String tenantId,
			AgentRequest agentRequest) {
		if (principalMode) {
			EmployeeAuthTokenContext tokenContext = executionContextClient.issueContext(tenantId,
					employee.getIamPrincipalId(), employee.getEmployeeName());
			return executeAsPrincipal(agentRequest, tokenContext, tenantId);
		}
		return dataAgentService.executeAgentOnce(agentRequest);
	}

	private Flux<ServerSentEvent<AgentResponse>> executeConversationStream(boolean principalMode,
			DigitalEmployee employee, String tenantId, AgentRequest agentRequest) {
		if (principalMode) {
			EmployeeAuthTokenContext tokenContext = executionContextClient.issueContext(tenantId,
					employee.getIamPrincipalId(), employee.getEmployeeName());
			DataAgentOutboundContext.Snapshot outbound = DataAgentOutboundContext.withPrincipalToken(
					DataAgentOutboundContext.get(), tokenContext.tokenValue(), tenantId);
			DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.snapshotForDelegatedToken(
					tokenContext.tokenValue(), outbound);
			return asyncContextBridge.supplyWith(snapshot, () -> dataAgentService.streamSearch(agentRequest));
		}
		return dataAgentService.streamSearch(agentRequest);
	}

	private ServerSentEvent<AgentResponse> conversationStartedEvent(ConversationPrep prep) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("eventType", AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS);
		metadata.put("stageCode", STREAM_STAGE_EMPLOYEE_CONVERSATION);
		metadata.put("status", AgentRuntimeProgressService.STATUS_RUNNING);
		metadata.put("executionMode", prep.executionMode());
		metadata.put("sessionId", prep.session().getId());
		if (prep.run() != null && prep.run().id() != null) {
			metadata.put("runtimeRunId", prep.run().id());
		}
		AgentResponse data = AgentResponse.builder()
			.agentId(String.valueOf(prep.employee().getId()))
			.threadId(String.valueOf(prep.session().getId()))
			.textType(TextType.TEXT)
			.text("")
			.metadata(metadata)
			.build();
		return ServerSentEvent.<AgentResponse>builder()
			.event(AgentRuntimeProgressService.STREAM_EVENT_RUNTIME_PROGRESS)
			.data(data)
			.build();
	}

	private Flux<ServerSentEvent<AgentResponse>> attachRunLifecycle(Flux<ServerSentEvent<AgentResponse>> stream,
			String tenantId, RuntimeRunResp run) {
		if (run == null || run.id() == null) {
			return stream;
		}
		AtomicBoolean closed = new AtomicBoolean(false);
		StringBuilder reply = new StringBuilder();
		return stream.doOnNext(event -> onStreamEvent(event, tenantId, run, reply, closed))
			.doOnComplete(() -> succeedChatRunOnce(tenantId, run, reply.toString(), closed))
			.doOnCancel(() -> log.info("数字员工对话流断开, 运行继续. runtimeRunId={}", run.id()))
			.doOnError(error -> failChatRunOnce(tenantId, run,
					error != null && StringUtils.hasText(error.getMessage()) ? error.getMessage() : CONVERSATION_FAILED,
					closed));
	}

	private void onStreamEvent(ServerSentEvent<AgentResponse> event, String tenantId, RuntimeRunResp run,
			StringBuilder reply, AtomicBoolean closed) {
		if (event == null) {
			return;
		}
		if (Constant.STREAM_EVENT_COMPLETE.equals(event.event())) {
			succeedChatRunOnce(tenantId, run, reply.toString(), closed);
			return;
		}
		if (Constant.STREAM_EVENT_ERROR.equals(event.event())) {
			String message = event.data() != null && StringUtils.hasText(event.data().getText())
					? event.data().getText() : CONVERSATION_FAILED;
			failChatRunOnce(tenantId, run, message, closed);
			return;
		}
		if (event.data() != null && StringUtils.hasText(event.data().getText())
				&& (event.event() == null || STREAM_EVENT_MESSAGE.equals(event.event()))) {
			reply.append(event.data().getText());
		}
	}

	private void succeedChatRunOnce(String tenantId, RuntimeRunResp run, String reply, AtomicBoolean closed) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		succeedChatRunSafely(tenantId, run, reply);
	}

	private void failChatRunOnce(String tenantId, RuntimeRunResp run, String message, AtomicBoolean closed) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		failChatRunSafely(tenantId, run, new IllegalStateException(message));
	}

	private record ConversationPrep(DigitalEmployee employee, DataChatSession session, AgentRequest agentRequest,
			boolean principalMode, String executionMode, String tenantId, RuntimeRunResp run) {
	}

	private RuntimeRunResp startChatRunSafely(String tenantId, Long callerUserId, DigitalEmployee employee,
			DataChatSession session, AgentRequest agentRequest) {
		return runtimeRunService.startInteractiveRun(tenantId, String.valueOf(callerUserId),
				new RuntimeRunCreateReq("CHAT:" + session.getId() + ":" + UUID.randomUUID(), OWNER_TYPE_EMPLOYEE,
						employee.getId(), employee.getId(), agentRequest.getReleaseId(), employee.getId(),
						String.valueOf(session.getId()), agentRequest.getQuery(), RUN_MODE_CHAT,
						chatDeadlineSeconds(), TRIGGER_SOURCE_CHAT));
	}

	/**
	 * CHAT 截止秒数：取 Runtime.chatDeadline；空/零/负回落到 20 分钟。
	 * {@link RuntimeRunCreateReq#deadlineSeconds} 带 {@code @Positive}，必须为正。
	 */
	private long chatDeadlineSeconds() {
		Duration deadline = dataAgentProperties.getRuntime() == null ? null
				: dataAgentProperties.getRuntime().getChatDeadline();
		if (deadline == null || deadline.isZero() || deadline.isNegative()) {
			return DEFAULT_CHAT_DEADLINE_SECONDS;
		}
		long seconds = deadline.toSeconds();
		return seconds > 0 ? seconds : DEFAULT_CHAT_DEADLINE_SECONDS;
	}

	private void bindRuntimeRequestId(AgentRequest agentRequest, RuntimeRunResp run) {
		if (agentRequest == null || run == null || !StringUtils.hasText(run.runtimeRequestId())) {
			return;
		}
		agentRequest.setRuntimeRequestId(run.runtimeRequestId());
		agentRequest.setDurableRunId(run.id());
	}

	private void succeedChatRunSafely(String tenantId, RuntimeRunResp run, String reply) {
		if (run == null || run.id() == null) {
			return;
		}
		try {
			runtimeRunService.succeedInteractiveRun(tenantId, run.id(), reply == null ? "" : reply);
		}
		catch (RuntimeException ex) {
			log.error("数字员工对话运行成功收尾失败. runtimeRunId={}", run.id(), ex);
		}
	}

	private void failChatRunSafely(String tenantId, RuntimeRunResp run, RuntimeException failure) {
		if (run == null || run.id() == null) {
			return;
		}
		try {
			String message = failure == null || !StringUtils.hasText(failure.getMessage()) ? CONVERSATION_FAILED
					: failure.getMessage();
			runtimeRunService.failInteractiveRun(tenantId, run.id(), CONVERSATION_FAILED, message);
		}
		catch (RuntimeException ex) {
			log.error("数字员工对话运行失败收尾失败. runtimeRunId={}", run.id(), ex);
		}
	}

	/**
	 * 以员工 Principal token 委托执行：委托 Token Session 是权限唯一来源，
	 * 出站头同步换成 Principal token，避免 Feign 继续透传真人 V4-Authorization。
	 */
	private String executeAsPrincipal(AgentRequest agentRequest, EmployeeAuthTokenContext tokenContext,
			String tenantId) {
		DataAgentOutboundContext.Snapshot outbound = DataAgentOutboundContext.withPrincipalToken(
				DataAgentOutboundContext.get(), tokenContext.tokenValue(), tenantId);
		DataAgentAsyncContextBridge.Snapshot snapshot = asyncContextBridge.snapshotForDelegatedToken(
				tokenContext.tokenValue(), outbound);
		return asyncContextBridge.supplyWith(snapshot, () -> dataAgentService.executeAgentOnce(agentRequest));
	}

	private DigitalEmployee requireConversableEmployee(Long employeeId, String tenantId) {
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, tenantId);
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		if (EmployeeStatusDict.ARCHIVED.getValue().equals(employee.getStatus())) {
			throw CheckedException.badRequest("已封存的数字员工不可对话");
		}
		return employee;
	}

	private DataChatSession resolveSession(Long sessionId, DigitalEmployee employee, Long callerUserId) {
		if (sessionId == null) {
			DataChatSession created = chatSessionService.createSession(employee.getId(),
					"数字员工-" + employee.getEmployeeName(), callerUserId, SESSION_CHANNEL_TYPE, null, null, null,
					"digital-employee:" + employee.getId());
			if (created == null || created.getId() == null) {
				throw CheckedException.fail("数字员工会话创建失败");
			}
			return created;
		}
		DataChatSession session = chatSessionService.requireSessionForAgent(sessionId, employee.getId());
		if (session.getUserId() == null || !session.getUserId().equals(callerUserId)) {
			throw CheckedException.forbidden("会话不属于当前用户，禁止在该会话下与数字员工对话");
		}
		return session;
	}

	private Long resolveActiveReleaseId(Long employeeId) {
		DigitalEmployeeDeployment deployment = deploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.PRODUCTION.getValue());
		return deployment == null ? null : deployment.getActiveReleaseId();
	}

	private Long requireCurrentUserId() {
		String userId;
		try {
			userId = authenticationContext.userId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录用户，禁止数字员工对话");
		}
		if (!StringUtils.hasText(userId)) {
			throw CheckedException.forbidden("当前登录用户为空，禁止数字员工对话");
		}
		try {
			return Long.valueOf(userId.trim());
		}
		catch (NumberFormatException ex) {
			throw CheckedException.forbidden("当前登录用户ID不合法: " + userId);
		}
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止数字员工对话");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止数字员工对话");
		}
		return tenantId.trim();
	}

}
