/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.outbox;

import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 持久运行时 Outbox 定时派发器（参照 McpToolSyncScheduler 的定时 + 分布式锁模式）。
 *
 * <p>多副本下同一时刻只让一个副本派发：认领 SQL 的 FOR UPDATE SKIP LOCKED 只覆盖单条语句，
 * 逐条「发布 → 标记」跨语句执行，需要 Redis 锁做副本级互斥；抢不到锁直接跳过本轮，
 * 下一个周期还会再来。锁失效导致的重复派发由 at-least-once 语义兜底（消费方按 eventKey 幂等，
 * mark* 状态谓词保证重复标记无副作用）。</p>
 */
@Slf4j
//@Component
@RequiredArgsConstructor
public class RuntimeOutboxDispatcher {

	private static final String DISPATCH_LOCK = "dataagent:runtime:outbox-dispatch";

	/** 单轮认领批量：审批/运行事件为低频消息，小批量即可跟上产生速度。 */
	private static final int DISPATCH_BATCH = 50;

	private final RuntimeOutboxService runtimeOutboxService;

	private final RedisLockHelper redisLockHelper;

	@Scheduled(fixedDelayString = "${spring.ai.agent.runtime.outbox-dispatch.fixed-delay:5000}")
	public void dispatch() {
		try {
			redisLockHelper.execute(DISPATCH_LOCK, 0L, TimeUnit.SECONDS, () -> {
				int dispatched = runtimeOutboxService.dispatchPending(DISPATCH_BATCH);
				if (dispatched > 0) {
					log.info("Outbox 定时派发完成. dispatched={}", dispatched);
				}
				return dispatched;
			});
		}
		catch (RedisLockException ex) {
			log.debug("Outbox 定时派发跳过, 其他副本持有派发锁");
		}
		catch (Exception ex) {
			// 单轮失败不吞：记录后等待下一个周期重试，未派发消息仍在 PENDING/FAILED 状态可追溯。
			log.warn("Outbox 定时派发异常, 等待下一轮重试", ex);
		}
	}

}
