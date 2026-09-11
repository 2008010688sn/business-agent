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
package com.sn68.agent.dataagent.config;

import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataAgentDbOperationExecutorContextTest {

	@Test
	void dbOperationExecutor_propagatesOutboundContext() throws Exception {
		DataAgentConfiguration configuration = new DataAgentConfiguration();
		ExecutorService executor = configuration.dbOperationExecutor();
		try {
			DataAgentOutboundContext.set(
					new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer user-token")));

			String token = executor.submit(() -> DataAgentOutboundContext.get().headers().get("V4-Authorization"))
				.get(5, TimeUnit.SECONDS);

			assertEquals("Bearer user-token", token);
		}
		finally {
			DataAgentOutboundContext.clear();
			configuration.destroy();
		}
	}

	@Test
	void flowResolverExecutorUsesDedicatedPoolAndPropagatesContext() throws Exception {
		DataAgentConfiguration configuration = new DataAgentConfiguration();
		DataAgentProperties properties = new DataAgentProperties();
		properties.getFlow().setMaxParallelResolvers(2);
		properties.getFlow().setExecutorQueueCapacity(3);
		ExecutorService executor = configuration.flowResolverExecutor(properties);
		try {
			DataAgentOutboundContext.set(
					new DataAgentOutboundContext.Snapshot(Map.of("V4-Authorization", "Bearer user-token")));

			String result = executor.submit(() -> Thread.currentThread().getName() + ":"
					+ DataAgentOutboundContext.get().headers().get("V4-Authorization"))
				.get(5, TimeUnit.SECONDS);

			assertEquals("flow-resolver-1:Bearer user-token", result);
		}
		finally {
			DataAgentOutboundContext.clear();
			configuration.destroy();
		}
	}

}
