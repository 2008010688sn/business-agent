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
package com.sn68.agent.dataagent.im.service;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.event.ImConnectorChangedEvent;
import com.sn68.agent.dataagent.im.event.ImProviderConfigChangedEvent;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.repository.AgentImConnectorMapper;
import com.sn68.agent.dataagent.notification.service.NotificationJsonSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 钉钉 Stream 连接生命周期服务。
 *
 * <p>系统级组件：启动时为所有租户的 Stream 连接器建立连接。connectorCode 只在租户内唯一，
 * 因此运行时与租约一律以 connectorId（全局唯一主键）定位，connectorCode 仅用于日志。
 *
 * <p>对话入口按连接器选主：同一连接器同一时刻只有一个实例持有 WebSocket。Client ID 占用登记只阻止
 * 两个不同连接器共用一把钉钉应用，不会把多租户消息合流到同一 listener。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.ai.agent.im.dingtalk", name = "enabled", havingValue = "true",
		matchIfMissing = false)
public class DingTalkStreamLifecycleService implements DisposableBean {

	static final String LEASE_PREFIX = "dataagent:im:dingtalk:stream:";

	static final String APP_OCCUPANCY_PREFIX = "dataagent:im:dingtalk:app:";

	private static final String STREAM_STATUS_STOPPED = "STOPPED";

	private static final String STREAM_STATUS_CONNECTED = "CONNECTED";

	private static final String STREAM_STATUS_FAILED = "FAILED";

	/** 未配置 Redis 时的租约占位持有者：表示仅本地单实例运行，不参与分布式抢占。 */
	static final String LEASE_OWNER_LOCAL_ONLY = "LOCAL_ONLY";

	private final AgentImConnectorMapper connectorMapper;

	private final NotificationJsonSupport jsonSupport;

	private final ImRuntimeConfigService runtimeConfigService;

	private final DingTalkStreamMessageHandler messageHandler;

	private final ObjectProvider<StringRedisTemplate> stringRedisTemplateProvider;

	private final Map<Long, StreamRuntime> runtimes = new ConcurrentHashMap<>();

	private final String instanceId = UUID.randomUUID().toString();

	private volatile String lastStreamWorkerFingerprint;

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void startAll() {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig;
		try {
			runtimeConfig = runtimeConfigService.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		}
		catch (Exception ex) {
			log.warn("钉钉 Stream 平台配置未初始化，跳过连接启动。", ex);
			return;
		}
		lastStreamWorkerFingerprint = streamWorkerFingerprint(runtimeConfig);
		if (!runtimeConfig.streamWorkerEnabled()) {
			log.info("钉钉 Stream worker 未启用，跳过连接启动。");
			return;
		}
		connectorMapper.findAllOrderedAllTenants().stream().filter(this::shouldStart)
			.forEach(connector -> refresh(connector.getId()));
	}

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	@EventListener
	public void onConnectorChanged(ImConnectorChangedEvent event) {
		if (event != null && event.connectorId() != null) {
			refresh(event.connectorId());
		}
	}

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	@EventListener
	public void onProviderConfigChanged(ImProviderConfigChangedEvent event) {
		if (event == null || !ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(event.provider())) {
			return;
		}
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig;
		try {
			runtimeConfig = runtimeConfigService.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		}
		catch (Exception ex) {
			log.warn("钉钉 Stream 平台配置读取失败，忽略本次刷新。", ex);
			return;
		}
		String fingerprint = streamWorkerFingerprint(runtimeConfig);
		if (fingerprint.equals(lastStreamWorkerFingerprint)) {
			return;
		}
		lastStreamWorkerFingerprint = fingerprint;
		if (!runtimeConfig.streamWorkerEnabled()) {
			connectorMapper.findAllOrderedAllTenants()
				.stream()
				.filter(connector -> ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(connector.getProvider()))
				.forEach(connector -> stopQuietly(connector.getId(), "钉钉 Stream worker 未启用"));
			return;
		}
		connectorMapper.findAllOrderedAllTenants()
			.stream()
			.filter(connector -> ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(connector.getProvider()))
			.forEach(connector -> refresh(connector.getId()));
	}

