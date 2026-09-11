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

import com.sn68.agent.dataagent.agentscope.service.AgentScopeModelFactory;
import com.sn68.agent.dataagent.agentscope.session.AgentRuntimeRegistry;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeMysqlSession;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.authorization.pep.RuntimePolicyEvaluator;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceVersionMapper;
import com.sn68.agent.dataagent.service.agent.AgentModelConfigService;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import com.sn68.agent.dataagent.service.analysis.AnalysisWorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * AgentScope 2.0 运行时装配：Harness/Mapper/Adapter/StateStore 始终创建。
 * ReAct 一律走 HarnessAgentFactory；工厂缺失失败关闭，禁止回退 {@code ReActAgent.call}。
 */
@Configuration
@EnableConfigurationProperties(AgentScopeV2Properties.class)
public class AgentScopeV2Configuration {

	@Configuration
	static class RuntimeBeans {

		@Bean
		V2EventToAgentResponseMapper v2EventToAgentResponseMapper() {
			return new V2EventToAgentResponseMapper();
		}

		@Bean
		V2SpringAiChatModelAdapter v2SpringAiChatModelAdapter(AgentScopeModelFactory agentScopeModelFactory) {
			return new V2SpringAiChatModelAdapter(agentScopeModelFactory);
		}

		@Bean
		V2AgentStateStore v2AgentStateStore(AgentScopeV2Properties properties,
				ObjectProvider<StringRedisTemplate> redisProvider, AgentScopeMysqlSession mysqlSession) {
			return new V2AgentStateStore(properties, redisProvider, mysqlSession);
		}

		@Bean
		V2TenantGuardMiddleware v2TenantGuardMiddleware(AgentRuntimeRegistry runtimeRegistry) {
			return new V2TenantGuardMiddleware(runtimeRegistry);
		}

		@Bean
		V2BudgetMiddleware v2BudgetMiddleware(AgentScopeV2Properties properties) {
			return new V2BudgetMiddleware(properties);
		}

		@Bean
		V2ConfirmCredentialStore v2ConfirmCredentialStore(AgentScopeV2Properties properties,
				ObjectProvider<StringRedisTemplate> redisProvider) {
			return new V2ConfirmCredentialStore(properties, redisProvider);
		}

		@Bean
		V2ActingPermissionMiddleware v2ActingPermissionMiddleware(ObjectProvider<RuntimePolicyEvaluator> pep,
				ObjectProvider<AgentExecutionResourceVersionMapper> resourceVersions,
				ObjectProvider<PepAuthorizationProperties> pepProperties, V2ConfirmCredentialStore confirmStore) {
			return new V2ActingPermissionMiddleware(pep.getIfAvailable(), resourceVersions.getIfAvailable(),
					pepProperties.getIfAvailable(), confirmStore);
		}

		@Bean
		V2CompactionGuardMiddleware v2CompactionGuardMiddleware() {
			return new V2CompactionGuardMiddleware();
		}

		@Bean
		V2ReasoningThrottleMiddleware v2ReasoningThrottleMiddleware() {
			return new V2ReasoningThrottleMiddleware();
		}

		@Bean
		HarnessAgentFactory harnessAgentFactory(V2SpringAiChatModelAdapter modelAdapter,
				V2EventToAgentResponseMapper eventMapper, DynamicModelFactory dynamicModelFactory,
				AgentModelConfigService agentModelConfigService, ModelConfigDataService modelConfigDataService,
				com.sn68.agent.dataagent.service.agent.DataAgentService agentService, V2AgentStateStore stateStore,
				V2TenantGuardMiddleware tenantGuardMiddleware, AgentScopeV2Properties properties,
				V2BudgetMiddleware budgetMiddleware, V2ActingPermissionMiddleware permissionMiddleware,
				V2CompactionGuardMiddleware compactionGuardMiddleware,
				V2ReasoningThrottleMiddleware reasoningThrottleMiddleware,
				ObjectProvider<AnalysisWorkspaceStore> workspaceStore) {
			return new HarnessAgentFactory(modelAdapter, eventMapper, dynamicModelFactory, agentModelConfigService,
					modelConfigDataService, agentService, stateStore, tenantGuardMiddleware, properties,
					budgetMiddleware, permissionMiddleware, compactionGuardMiddleware, reasoningThrottleMiddleware,
					workspaceStore.getIfAvailable());
		}

	}

}
