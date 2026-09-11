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

import com.sn68.agent.dataagent.properties.AgentScopeObservabilityProperties;
import io.opentelemetry.api.trace.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AgentScope 链路追踪配置：装配观测导出器并在单例初始化完成后接线。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(AgentScopeObservabilityProperties.class)
public class AgentScopeTracingConfiguration implements SmartInitializingSingleton {

	private final AgentScopeObservabilityProperties properties;

	private final OpenTelemetryConfig openTelemetryConfig;

	@Qualifier("langfuseTracer")
	private final Tracer langfuseTracer;

	@Qualifier("agentScopeLocalTracer")
	private final Tracer agentScopeLocalTracer;

	@Bean("agentScopeTracer")
	@Primary
	public Tracer agentScopeTracer() {
		return selectTracer();
	}

	@Override
	public void afterSingletonsInstantiated() {
		if (!properties.isEnabled()) {
			log.info("AgentScope native tracing is disabled by configuration.");
			return;
		}
		// 2.0: TelemetryTracer moved out of agentscope-core; ChatModelBase still reads
		// TracerRegistry (noop unless a core Tracer is registered). Per-agent tracing is
		// OtelTracingMiddleware, not wired in PR0.
		log.info("AgentScope 2.0 tracing uses OtelTracingMiddleware; TracerRegistry left as no-op.");
	}

	private Tracer selectTracer() {
		if (properties.isUseLangfuseTracer() && openTelemetryConfig.isEnabled()) {
			return langfuseTracer;
		}
		return agentScopeLocalTracer;
	}

}
