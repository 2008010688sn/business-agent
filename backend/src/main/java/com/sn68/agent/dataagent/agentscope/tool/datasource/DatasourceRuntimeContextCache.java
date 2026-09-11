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
package com.sn68.agent.dataagent.agentscope.tool.datasource;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数据源运行时上下文进程内缓存：按租户/数据源缓存探索上下文与权限规则，带 TTL 且配置变更即整体失效，
 * 用于减少一次会话内的重复元数据加载。
 */
@Slf4j
@Service
public class DatasourceRuntimeContextCache {

	private static final String CONFIG_PREFIX = Constant.PROJECT_PROPERTIES_PREFIX + ".datasource-runtime-cache";

	private static final String ENABLED_KEY = CONFIG_PREFIX + ".enabled";

	private static final String TTL_KEY = CONFIG_PREFIX + ".ttl";

	private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

	private final ConcurrentHashMap<ExplorerContextKey, CacheEntry<DatasourceExplorerService.ExplorerContext>> explorerContexts = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<PermissionRulesKey, CacheEntry<List<DatasourcePermissionRule>>> permissionRules = new ConcurrentHashMap<>();

	private final Set<MetadataReadKey> routedMetadataReads = ConcurrentHashMap.newKeySet();

	private final Set<SearchSucceededKey> successfulSearches = ConcurrentHashMap.newKeySet();

	private final Environment environment;

	private final Clock clock;

	@Autowired
	public DatasourceRuntimeContextCache(Environment environment) {
		this(environment, Clock.systemUTC());
	}

	DatasourceRuntimeContextCache(Environment environment, Clock clock) {
		this.environment = environment;
		this.clock = clock;
	}

	public DatasourceExplorerService.ExplorerContext getOrLoadExplorerContext(String skillId,
			@Nullable AgentRequest request, ThrowingSupplier<DatasourceExplorerService.ExplorerContext> loader)
			throws Exception {
		if (!cacheEnabled()) {
			return loader.get();
		}
		ExplorerContextKey key = explorerContextKey(skillId, request);
		if (key == null) {
			return loader.get();
		}
		return getOrLoad(explorerContexts, key, loader);
	}

	public List<DatasourcePermissionRule> getOrLoadPermissionRules(Long datasourceId, @Nullable AgentRequest request,
			Supplier<List<DatasourcePermissionRule>> loader) {
		if (!cacheEnabled()) {
			return loader.get();
		}
		PermissionRulesKey key = permissionRulesKey(datasourceId, request);
		if (key == null) {
			return loader.get();
		}
		try {
			return getOrLoad(permissionRules, key, loader::get);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("加载数据源权限规则缓存失败", ex);
		}
	}

	public void clear(String threadId, String runtimeRequestId) {
		if (!StringUtils.hasText(threadId) || !StringUtils.hasText(runtimeRequestId)) {
			return;
		}
		explorerContexts.keySet()
			.removeIf(key -> Objects.equals(threadId, key.threadId())
					&& Objects.equals(runtimeRequestId, key.runtimeRequestId()));
		permissionRules.keySet()
			.removeIf(key -> Objects.equals(threadId, key.threadId())
					&& Objects.equals(runtimeRequestId, key.runtimeRequestId()));
		routedMetadataReads.removeIf(key -> Objects.equals(threadId, key.threadId())
				&& Objects.equals(runtimeRequestId, key.runtimeRequestId()));
		successfulSearches.removeIf(key -> Objects.equals(threadId, key.threadId())
				&& Objects.equals(runtimeRequestId, key.runtimeRequestId()));
	}

	public void clearAll() {
		explorerContexts.clear();
		permissionRules.clear();
		routedMetadataReads.clear();
		successfulSearches.clear();
	}

	/**
	 * GET_TABLE_SCHEMA 探查表数的兜底上限：仅当 properties 缺省或取值非法时生效，
	 * 与 {@link DataAgentProperties} 中 {@code maxSchemaTablesPerRequest} 的默认值保持一致。
	 */
	public static final int DEFAULT_MAX_SCHEMA_TABLES_PER_REQUEST = 7;

	public boolean tryAcquireMetadataRead(@Nullable AgentRequest request, String operationKey) {
		return tryAcquireMetadataRead(request, operationKey, DEFAULT_MAX_SCHEMA_TABLES_PER_REQUEST);
	}

