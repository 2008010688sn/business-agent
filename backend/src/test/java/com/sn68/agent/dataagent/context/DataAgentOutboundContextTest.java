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

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentOutboundContextTest {

	@AfterEach
	void tearDown() {
		RequestContextHolder.resetRequestAttributes();
		DataAgentOutboundContext.clear();
	}

	@Test
	void captureFromRequest_keepsOnlyOutboundHeaders() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("V4-Authorization", "Bearer user-token");
		request.addHeader("x-tenant-id", "tenant-a");
		request.addHeader("DataPermission", "must-not-propagate");
		request.addHeader("UserInfoDetails", "must-not-propagate");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		DataAgentOutboundContext.Snapshot snapshot = DataAgentOutboundContext.captureFromRequest();

		assertEquals("Bearer user-token", snapshot.headers().get("V4-Authorization"));
		assertEquals("tenant-a", snapshot.headers().get("x-tenant-id"));
		assertFalse(snapshot.headers().containsKey("DataPermission"));
		assertFalse(snapshot.headers().containsKey("UserInfoDetails"));
	}

	@Test
	void withPrincipalToken_fillsMissingTenantHeader() {
		DataAgentOutboundContext.Snapshot snapshot = DataAgentOutboundContext.withPrincipalToken(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "old")), "principal-token", "7");

		assertEquals("principal-token", snapshot.headers().get("V4-Authorization"));
		assertEquals("7", snapshot.headers().get("x-tenant-id"));
		assertTrue(snapshot.forceHttpAuthorization());
	}

	@Test
	void withPrincipalToken_doesNotOverrideExistingTenantHeader() {
		DataAgentOutboundContext.Snapshot snapshot = DataAgentOutboundContext.withPrincipalToken(
				new DataAgentOutboundContext.Snapshot(Map.of("x-tenant-id", "tenant-a")), "principal-token", "7");

		assertEquals("tenant-a", snapshot.headers().get("x-tenant-id"));
		assertEquals("principal-token", snapshot.headers().get("V4-Authorization"));
	}

	@Test
	void snapshot_filtersManualNonHeaderContext() {
		DataAgentOutboundContext.Snapshot snapshot = new DataAgentOutboundContext.Snapshot(Map.of(
				"V4-Authorization", "Bearer user-token",
				"funcPermissionList", "must-not-propagate",
				"rolePermissionList", "must-not-propagate",
				"AuthenticationContext", "must-not-propagate"));

		assertEquals(Map.of("V4-Authorization", "Bearer user-token"), snapshot.headers());
	}

	@Test
	void runWith_restoresPreviousContext() {
		DataAgentOutboundContext.set(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer first-token")));

		DataAgentOutboundContext.runWith(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer second-token")),
				() -> assertEquals("Bearer second-token",
						DataAgentOutboundContext.get().headers().get("V4-Authorization")));

		assertEquals("Bearer first-token", DataAgentOutboundContext.get().headers().get("V4-Authorization"));
	}

	@Test
	void setEmpty_clearsContext() {
		DataAgentOutboundContext.set(
				new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer user-token")));

		DataAgentOutboundContext.set(DataAgentOutboundContext.Snapshot.empty());

		assertTrue(DataAgentOutboundContext.get().isEmpty());
	}

}
