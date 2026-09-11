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
package com.sn68.agent.dataagent.employee.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

class CachingEmployeeExecutionContextClientTest {

	private static final String TENANT_ID = "7";

	private static final String PRINCIPAL_ID = "sp_abc";

	private final LocalPrincipalStore store = new LocalPrincipalStore();

	@SuppressWarnings("unchecked")
	private final ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);

	private CachingEmployeeExecutionContextClient client;

	@BeforeEach
	void setUp() {
		when(provider.getIfAvailable()).thenReturn(null);
		store.provision(TENANT_ID, "abc", "emp");
		client = new CachingEmployeeExecutionContextClient(store, provider, new DigitalEmployeeProperties(),
				new ObjectMapper());
	}

	@Test
	@DisplayName("本地 Principal 可签发执行上下文")
	void issueContextFromLocalStore() {
		EmployeeAuthTokenContext context = client.issueContext(TENANT_ID, PRINCIPAL_ID, "emp");

		assertNotNull(context.tokenValue());
		assertEquals("Bearer", context.tokenType());
		assertEquals(1L, context.authRevision());
	}

}
