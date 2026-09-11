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

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.framework.commons.security.DataPermission;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class AgentScopeV2PropertiesTest {

	@Test
	void redisAndBudgetDefaults() {
		AgentScopeV2Properties properties = new AgentScopeV2Properties();
		assertEquals("as2:", properties.resolvedRedisKeyPrefix());
		assertEquals(Duration.ofDays(7), properties.getRedisTtl());
		assertEquals(AgentScopeV2Properties.DEFAULT_MAX_ITERS, properties.resolvedMaxIters());
		assertEquals(AgentScopeV2Properties.DEFAULT_MAX_DURATION, properties.resolvedMaxDuration());
		assertEquals(16, AgentScopeV2Properties.DEFAULT_MAX_ITERS);
		assertEquals(Duration.ofSeconds(180), AgentScopeV2Properties.DEFAULT_MAX_DURATION);
		assertEquals(5_000_000L, AgentScopeV2Properties.DEFAULT_MAX_TOKENS);
		assertEquals(2_500_000L, AgentScopeV2Properties.DEFAULT_MAX_COST);
		assertEquals(V2BudgetEnvelope.costMillicentsForTokens(5_000_000L), AgentScopeV2Properties.DEFAULT_MAX_COST);
		assertEquals(AgentScopeV2Properties.DEFAULT_MAX_TOKENS, properties.resolvedMaxTokens());
		assertEquals(AgentScopeV2Properties.DEFAULT_MAX_COST, properties.resolvedMaxCost());
		assertEquals(AgentScopeV2Properties.DEFAULT_FINGERPRINT_REPEAT_LIMIT,
				properties.resolvedFingerprintRepeatLimit());
	}

	@Test
	void redisKeyPrefixAlwaysEndsWithColon() {
		AgentScopeV2Properties properties = new AgentScopeV2Properties();
		properties.setRedisKeyPrefix("as2");
		assertEquals("as2:", properties.resolvedRedisKeyPrefix());
	}

	@Test
	void runtimeSnapshotReusesRequestObjectsWithoutReparsingHeaders() {
		DataPermission permission = DataPermission.builder().build();
		AgentRequest request = AgentRequest.builder()
			.threadId("100")
			.runtimeRequestId("run-1")
			.tenantIdSnapshot("tenant-9")
			.tenantCodeSnapshot("T9")
			.userIdSnapshot("user-3")
			.dataPermissionSnapshot(permission)
			.agentId("1")
			.ownerType("DATA_AGENT")
			.ownerId(1L)
			.durableRunId(99L)
			.build();

		V2RuntimeSnapshot snapshot = V2RuntimeSnapshot.from(request);

		assertEquals("tenant-9", snapshot.tenantId());
		assertEquals("T9", snapshot.tenantCode());
		assertEquals("user-3", snapshot.userId());
		assertEquals("100", snapshot.sessionId());
		assertSame(permission, snapshot.dataPermission());
		assertEquals("1", snapshot.agentId());
		assertEquals("DATA_AGENT", snapshot.ownerType());
		assertEquals(1L, snapshot.ownerId());
		assertEquals(99L, snapshot.durableRunId());
	}

}
