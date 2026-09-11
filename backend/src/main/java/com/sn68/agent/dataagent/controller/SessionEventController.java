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
package com.sn68.agent.dataagent.controller;

import com.sn68.agent.dataagent.channel.enums.ChannelSessionErrorDict;
import com.sn68.agent.dataagent.dto.agent.AgentIdReq;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeEventResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunEventStreamReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.dataagent.service.chat.SessionEventPublisher;
import com.sn68.agent.dataagent.vo.SessionUpdateEvent;
import com.sn68.agent.framework.commons.annotation.IgnoreGlobalResponse;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 推送 DataAgent 会话更新事件；并提供持久运行时事件的 afterSeq SSE 回放（W2）。
 */
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "会话事件", description = "推送 DataAgent 会话更新事件")
public class SessionEventController {

	/**
	 * 持久事件流轮询间隔与单批上限：事件源是数据库持久化序列，不依赖 JVM 内存 Sink。
	 */
	private static final Duration RUNTIME_EVENT_POLL_INTERVAL = Duration.ofMillis(800);

	private static final int RUNTIME_EVENT_POLL_BATCH = 500;

	private static final Duration RUNTIME_EVENT_STREAM_MAX_DURATION = Duration.ofMinutes(30);

	/**
	 * 收到运行终态事件后结束 SSE 流。
	 */
	private static final Set<String> RUN_TERMINAL_EVENT_TYPES = Set.of("RUN_SUCCEEDED", "RUN_FAILED", "RUN_CANCELLED",
			"RUN_TIMED_OUT");

	/**
	 * 重连回放跳过正文增量；快照与其它事件照常下发。字符串比较，避免与并行枚举落地耦合。
	 */
	private static final String ASSISTANT_DELTA_EVENT_TYPE = "ASSISTANT_DELTA";

	private final SessionEventPublisher sessionEventPublisher;

	private final RuntimeRunService runtimeRunService;

	private final DataChatSessionService chatSessionService;

	private final AuthenticationContext authenticationContext;

	@Operation(summary = "流式推送会话事件会话", description = "流式推送会话事件会话，用于会话事件相关管理和运行场景。")
	@IgnoreGlobalResponse(description = "会话更新SSE")
	@PostMapping(value = "/sessions/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<SessionUpdateEvent>> streamSessionUpdates(@RequestBody AgentIdReq request) {
		// SSE 端点保留显式判空：校验异常会被全局处理器渲染成 JSON，与 text/event-stream 协商冲突
		Long agentId = request == null ? null : request.agentId();
		if (agentId == null) {
			throw CheckedException.badRequest("agentId cannot be null");
		}
		return sessionEventPublisher.register(agentId);
	}

	@Operation(summary = "流式推送持久运行时事件",
			description = "基于持久化事件序列（agent_runtime_event）推送运行事件；断线后携最后收到的 seq 作为 afterSeq 重连，"
					+ "无遗漏、无乱序、无重复；收到运行终态事件后自动结束。")
	@IgnoreGlobalResponse(description = "持久运行时事件SSE")
	@PostMapping(value = "/runtime-runs/events/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<ServerSentEvent<RuntimeEventResp>> streamRuntimeRunEvents(
			@RequestBody RuntimeRunEventStreamReq request) {
		// SSE 端点保留显式判空，原因同上
		if (request == null || request.runId() == null) {
			throw CheckedException.badRequest("runId cannot be null");
		}
		// 订阅线程无租户上下文，必须在请求线程捕获租户并逐次带入查询
		String tenantId = currentTenantId();
		Long runId = request.runId();
		// 首次调用即校验 run 归属租户，不存在/越权直接以 JSON 错误响应结束
		RuntimeRunDetailResp detail = runtimeRunService.detail(tenantId, runId);
		authorizeSessionOwner(detail == null ? null : detail.run());
		AtomicLong cursor = new AtomicLong(request.afterSeq() == null ? 0L : request.afterSeq());
		return Flux.interval(Duration.ZERO, RUNTIME_EVENT_POLL_INTERVAL)
			.onBackpressureDrop()
			.concatMap(tick -> Mono
				.fromCallable(() -> runtimeRunService.events(tenantId, runId, cursor.get(), RUNTIME_EVENT_POLL_BATCH))
				.subscribeOn(Schedulers.boundedElastic())
				.flatMapMany(Flux::fromIterable), 1)
			.doOnNext(event -> cursor.accumulateAndGet(event.seq() == null ? 0L : event.seq(), Math::max))
			.filter(this::shouldReplayRuntimeEvent)
			.takeUntil(event -> RUN_TERMINAL_EVENT_TYPES.contains(event.eventType()))
			.take(RUNTIME_EVENT_STREAM_MAX_DURATION)
			.map(event -> ServerSentEvent.<RuntimeEventResp>builder()
				.id(event.seq() == null ? null : String.valueOf(event.seq()))
				.event(event.eventType())
				.data(event)
				.build());
	}

	/**
	 * 用户态事件流必须校验会话属主；不要求 WEB 渠道，数字员工会话同样可订阅。订阅方不是执行器，不续租约。
	 */
	void authorizeSessionOwner(RuntimeRunResp run) {
		if (run == null || !StringUtils.hasText(run.threadId())) {
			throw sessionOwnerForbidden();
		}
		Long sessionId;
		try {
			sessionId = Long.valueOf(run.threadId().trim());
		}
		catch (NumberFormatException ex) {
			throw sessionOwnerForbidden();
		}
		DataChatSession session = chatSessionService.findBySessionId(sessionId);
		if (session == null) {
			throw sessionOwnerForbidden();
		}
		String currentUser = authenticationContext.userId();
		if (!StringUtils.hasText(currentUser)) {
			throw sessionOwnerForbidden();
		}
		String current = currentUser.trim();
		if (session.getUserId() != null && current.equals(String.valueOf(session.getUserId()))) {
			return;
		}
		if (StringUtils.hasText(session.getCreateBy()) && current.equals(session.getCreateBy().trim())) {
			return;
		}
		throw sessionOwnerForbidden();
	}

	boolean shouldReplayRuntimeEvent(RuntimeEventResp event) {
		return event != null && !ASSISTANT_DELTA_EVENT_TYPE.equals(event.eventType());
	}

	private static CheckedException sessionOwnerForbidden() {
		return CheckedException.forbidden(ChannelSessionErrorDict.SESSION_OWNER_FORBIDDEN.getLabel());
	}

	private String currentTenantId() {
		String tenantId = authenticationContext.tenantId();
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失");
		}
		return tenantId.trim();
	}

}