	/**
	 * 连接器配置变更后重建连接。凭据或启停变化必须先停再建。
	 */
	public synchronized void refresh(Long connectorId) {
		if (connectorId == null) {
			return;
		}
		stop(connectorId);
		AgentImConnector connector = connectorMapper.selectById(connectorId);
		if (connector == null) {
			runtimes.put(connectorId, StreamRuntime.stopped(connectorId, String.valueOf(connectorId),
					"连接器不存在、未启用或非 Stream 模式"));
			return;
		}
		try {
			if (!shouldStart(connector)) {
				runtimes.put(connectorId, StreamRuntime.stopped(connectorId, connector.getConnectorCode(),
						"连接器不存在、未启用或非 Stream 模式"));
				return;
			}
		}
		catch (Exception ex) {
			log.warn("钉钉 Stream 启动条件检查失败。connectorId={}, connectorCode={}", connectorId,
					connector.getConnectorCode(), ex);
			runtimes.put(connectorId, StreamRuntime.stopped(connectorId, connector.getConnectorCode(),
					firstText(ex.getMessage(), "钉钉 Stream 平台配置未初始化")));
			return;
		}
		start(connector);
	}

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	public Map<String, Object> status(Long connectorId) {
		StreamRuntime runtime = connectorId == null ? null : runtimes.get(connectorId);
		if (runtime == null) {
			return Map.of("streamStatus", STREAM_STATUS_STOPPED);
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("streamStatus", runtime.status);
		result.put("leaseOwner", runtime.leaseOwner);
		result.put("lastError", runtime.lastError);
		return result;
	}

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	@Scheduled(fixedDelay = 5000)
	public void renewLeases() {
		Instant now = Instant.now();
		runtimes.values().forEach(runtime -> {
			try {
				if (STREAM_STATUS_CONNECTED.equals(runtime.status) && shouldRenew(runtime, now)) {
					renewLease(runtime);
				}
				if (shouldRetryStart(runtime) && shouldReconnect(runtime, now)) {
					retryStart(runtime.connectorId);
				}
			}
			catch (Exception ex) {
				runtime.status = STREAM_STATUS_FAILED;
				runtime.lastError = ex.getMessage();
				runtime.nextReconnectAt = Instant.now().plusSeconds(30);
				log.warn("钉钉 Stream 定时维护失败。connectorId={}, connectorCode={}", runtime.connectorId,
						runtime.connectorCode, ex);
			}
		});
	}

	/**
	 * 处理DingTalkStreamLifecycle。
	 */
	@Override
	public void destroy() {
		runtimes.keySet().forEach(this::stop);
	}

	private synchronized void retryStart(Long connectorId) {
		StreamRuntime existing = runtimes.get(connectorId);
		if (existing != null && STREAM_STATUS_CONNECTED.equals(existing.status)) {
			return;
		}
		AgentImConnector connector = connectorMapper.selectById(connectorId);
		if (connector == null) {
			stopQuietly(connectorId, "连接器不存在、未启用或非 Stream 模式");
			return;
		}
		try {
			if (!shouldStart(connector)) {
				stopQuietly(connectorId, "连接器不存在、未启用或非 Stream 模式");
				return;
			}
		}
		catch (Exception ex) {
			stopQuietly(connectorId, firstText(ex.getMessage(), "钉钉 Stream 平台配置未初始化"));
			return;
		}
		start(connector);
	}

	private void start(AgentImConnector connector) {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig;
		try {
			runtimeConfig = runtimeConfigService.providerRuntimeConfig(connector.getProvider());
		}
		catch (Exception ex) {
			log.warn("钉钉 Stream 平台配置未初始化，停止连接器。connectorCode={}", connector.getConnectorCode(), ex);
			runtimes.put(connector.getId(), StreamRuntime.stopped(connector.getId(), connector.getConnectorCode(),
					firstText(ex.getMessage(), "钉钉 Stream 平台配置未初始化")));
			return;
		}
		Map<String, Object> config = jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
		String clientId = stringValue(config.get("clientId"));
		String clientSecret = stringValue(config.get("clientSecret"));
		StreamRuntime runtime = StreamRuntime.connecting(connector.getId(), connector.getConnectorCode());
		runtimes.put(connector.getId(), runtime);
		try {
			if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
				throw new IllegalStateException("钉钉 Client ID 或 Client Secret 为空");
			}
			if (!acquireConnectorLease(connector.getId(), runtime, runtimeConfig)) {
				runtime.status = STREAM_STATUS_STOPPED;
				runtime.lastError = "其他实例已持有 Stream 租约";
				runtime.nextReconnectAt = Instant.now()
					.plusSeconds(Math.max(5, runtimeConfig.streamLeaseRenewSeconds()));
				return;
			}
			String occupancyError = claimAppOccupancy(connector, clientId, runtime, runtimeConfig);
			if (occupancyError != null) {
				runtime.status = STREAM_STATUS_FAILED;
				runtime.lastError = occupancyError;
				runtime.nextReconnectAt = Instant.now()
					.plusSeconds(Math.max(5, runtimeConfig.streamLeaseRenewSeconds()));
				releaseLease(runtime);
				return;
			}
			warnIfLocalOnly(runtime, connector, clientId);
			OpenDingTalkClient client = OpenDingTalkStreamClientBuilder.custom()
				.credential(new AuthClientCredential(clientId, clientSecret))
				.connectTimeout(Math.max(1000, runtimeConfig.streamConnectTimeoutMs()))
				.registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, messageHandler.listener(connector))
				.build();
			client.start();
			runtime.client = client;
			runtime.status = STREAM_STATUS_CONNECTED;
			runtime.lastError = null;
			runtime.nextRenewAt = Instant.now().plusSeconds(Math.max(5, runtimeConfig.streamLeaseRenewSeconds()));
			runtime.nextReconnectAt = null;
			log.info("钉钉 Stream 连接已启动。connectorCode={}", connector.getConnectorCode());
		}
		catch (Exception ex) {
			runtime.status = STREAM_STATUS_FAILED;
			runtime.lastError = ex.getMessage();
			runtime.nextReconnectAt = Instant.now().plusMillis(Math.max(1000, runtimeConfig.streamReconnectDelayMs()));
			releaseLease(runtime);
			log.warn("钉钉 Stream 连接启动失败。connectorCode={}", connector.getConnectorCode(), ex);
		}
	}

	private void stop(Long connectorId) {
		StreamRuntime runtime = runtimes.remove(connectorId);
		if (runtime == null) {
			return;
		}
		try {
			if (runtime.client != null) {
				runtime.client.stop();
			}
		}
		catch (Exception ex) {
			log.warn("停止钉钉 Stream 连接失败。connectorId={}, connectorCode={}", connectorId, runtime.connectorCode, ex);
		}
		finally {
			runtime.status = STREAM_STATUS_STOPPED;
			releaseLease(runtime);
		}
	}

	private void stopQuietly(Long connectorId, String reason) {
		stop(connectorId);
		AgentImConnector connector = connectorMapper.selectById(connectorId);
		String code = connector == null ? String.valueOf(connectorId) : connector.getConnectorCode();
		runtimes.put(connectorId, StreamRuntime.stopped(connectorId, code, reason));
	}

	private boolean shouldStart(AgentImConnector connector) {
		if (connector == null || !ImConstants.PROVIDER_DINGTALK.equalsIgnoreCase(connector.getProvider())
				|| !ImConstants.STATUS_ENABLED.equalsIgnoreCase(connector.getStatus())) {
			return false;
		}
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(connector.getProvider());
		if (!runtimeConfig.streamWorkerEnabled()) {
			return false;
		}
		Map<String, Object> config = jsonSupport.readEncryptedMap(connector.getEncryptedConfig());
		return ImConstants.CONNECT_MODE_STREAM.equalsIgnoreCase(stringValue(config.get("mode")))
				&& boolValue(config.get("streamAutoStart"), true);
	}

	private boolean acquireConnectorLease(Long connectorId, StreamRuntime runtime,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		StringRedisTemplate redisTemplate = stringRedisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			runtime.leaseOwner = LEASE_OWNER_LOCAL_ONLY;
			return true;
		}
		String key = LEASE_PREFIX + connectorId;
		String owner = instanceId + ":" + connectorId;
		Boolean acquired = redisTemplate.opsForValue()
			.setIfAbsent(key, owner, Duration.ofSeconds(Math.max(10, runtimeConfig.streamLeaseTtlSeconds())));
		runtime.leaseKey = key;
		if (Boolean.TRUE.equals(acquired)) {
			runtime.leaseOwner = owner;
			runtime.leaseHeld = true;
			return true;
		}
		runtime.leaseOwner = redisTemplate.opsForValue().get(key);
		runtime.leaseHeld = false;
		return false;
	}

	/**
	 * 登记 Client ID 占用。同一连接器的 HA 副本视为放行；被其他连接器占用则拒绝建连。
	 * 返回错误文案；null 表示可以继续连 Stream。
	 */
	String claimAppOccupancy(AgentImConnector connector, String clientId, StreamRuntime runtime,
			ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		StringRedisTemplate redisTemplate = stringRedisTemplateProvider.getIfAvailable();
		if (redisTemplate == null || !StringUtils.hasText(clientId)) {
			return null;
		}
		String key = APP_OCCUPANCY_PREFIX + clientId.trim();
		String value = occupancyValue(connector, instanceId);
		Duration ttl = Duration.ofSeconds(Math.max(10, runtimeConfig.streamLeaseTtlSeconds()));
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, value, ttl);
		runtime.appOccupancyKey = key;
		runtime.appOccupancyOwner = value;
		if (Boolean.TRUE.equals(acquired)) {
			runtime.appOccupancyHeld = true;
			return null;
		}
		String existing = redisTemplate.opsForValue().get(key);
		Long occupiedBy = parseOccupancyConnectorId(existing);
		if (occupiedBy != null && occupiedBy.equals(connector.getId())) {
			redisTemplate.opsForValue().set(key, value, ttl);
			runtime.appOccupancyHeld = true;
			return null;
		}
		runtime.appOccupancyHeld = false;
		return occupancyConflictMessage(existing);
	}

	static String occupancyValue(AgentImConnector connector, String instanceId) {
		return firstNonBlank(connector.getTenantId(), "") + "|" + connector.getId() + "|"
				+ firstNonBlank(connector.getConnectorCode(), "") + "|" + firstNonBlank(instanceId, "");
	}

	static Long parseOccupancyConnectorId(String occupancy) {
		if (!StringUtils.hasText(occupancy)) {
			return null;
		}
		String[] parts = occupancy.split("\\|", -1);
		if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
			return null;
		}
		try {
			return Long.valueOf(parts[1].trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	static String occupancyConflictMessage(String occupancy) {
		if (!StringUtils.hasText(occupancy)) {
			return "该钉钉应用已被其他连接器占用";
		}
		String[] parts = occupancy.split("\\|", -1);
		String tenantId = parts.length > 0 ? parts[0] : "";
		String connectorCode = parts.length > 2 ? parts[2] : "";
		return "该钉钉应用已被连接器 " + firstNonBlank(connectorCode, "未知") + "（租户 "
				+ firstNonBlank(tenantId, "未知") + "）占用";
	}

	private void warnIfLocalOnly(StreamRuntime runtime, AgentImConnector connector, String clientId) {
		if (!LEASE_OWNER_LOCAL_ONLY.equals(runtime.leaseOwner)) {
			return;
		}
		log.warn("钉钉 Stream 未配置 Redis，本实例以单机模式连接。多实例会抢同一 Client ID 导致连接漂移。"
				+ "connectorCode={}, clientId={}", connector.getConnectorCode(), maskClientId(clientId));
	}

	static String maskClientId(String clientId) {
		if (!StringUtils.hasText(clientId)) {
			return clientId;
		}
		String text = clientId.trim();
		if (text.length() <= 6) {
			return "****";
		}
		return text.substring(0, 3) + "****" + text.substring(text.length() - 2);
	}

	static String streamWorkerFingerprint(ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig) {
		if (runtimeConfig == null) {
			return "";
		}
		return runtimeConfig.streamWorkerEnabled() + "|" + runtimeConfig.streamConnectTimeoutMs() + "|"
				+ runtimeConfig.streamReconnectDelayMs() + "|" + runtimeConfig.streamLeaseTtlSeconds() + "|"
				+ runtimeConfig.streamLeaseRenewSeconds();
	}

	private void renewLease(StreamRuntime runtime) {
		ImRuntimeConfigService.ProviderRuntimeConfig runtimeConfig = runtimeConfigService
			.providerRuntimeConfig(ImConstants.PROVIDER_DINGTALK);
		if (!StringUtils.hasText(runtime.leaseKey) || LEASE_OWNER_LOCAL_ONLY.equals(runtime.leaseOwner)) {
			runtime.nextRenewAt = Instant.now().plusSeconds(Math.max(5, runtimeConfig.streamLeaseRenewSeconds()));
			return;
		}
		StringRedisTemplate redisTemplate = stringRedisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			return;
		}
		String owner = redisTemplate.opsForValue().get(runtime.leaseKey);
		if (!runtime.leaseOwner.equals(owner)) {
			log.warn("钉钉 Stream 租约已被其他实例接管，停止本地连接。connectorId={}, connectorCode={}",
					runtime.connectorId, runtime.connectorCode);
			stop(runtime.connectorId);
			return;
		}
		Duration ttl = Duration.ofSeconds(Math.max(10, runtimeConfig.streamLeaseTtlSeconds()));
		redisTemplate.expire(runtime.leaseKey, ttl);
		if (runtime.appOccupancyHeld && StringUtils.hasText(runtime.appOccupancyKey)) {
			redisTemplate.expire(runtime.appOccupancyKey, ttl);
		}
		runtime.nextRenewAt = Instant.now().plusSeconds(Math.max(5, runtimeConfig.streamLeaseRenewSeconds()));
	}

	private boolean shouldRenew(StreamRuntime runtime, Instant now) {
		return runtime.nextRenewAt == null || !runtime.nextRenewAt.isAfter(now);
	}

	private boolean shouldReconnect(StreamRuntime runtime, Instant now) {
		return runtime.nextReconnectAt == null || !runtime.nextReconnectAt.isAfter(now);
	}

	/**
	 * 连接失败要重试；抢租约失败也要重试，否则持有者下线后没有副本会补位。
	 * 因连接器停用/非 Stream 模式而停止的运行时不排期，{@code nextReconnectAt} 为空即代表不重试。
	 */
	private boolean shouldRetryStart(StreamRuntime runtime) {
		return STREAM_STATUS_FAILED.equals(runtime.status)
				|| (STREAM_STATUS_STOPPED.equals(runtime.status) && runtime.nextReconnectAt != null);
	}

	private void releaseLease(StreamRuntime runtime) {
		// 抢租约失败时 leaseOwner 记的是对端实例，只按 owner 比对会把在线副本的租约删掉，
		// 让它在下一次续约时误判「租约被接管」并主动断开。只释放本实例真正抢到的租约。
		if (!runtime.leaseHeld || !StringUtils.hasText(runtime.leaseKey) || LEASE_OWNER_LOCAL_ONLY.equals(runtime.leaseOwner)) {
			releaseAppOccupancy(runtime);
			return;
		}
		StringRedisTemplate redisTemplate = stringRedisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			runtime.leaseHeld = false;
			return;
		}
		String owner = redisTemplate.opsForValue().get(runtime.leaseKey);
		if (runtime.leaseOwner.equals(owner)) {
			redisTemplate.delete(runtime.leaseKey);
		}
		runtime.leaseHeld = false;
		releaseAppOccupancy(runtime);
	}

	private void releaseAppOccupancy(StreamRuntime runtime) {
		if (!runtime.appOccupancyHeld || !StringUtils.hasText(runtime.appOccupancyKey)) {
			return;
		}
		StringRedisTemplate redisTemplate = stringRedisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			runtime.appOccupancyHeld = false;
			return;
		}
		String owner = redisTemplate.opsForValue().get(runtime.appOccupancyKey);
		if (runtime.appOccupancyOwner != null && runtime.appOccupancyOwner.equals(owner)) {
			redisTemplate.delete(runtime.appOccupancyKey);
		}
		runtime.appOccupancyHeld = false;
	}

	private boolean boolValue(Object value, boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		if (value instanceof Boolean bool) {
			return bool;
		}
		return Boolean.parseBoolean(String.valueOf(value));
	}

	private String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
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

	private static String firstNonBlank(String value, String fallback) {
		return StringUtils.hasText(value) ? value.trim() : fallback;
	}

	static class StreamRuntime {

		private final Long connectorId;

		private final String connectorCode;

		private OpenDingTalkClient client;

		private String status;

		private String leaseKey;

		private String leaseOwner;

		private boolean leaseHeld;

		private String appOccupancyKey;

		private String appOccupancyOwner;

		private boolean appOccupancyHeld;

		private String lastError;

		private Instant nextRenewAt;

		private Instant nextReconnectAt;

		private StreamRuntime(Long connectorId, String connectorCode, String status) {
			this.connectorId = connectorId;
			this.connectorCode = connectorCode;
			this.status = status;
		}

		static StreamRuntime connecting(Long connectorId, String connectorCode) {
			return new StreamRuntime(connectorId, connectorCode, "CONNECTING");
		}

		static StreamRuntime stopped(Long connectorId, String connectorCode, String reason) {
			StreamRuntime runtime = new StreamRuntime(connectorId, connectorCode, STREAM_STATUS_STOPPED);
			runtime.lastError = reason;
			return runtime;
		}

	}

}
