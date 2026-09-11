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
package com.sn68.agent.dataagent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import com.sn68.agent.framework.commons.threadlocal.ThreadLocalHolder;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;

class DataAgentAsyncContextBridgeTest {

	private final DataAgentAsyncContextBridge bridge = new DataAgentAsyncContextBridge();

	@AfterEach
	void tearDown() {
		ThreadLocalHolder.clear();
		LocaleContextHolder.resetLocaleContext();
		DataAgentOutboundContext.clear();
	}

	@Test
	void callWith_restoresCapturedContextAndThenPreviousContext() throws Exception {
		ThreadLocalHolder.set("user", "captured-user");
		LocaleContextHolder.setLocale(Locale.CHINA);
		DataAgentOutboundContext.set(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer captured-token")));
		DataAgentAsyncContextBridge.Snapshot snapshot = bridge.capture();

		ThreadLocalHolder.clear();
		ThreadLocalHolder.set("user", "previous-user");
		LocaleContextHolder.setLocale(Locale.US);
		DataAgentOutboundContext.set(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer previous-token")));

		String user = bridge.callWith(snapshot, () -> {
			assertEquals("captured-user", ThreadLocalHolder.get("user"));
			assertEquals(Locale.CHINA, LocaleContextHolder.getLocale());
			assertEquals("Bearer captured-token",
					DataAgentOutboundContext.get().headers().get("V4-Authorization"));
			return String.valueOf(ThreadLocalHolder.get("user"));
		});

		assertEquals("captured-user", user);
		assertEquals("previous-user", ThreadLocalHolder.get("user"));
		assertEquals(Locale.US, LocaleContextHolder.getLocale());
		assertEquals("Bearer previous-token", DataAgentOutboundContext.get().headers().get("V4-Authorization"));
	}

	@Test
	void capture_insideCallWithReusesCurrentSnapshot() throws Exception {
		LocalPrincipalStore store = new LocalPrincipalStore();
		store.provision("7", "abc", "emp");
		LocalPrincipalStore.IssuedToken issued = store.issueToken("7", "sp_abc", "emp");
		@SuppressWarnings("unchecked")
		ObjectProvider<LocalPrincipalStore> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(store);
		DataAgentAsyncContextBridge hydrating = new DataAgentAsyncContextBridge(provider);
		DataAgentOutboundContext.Snapshot outbound =
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer " + issued.tokenValue()));
		DataAgentAsyncContextBridge.Snapshot delegated =
				hydrating.snapshotForDelegatedToken(issued.tokenValue(), outbound);

		hydrating.callWith(delegated, () -> {
			DataAgentAsyncContextBridge.Snapshot captured = hydrating.capture();
			assertTrue(captured.delegated());
			assertEquals(issued.tokenValue(), captured.tokenValue());
			return null;
		});
	}

	@Test
	void capture_withoutSaTokenContextDoesNotThrow() throws Exception {
		ThreadLocalHolder.set("user", "anonymous");
		ThreadLocalHolder.set("nullable", null);

		DataAgentAsyncContextBridge.Snapshot snapshot = bridge.capture();

		assertEquals("anonymous", snapshot.threadLocalData().get("user"));
		assertTrue(snapshot.threadLocalData().containsKey("nullable"));
		assertEquals("anonymous", bridge.callWith(snapshot, () -> ThreadLocalHolder.get("user")));
	}

	@Test
	void callWith_cleansContextWhenNoPreviousContextEvenOnException() {
		DataAgentAsyncContextBridge.Snapshot snapshot = new DataAgentAsyncContextBridge.Snapshot(null,
				Locale.CHINA, Map.of("user", "captured-user"), DataAgentOutboundContext.Snapshot.empty());

		assertThrows(IllegalStateException.class, () -> bridge.callWith(snapshot, () -> {
			assertEquals("captured-user", ThreadLocalHolder.get("user"));
			throw new IllegalStateException("boom");
		}));

		assertTrue(ThreadLocalHolder.getAll().isEmpty());
	}

	@Test
	void delegatedSnapshotWithoutSessionFailsClosedAndRestoresPreviousContext() {
		ThreadLocalHolder.set("USER_INFO_KEY", "previous-user");
		ThreadLocalHolder.set("USER_ANONYMOUS_KEY", true);
		ThreadLocalHolder.set("MERGED_DATA_PERMISSION_KEY", "previous-permission");
		ThreadLocalHolder.set("trace", "trace-1");
		DataAgentOutboundContext.Snapshot outbound =
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer delegated-token"));

		DataAgentAsyncContextBridge.Snapshot delegated =
				bridge.snapshotForDelegatedToken("delegated-token", outbound);

		assertFalse(delegated.threadLocalData().containsKey("USER_INFO_KEY"));
		assertFalse(delegated.threadLocalData().containsKey("USER_ANONYMOUS_KEY"));
		assertFalse(delegated.threadLocalData().containsKey("MERGED_DATA_PERMISSION_KEY"));
		IllegalStateException thrown = assertThrows(IllegalStateException.class,
				() -> bridge.callWith(delegated, () -> "should-not-run"));
		assertTrue(thrown.getMessage().contains("授权上下文"));

		assertEquals("previous-user", ThreadLocalHolder.get("USER_INFO_KEY"));
		assertEquals("previous-permission", ThreadLocalHolder.get("MERGED_DATA_PERMISSION_KEY"));
	}

	@Test
	void delegatedSnapshotHydratesUserInfoFromTokenSession() throws Exception {
		LocalPrincipalStore store = new LocalPrincipalStore();
		store.provision("7", "abc", "emp");
		LocalPrincipalStore.IssuedToken issued = store.issueToken("7", "sp_abc", "emp");
		@SuppressWarnings("unchecked")
		ObjectProvider<LocalPrincipalStore> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(store);
		DataAgentAsyncContextBridge hydrating = new DataAgentAsyncContextBridge(provider);
		ThreadLocalHolder.set("USER_INFO_KEY", "previous-user");
		DataAgentOutboundContext.Snapshot outbound =
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer " + issued.tokenValue()));

		hydrating.callWith(hydrating.snapshotForDelegatedToken(issued.tokenValue(), outbound), () -> {
			UserInfoDetails details = (UserInfoDetails) ThreadLocalHolder.get("USER_INFO_KEY");
			assertEquals("sp_abc", details.getUserId());
			assertEquals(false, ThreadLocalHolder.get("USER_ANONYMOUS_KEY"));
			return null;
		});

		assertEquals("previous-user", ThreadLocalHolder.get("USER_INFO_KEY"));
	}

}