	public boolean tryAcquireMetadataRead(@Nullable AgentRequest request, String operationKey, int maxDistinctTables) {
		MetadataReadKey key = metadataReadKey(request, operationKey);
		if (key == null) {
			return true;
		}
		if (operationKey != null && operationKey.startsWith("GET_TABLE_SCHEMA:")) {
			if (routedMetadataReads.contains(key)) {
				return true;
			}
			int limit = maxDistinctTables < 1 ? DEFAULT_MAX_SCHEMA_TABLES_PER_REQUEST : maxDistinctTables;
			long schemaReads = routedMetadataReads.stream()
				.filter(existing -> Objects.equals(existing.threadId(), key.threadId())
						&& Objects.equals(existing.runtimeRequestId(), key.runtimeRequestId())
						&& existing.operationKey() != null && existing.operationKey().startsWith("GET_TABLE_SCHEMA:"))
				.count();
			if (schemaReads >= limit) {
				return false;
			}
		}
		return routedMetadataReads.add(key);
	}

	public void releaseMetadataRead(@Nullable AgentRequest request, String operationKey) {
		MetadataReadKey key = metadataReadKey(request, operationKey);
		if (key != null) {
			routedMetadataReads.remove(key);
		}
	}

	public void markSearchSucceeded(@Nullable AgentRequest request) {
		SearchSucceededKey key = searchSucceededKey(request);
		if (key != null) {
			successfulSearches.add(key);
		}
	}

	public boolean hasSuccessfulSearch(@Nullable AgentRequest request) {
		SearchSucceededKey key = searchSucceededKey(request);
		return key != null && successfulSearches.contains(key);
	}

	@EventListener
	public void onEnvironmentChange(EnvironmentChangeEvent event) {
		if (event == null || event.getKeys() == null) {
			return;
		}
		boolean changed = event.getKeys()
			.stream()
			.anyMatch(key -> ENABLED_KEY.equals(key) || TTL_KEY.equals(key));
		if (changed) {
			clearAll();
			log.info("Datasource runtime context cache cleared because configuration changed.");
		}
	}

	private <K, V> V getOrLoad(ConcurrentHashMap<K, CacheEntry<V>> cache, K key, ThrowingSupplier<V> loader)
			throws Exception {
		CacheEntry<V> existing = cache.get(key);
		if (existing != null) {
			if (!existing.expired(clock.instant())) {
				return existing.value();
			}
			cache.remove(key, existing);
		}
		V value = loader.get();
		cache.put(key, new CacheEntry<>(value, clock.instant().plus(cacheTtl())));
		return value;
	}

	private boolean cacheEnabled() {
		return currentProperties().isEnabled();
	}

	private Duration cacheTtl() {
		Duration ttl = currentProperties().getTtl();
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			return DEFAULT_TTL;
		}
		return ttl;
	}

	private DataAgentProperties.DatasourceRuntimeCache currentProperties() {
		DataAgentProperties.DatasourceRuntimeCache defaults = new DataAgentProperties.DatasourceRuntimeCache();
		DataAgentProperties.DatasourceRuntimeCache bound = Binder.get(environment)
			.bind(CONFIG_PREFIX, Bindable.of(DataAgentProperties.DatasourceRuntimeCache.class))
			.orElse(defaults);
		if (bound.getTtl() == null || bound.getTtl().isZero() || bound.getTtl().isNegative()) {
			bound.setTtl(DEFAULT_TTL);
		}
		return bound;
	}

	private ExplorerContextKey explorerContextKey(String skillId, @Nullable AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId()) || !StringUtils.hasText(skillId)) {
			return null;
		}
		return new ExplorerContextKey(request.getThreadId(), request.getRuntimeRequestId(), skillId);
	}

	private PermissionRulesKey permissionRulesKey(Long datasourceId, @Nullable AgentRequest request) {
		if (datasourceId == null || request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		return new PermissionRulesKey(request.getThreadId(), request.getRuntimeRequestId(), datasourceId);
	}

	private MetadataReadKey metadataReadKey(@Nullable AgentRequest request, String operationKey) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId()) || !StringUtils.hasText(operationKey)) {
			return null;
		}
		return new MetadataReadKey(request.getThreadId(), request.getRuntimeRequestId(),
				request.getRoutedSkillVersionId(), operationKey);
	}

	@FunctionalInterface
	public interface ThrowingSupplier<T> {

		T get() throws Exception;

	}

	private record ExplorerContextKey(String threadId, String runtimeRequestId, String skillId) {
	}

	private record PermissionRulesKey(String threadId, String runtimeRequestId, Long datasourceId) {
	}

	private record MetadataReadKey(String threadId, String runtimeRequestId, Long skillVersionId, String operationKey) {
	}

	private record SearchSucceededKey(String threadId, String runtimeRequestId) {
	}

	private SearchSucceededKey searchSucceededKey(@Nullable AgentRequest request) {
		if (request == null || !StringUtils.hasText(request.getThreadId())
				|| !StringUtils.hasText(request.getRuntimeRequestId())) {
			return null;
		}
		return new SearchSucceededKey(request.getThreadId(), request.getRuntimeRequestId());
	}

	private record CacheEntry<T>(T value, Instant expireAt) {

		private boolean expired(Instant now) {
			return !expireAt.isAfter(now);
		}

	}

}
