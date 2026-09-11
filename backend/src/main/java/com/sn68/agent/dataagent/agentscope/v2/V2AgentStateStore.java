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
package com.sn68.agent.dataagent.agentscope.v2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeMysqlSession;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;

/**
 * v2 会话状态：Redis 优先，PG {@link AgentScopeMysqlSession} 作耐久备份。
 *
 * <p>键含 tenant + user + session，前缀 {@code as2:}，不读 1.0 无前缀行。必须 bind 带 tenant/user 的快照，禁止 {@code _}。
 * Redis 缺省则仅 PG（只打一次日志）。双写任一侧失败都抛 {@link CheckedException}（fail-closed）。
 * 删除先写 Redis 墓碑（payload {@code D} + deleted SET，TTL ≥ 状态 TTL）再把 PG 覆盖为 {@link DeletedState}，
 * 不物理删除行，避免墓碑 TTL 后 Redis miss 把残留数据复活。PG 失败抛错。
 * {@code get} 在墓碑命中、GET 失败、或 live SET 存在但无该 id / deleted SET 含该 id 时跳过 PG。
 * {@link #listSessionIds(String)} 只读 Redis 会话索引，不扫 PG。
 */
@Slf4j
public class V2AgentStateStore implements AgentStateStore {

	static final String ANON = "_";

	static final String INDEX_MODULE = "__as2_index";

	private static final char KIND_SINGLE = 'S';

	private static final char KIND_LIST = 'L';

	private static final char KIND_DELETED = 'D';

	private final AgentScopeV2Properties properties;

	private final ObjectProvider<StringRedisTemplate> redisProvider;

	private final AgentScopeMysqlSession pgSession;

	private final JsonCodec jsonCodec;

	private final AtomicBoolean redisAbsentLogged;

	private final V2RuntimeSnapshot scope;

	public V2AgentStateStore(AgentScopeV2Properties properties, ObjectProvider<StringRedisTemplate> redisProvider,
			AgentScopeMysqlSession pgSession) {
		this(properties, redisProvider, pgSession, JsonUtils.getJsonCodec(), new AtomicBoolean(), null);
	}

	private V2AgentStateStore(AgentScopeV2Properties properties, ObjectProvider<StringRedisTemplate> redisProvider,
			AgentScopeMysqlSession pgSession, JsonCodec jsonCodec, AtomicBoolean redisAbsentLogged,
			V2RuntimeSnapshot scope) {
		this.properties = Objects.requireNonNull(properties, "properties");
		this.redisProvider = Objects.requireNonNull(redisProvider, "redisProvider");
		this.pgSession = Objects.requireNonNull(pgSession, "pgSession");
		this.jsonCodec = jsonCodec;
		this.redisAbsentLogged = redisAbsentLogged;
		this.scope = scope;
	}

	public V2AgentStateStore bind(V2RuntimeSnapshot snapshot) {
		return new V2AgentStateStore(properties, redisProvider, pgSession, jsonCodec, redisAbsentLogged, snapshot);
	}

	@Override
	public void save(String userId, String sessionId, String key, State value) {
		Slot slot = slot(userId, sessionId, key);
		String payload = KIND_SINGLE + jsonCodec.toJson(value);
		write(slot, payload, () -> pgSession.save(slot.userId(), slot.sessionId(), slot.pgModule(), value));
	}

	@Override
	public void save(String userId, String sessionId, String key, List<? extends State> values) {
		Slot slot = slot(userId, sessionId, key);
		List<? extends State> safe = values == null ? List.of() : values;
		String payload = KIND_LIST + jsonCodec.toJson(safe);
		write(slot, payload, () -> pgSession.save(slot.userId(), slot.sessionId(), slot.pgModule(), safe));
	}

