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
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatasourceRuntimeContextCacheTest {

	@Test
	void springContextCreatesCacheBeanWithEnvironmentConstructor() {
		new ApplicationContextRunner().withBean(DatasourceRuntimeContextCache.class)
			.run(context -> assertEquals(1, context.getBeanNamesForType(DatasourceRuntimeContextCache.class).length));
	}

	@Test
	void permissionRules_defaultConfigCachesWithinSameRuntimeRequest() {
		MutableClock clock = new MutableClock(Instant.parse("2026-06-13T00:00:00Z"));
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(), clock);
		AtomicInteger loadCount = new AtomicInteger();
		AgentRequest request = request("thread-1", "run-1");

		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));
		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));

		assertEquals(1, loadCount.get());
	}

	@Test
	void permissionRules_disabledConfigSkipsCache() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.ai.agent.datasource-runtime-cache.enabled", "false");
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(environment,
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AtomicInteger loadCount = new AtomicInteger();
		AgentRequest request = request("thread-1", "run-1");

		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));
		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));

		assertEquals(2, loadCount.get());
	}

	@Test
	void permissionRules_ttlExpiresLazily() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("spring.ai.agent.datasource-runtime-cache.ttl", "2m");
		MutableClock clock = new MutableClock(Instant.parse("2026-06-13T00:00:00Z"));
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(environment, clock);
		AtomicInteger loadCount = new AtomicInteger();
		AgentRequest request = request("thread-1", "run-1");

		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));
		clock.advance(Duration.ofMinutes(2));
		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));

		assertEquals(2, loadCount.get());
	}

	@Test
	void permissionRules_clearRemovesOnlyCurrentRuntimeRequest() {
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(),
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AtomicInteger loadCount = new AtomicInteger();
		AgentRequest first = request("thread-1", "run-1");
		AgentRequest second = request("thread-1", "run-2");

		cache.getOrLoadPermissionRules(1L, first, () -> rules(loadCount.incrementAndGet()));
		cache.getOrLoadPermissionRules(1L, second, () -> rules(loadCount.incrementAndGet()));
		cache.clear("thread-1", "run-1");
		cache.getOrLoadPermissionRules(1L, first, () -> rules(loadCount.incrementAndGet()));
		cache.getOrLoadPermissionRules(1L, second, () -> rules(loadCount.incrementAndGet()));

		assertEquals(3, loadCount.get());
	}

	@Test
	void schemaReadsAllowSameTableAndCapDistinctTables() {
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(),
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AgentRequest request = request("thread-1", "run-1");

		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t1", 4));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t2", 4));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t3", 4));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t4", 4));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t1", 4));
		assertEquals(false, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:t5", 4));
	}

	@Test
	void successfulSearchBlocksLaterSchemaReadsForSameRuntime() {
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(),
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AgentRequest request = request("thread-1", "run-1");

		assertEquals(false, cache.hasSuccessfulSearch(request));
		cache.markSearchSucceeded(request);
		assertEquals(true, cache.hasSuccessfulSearch(request));
		assertEquals(false, cache.hasSuccessfulSearch(request("thread-1", "run-2")));
	}

	@Test
	void findTablesKeysDoNotConsumeSchemaReadBudget() {
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(),
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AgentRequest request = request("thread-1", "run-1");

		assertEquals(true, cache.tryAcquireMetadataRead(request, "FIND_TABLES:用箱量"));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:bill_cost"));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:bill_contract"));
		assertEquals(true, cache.tryAcquireMetadataRead(request, "GET_TABLE_SCHEMA:bill_cost"));
	}

	@Test
	void permissionRules_environmentChangeClearsAllCache() {
		DatasourceRuntimeContextCache cache = new DatasourceRuntimeContextCache(new MockEnvironment(),
				new MutableClock(Instant.parse("2026-06-13T00:00:00Z")));
		AtomicInteger loadCount = new AtomicInteger();
		AgentRequest request = request("thread-1", "run-1");

		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));
		cache.onEnvironmentChange(new EnvironmentChangeEvent(
				Set.of("spring.ai.agent.datasource-runtime-cache.ttl")));
		cache.getOrLoadPermissionRules(1L, request, () -> rules(loadCount.incrementAndGet()));

		assertEquals(2, loadCount.get());
	}

	private static List<DatasourcePermissionRule> rules(int marker) {
		DatasourcePermissionRule rule = new DatasourcePermissionRule();
		rule.setDatasourceId((long) marker);
		return List.of(rule);
	}

	private static AgentRequest request(String threadId, String runtimeRequestId) {
		AgentRequest request = new AgentRequest();
		request.setThreadId(threadId);
		request.setRuntimeRequestId(runtimeRequestId);
		return request;
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}

	}

}
