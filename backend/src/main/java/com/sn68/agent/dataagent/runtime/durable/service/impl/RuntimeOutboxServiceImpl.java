/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeOutbox;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeOutboxState;
import com.sn68.agent.dataagent.runtime.durable.event.RuntimeOutboxEvent;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeOutboxMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 持久运行时 Outbox 服务实现。
 *
 * <p>payload 列存信封 JSON：{@code {"eventKey":..., "workspaceId":..., "data":{...}}}，
 * eventKey / workspaceId 无独立列，借信封承载；派发时还原为 {@link RuntimeOutboxEvent}。</p>
 *
 * <p>{@link #dispatchPending} 刻意不加 @Transactional：认领后逐条「发布 → 标记」按语句自动提交，
 * 单条监听方失败只影响该条的重试计数，不会因 PostgreSQL 事务 aborted 拖垮整批；多副本互斥由
 * 派发器的 Redis 锁承担，mark* 语句自带状态谓词，重复派发收敛为 at-least-once。</p>
 *
 * <p>监听方（含 IM Webhook 推送）可能发慢 HTTP 请求，而本方法在派发锁内执行：为避免单条慢消息
 * 拖垮整批 / 长期占锁，单条发布放到受限执行器里带超时等待，超时按派发失败进重试（监听方须按
 * eventKey 幂等——超时后监听方可能已实际完成，重试会重复投递，这是 at-least-once 的既有契约）；
 * 整批另有时间预算，超出即停止本轮，剩余消息留待下一轮认领。消息仍逐条顺序处理，顺序语义不变。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeOutboxServiceImpl implements RuntimeOutboxService {

	/** 派发重试上限，达到后置 DEAD 并 error 日志（消息内容仍在表中可人工恢复）。 */
	private static final int MAX_RETRY = 5;

	/** 重试退避基数：第 n 次失败后延迟 n * 60s 再重试。 */
	private static final Duration RETRY_BACKOFF_BASE = Duration.ofSeconds(60);

	/** 目标通道：Spring 进程内事件（监听方自行扇出到 IM / MQ / WEBHOOK）。 */
	private static final String TARGET_CHANNEL_EVENT = "EVENT";

	private static final String ENVELOPE_EVENT_KEY = "eventKey";

	private static final String ENVELOPE_WORKSPACE_ID = "workspaceId";

	private static final String ENVELOPE_DATA = "data";

	/** 单条消息监听方执行超时；包内可见供单测缩短等待，生产用默认值。 */
	Duration listenerTimeout = Duration.ofSeconds(10);

	/** 单轮派发时间预算；超出后停止本轮、剩余消息留待下一轮，限定派发锁持有时长。包内可见供单测。 */
	Duration batchTimeBudget = Duration.ofSeconds(30);

	private final AgentRuntimeOutboxMapper outboxMapper;

	private final ApplicationEventPublisher eventPublisher;

	private final ObjectMapper objectMapper;

	/**
	 * 监听方执行线程池：常态单线程顺序执行；仅当上一条超时被 cancel 后监听方未响应中断仍在挂起时，
	 * 才临时扩容（上限 4），避免一个挂死的 Webhook 占住后续所有消息。SynchronousQueue 不排队，
	 * 4 个线程全部挂死时 submit 直接拒绝，按该条派发失败进重试。
	 */
	private final ExecutorService listenerExecutor = new ThreadPoolExecutor(1, 4, 60L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), runnable -> {
				Thread thread = new Thread(runnable, "runtime-outbox-listener");
				thread.setDaemon(true);
				return thread;
			});

	@Override
	public Long append(OutboxAppend append) {
		if (append == null || !StringUtils.hasText(append.eventType()) || !StringUtils.hasText(append.eventKey())) {
			throw CheckedException.badRequest("Outbox 消息不完整：eventType 与 eventKey 必填");
		}
		Instant now = Instant.now();
		AgentRuntimeOutbox outbox = AgentRuntimeOutbox.builder()
			// tenant_id 为冗余元数据：outbox 是本地待派发队列，派发按 id/state 认领，不做跨租户读取；
			// 审批链路侧已强制真实租户，这里的 0 只会出现在镜像等系统级链路。
			.tenantId(append.tenantId())
			.runId(append.runId())
			.eventId(append.eventId())
			.messageType(append.eventType().trim())
			.targetChannel(TARGET_CHANNEL_EVENT)
			.payload(envelopeJson(append))
			.state(RuntimeOutboxState.PENDING.getValue())
			.retryCount(0)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		outboxMapper.insert(outbox);
		return outbox.getId();
	}

	@Override
	public int dispatchPending(int limit) {
		Instant deadline = Instant.now().plus(batchTimeBudget);
		List<AgentRuntimeOutbox> batch = outboxMapper.claimPending(Instant.now(), Math.max(1, limit));
		int dispatched = 0;
		for (AgentRuntimeOutbox message : batch) {
			if (!Instant.now().isBefore(deadline)) {
				log.warn("Outbox 本轮派发超出时间预算({}s), 停止处理, 剩余消息留待下一轮. batchSize={}, dispatched={}",
						batchTimeBudget.toSeconds(), batch.size(), dispatched);
				break;
			}
			try {
				publishWithTimeout(toEvent(message));
				if (outboxMapper.markDispatched(message.getId(), Instant.now()) == 0) {
					log.warn("Outbox 消息标记已派发失败（状态已被并发迁移）. outboxId={}", message.getId());
					continue;
				}
				dispatched++;
			}
			catch (Exception ex) {
				handleDispatchFailure(message, ex);
			}
		}
		return dispatched;
	}

	/**
	 * 在受限执行器中同步等待监听方执行完成，超时即中断并抛业务异常（调用方按派发失败进重试）。
	 * 监听方内部异常原样解包上抛，保持与直接同步调用相同的失败记账语义。
	 */
	private void publishWithTimeout(RuntimeOutboxEvent event) throws Exception {
		Future<?> future = listenerExecutor.submit(() -> eventPublisher.publishEvent(event));
		try {
			future.get(listenerTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw CheckedException.fail("Outbox 监听方执行超时(" + listenerTimeout.toSeconds() + "s), 按派发失败进入重试");
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			throw ex;
		}
		catch (ExecutionException ex) {
			throw ex.getCause() instanceof Exception cause ? cause : ex;
		}
	}

	/** 应用停机时中断仍在执行的监听方线程，避免退出挂起。 */
	@PreDestroy
	public void shutdownListenerExecutor() {
		listenerExecutor.shutdownNow();
	}

	/** 单条派发失败：未达上限退避重试（FAILED），达到上限置 DEAD 并 error 日志。 */
	private void handleDispatchFailure(AgentRuntimeOutbox message, Exception ex) {
		int failedCount = (message.getRetryCount() == null ? 0 : message.getRetryCount()) + 1;
		String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
		if (failedCount >= MAX_RETRY) {
			outboxMapper.markFailed(message.getId(), RuntimeOutboxState.DEAD.getValue(), null, error);
			log.error("Outbox 消息派发重试达上限, 已置 DEAD. outboxId={}, messageType={}, retryCount={}",
					message.getId(), message.getMessageType(), failedCount, ex);
			return;
		}
		Instant nextRetryAt = Instant.now().plus(RETRY_BACKOFF_BASE.multipliedBy(failedCount));
		outboxMapper.markFailed(message.getId(), RuntimeOutboxState.FAILED.getValue(), nextRetryAt, error);
		log.warn("Outbox 消息派发失败, 将于 {} 重试. outboxId={}, messageType={}, retryCount={}, errorType={}",
				nextRetryAt, message.getId(), message.getMessageType(), failedCount, ex.getClass().getSimpleName());
	}

	/** 信封还原失败（脏数据）直接抛出，走失败重试直至 DEAD，保证坏消息可见而不是被静默丢弃。 */
	private RuntimeOutboxEvent toEvent(AgentRuntimeOutbox message) throws Exception {
		JsonNode envelope = objectMapper.readTree(
				StringUtils.hasText(message.getPayload()) ? message.getPayload() : "{}");
		String eventKey = envelope.path(ENVELOPE_EVENT_KEY).asText(null);
		if (!StringUtils.hasText(eventKey)) {
			throw CheckedException.fail("Outbox 消息缺少 eventKey, 无法派发, outboxId=" + message.getId());
		}
		Long workspaceId = envelope.hasNonNull(ENVELOPE_WORKSPACE_ID)
				? envelope.get(ENVELOPE_WORKSPACE_ID).asLong() : null;
		JsonNode data = envelope.path(ENVELOPE_DATA);
		String payloadJson = data.isMissingNode() || data.isNull() ? "{}" : objectMapper.writeValueAsString(data);
		return new RuntimeOutboxEvent(message.getTenantId(), workspaceId,
				message.getRunId() == null ? null : String.valueOf(message.getRunId()), message.getMessageType(),
				eventKey, payloadJson);
	}

	private String envelopeJson(OutboxAppend append) {
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put(ENVELOPE_EVENT_KEY, append.eventKey().trim());
		if (append.workspaceId() != null) {
			envelope.put(ENVELOPE_WORKSPACE_ID, append.workspaceId());
		}
		envelope.put(ENVELOPE_DATA, append.payload() == null ? Map.of() : append.payload());
		try {
			return objectMapper.writeValueAsString(envelope);
		}
		catch (Exception ex) {
			throw CheckedException.fail("Outbox 消息负载序列化失败：" + ex.getMessage());
		}
	}

}