	@Override
	public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
		Slot slot = slot(userId, sessionId, key);
		RedisRead read = readRedis(slot);
		if (read.kind() == RedisRead.Kind.TOMBSTONE) {
			refreshTombstone(slot);
			return Optional.empty();
		}
		if (read.kind() == RedisRead.Kind.ERROR) {
			return Optional.empty();
		}
		if (read.kind() == RedisRead.Kind.HIT) {
			refreshTtl(slot);
			return decodeSingle(read.payload(), type);
		}
		return pgLoad(slot, type, true);
	}

	@Override
	public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> itemType) {
		Slot slot = slot(userId, sessionId, key);
		RedisRead read = readRedis(slot);
		if (read.kind() == RedisRead.Kind.TOMBSTONE) {
			refreshTombstone(slot);
			return List.of();
		}
		if (read.kind() == RedisRead.Kind.ERROR) {
			return List.of();
		}
		if (read.kind() == RedisRead.Kind.HIT) {
			refreshTtl(slot);
			return decodeList(read.payload(), itemType);
		}
		return pgGetList(slot, itemType);
	}

	@Override
	public boolean exists(String userId, String sessionId) {
		Slot slot = slot(userId, sessionId, INDEX_MODULE);
		StringRedisTemplate redis = redis();
		if (redis != null) {
			try {
				Set<String> members = redis.opsForSet().members(slot.redisKeySet());
				return members != null && !members.isEmpty();
			}
			catch (RuntimeException ex) {
				log.error("v2 AgentStateStore Redis exists 失败, 不回落 PG. tenant={} user={} session={}", slot.tenantId(),
						slot.userId(), slot.sessionId(), ex);
				return false;
			}
		}
		return pgLoad(slot, KeyIndex.class, false).filter(index -> !index.keys().isEmpty()).isPresent();
	}

	@Override
	public void delete(String userId, String sessionId) {
		requireSessionId(sessionId);
		Set<String> keys = new LinkedHashSet<>(listedKeys(userId, sessionId));
		keys.add(INDEX_MODULE);
		for (String key : keys) {
			delete(userId, sessionId, key);
		}
		StringRedisTemplate redis = redis();
		if (redis != null) {
			Slot slot = slot(userId, sessionId, INDEX_MODULE);
			redisRemove(redis, slot.redisKeySet());
			try {
				redis.opsForSet().remove(slot.redisSessionIndex(), slot.sessionId());
			}
			catch (RuntimeException ex) {
				throw redisWriteFailure(ex);
			}
		}
	}

	@Override
	public void delete(String userId, String sessionId, String key) {
		Slot slot = slot(userId, sessionId, key);
		writeTombstone(slot);
		pgDeleteModule(slot);
		if (!INDEX_MODULE.equals(slot.key())) {
			removePgIndex(slot);
		}
	}

	@Override
	public Set<String> listSessionIds(String userId) {
		String user = resolveUser(userId);
		String tenant = requireTenant();
		StringRedisTemplate redis = redis();
		if (redis == null) {
			throw new UnsupportedOperationException(
					"listSessionIds is Redis-index only; PG scan of all tenants is not supported");
		}
		try {
			Set<String> members = redis.opsForSet().members(sessionIndexKey(tenant, user));
			return members == null ? Set.of() : Set.copyOf(members);
		}
		catch (RuntimeException ex) {
			throw redisWriteFailure(ex);
		}
	}

	private void write(Slot slot, String payload, Runnable pgWrite) {
		requireSessionId(slot.sessionId());
		RuntimeException redisError = redisWrite(slot, payload);
		RuntimeException pgError = pgBackup(slot, pgWrite);
		if (pgError == null && !INDEX_MODULE.equals(slot.key())) {
			pgError = pgAddIndex(slot);
		}
		if (redisError != null) {
			throw redisWriteFailure(redisError);
		}
		if (pgError != null) {
			throw pgFailure("写入", pgError);
		}
	}

	private RuntimeException redisWrite(Slot slot, String payload) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return null;
		}
		try {
			Duration ttl = ttl();
			redis.opsForValue().set(slot.redisDataKey(), payload, ttl);
			redis.opsForSet().add(slot.redisKeySet(), slot.key());
			redis.expire(slot.redisKeySet(), ttl);
			redis.opsForSet().remove(slot.redisDeletedSet(), slot.key());
			redis.opsForSet().add(slot.redisSessionIndex(), slot.sessionId());
			redis.expire(slot.redisSessionIndex(), ttl);
			return null;
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore Redis 写入失败, 将尝试 PG 备份. tenant={} user={} session={} key={}",
					slot.tenantId(), slot.userId(), slot.sessionId(), slot.key(), ex);
			return ex;
		}
	}

	private RuntimeException pgBackup(Slot slot, Runnable pgWrite) {
		if (!numericSession(slot.sessionId())) {
			if (redis() == null) {
				return new IllegalArgumentException("v2 PG backup requires numeric sessionId: " + slot.sessionId());
			}
			log.debug("v2 PG backup skipped, sessionId not numeric. sessionId={}", slot.sessionId());
			return null;
		}
		try {
			pgWrite.run();
			return null;
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 备份失败. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			return ex;
		}
	}

	private RedisRead readRedis(Slot slot) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return RedisRead.disabled();
		}
		try {
			String payload = redis.opsForValue().get(slot.redisDataKey());
			if (isTombstone(payload)) {
				return RedisRead.tombstone();
			}
			if (payload != null) {
				return RedisRead.hit(payload);
			}
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore Redis 读取失败, 不回落 PG. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			return RedisRead.error();
		}
		if (isDeletedMarker(slot, redis)) {
			return RedisRead.tombstone();
		}
		return RedisRead.miss();
	}

	private boolean isDeletedMarker(Slot slot, StringRedisTemplate redis) {
		try {
			if (Boolean.TRUE.equals(redis.opsForSet().isMember(slot.redisDeletedSet(), slot.key()))) {
				return true;
			}
			if (!Boolean.TRUE.equals(redis.hasKey(slot.redisKeySet()))) {
				return false;
			}
			return !Boolean.TRUE.equals(redis.opsForSet().isMember(slot.redisKeySet(), slot.key()));
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore Redis 墓碑检查失败, 不回落 PG. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			return true;
		}
	}

	private <T extends State> Optional<T> pgLoad(Slot slot, Class<T> type, boolean backfill) {
		if (!numericSession(slot.sessionId())) {
			return Optional.empty();
		}
		try {
			Optional<T> loaded = pgSession.get(slot.userId(), slot.sessionId(), slot.pgModule(), type);
			if (loaded.isPresent() && loaded.get() instanceof DeletedState) {
				return Optional.empty();
			}
			if (backfill) {
				loaded.ifPresent(value -> backfillRedis(slot, KIND_SINGLE + jsonCodec.toJson(value)));
			}
			return loaded;
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 读取失败. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			return Optional.empty();
		}
	}

	private <T extends State> List<T> pgGetList(Slot slot, Class<T> itemType) {
		if (!numericSession(slot.sessionId())) {
			return List.of();
		}
		try {
			List<T> loaded = pgSession.getList(slot.userId(), slot.sessionId(), slot.pgModule(), itemType);
			if (loaded != null && loaded.size() == 1 && loaded.get(0) instanceof DeletedState) {
				return List.of();
			}
			if (loaded != null && !loaded.isEmpty()) {
				backfillRedis(slot, KIND_LIST + jsonCodec.toJson(loaded));
			}
			return loaded == null ? List.of() : loaded;
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 列表读取失败. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			return List.of();
		}
	}

	private void backfillRedis(Slot slot, String payload) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return;
		}
		try {
			String existing = redis.opsForValue().get(slot.redisDataKey());
			if (isTombstone(existing) || isDeletedMarker(slot, redis)) {
				return;
			}
			Duration ttl = ttl();
			redis.opsForValue().set(slot.redisDataKey(), payload, ttl);
			redis.opsForSet().add(slot.redisKeySet(), slot.key());
			redis.expire(slot.redisKeySet(), ttl);
			redis.opsForSet().add(slot.redisSessionIndex(), slot.sessionId());
			redis.expire(slot.redisSessionIndex(), ttl);
		}
		catch (RuntimeException ex) {
			log.warn("v2 AgentStateStore Redis 回填失败. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
		}
	}

	private void writeTombstone(Slot slot) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return;
		}
		try {
			Duration tombstoneTtl = tombstoneTtl();
			redis.opsForValue().set(slot.redisDataKey(), String.valueOf(KIND_DELETED), tombstoneTtl);
			redis.opsForSet().add(slot.redisDeletedSet(), slot.key());
			redis.expire(slot.redisDeletedSet(), tombstoneTtl);
			redis.opsForSet().remove(slot.redisKeySet(), slot.key());
		}
		catch (RuntimeException ex) {
			throw redisWriteFailure(ex);
		}
	}

	private void pgDeleteModule(Slot slot) {
		if (!numericSession(slot.sessionId())) {
			if (redis() == null) {
				throw CheckedException.fail("v2 AgentStateStore PG 删除失败: sessionId 必须为数字");
			}
			return;
		}
		try {
			pgSession.saveTombstone(slot.userId(), slot.sessionId(), slot.pgModule(), new DeletedState());
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 墓碑写入失败. tenant={} user={} session={} key={}", slot.tenantId(),
					slot.userId(), slot.sessionId(), slot.key(), ex);
			throw pgFailure("删除", ex);
		}
	}

	private RuntimeException pgAddIndex(Slot slot) {
		if (!numericSession(slot.sessionId())) {
			return redis() == null
					? new IllegalArgumentException("v2 PG index requires numeric sessionId: " + slot.sessionId())
					: null;
		}
		LinkedHashSet<String> keys = new LinkedHashSet<>(pgIndexKeys(slot));
		if (!keys.add(slot.key())) {
			return null;
		}
		try {
			pgSession.save(slot.userId(), slot.sessionId(), pgModule(slot.tenantId(), slot.userId(), INDEX_MODULE),
					new KeyIndex(List.copyOf(keys)));
			return null;
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 索引写入失败. tenant={} user={} session={}", slot.tenantId(), slot.userId(),
					slot.sessionId(), ex);
			return ex;
		}
	}

	private void removePgIndex(Slot slot) {
		if (!numericSession(slot.sessionId())) {
			return;
		}
		LinkedHashSet<String> keys = new LinkedHashSet<>(pgIndexKeys(slot));
		if (!keys.remove(slot.key())) {
			return;
		}
		try {
			String indexModule = pgModule(slot.tenantId(), slot.userId(), INDEX_MODULE);
			if (keys.isEmpty()) {
				pgSession.delete(slot.userId(), slot.sessionId(), indexModule);
			}
			else {
				pgSession.save(slot.userId(), slot.sessionId(), indexModule, new KeyIndex(List.copyOf(keys)));
			}
		}
		catch (RuntimeException ex) {
			log.error("v2 AgentStateStore PG 索引删除失败. tenant={} user={} session={}", slot.tenantId(), slot.userId(),
					slot.sessionId(), ex);
			throw pgFailure("索引删除", ex);
		}
	}

	private List<String> listedKeys(String userId, String sessionId) {
		Slot slot = slot(userId, sessionId, INDEX_MODULE);
		StringRedisTemplate redis = redis();
		if (redis != null) {
			try {
				Set<String> members = redis.opsForSet().members(slot.redisKeySet());
				if (members != null && !members.isEmpty()) {
					return List.copyOf(members);
				}
			}
			catch (RuntimeException ex) {
				log.error("v2 AgentStateStore Redis 列 key 失败, 回落 PG. tenant={} user={} session={}", slot.tenantId(),
						slot.userId(), slot.sessionId(), ex);
			}
		}
		return pgIndexKeys(slot);
	}

	private List<String> pgIndexKeys(Slot slot) {
		return pgLoad(new Slot(slot.tenantId(), slot.userId(), slot.sessionId(), INDEX_MODULE, properties),
				KeyIndex.class, false)
			.map(KeyIndex::keys)
			.orElse(List.of());
	}

	private <T extends State> Optional<T> decodeSingle(String payload, Class<T> type) {
		if (!StringUtils.hasText(payload) || isTombstone(payload)) {
			return Optional.empty();
		}
		if (payload.charAt(0) == KIND_LIST) {
			List<T> list = decodeList(payload, type);
			if (list.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(list.get(list.size() - 1));
		}
		String json = payload.charAt(0) == KIND_SINGLE ? payload.substring(1) : payload;
		return Optional.ofNullable(jsonCodec.fromJson(json, type));
	}

	private <T extends State> List<T> decodeList(String payload, Class<T> itemType) {
		if (!StringUtils.hasText(payload) || isTombstone(payload)) {
			return List.of();
		}
		if (payload.charAt(0) == KIND_SINGLE) {
			T single = jsonCodec.fromJson(payload.substring(1), itemType);
			return single == null ? List.of() : List.of(single);
		}
		String json = payload.charAt(0) == KIND_LIST ? payload.substring(1) : payload;
		List<Object> raw = jsonCodec.fromJson(json, new TypeReference<List<Object>>() {
		});
		if (raw == null || raw.isEmpty()) {
			return List.of();
		}
		List<T> out = new ArrayList<>(raw.size());
		for (Object item : raw) {
			if (item == null) {
				continue;
			}
			out.add(itemType.isInstance(item) ? itemType.cast(item) : jsonCodec.convertValue(item, itemType));
		}
		return out;
	}

	private Slot slot(String userId, String sessionId, String key) {
		requireSessionId(sessionId);
		if (!StringUtils.hasText(key)) {
			throw new IllegalArgumentException("key must not be blank");
		}
		return new Slot(requireTenant(), resolveUser(userId), sessionId.trim(), key.trim(), properties);
	}

	private String requireTenant() {
		if (scope == null || !StringUtils.hasText(scope.tenantId()) || ANON.equals(scope.tenantId().trim())) {
			throw CheckedException.fail("v2 AgentStateStore requires tenantId from V2RuntimeSnapshot");
		}
		return scope.tenantId().trim();
	}

	private String resolveUser(String userId) {
		if (scope == null || !StringUtils.hasText(scope.userId()) || ANON.equals(scope.userId().trim())) {
			throw CheckedException.fail("v2 AgentStateStore requires userId from V2RuntimeSnapshot");
		}
		String scoped = scope.userId().trim();
		if (StringUtils.hasText(userId) && !scoped.equals(userId.trim())) {
			throw CheckedException.fail("v2 AgentStateStore userId mismatch with V2RuntimeSnapshot");
		}
		return scoped;
	}

	private StringRedisTemplate redis() {
		StringRedisTemplate redis = redisProvider.getIfAvailable();
		if (redis == null && redisAbsentLogged.compareAndSet(false, true)) {
			log.warn("AgentScope v2 AgentStateStore: StringRedisTemplate absent, using PG only");
		}
		return redis;
	}

	private Duration ttl() {
		Duration ttl = properties.getRedisTtl();
		return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofDays(7) : ttl;
	}

	private Duration tombstoneTtl() {
		Duration ttl = ttl();
		Duration doubled = ttl.multipliedBy(2);
		return doubled.compareTo(ttl) >= 0 ? doubled : ttl;
	}

	private String sessionIndexKey(String tenant, String user) {
		return properties.resolvedRedisKeyPrefix() + "t:" + enc(tenant) + ":u:" + enc(user) + ":sessions";
	}

	private static void redisRemove(StringRedisTemplate redis, String key) {
		redis.delete(key);
	}

	private void refreshTtl(Slot slot) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return;
		}
		try {
			Duration ttl = ttl();
			redis.expire(slot.redisDataKey(), ttl);
			redis.expire(slot.redisKeySet(), ttl);
			redis.expire(slot.redisSessionIndex(), ttl);
		}
		catch (RuntimeException ex) {
			log.debug("v2 AgentStateStore Redis TTL 续期失败. session={} key={}", slot.sessionId(), slot.key());
		}
	}

	private void refreshTombstone(Slot slot) {
		StringRedisTemplate redis = redis();
		if (redis == null) {
			return;
		}
		try {
			Duration ttl = tombstoneTtl();
			redis.expire(slot.redisDataKey(), ttl);
			redis.expire(slot.redisDeletedSet(), ttl);
		}
		catch (RuntimeException ex) {
			log.debug("v2 AgentStateStore Redis 墓碑 TTL 续期失败. session={} key={}", slot.sessionId(), slot.key());
		}
	}

	private static boolean isTombstone(String payload) {
		return StringUtils.hasText(payload) && payload.charAt(0) == KIND_DELETED;
	}

	private static CheckedException redisWriteFailure(RuntimeException ex) {
		return CheckedException.fail("v2 AgentStateStore Redis 写入失败: " + ex.getMessage());
	}

	private static CheckedException pgFailure(String action, RuntimeException ex) {
		return CheckedException.fail("v2 AgentStateStore PG " + action + "失败: " + ex.getMessage());
	}

	private static void requireSessionId(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			throw new IllegalArgumentException("sessionId must not be blank");
		}
	}

	private static boolean numericSession(String sessionId) {
		if (!StringUtils.hasText(sessionId)) {
			return false;
		}
		for (int i = 0; i < sessionId.length(); i++) {
			if (!Character.isDigit(sessionId.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	static String pgModule(String tenantId, String userId, String key) {
		return "as2:" + enc(tenantId) + ":" + enc(userId) + ":" + key;
	}

	static String enc(String raw) {
		String value = StringUtils.hasText(raw) ? raw.trim() : ANON;
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public record DeletedState(String marker) implements State {

		public DeletedState() {
			this("D");
		}

	}

	public record KeyIndex(List<String> keys) implements State {

		public KeyIndex {
			keys = keys == null ? List.of() : List.copyOf(keys);
		}

	}

	private record Slot(String tenantId, String userId, String sessionId, String key, AgentScopeV2Properties properties) {

		String redisDataKey() {
			return properties.resolvedRedisKeyPrefix() + "t:" + enc(tenantId) + ":u:" + enc(userId) + ":s:"
					+ enc(sessionId) + ":k:" + enc(key);
		}

		String redisKeySet() {
			return properties.resolvedRedisKeyPrefix() + "t:" + enc(tenantId) + ":u:" + enc(userId) + ":s:"
					+ enc(sessionId) + ":keys";
		}

		String redisSessionIndex() {
			return properties.resolvedRedisKeyPrefix() + "t:" + enc(tenantId) + ":u:" + enc(userId) + ":sessions";
		}

		String redisDeletedSet() {
			return properties.resolvedRedisKeyPrefix() + "t:" + enc(tenantId) + ":u:" + enc(userId) + ":s:"
					+ enc(sessionId) + ":deleted";
		}

		String pgModule() {
			return V2AgentStateStore.pgModule(tenantId, userId, key);
		}

	}

	private record RedisRead(Kind kind, String payload) {

		enum Kind {
			DISABLED, HIT, MISS, TOMBSTONE, ERROR
		}

		static RedisRead disabled() {
			return new RedisRead(Kind.DISABLED, null);
		}

		static RedisRead hit(String payload) {
			return new RedisRead(Kind.HIT, payload);
		}

		static RedisRead miss() {
			return new RedisRead(Kind.MISS, null);
		}

		static RedisRead tombstone() {
			return new RedisRead(Kind.TOMBSTONE, String.valueOf(KIND_DELETED));
		}

		static RedisRead error() {
			return new RedisRead(Kind.ERROR, null);
		}

	}

}
