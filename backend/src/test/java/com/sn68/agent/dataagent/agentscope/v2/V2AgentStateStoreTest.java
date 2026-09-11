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

import com.sn68.agent.dataagent.agentscope.session.AgentScopeMysqlSession;
import com.sn68.agent.framework.commons.exception.CheckedException;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.State;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class V2AgentStateStoreTest {

	@Test
	void redisSaveLoadDeleteRoundtrip() {
		FakeRedis redis = new FakeRedis();
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis.template, pg.session).bind(snapshot("t1", "user-a", "100"));
		Msg message = msg("hello");

		store.save("user-a", "100", "memory_messages", message);

		Optional<Msg> loaded = store.get("user-a", "100", "memory_messages", Msg.class);
		assertTrue(loaded.isPresent());
		assertEquals("hello", loaded.get().getTextContent());
		assertTrue(store.exists("user-a", "100"));
		assertTrue(redis.values.keySet().stream().anyMatch(key -> key.startsWith("as2:")));

		store.delete("user-a", "100");
		assertTrue(store.get("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertFalse(store.exists("user-a", "100"));
	}

	@Test
	void userACannotLoadUserB() {
		FakeRedis redis = new FakeRedis();
		V2AgentStateStore store = newStore(redis.template, new PgBackend().session);
		store.bind(snapshot("t1", "user-a", "100")).save("user-a", "100", "memory_messages", msg("secret-a"));

		Optional<Msg> stolen = store.bind(snapshot("t1", "user-b", "100"))
			.get("user-b", "100", "memory_messages", Msg.class);
		assertTrue(stolen.isEmpty());
		assertEquals("secret-a", store.bind(snapshot("t1", "user-a", "100"))
			.get("user-a", "100", "memory_messages", Msg.class)
			.orElseThrow()
			.getTextContent());
	}

	@Test
	void tenantACannotLoadTenantB() {
		FakeRedis redis = new FakeRedis();
		V2AgentStateStore store = newStore(redis.template, new PgBackend().session);
		store.bind(snapshot("tenant-a", "user-a", "100")).save("user-a", "100", "memory_messages", msg("tenant-a-secret"));

		Optional<Msg> stolen = store.bind(snapshot("tenant-b", "user-a", "100"))
			.get("user-a", "100", "memory_messages", Msg.class);
		assertTrue(stolen.isEmpty());
	}

	@Test
	void listSessionIdsRequiresUserAndStaysInTenant() {
		FakeRedis redis = new FakeRedis();
		V2AgentStateStore store = newStore(redis.template, new PgBackend().session);
		store.bind(snapshot("t1", "user-a", "100")).save("user-a", "100", "memory_messages", msg("a"));
		store.bind(snapshot("t1", "user-b", "200")).save("user-b", "200", "memory_messages", msg("b"));
		store.bind(snapshot("t2", "user-a", "300")).save("user-a", "300", "memory_messages", msg("c"));

		assertEquals(Set.of("100"), store.bind(snapshot("t1", "user-a", "100")).listSessionIds("user-a"));
		assertThrows(CheckedException.class, () -> store.listSessionIds(null));
		assertThrows(CheckedException.class, () -> store.listSessionIds(""));
		assertThrows(CheckedException.class,
				() -> store.bind(snapshot("t1", "user-a", "100")).listSessionIds("user-b"));
	}

	@Test
	void blankTenantOrUserIsRejected() {
		FakeRedis redis = new FakeRedis();
		V2AgentStateStore store = newStore(redis.template, new PgBackend().session);
		assertThrows(CheckedException.class,
				() -> store.bind(snapshot(null, "user-a", "100")).save("user-a", "100", "memory_messages", msg("x")));
		assertThrows(CheckedException.class,
				() -> store.bind(snapshot("t1", null, "100")).save("user-a", "100", "memory_messages", msg("x")));
		assertThrows(CheckedException.class, () -> store.save("user-a", "100", "memory_messages", msg("x")));
	}

	@Test
	void boundStoreRejectsMismatchedUserId() {
		FakeRedis redis = new FakeRedis();
		V2AgentStateStore store = newStore(redis.template, new PgBackend().session)
			.bind(snapshot("t1", "user-a", "100"));
		assertThrows(CheckedException.class, () -> store.save("user-b", "100", "memory_messages", msg("x")));
		assertThrows(CheckedException.class, () -> store.get("user-b", "100", "memory_messages", Msg.class));
	}

	@Test
	void pgTombstoneFailureDoesNotResurrectViaRedisBackfill() {
		FakeRedis redis = new FakeRedis();
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis.template, pg.session).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("keep"));
		pg.failDeletedState();

		CheckedException ex = assertThrows(CheckedException.class,
				() -> store.delete("user-a", "100", "memory_messages"));
		assertTrue(ex.getMessage().contains("PG"));
		assertTrue(store.get("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(pg.byModule.values().stream().anyMatch(value -> value instanceof Msg));
		assertTrue(redis.values.values().stream().anyMatch(payload -> payload.startsWith("D")));
		assertTrue(redis.values.values().stream().noneMatch(payload -> payload.startsWith("S") && payload.contains("keep")));
	}

	@Test
	void redisGetMissAfterFailedPgTombstoneDoesNotResurrectFromPg() {
		FakeRedis redis = new FakeRedis();
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis.template, pg.session).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("keep"));
		pg.failDeletedState();
		assertThrows(CheckedException.class, () -> store.delete("user-a", "100", "memory_messages"));

		redis.values.entrySet().removeIf(entry -> entry.getValue() != null && entry.getValue().startsWith("D"));
		assertTrue(pg.byModule.values().stream().anyMatch(value -> value instanceof Msg));
		assertTrue(redis.sets.entrySet()
			.stream()
			.filter(entry -> entry.getKey().endsWith(":keys"))
			.noneMatch(entry -> entry.getValue().contains("memory_messages")));
		assertTrue(redis.sets.entrySet()
			.stream()
			.anyMatch(entry -> entry.getKey().endsWith(":deleted") && entry.getValue().contains("memory_messages")),
				() -> "deleted markers=" + redis.sets);

		assertTrue(store.get("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(store.getList("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(redis.values.values().stream().noneMatch(payload -> payload.startsWith("S") && payload.contains("keep")));
	}

	@Test
	void redisTombstoneExpiryDoesNotResurrectDeletedPgRow() {
		FakeRedis redis = new FakeRedis();
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis.template, pg.session).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("keep"));
		store.delete("user-a", "100", "memory_messages");

		assertTrue(pg.byModule.values().stream().anyMatch(value -> value instanceof V2AgentStateStore.DeletedState));
		redis.values.clear();
		redis.sets.clear();

		assertTrue(store.get("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(store.getList("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(redis.values.values().stream().noneMatch(payload -> payload != null && payload.startsWith("S")));
	}

	@Test
	void redisGetErrorDoesNotLoadPgLeftover() {
		FakeRedis redis = new FakeRedis();
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis.template, pg.session).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("keep"));
		pg.failDeletedState();
		assertThrows(CheckedException.class, () -> store.delete("user-a", "100", "memory_messages"));
		redis.throwOnGet();

		assertTrue(store.get("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(store.getList("user-a", "100", "memory_messages", Msg.class).isEmpty());
		assertTrue(pg.byModule.values().stream().anyMatch(value -> value instanceof Msg));
		assertTrue(redis.values.values().stream().noneMatch(payload -> payload.startsWith("S") && payload.contains("keep")));
	}

	@Test
	void redisOkButPgBackupFailureFailsClosed() {
		FakeRedis redis = new FakeRedis();
		AgentScopeMysqlSession mysql = mock(AgentScopeMysqlSession.class);
		doThrow(new RuntimeException("pg down")).when(mysql).save(any(), any(), any(), any(State.class));
		V2AgentStateStore store = newStore(redis.template, mysql).bind(snapshot("t1", "user-a", "100"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> store.save("user-a", "100", "memory_messages", msg("x")));
		assertTrue(ex.getMessage().contains("PG"));
	}

	@Test
	void fallsBackToPgWhenRedisAbsent() {
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(null, pg.session).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("pg-only"));

		Optional<Msg> loaded = store.get("user-a", "100", "memory_messages", Msg.class);
		assertTrue(loaded.isPresent());
		assertEquals("pg-only", loaded.get().getTextContent());
		assertTrue(pg.byModule.keySet().stream().allMatch(module -> module.startsWith("as2:")));
		assertFalse(pg.byModule.containsKey("memory_messages"));
	}

	@Test
	void redisWriteFailureIsVisibleAfterPgBackup() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> valueOps = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(valueOps);
		doThrow(new RuntimeException("redis down")).when(valueOps).set(anyString(), anyString(), any(Duration.class));
		PgBackend pg = new PgBackend();
		V2AgentStateStore store = newStore(redis, pg.session).bind(snapshot("t1", "user-a", "100"));

		CheckedException ex = assertThrows(CheckedException.class,
				() -> store.save("user-a", "100", "memory_messages", msg("keep")));
		assertTrue(ex.getMessage().contains("Redis"));
		assertFalse(pg.byModule.isEmpty());
	}

	@Test
	void pgModuleUsesV2PrefixNotLegacyName() {
		FakeRedis redis = new FakeRedis();
		AgentScopeMysqlSession mysql = mock(AgentScopeMysqlSession.class);
		V2AgentStateStore store = newStore(redis.template, mysql).bind(snapshot("t1", "user-a", "100"));
		store.save("user-a", "100", "memory_messages", msg("x"));

		ArgumentCaptor<String> module = ArgumentCaptor.forClass(String.class);
		verify(mysql, atLeastOnce()).save(eq("user-a"), eq("100"), module.capture(), any(State.class));
		assertFalse(module.getAllValues().isEmpty());
		assertTrue(module.getAllValues().stream().allMatch(value -> value.startsWith("as2:")));
		assertTrue(module.getAllValues().stream().noneMatch("memory_messages"::equals));
		verify(mysql, never()).save(any(), any(), eq("memory_messages"), any(State.class));
		verify(mysql, never()).delete(any(), any());
	}

	@Test
	void deletePersistsPgTombstoneThroughSaveTombstone() {
		FakeRedis redis = new FakeRedis();
		AgentScopeMysqlSession mysql = mock(AgentScopeMysqlSession.class);
		V2AgentStateStore store = newStore(redis.template, mysql).bind(snapshot("t1", "user-a", "100"));

		store.delete("user-a", "100", "memory_messages");

		verify(mysql, atLeastOnce()).saveTombstone(eq("user-a"), eq("100"), any(),
				any(V2AgentStateStore.DeletedState.class));
		verify(mysql, never()).save(eq("user-a"), eq("100"), any(), any(V2AgentStateStore.DeletedState.class));
	}

	private static V2AgentStateStore newStore(StringRedisTemplate redis, AgentScopeMysqlSession mysql) {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(redis);
		return new V2AgentStateStore(new AgentScopeV2Properties(), provider, mysql);
	}

	private static V2RuntimeSnapshot snapshot(String tenantId, String userId, String sessionId) {
		return new V2RuntimeSnapshot(tenantId, tenantId, userId, null, null, null, List.of(), sessionId, "run-1", null,
				null, null, null);
	}

	private static Msg msg(String text) {
		return Msg.builder().name("user").role(MsgRole.USER).textContent(text).build();
	}

	private static final class PgBackend {

		private final Map<String, Object> byModule = new ConcurrentHashMap<>();

		private final AgentScopeMysqlSession session = mock(AgentScopeMysqlSession.class);

		private PgBackend() {
			doAnswer(inv -> {
				byModule.put(inv.getArgument(2), inv.getArgument(3));
				return null;
			}).when(session).save(any(), any(), any(), any(State.class));
			doAnswer(inv -> {
				byModule.put(inv.getArgument(2), inv.getArgument(3));
				return null;
			}).when(session).saveTombstone(any(), any(), any(), any(State.class));
			doAnswer(inv -> {
				byModule.put(inv.getArgument(2), inv.getArgument(3));
				return null;
			}).when(session).save(any(), any(), any(), any(List.class));
			when(session.get(any(), any(), any(), any())).thenAnswer(inv -> {
				Object value = byModule.get(inv.getArgument(2));
				Class<?> type = inv.getArgument(3);
				return type.isInstance(value) ? Optional.of(value) : Optional.empty();
			});
			when(session.getList(any(), any(), any(), any())).thenAnswer(inv -> {
				Object value = byModule.get(inv.getArgument(2));
				return value instanceof List<?> list ? list : List.of();
			});
			doAnswer(inv -> {
				byModule.remove(inv.getArgument(2));
				return null;
			}).when(session).delete(any(), any(), anyString());
		}

		private void failDeletedState() {
			doAnswer(inv -> {
				Object value = inv.getArgument(3);
				if (value instanceof V2AgentStateStore.DeletedState) {
					throw new RuntimeException("pg down");
				}
				byModule.put(inv.getArgument(2), value);
				return null;
			}).when(session).saveTombstone(any(), any(), any(), any(State.class));
		}

	}

	private static final class FakeRedis {

		private final Map<String, String> values = new ConcurrentHashMap<>();

		private final Map<String, Set<String>> sets = new ConcurrentHashMap<>();

		private final StringRedisTemplate template = mock(StringRedisTemplate.class);

		@SuppressWarnings("unchecked")
		private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

		@SuppressWarnings("unchecked")
		private FakeRedis() {
			SetOperations<String, String> setOps = mock(SetOperations.class, invocation -> {
				String name = invocation.getMethod().getName();
				if ("add".equals(name)) {
					return addToSet(invocation);
				}
				if ("remove".equals(name)) {
					return removeFromSet(invocation);
				}
				if ("members".equals(name)) {
					Set<String> bucket = sets.get(invocation.getArgument(0));
					return bucket == null ? Set.of() : Set.copyOf(bucket);
				}
				if ("isMember".equals(name)) {
					Set<String> bucket = sets.get(invocation.getArgument(0));
					return bucket != null && bucket.contains(String.valueOf(invocation.getArgument(1)));
				}
				return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
			});
			when(template.opsForValue()).thenReturn(valueOps);
			when(template.opsForSet()).thenReturn(setOps);
			when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
			doAnswer(inv -> {
				values.put(inv.getArgument(0), inv.getArgument(1));
				return null;
			}).when(valueOps).set(anyString(), anyString(), any(Duration.class));
			when(template.delete(anyString())).thenAnswer(inv -> {
				String key = inv.getArgument(0);
				boolean removed = values.remove(key) != null;
				removed |= sets.remove(key) != null;
				return removed;
			});
			when(template.expire(anyString(), any(Duration.class))).thenReturn(true);
			when(template.hasKey(anyString())).thenAnswer(inv -> {
				String key = inv.getArgument(0);
				if (values.containsKey(key)) {
					return true;
				}
				Set<String> bucket = sets.get(key);
				return bucket != null && !bucket.isEmpty();
			});
		}

		private void throwOnGet() {
			when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis get down"));
		}

		private Long addToSet(org.mockito.invocation.InvocationOnMock inv) {
			Set<String> bucket = sets.computeIfAbsent(inv.getArgument(0), key -> ConcurrentHashMap.newKeySet());
			long added = 0;
			for (String item : valuesOf(inv)) {
				if (bucket.add(item)) {
					added++;
				}
			}
			return added;
		}

		private Long removeFromSet(org.mockito.invocation.InvocationOnMock inv) {
			Set<String> bucket = sets.get(inv.getArgument(0));
			if (bucket == null) {
				return 0L;
			}
			long removed = 0;
			for (String item : valuesOf(inv)) {
				if (bucket.remove(item)) {
					removed++;
				}
			}
			return removed;
		}

		private static List<String> valuesOf(org.mockito.invocation.InvocationOnMock inv) {
			List<String> items = new java.util.ArrayList<>();
			for (int i = 1; i < inv.getArguments().length; i++) {
				Object arg = inv.getArgument(i);
				if (arg instanceof Object[] arr) {
					for (Object item : arr) {
						if (item != null) {
							items.add(String.valueOf(item));
						}
					}
				}
				else if (arg != null) {
					items.add(String.valueOf(arg));
				}
			}
			return items;
		}

	}

}
