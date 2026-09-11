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
package com.sn68.agent.dataagent.mcp.exposure.service;

import com.alibaba.fastjson2.JSON;
import com.sn68.agent.dataagent.mcp.exposure.dto.McpExposureRuntimeSyncResult;
import com.sn68.agent.dataagent.mcp.exposure.service.McpExposureRuntimeSyncEvent.Reason;
import com.sn68.agent.framework.redis.plus.listener.AbstractMessageEventListener;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.Topic;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 协调 MCP 暴露配置提交后的本地校准和多实例通知。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpExposureRuntimeSyncCoordinator
		implements AbstractMessageEventListener<McpExposureRuntimeSyncEvent> {

	static final String SYNC_CHANNEL = "dataagent:mcp-exposure:runtime-sync";

	private final McpExposureRuntimeRegistry runtimeRegistry;

	private final StringRedisTemplate redisTemplate;

	private final String sourceInstanceId = UUID.randomUUID().toString();

	/**
	 * 在配置事务提交后同步；没有活动事务时立即同步。
	 */
	public void requestAfterCommit(Reason reason) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			synchronizeAutomatically(reason);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				synchronizeAutomatically(reason);
			}
		});
	}

	/**
	 * 严格执行手动同步，任一步失败都向调用方暴露。
	 */
	public McpExposureRuntimeSyncResult synchronizeManually() {
		McpExposureRuntimeSyncEvent event = newEvent(Reason.MANUAL);
		runtimeRegistry.reconcile();
		long subscriberCount = publish(event);
		long peerCount = Math.max(0L, subscriberCount - 1L);
		Instant syncedAt = Instant.now();
		log.info("MCP 暴露运行时手动同步完成，eventId={}, sourceInstanceId={}, notifiedPeerCount={}",
				event.eventId(), sourceInstanceId, peerCount);
		return new McpExposureRuntimeSyncResult(true, peerCount, event.eventId(), syncedAt);
	}

	@Override
	public void handleMessage(McpExposureRuntimeSyncEvent event) {
		if (event == null) {
			log.warn("忽略空的 MCP 暴露运行时同步事件");
			return;
		}
		if (Objects.equals(sourceInstanceId, event.sourceInstanceId())) {
			log.debug("忽略本实例 MCP 暴露运行时同步回环事件，eventId={}", event.eventId());
			return;
		}
		try {
			runtimeRegistry.reconcile();
			log.info("MCP 暴露运行时集群同步完成，eventId={}, sourceInstanceId={}, reason={}", event.eventId(),
					event.sourceInstanceId(), event.reason());
		}
		catch (Exception ex) {
			log.error("MCP 暴露运行时集群同步失败，eventId={}, sourceInstanceId={}, reason={}", event.eventId(),
					event.sourceInstanceId(), event.reason(), ex);
		}
	}

	@Override
	public Topic topic() {
		return new ChannelTopic(SYNC_CHANNEL);
	}

	@Override
	public Type type() {
		return McpExposureRuntimeSyncEvent.class;
	}

	private void synchronizeAutomatically(Reason reason) {
		McpExposureRuntimeSyncEvent event = newEvent(reason);
		try {
			runtimeRegistry.reconcile();
			log.info("MCP 暴露运行时本地同步完成，eventId={}, sourceInstanceId={}, reason={}", event.eventId(),
					sourceInstanceId, reason);
		}
		catch (Exception ex) {
			log.error("MCP 暴露运行时本地同步失败，eventId={}, sourceInstanceId={}, reason={}", event.eventId(),
					sourceInstanceId, reason, ex);
		}
		try {
			publish(event);
		}
		catch (Exception ex) {
			log.error("MCP 暴露运行时集群通知失败，eventId={}, sourceInstanceId={}, reason={}", event.eventId(),
					sourceInstanceId, reason, ex);
		}
	}

	private long publish(McpExposureRuntimeSyncEvent event) {
		Long subscriberCount = redisTemplate.convertAndSend(SYNC_CHANNEL, JSON.toJSONString(event));
		long count = subscriberCount == null ? 0L : subscriberCount;
		log.info("MCP 暴露运行时同步事件已发布，eventId={}, sourceInstanceId={}, reason={}, subscriberCount={}",
				event.eventId(), sourceInstanceId, event.reason(), count);
		return count;
	}

	private McpExposureRuntimeSyncEvent newEvent(Reason reason) {
		return new McpExposureRuntimeSyncEvent(UUID.randomUUID().toString(), sourceInstanceId, reason, Instant.now());
	}

}
