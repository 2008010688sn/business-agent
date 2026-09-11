/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

import com.sn68.agent.dataagent.ui.ToolConfirmUiAssembler;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class V2ConfirmCredentialStoreTest {

	@Test
	void approvedFingerprintSkipsAskAndIsOneShot() {
		FakeRedis redis = new FakeRedis();
		V2ConfirmCredentialStore store = newStore(redis.template);
		V2RuntimeSnapshot snapshot = snapshot();
		ToolUseBlock call = ToolUseBlock.builder().id("call-1").name("crm_update").input(Map.of("id", 1)).build();

		store.savePending(snapshot, "reply-1", call);
		assertFalse(store.isApproved(snapshot, call));

		store.markDecision("t1", "user-a", "call-1", "crm_update", ToolConfirmUiAssembler.fingerprint(call.getInput()),
				true);
		assertTrue(store.isApproved(snapshot, call));
		assertTrue(store.restore(snapshot, call).isPresent());

		ToolUseBlock retry = ToolUseBlock.builder().id("call-2").name("crm_update").input(Map.of("id", 1)).build();
		assertTrue(store.isApproved(snapshot, retry));

		store.consume(snapshot, retry);
		assertFalse(store.isApproved(snapshot, retry));
	}

	@Test
	void mutatedArgsDoNotReuseApprovedCredential() {
		FakeRedis redis = new FakeRedis();
		V2ConfirmCredentialStore store = newStore(redis.template);
		V2RuntimeSnapshot snapshot = snapshot();
		ToolUseBlock approved = ToolUseBlock.builder().id("call-1").name("crm_update").input(Map.of("id", 1)).build();
		store.markDecision("t1", "user-a", "call-1", "crm_update",
				ToolConfirmUiAssembler.fingerprint(approved.getInput()), true);

		ToolUseBlock mutated = ToolUseBlock.builder().id("call-1").name("crm_update").input(Map.of("id", 2)).build();
		assertFalse(store.isApproved(snapshot, mutated));
	}

	private static V2ConfirmCredentialStore newStore(StringRedisTemplate redis) {
		@SuppressWarnings("unchecked")
		ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(redis);
		return new V2ConfirmCredentialStore(new AgentScopeV2Properties(), provider);
	}

	private static V2RuntimeSnapshot snapshot() {
		return new V2RuntimeSnapshot("t1", "t1", "user-a", null, null, null, java.util.List.of(), "100", "run-1");
	}

	private static final class FakeRedis {

		private final Map<String, String> values = new ConcurrentHashMap<>();

		private final StringRedisTemplate template = mock(StringRedisTemplate.class);

		@SuppressWarnings("unchecked")
		private FakeRedis() {
			ValueOperations<String, String> valueOps = mock(ValueOperations.class);
			when(template.opsForValue()).thenReturn(valueOps);
			when(valueOps.get(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0)));
			org.mockito.Mockito.doAnswer(inv -> {
				values.put(inv.getArgument(0), inv.getArgument(1));
				return null;
			}).when(valueOps).set(anyString(), anyString(), any(Duration.class));
		}

	}

}
