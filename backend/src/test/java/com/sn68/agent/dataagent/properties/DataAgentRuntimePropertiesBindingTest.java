/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

import com.sn68.agent.dataagent.constant.Constant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DataAgentRuntimePropertiesBindingTest {

	@Test
	void bindsAllRuntimeProperties() {
		Map<String, Object> values = Map.ofEntries(
				Map.entry("spring.ai.agent.runtime.total-timeout", "150s"),
				Map.entry("spring.ai.agent.runtime.model-timeout", "40s"),
				Map.entry("spring.ai.agent.runtime.tool-timeout", "25s"),
				Map.entry("spring.ai.agent.runtime.finish-buffer", "3s"),
				Map.entry("spring.ai.agent.runtime.model-http-connect-timeout", "4s"),
				Map.entry("spring.ai.agent.runtime.routing.total-timeout", "2s"),
				Map.entry("spring.ai.agent.runtime.routing.model-timeout", "1200ms"),
				Map.entry("spring.ai.agent.runtime.routing.vector-timeout", "300ms"),
				Map.entry("spring.ai.agent.runtime.routing.model-min-start", "500ms"),
				Map.entry("spring.ai.agent.runtime.routing.finish-buffer", "200ms"),
				Map.entry("spring.ai.agent.runtime.routing.model-probe-timeout", "20s"),
				Map.entry("spring.ai.agent.runtime.routing.model-probe-connect-timeout", "2s"),
				Map.entry("spring.ai.agent.runtime.routing.embedding-probe-timeout", "25s"),
				Map.entry("spring.ai.agent.runtime.deterministic.total-timeout", "18s"),
				Map.entry("spring.ai.agent.runtime.deterministic.planner-timeout", "7s"),
				Map.entry("spring.ai.agent.runtime.deterministic.sql-timeout", "5s"),
				Map.entry("spring.ai.agent.runtime.deterministic.max-output-tokens", 1536),
				Map.entry("spring.ai.agent.runtime.deterministic.max-attempts", 1),
				Map.entry("spring.ai.agent.runtime.deterministic.finish-buffer", "500ms"),
				Map.entry("spring.ai.agent.runtime.react-max-iterations", 8),
				Map.entry("spring.ai.agent.runtime.no-progress-max-completed-identical-calls", 3),
				Map.entry("spring.ai.agent.runtime.stale-turn-recovery-enabled", true),
				Map.entry("spring.ai.agent.runtime.stale-turn-grace", "90s"),
				Map.entry("spring.ai.agent.runtime.stale-turn-recovery-batch-size", 50));
		Binder binder = new Binder(new MapConfigurationPropertySource(values));

		DataAgentProperties.Runtime runtime = binder
			.bind(Constant.PROJECT_PROPERTIES_PREFIX, Bindable.of(DataAgentProperties.class))
			.orElseThrow(() -> new IllegalStateException("Runtime properties were not bound"))
			.getRuntime();

		assertEquals(Duration.ofSeconds(150), runtime.getTotalTimeout());
		assertEquals(Duration.ofSeconds(40), runtime.getModelTimeout());
		assertEquals(Duration.ofSeconds(25), runtime.getToolTimeout());
		assertEquals(Duration.ofSeconds(3), runtime.getFinishBuffer());
		assertEquals(Duration.ofSeconds(4), runtime.getModelHttpConnectTimeout());
		assertEquals(Duration.ofSeconds(2), runtime.getRouting().getTotalTimeout());
		assertEquals(Duration.ofMillis(1200), runtime.getRouting().getModelTimeout());
		assertEquals(Duration.ofMillis(300), runtime.getRouting().getVectorTimeout());
		assertEquals(Duration.ofMillis(500), runtime.getRouting().getModelMinStart());
		assertEquals(Duration.ofMillis(200), runtime.getRouting().getFinishBuffer());
		assertEquals(Duration.ofSeconds(20), runtime.getRouting().getModelProbeTimeout());
		assertEquals(Duration.ofSeconds(2), runtime.getRouting().getModelProbeConnectTimeout());
		assertEquals(Duration.ofSeconds(25), runtime.getRouting().getEmbeddingProbeTimeout());
		assertEquals(Duration.ofSeconds(18), runtime.getDeterministic().getTotalTimeout());
		assertEquals(Duration.ofSeconds(7), runtime.getDeterministic().getPlannerTimeout());
		assertEquals(Duration.ofSeconds(5), runtime.getDeterministic().getSqlTimeout());
		assertEquals(1536, runtime.getDeterministic().getMaxOutputTokens());
		assertEquals(1, runtime.getDeterministic().getMaxAttempts());
		assertEquals(Duration.ofMillis(500), runtime.getDeterministic().getFinishBuffer());
		assertEquals(8, runtime.getReactMaxIterations());
		assertEquals(3, runtime.getNoProgressMaxCompletedIdenticalCalls());
		assertEquals(true, runtime.isStaleTurnRecoveryEnabled());
		assertEquals(Duration.ofSeconds(90), runtime.getStaleTurnGrace());
		assertEquals(50, runtime.getStaleTurnRecoveryBatchSize());
	}

	@Test
	void rejectsModelMinimumStartAboveModelTimeout() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("spring.ai.agent.runtime.routing.model-timeout=500ms",
					"spring.ai.agent.runtime.routing.model-min-start=600ms")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.hasMessageContaining("routing.model-timeout + routing.vector-timeout");
			});
	}

	@Test
	void rejectsStageTimeoutOutsideUsableRouteBudget() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("spring.ai.agent.runtime.routing.total-timeout=500ms",
					"spring.ai.agent.runtime.routing.finish-buffer=100ms",
					"spring.ai.agent.runtime.routing.model-timeout=450ms")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.hasMessageContaining("routing.model-timeout + routing.vector-timeout");
			});
	}

	@Test
	void rejectsCombinedVectorAndModelTimeoutOutsideUsableRouteBudget() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("spring.ai.agent.runtime.routing.total-timeout=1500ms",
					"spring.ai.agent.runtime.routing.finish-buffer=150ms",
					"spring.ai.agent.runtime.routing.vector-timeout=700ms",
					"spring.ai.agent.runtime.routing.model-timeout=800ms")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.hasMessageContaining("routing.model-timeout + routing.vector-timeout");
			});
	}

	@Test
	void rejectsNegativeRoutingTimeout() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("spring.ai.agent.runtime.routing.vector-timeout=-1ms")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.hasMessageContaining("routing timeouts must be greater than zero");
			});
	}

	@Test
	void rejectsDeterministicStagesOutsideTotalBudget() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues("spring.ai.agent.runtime.deterministic.total-timeout=10s",
					"spring.ai.agent.runtime.deterministic.planner-timeout=6s",
					"spring.ai.agent.runtime.deterministic.sql-timeout=4s",
					"spring.ai.agent.runtime.deterministic.finish-buffer=1s")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.hasMessageContaining(
							"deterministic stage timeouts must fit within total-timeout after finish-buffer");
			});
	}

	@Test
	void bindsAllFlowProperties() {
		Map<String, Object> values = Map.of(
				"spring.ai.agent.flow.extract-timeout", "9s",
				"spring.ai.agent.flow.extract-max-tokens", 1200,
				"spring.ai.agent.flow.max-parallel-resolvers", 5,
				"spring.ai.agent.flow.max-resolver-waves", 6,
				"spring.ai.agent.flow.max-fan-out-items", 30,
				"spring.ai.agent.flow.executor-queue-capacity", 40);
		Binder binder = new Binder(new MapConfigurationPropertySource(values));

		DataAgentProperties.Flow flow = binder
			.bind(Constant.PROJECT_PROPERTIES_PREFIX, Bindable.of(DataAgentProperties.class))
			.orElseThrow(() -> new IllegalStateException("Flow properties were not bound"))
			.getFlow();

		assertEquals(Duration.ofSeconds(9), flow.getExtractTimeout());
		assertEquals(1200, flow.getExtractMaxTokens());
		assertEquals(5, flow.getMaxParallelResolvers());
		assertEquals(6, flow.getMaxResolverWaves());
		assertEquals(30, flow.getMaxFanOutItems());
		assertEquals(40, flow.getExecutorQueueCapacity());
	}

	@Test
	void bindsWebEvidenceFetchAndOrigins() {
		Map<String, Object> values = Map.of(
				"spring.ai.agent.web-evidence.fetch-enabled", true,
				"spring.ai.agent.web-evidence.app-origins",
				"http://10.0.0.1:31770,https://i.example.com");
		Binder binder = new Binder(new MapConfigurationPropertySource(values));

		DataAgentProperties properties = binder
			.bind(Constant.PROJECT_PROPERTIES_PREFIX, Bindable.of(DataAgentProperties.class))
			.orElseThrow(() -> new IllegalStateException("Web evidence properties were not bound"));

		assertEquals(true, properties.getWebEvidence().isFetchEnabled());
		assertEquals(List.of("http://10.0.0.1:31770", "https://i.example.com"),
				properties.getWebEvidence().getAppOrigins());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(DataAgentProperties.class)
	static class PropertiesConfiguration {
	}

}
