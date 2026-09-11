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
package com.sn68.agent.dataagent.service.aimodelconfig;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelEndpointDialect;
import com.sn68.agent.dataagent.enums.ModelPreservedReasoningPolicy;
import com.sn68.agent.dataagent.enums.ModelReasoningLevel;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelStructuredOutputMode;
import com.sn68.agent.dataagent.enums.ModelTemperaturePolicy;
import com.sn68.agent.dataagent.flow.FlowPatchSubmissionToolCallback;
import com.sn68.agent.dataagent.flow.FlowStructuredOutputProtocol;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.routing.RouteModelOutputProtocol;
import com.sn68.agent.dataagent.routing.RouteModelProtocol;
import com.sn68.agent.dataagent.routing.RouteModelCallTelemetry;
import com.sn68.agent.dataagent.routing.RouteModelTimingConnectionManager;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestCapabilities;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsOverride;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ModelRequestOptionsResolver;
import com.sn68.agent.dataagent.service.aimodelconfig.options.ResolvedModelRequestOptions;
import io.netty.channel.ChannelOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

/**
 * Dynamic模型组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
public class DynamicModelFactory {

	private static final String MODEL_CACHE_ENABLED_KEY = "spring.ai.agent.model-cache.enabled";

	private static final String MODEL_CACHE_MAX_SIZE_KEY = "spring.ai.agent.model-cache.max-size";

	private static final int DEFAULT_MODEL_CACHE_MAX_SIZE = 64;

	private static final RetryTemplate NO_RETRY_TEMPLATE = RetryTemplate.builder().maxAttempts(1).noBackoff().build();

	private static final double ROUTE_MODEL_TEMPERATURE = 0D;

	private final Environment environment;

	private final DataAgentProperties dataAgentProperties;

	private final ModelRequestOptionsResolver requestOptionsResolver;

	private final ConcurrentHashMap<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

	public DynamicModelFactory(Environment environment) {
		this(environment, new DataAgentProperties(), new ModelRequestOptionsResolver());
	}

	public DynamicModelFactory(Environment environment, DataAgentProperties dataAgentProperties) {
		this(environment, dataAgentProperties, new ModelRequestOptionsResolver());
	}

	@Autowired
	public DynamicModelFactory(Environment environment, DataAgentProperties dataAgentProperties,
			ModelRequestOptionsResolver requestOptionsResolver) {
		this.environment = environment;
		this.dataAgentProperties = dataAgentProperties == null ? new DataAgentProperties() : dataAgentProperties;
		this.requestOptionsResolver = requestOptionsResolver == null ? new ModelRequestOptionsResolver()
				: requestOptionsResolver;
	}

	/**
	 * 统一使用 OpenAiChatModel，通过 baseUrl 兼容多厂商。
	 */
	public ChatModel createChatModel(ModelConfigDTO config) {
		checkBasic(config);
		if (!chatModelCacheEnabled()) {
			return buildChatModel(config, false);
		}
		String cacheKey = chatModelCacheKey(config);
		ChatModel cached = chatModelCache.get(cacheKey);
		if (cached != null) {
			log.info("Reusing cached ChatModel instance. provider={}, model={}, baseUrl={}, cacheEnabled=true, cacheHit=true",
					config.getProvider(), config.getModelName(), config.getBaseUrl());
			return cached;
		}
		if (chatModelCache.size() >= chatModelCacheMaxSize()) {
			chatModelCache.clear();
			log.info("ChatModel cache cleared because max size was reached. maxSize={}", chatModelCacheMaxSize());
		}
		return chatModelCache.computeIfAbsent(cacheKey, key -> buildChatModel(config, true));
	}

	/**
	 * Creates the bounded model used for one FLOW extraction request. AUTO uses
	 * prompt JSON so a FLOW never sends an unverified provider response format.
	 */
	public FlowExtractionModel createFlowExtractionModel(ModelConfigDTO config, Duration timeout,
			long maxOutputTokens, String jsonSchema) {
		return createFlowExtractionModel(config, timeout, maxOutputTokens, jsonSchema,
				resolveFlowExtractionOutputMode(config));
	}

	/**
	 * Resolves the first FLOW response protocol from the persisted model setting.
	 * AUTO deliberately means no response_format request field.
	 */
	public ModelStructuredOutputMode resolveFlowExtractionOutputMode(ModelConfigDTO config) {
		ModelStructuredOutputMode configured = configuredStructuredOutputMode(config);
		return configured == ModelStructuredOutputMode.AUTO ? ModelStructuredOutputMode.PROMPT_JSON : configured;
	}

	/**
	 * Creates a bounded FLOW extraction model for the specified JSON response
	 * protocol. Provider reasoning and temperature settings remain model-owned.
	 */
	public FlowExtractionModel createFlowExtractionModel(ModelConfigDTO config, Duration timeout,
			long maxOutputTokens, String jsonSchema, ModelStructuredOutputMode outputMode) {
		checkBasic(config);
		Assert.hasText(jsonSchema, "jsonSchema must not be empty");
		ModelStructuredOutputMode effectiveOutputMode = outputMode == null || outputMode == ModelStructuredOutputMode.AUTO
				? resolveFlowExtractionOutputMode(config) : outputMode;
		if (effectiveOutputMode != ModelStructuredOutputMode.STRICT_JSON_SCHEMA
				&& effectiveOutputMode != ModelStructuredOutputMode.JSON_OBJECT
				&& effectiveOutputMode != ModelStructuredOutputMode.PROMPT_JSON) {
			throw new IllegalArgumentException("FLOW extraction output mode is invalid");
		}
		Duration requestTimeout = requirePositiveTimeout(timeout, "flow-extraction-timeout");
		int requestedMaxTokens = toSpringAiMaxTokens(maxOutputTokens);
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.maxOutputTokens(maxOutputTokens)
			.structuredOutputMode(effectiveOutputMode)
			.build();
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, override, null);
		validateFlowExtractionOptions(resolved, requestedMaxTokens, effectiveOutputMode);
		Duration connectTimeout = requestTimeout.compareTo(modelHttpConnectTimeout()) < 0 ? requestTimeout
				: modelHttpConnectTimeout();
		ChatModel model = buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, requestTimeout, connectTimeout,
				resolved, responseFormat(effectiveOutputMode, jsonSchema), false);
		return new FlowExtractionModel(model, effectiveOutputMode);
	}

	/**
	 * Creates the bounded model for a previously verified FLOW structured-output
	 * protocol. Sampling and reasoning stay model-owned: disable reasoning and
	 * send temperature 0 only when the dialect can enforce those preferences.
	 * This path is intentionally separate from normal chat and route transport.
	 */
	public FlowStructuredModel createFlowStructuredModel(ModelConfigDTO config, Duration timeout,
			long maxOutputTokens, String jsonSchema, FlowStructuredOutputProtocol protocol) {
		checkBasic(config);
		if (protocol == null || protocol == FlowStructuredOutputProtocol.NONE) {
			throw new IllegalArgumentException("FLOW structured output protocol is required");
		}
		if (protocol == FlowStructuredOutputProtocol.FUNCTION_CALL
				|| protocol == FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA) {
			Assert.hasText(jsonSchema, "jsonSchema must not be empty");
		}
		Duration requestTimeout = requirePositiveTimeout(timeout, "flow-structured-timeout");
		int requestedMaxTokens = toSpringAiMaxTokens(maxOutputTokens);
		ModelStructuredOutputMode outputMode = protocol == FlowStructuredOutputProtocol.FUNCTION_CALL ? null
				: flowStructuredOutputMode(protocol);
		ModelRequestCapabilities capabilities = requestOptionsResolver.capabilities(config);
		ModelRequestOptionsOverride.ModelRequestOptionsOverrideBuilder overrideBuilder = ModelRequestOptionsOverride.builder()
			.maxOutputTokens(maxOutputTokens)
			.preservedReasoningPolicy(ModelPreservedReasoningPolicy.DROP);
		if (Boolean.TRUE.equals(capabilities.temperatureSupported())) {
			overrideBuilder.temperature(0D).temperaturePolicy(ModelTemperaturePolicy.SEND);
		}
		if (Boolean.TRUE.equals(capabilities.reasoningDisableSupported())) {
			overrideBuilder.reasoningMode(ModelReasoningMode.DISABLED).reasoningLevel(ModelReasoningLevel.NONE);
		}
		if (outputMode != null) {
			overrideBuilder.structuredOutputMode(outputMode);
		}
		ModelRequestOptionsOverride override = overrideBuilder.build();
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, override, null);
		validateFlowStructuredOptions(resolved, requestedMaxTokens, outputMode);
		log.info("FLOW structured model options. provider={}, model={}, dialect={}, protocol={}, "
						+ "temperature={}, reasoningMode={}, disableSupported={}, temperatureSupported={}",
				config.getProvider(), config.getModelName(), resolved.endpointDialect(), protocol, resolved.temperature(),
				resolved.reasoningMode(), capabilities.reasoningDisableSupported(), capabilities.temperatureSupported());
		Duration connectTimeout = requestTimeout.compareTo(modelHttpConnectTimeout()) < 0 ? requestTimeout
				: modelHttpConnectTimeout();
		ResponseFormat responseFormat = protocol == FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA
				? responseFormat(outputMode, jsonSchema, "flow_patch")
				: protocol == FlowStructuredOutputProtocol.JSON_OBJECT ? responseFormat(outputMode, null, "flow_patch") : null;
		ToolCallback callback = protocol == FlowStructuredOutputProtocol.FUNCTION_CALL
				? new FlowPatchSubmissionToolCallback(jsonSchema) : null;
		ChatModel model = buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, requestTimeout, connectTimeout,
				resolved, responseFormat, false, callback,
				callback == null ? null : ToolChoiceBuilder.function(FlowPatchSubmissionToolCallback.NAME));
		return new FlowStructuredModel(model, protocol);
	}

	/**
	 * Creates a bounded, single-attempt model for structured runtime work.
	 */
	public ChatModel createBoundedStructuredModel(ModelConfigDTO config, Duration timeout, long maxTokens) {
		return createBoundedStructuredModel(config, timeout, maxTokens, null);
	}

	/**
	 * Creates a bounded structured model and applies the configured output preference
	 * when a real JSON Schema is available.
	 */
	public ChatModel createBoundedStructuredModel(ModelConfigDTO config, Duration timeout, long maxTokens,
			String jsonSchema) {
		checkBasic(config);
		if (maxTokens <= 0) {
			throw new IllegalArgumentException("maxTokens must be greater than zero");
		}
		long boundedTokens = maxTokens;
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.maxOutputTokens(boundedTokens)
			.build();
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, override, null);
		ResponseFormat responseFormat = boundedResponseFormat(resolved.structuredOutputMode(), jsonSchema);
		return buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, timeout, modelHttpConnectTimeout(), resolved,
				responseFormat, false);
	}

	/**
	 * Creates the bounded, single-attempt model used by deterministic SQL planning.
	 */
	public DeterministicPlannerModel createDeterministicPlannerModel(ModelConfigDTO config, Duration timeout,
			long maxOutputTokens, String jsonSchema) {
		return createDeterministicPlannerModel(config, timeout, maxOutputTokens, null, jsonSchema);
	}

	/**
	 * Creates a deterministic planner model using an optional endpoint capability snapshot.
	 */
	public DeterministicPlannerModel createDeterministicPlannerModel(ModelConfigDTO config, Duration timeout,
			long maxOutputTokens, ModelRequestCapabilities capabilities, String jsonSchema) {
		checkBasic(config);
		Assert.hasText(jsonSchema, "jsonSchema must not be empty");
		Duration requestTimeout = requirePositiveTimeout(timeout, "deterministic-planner-timeout");
		int requestedMaxTokens = toSpringAiMaxTokens(maxOutputTokens);
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.reasoningMode(ModelReasoningMode.DISABLED)
			.reasoningLevel(ModelReasoningLevel.NONE)
			.maxOutputTokens(maxOutputTokens)
			.temperature(0D)
			.temperaturePolicy(ModelTemperaturePolicy.SEND)
			.structuredOutputMode(preferredDeterministicOutputMode(config, capabilities))
			.preservedReasoningPolicy(ModelPreservedReasoningPolicy.DROP)
			.build();
		ResolvedModelRequestOptions resolved;
		try {
			resolved = requestOptionsResolver.resolve(config, override, capabilities);
		}
		catch (IllegalArgumentException ex) {
			throw new DeterministicPlannerModelCapabilityException(
					"Configured model cannot satisfy deterministic planner requirements", ex);
		}
		validateDeterministicOptions(resolved, requestedMaxTokens);
		ResponseFormat responseFormat = responseFormat(resolved.structuredOutputMode(), jsonSchema);
		Duration connectTimeout = requestTimeout.compareTo(modelHttpConnectTimeout()) < 0 ? requestTimeout
				: modelHttpConnectTimeout();
		ChatModel model = buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, requestTimeout, connectTimeout,
				resolved, responseFormat, false);
		return new DeterministicPlannerModel(model, resolved.structuredOutputMode());
	}

	/**
	 * Creates the single-attempt model used by route probing and disambiguation.
	 */
	public ChatModel createRouteModel(ModelConfigDTO config, Duration timeout, String jsonSchema) {
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.maxOutputTokens(256L)
			.temperature(ROUTE_MODEL_TEMPERATURE)
			.structuredOutputMode(ModelStructuredOutputMode.STRICT_JSON_SCHEMA)
			.build();
		return createRouteModel(config, timeout, modelHttpConnectTimeout(), override, null, jsonSchema);
	}

	/**
	 * Creates a route model from logical task settings and a previously probed capability
	 * snapshot. The route layer does not need to construct provider-specific request fields.
	 */
	public ChatModel createRouteModel(ModelConfigDTO config, Duration timeout, Duration connectTimeout,
			ModelRequestOptionsOverride taskOverride, ModelRequestCapabilities capabilities, String jsonSchema) {
		checkBasic(config);
		Assert.hasText(jsonSchema, "jsonSchema must not be empty");
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, taskOverride, capabilities);
		ResponseFormat responseFormat = responseFormat(resolved.structuredOutputMode(), jsonSchema);
		return buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, timeout, connectTimeout, resolved,
				responseFormat, false);
	}

	/**
	 * Exposes the same resolution used by model construction for route probes and diagnostics.
	 */
	public ResolvedModelRequestOptions resolveRequestOptions(ModelConfigDTO config,
			ModelRequestOptionsOverride taskOverride, ModelRequestCapabilities capabilities) {
		checkBasic(config);
		return requestOptionsResolver.resolve(config, taskOverride, capabilities);
	}

	/**
	 * Compatibility-friendly route entry point that still accepts the route protocol enum
	 * while keeping reasoning and provider fields in the model option resolver.
	 */
	public ChatModel createRouteModelForProtocol(ModelConfigDTO config, Duration timeout, Duration connectTimeout,
			RouteModelOutputProtocol protocol, ModelRequestOptionsOverride taskOverride,
			ModelRequestCapabilities capabilities, String jsonSchema) {
		if (protocol == null || protocol == RouteModelOutputProtocol.NONE) {
			throw new IllegalArgumentException("Route model output protocol is required");
		}
		ModelStructuredOutputMode outputMode = switch (protocol) {
			case FUNCTION_CALL -> ModelStructuredOutputMode.PROMPT_JSON;
			case STRICT_SCHEMA -> ModelStructuredOutputMode.STRICT_JSON_SCHEMA;
			case JSON_OBJECT -> ModelStructuredOutputMode.JSON_OBJECT;
			case PROMPT_JSON -> ModelStructuredOutputMode.PROMPT_JSON;
			case NONE -> throw new IllegalArgumentException("Route model output protocol is required");
		};
		ModelRequestOptionsOverride base = taskOverride == null ? ModelRequestOptionsOverride.none() : taskOverride;
		ModelRequestOptionsOverride effective = base.toBuilder()
			.maxOutputTokens(base.maxOutputTokens() == null ? 256L : base.maxOutputTokens())
			.temperature(base.temperature() == null ? ROUTE_MODEL_TEMPERATURE : base.temperature())
			.structuredOutputMode(outputMode)
			.build();
		String schema = jsonSchema == null ? RouteModelProtocol.JSON_SCHEMA : jsonSchema;
		if (protocol == RouteModelOutputProtocol.FUNCTION_CALL) {
			return createRouteFunctionCallingModel(config, timeout, connectTimeout, effective, capabilities, schema);
		}
		return createRouteModel(config, timeout, connectTimeout, effective, capabilities, schema);
	}

	private ChatModel createRouteFunctionCallingModel(ModelConfigDTO config, Duration timeout, Duration connectTimeout,
			ModelRequestOptionsOverride taskOverride, ModelRequestCapabilities capabilities, String jsonSchema) {
		checkBasic(config);
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, taskOverride, capabilities);
		ToolCallback callback = new RoutePlanSubmissionToolCallback(jsonSchema);
		return buildResolvedChatModel(config, false, NO_RETRY_TEMPLATE, timeout, connectTimeout, resolved, null, false,
				callback, ToolChoiceBuilder.function(RoutePlanSubmissionToolCallback.NAME));
	}

	/**
	 * Embedding 模型仍按调用创建，避免和对话模型缓存共用生命周期。
	 */
	public EmbeddingModel createEmbeddingModel(ModelConfigDTO config) {
		log.info("Creating NEW EmbeddingModel instance. Provider: {}, Model: {}, BaseUrl: {}", config.getProvider(),
				config.getModelName(), config.getBaseUrl());
		checkBasic(config);

		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config));

		if (StringUtils.hasText(config.getEmbeddingsPath())) {
			apiBuilder.embeddingsPath(config.getEmbeddingsPath());
		}

		OpenAiApi openAiApi = apiBuilder.build();
		return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(),
				RetryUtils.DEFAULT_RETRY_TEMPLATE);
	}

	/**
	 * Creates a bounded, single-attempt embedding model for route probes.
	 */
	public EmbeddingModel createRouteEmbeddingModel(ModelConfigDTO config, Duration timeout) {
		checkBasic(config);
		OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
			.apiKey(StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "")
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config, timeout))
			.webClientBuilder(getProxiedWebClientBuilder(config, timeout));
		if (StringUtils.hasText(config.getEmbeddingsPath())) {
			apiBuilder.embeddingsPath(config.getEmbeddingsPath());
		}
		return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED,
				OpenAiEmbeddingOptions.builder().model(config.getModelName()).build(), NO_RETRY_TEMPLATE);
	}

	/**
	 * 创建Dynamic模型。
	 */
	public OpenAiAudioTranscriptionModel createAudioTranscriptionModel(ModelConfigDTO config) {
		log.info("Creating NEW AudioTranscriptionModel instance. Provider: {}, Model: {}, BaseUrl: {}",
				config.getProvider(), config.getModelName(), config.getBaseUrl());
		checkBasic(config);
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		OpenAiAudioApi audioApi = OpenAiAudioApi.builder()
			.apiKey(apiKey)
			.baseUrl(config.getBaseUrl())
			.restClientBuilder(getProxiedRestClientBuilder(config))
			.webClientBuilder(getProxiedWebClientBuilder(config))
			.build();
		OpenAiAudioTranscriptionOptions options = OpenAiAudioTranscriptionOptions.builder()
			.model(config.getModelName())
			.responseFormat(OpenAiAudioApi.TranscriptResponseFormat.JSON)
			.temperature(config.getTemperature() == null ? null : config.getTemperature().floatValue())
			.build();
		return new OpenAiAudioTranscriptionModel(audioApi, options, NO_RETRY_TEMPLATE);
	}

	/**
	 * 创建Dynamic模型。
	 */
	public RestClient.Builder createRestClientBuilder(ModelConfigDTO config) {
		return getProxiedRestClientBuilder(config);
	}

	private ChatModel buildChatModel(ModelConfigDTO config, boolean cacheEnabled) {
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config);
		return buildResolvedChatModel(config, cacheEnabled, RetryUtils.DEFAULT_RETRY_TEMPLATE, modelHttpTimeout(),
				modelHttpConnectTimeout(), resolved, null, true);
	}

	private ChatModel buildChatModel(ModelConfigDTO config, boolean cacheEnabled, RetryTemplate retryTemplate,
			Integer maxTokens, Duration httpTimeout) {
		ModelRequestOptionsOverride override = ModelRequestOptionsOverride.builder()
			.maxOutputTokens(maxTokens == null ? null : maxTokens.longValue())
			.build();
		ResolvedModelRequestOptions resolved = requestOptionsResolver.resolve(config, override, null);
		return buildResolvedChatModel(config, cacheEnabled, retryTemplate, httpTimeout, modelHttpConnectTimeout(),
				resolved, null, true);
	}

	private ChatModel buildResolvedChatModel(ModelConfigDTO config, boolean cacheEnabled, RetryTemplate retryTemplate,
			Duration httpTimeout, Duration connectTimeout, ResolvedModelRequestOptions resolved,
			ResponseFormat responseFormat, boolean streamUsage) {
		return buildResolvedChatModel(config, cacheEnabled, retryTemplate, httpTimeout, connectTimeout, resolved,
				responseFormat, streamUsage, null, null);
	}

	private ChatModel buildResolvedChatModel(ModelConfigDTO config, boolean cacheEnabled, RetryTemplate retryTemplate,
			Duration httpTimeout, Duration connectTimeout, ResolvedModelRequestOptions resolved,
			ResponseFormat responseFormat, boolean streamUsage, ToolCallback routeCallback, Object toolChoice) {
		log.info("Creating NEW ChatModel instance. provider={}, model={}, baseUrl={}, cacheEnabled={}, cacheHit=false",
				config.getProvider(), config.getModelName(), config.getBaseUrl(), cacheEnabled);
		OpenAiApi openAiApi = buildChatApi(config, httpTimeout, connectTimeout, resolved.extraBody());
		OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(config.getModelName())
			.streamUsage(streamUsage);
		if (resolved.temperature() != null) {
			optionsBuilder.temperature(resolved.temperature());
		}
		if (resolved.maxOutputTokens() != null && resolved.tokenLimitMode() != null) {
			if (resolved.tokenLimitMode() == com.sn68.agent.dataagent.enums.ModelTokenLimitMode.MAX_COMPLETION_TOKENS) {
				optionsBuilder.maxCompletionTokens(resolved.maxOutputTokens());
			}
			else {
				optionsBuilder.maxTokens(resolved.maxOutputTokens());
			}
		}
		if (responseFormat != null) {
			optionsBuilder.responseFormat(responseFormat);
		}
		if (!resolved.extraBody().isEmpty()) {
			optionsBuilder.extraBody(resolved.extraBody());
		}
		if (StringUtils.hasText(resolved.reasoningEffort())) {
			optionsBuilder.reasoningEffort(resolved.reasoningEffort());
		}
		if (routeCallback != null) {
			optionsBuilder.toolCallbacks(routeCallback)
				.toolChoice(toolChoice)
				.parallelToolCalls(false)
				.internalToolExecutionEnabled(false);
		}
		return OpenAiChatModel.builder().openAiApi(openAiApi).defaultOptions(optionsBuilder.build())
			.retryTemplate(retryTemplate).build();
	}

	private static final class RoutePlanSubmissionToolCallback implements ToolCallback {

		private static final String NAME = "submit_route_plan";

		private final ToolDefinition definition;

		private RoutePlanSubmissionToolCallback(String jsonSchema) {
			this.definition = ToolDefinition.builder().name(NAME)
				.description("提交经过候选句柄校验的路由计划，不执行任何业务能力")
				.inputSchema(jsonSchema).build();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			throw new IllegalStateException("Route plan submission callback must never execute a business tool");
		}

		@Override
		public String call(String toolInput, ToolContext toolContext) {
			throw new IllegalStateException("Route plan submission callback must never execute a business tool");
		}

	}

	private OpenAiApi buildChatApi(ModelConfigDTO config, Duration httpTimeout, Duration connectTimeout,
			Map<String, Object> extraBody) {
		String apiKey = StringUtils.hasText(config.getApiKey()) ? config.getApiKey() : "";
		RestClient.Builder restClientBuilder = getProxiedRestClientBuilder(config, httpTimeout, connectTimeout);
		WebClient.Builder webClientBuilder = getProxiedWebClientBuilder(config, httpTimeout, connectTimeout);
		String completionsPath = StringUtils.hasText(config.getCompletionsPath()) ? config.getCompletionsPath()
				: "/v1/chat/completions";
		String embeddingsPath = StringUtils.hasText(config.getEmbeddingsPath()) ? config.getEmbeddingsPath()
				: "/v1/embeddings";
		if (extraBody == null || extraBody.isEmpty()) {
			OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(apiKey).baseUrl(config.getBaseUrl())
				.restClientBuilder(restClientBuilder).webClientBuilder(webClientBuilder).completionsPath(completionsPath);
			return apiBuilder.build();
		}
		return new ProviderFieldsOpenAiApi(config.getBaseUrl(), apiKey, completionsPath, embeddingsPath,
				restClientBuilder, webClientBuilder, extraBody);
	}

	private ResponseFormat responseFormat(ModelStructuredOutputMode mode, String jsonSchema) {
		return responseFormat(mode, jsonSchema, "route_decision");
	}

	private ResponseFormat responseFormat(ModelStructuredOutputMode mode, String jsonSchema, String schemaName) {
		return switch (mode) {
			case STRICT_JSON_SCHEMA -> {
				Assert.hasText(jsonSchema, "jsonSchema must not be empty");
				Assert.hasText(schemaName, "schemaName must not be empty");
				yield ResponseFormat.builder()
					.type(ResponseFormat.Type.JSON_SCHEMA)
					.jsonSchema(ResponseFormat.JsonSchema.builder().name(schemaName).schema(jsonSchema).strict(true)
						.build())
					.build();
			}
			case JSON_OBJECT -> ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build();
			case PROMPT_JSON, AUTO -> null;
		};
	}

	private ResponseFormat boundedResponseFormat(ModelStructuredOutputMode mode, String jsonSchema) {
		if (mode == ModelStructuredOutputMode.STRICT_JSON_SCHEMA && !StringUtils.hasText(jsonSchema)) {
			throw new IllegalArgumentException("STRICT_JSON_SCHEMA requires a JSON schema");
		}
		return responseFormat(mode, jsonSchema);
	}

	private ModelStructuredOutputMode preferredDeterministicOutputMode(ModelConfigDTO config,
			ModelRequestCapabilities capabilities) {
		if (capabilities == null || capabilities.structuredOutputModes() == null) {
			ModelStructuredOutputMode configured = configuredStructuredOutputMode(config);
			if (configured != ModelStructuredOutputMode.AUTO) {
				return configured;
			}
			return isCustomDialect(config) ? ModelStructuredOutputMode.PROMPT_JSON
					: ModelStructuredOutputMode.STRICT_JSON_SCHEMA;
		}
		if (capabilities.structuredOutputModes().contains(ModelStructuredOutputMode.STRICT_JSON_SCHEMA)) {
			return ModelStructuredOutputMode.STRICT_JSON_SCHEMA;
		}
		if (capabilities.structuredOutputModes().contains(ModelStructuredOutputMode.JSON_OBJECT)) {
			return ModelStructuredOutputMode.JSON_OBJECT;
		}
		return ModelStructuredOutputMode.PROMPT_JSON;
	}

	private ModelStructuredOutputMode configuredStructuredOutputMode(ModelConfigDTO config) {
		if (config == null || !StringUtils.hasText(config.getStructuredOutputMode())) {
			return ModelStructuredOutputMode.AUTO;
		}
		try {
			return ModelStructuredOutputMode.valueOf(config.getStructuredOutputMode().trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			log.warn("Unknown structured output mode on the model config, falling back to AUTO. modelConfigId={}, "
					+ "structuredOutputMode={}", config.getId(), config.getStructuredOutputMode());
			return ModelStructuredOutputMode.AUTO;
		}
	}

	private boolean isCustomDialect(ModelConfigDTO config) {
		return config != null && ModelEndpointDialect.CUSTOM.name().equalsIgnoreCase(config.getEndpointDialect());
	}

	private void validateFlowExtractionOptions(ResolvedModelRequestOptions resolved, int requestedMaxTokens,
			ModelStructuredOutputMode outputMode) {
		if (resolved.maxOutputTokens() == null || resolved.maxOutputTokens() != requestedMaxTokens) {
			throw new IllegalArgumentException("Configured model cannot enforce the FLOW extraction output token limit");
		}
		if (resolved.structuredOutputMode() != outputMode) {
			throw new IllegalArgumentException("Configured model cannot enforce the FLOW extraction response protocol");
		}
	}

	private ModelStructuredOutputMode flowStructuredOutputMode(FlowStructuredOutputProtocol protocol) {
		return switch (protocol) {
			case FUNCTION_CALL -> throw new IllegalArgumentException("Function Calling does not use response_format");
			case STRICT_JSON_SCHEMA -> ModelStructuredOutputMode.STRICT_JSON_SCHEMA;
			case JSON_OBJECT -> ModelStructuredOutputMode.JSON_OBJECT;
			case NONE -> throw new IllegalArgumentException("FLOW structured output protocol is required");
		};
	}

	private void validateFlowStructuredOptions(ResolvedModelRequestOptions resolved, int requestedMaxTokens,
			ModelStructuredOutputMode outputMode) {
		if (resolved.maxOutputTokens() == null || resolved.maxOutputTokens() != requestedMaxTokens) {
			throw new IllegalArgumentException("Configured model cannot enforce the FLOW structured output token limit");
		}
		if (outputMode != null && resolved.structuredOutputMode() != outputMode) {
			throw new IllegalArgumentException("Configured model cannot enforce the FLOW structured response protocol");
		}
	}

	private void validateDeterministicOptions(ResolvedModelRequestOptions resolved, int requestedMaxTokens) {
		if (resolved.reasoningMode() != ModelReasoningMode.DISABLED
				|| resolved.reasoningLevel() != ModelReasoningLevel.NONE
				|| resolved.reasoningBudgetTokens() != null) {
			throw new DeterministicPlannerModelCapabilityException(
					"Configured model cannot disable reasoning for deterministic planning");
		}
		if (resolved.maxOutputTokens() == null || resolved.maxOutputTokens() != requestedMaxTokens) {
			throw new DeterministicPlannerModelCapabilityException(
					"Configured model cannot enforce the deterministic output token limit");
		}
		if (resolved.temperature() == null || Double.compare(resolved.temperature(), 0D) != 0) {
			throw new DeterministicPlannerModelCapabilityException(
					"Configured model cannot enforce temperature zero for deterministic planning");
		}
		if (resolved.preservedReasoningPolicy() != ModelPreservedReasoningPolicy.DROP) {
			throw new DeterministicPlannerModelCapabilityException(
					"Configured model cannot drop preserved reasoning for deterministic planning");
		}
	}

	private static Integer toSpringAiMaxTokens(Long maxTokens) {
		if (maxTokens == null) {
			return null;
		}
		if (maxTokens <= 0) {
			throw new IllegalArgumentException("最大输出 Token 必须大于 0");
		}
		if (maxTokens > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("最大输出 Token 超出 Spring AI 当前支持范围：" + Integer.MAX_VALUE);
		}
		return maxTokens.intValue();
	}

	private static void checkBasic(ModelConfigDTO config) {
		Assert.hasText(config.getBaseUrl(), "baseUrl must not be empty");
		if (!"custom".equalsIgnoreCase(config.getProvider())) {
			Assert.hasText(config.getApiKey(), "apiKey must not be empty");
		}
		Assert.hasText(config.getModelName(), "modelName must not be empty");
	}

	private boolean chatModelCacheEnabled() {
		return environment == null || environment.getProperty(MODEL_CACHE_ENABLED_KEY, Boolean.class, true);
	}

	private int chatModelCacheMaxSize() {
		if (environment == null) {
			return DEFAULT_MODEL_CACHE_MAX_SIZE;
		}
		Integer configured = environment.getProperty(MODEL_CACHE_MAX_SIZE_KEY, Integer.class);
		return configured == null || configured < 1 ? DEFAULT_MODEL_CACHE_MAX_SIZE : configured;
	}

	private String chatModelCacheKey(ModelConfigDTO config) {
		return String.join("|", text(config.getId()), text(config.getProvider()), text(config.getBaseUrl()),
				text(config.getModelName()), text(config.getEndpointDialect()), text(config.getCapabilityProfile()),
				text(config.getReasoningProtocol()),
				text(config.getReasoningMode()), text(config.getReasoningLevel()), text(config.getReasoningBudgetTokens()),
				text(config.getTokenLimitMode()), text(config.getTemperaturePolicy()),
				text(config.getStructuredOutputMode()), text(config.getPreservedReasoningPolicy()),
				text(config.getTemperature()), text(config.getMaxTokens()),
				text(config.getContextWindowTokens()), text(config.getCompletionsPath()),
				text(config.getProxyEnabled()), text(config.getProxyHost()), text(config.getProxyPort()),
				text(config.getProxyUsername()), sha256(text(config.getApiKey())),
				sha256(text(config.getProxyPassword())));
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder builder = new StringBuilder(hash.length * 2);
			for (byte item : hash) {
				builder.append(String.format("%02x", item));
			}
			return builder.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config) {
		return getProxiedRestClientBuilder(config, modelHttpTimeout());
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config, Duration httpTimeout) {
		return getProxiedRestClientBuilder(config, httpTimeout, modelHttpConnectTimeout());
	}

	private RestClient.Builder getProxiedRestClientBuilder(ModelConfigDTO config, Duration httpTimeout,
			Duration connectTimeout) {
		ConnectionConfig connectionConfig = ConnectionConfig.custom()
			.setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMillis(connectTimeout)))
			.build();
		HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
				.setDefaultConnectionConfig(connectionConfig)
				.build();
		HttpClientBuilder httpClientBuilder = HttpClients.custom()
			.setConnectionManager(new RouteModelTimingConnectionManager(connectionManager))
			.addRequestInterceptorLast((request, entityDetails, context) ->
				RouteModelCallTelemetry.markHttpRequest(entityDetails))
			.addResponseInterceptorLast((response, entityDetails, context) ->
				RouteModelCallTelemetry.markFirstResponse(response));
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return restClientBuilder(httpClientBuilder.build(), httpTimeout);
		}

		log.info("Model [{}] is using SYNC proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());
		BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
		if (StringUtils.hasText(config.getProxyUsername())) {
			log.info("Enabling Basic Auth for SYNC proxy, user: {}", config.getProxyUsername());
			credsProvider.setCredentials(new AuthScope(config.getProxyHost(), config.getProxyPort()),
					new UsernamePasswordCredentials(config.getProxyUsername(),
							config.getProxyPassword().toCharArray()));
		}

		CloseableHttpClient httpClient = httpClientBuilder
			.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()))
			.setDefaultCredentialsProvider(credsProvider)
			.build();

		return restClientBuilder(httpClient, httpTimeout);
	}

	private RestClient.Builder restClientBuilder(CloseableHttpClient httpClient) {
		return restClientBuilder(httpClient, modelHttpTimeout());
	}

	private RestClient.Builder restClientBuilder(CloseableHttpClient httpClient, Duration httpTimeout) {
		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(requirePositiveTimeout(httpTimeout, "model-http-timeout"));
		return RestClient.builder().requestFactory(requestFactory);
	}

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config) {
		return getProxiedWebClientBuilder(config, modelHttpTimeout());
	}

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config, Duration httpTimeout) {
		return getProxiedWebClientBuilder(config, httpTimeout, modelHttpConnectTimeout());
	}

	private WebClient.Builder getProxiedWebClientBuilder(ModelConfigDTO config, Duration httpTimeout,
			Duration connectTimeout) {
		HttpClient nettyClient = HttpClient.create()
			.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis(connectTimeout))
			.responseTimeout(requirePositiveTimeout(httpTimeout, "model-http-timeout"));
		if (config.getProxyEnabled() == null || !config.getProxyEnabled()) {
			return WebClient.builder().clientConnector(new ReactorClientHttpConnector(nettyClient));
		}

		log.info("Model [{}] is using ASYNC (Netty) proxy -> {}:{}", config.getModelName(), config.getProxyHost(),
				config.getProxyPort());
		nettyClient = nettyClient.proxy(p -> {
			ProxyProvider.Builder proxyBuilder = p.type(ProxyProvider.Proxy.HTTP)
				.host(config.getProxyHost())
				.port(config.getProxyPort());

			if (StringUtils.hasText(config.getProxyUsername())) {
				log.info("Enabling Basic Auth for ASYNC proxy, user: {}", config.getProxyUsername());
				proxyBuilder.username(config.getProxyUsername()).password(s -> config.getProxyPassword());
			}
		});

		return WebClient.builder().clientConnector(new ReactorClientHttpConnector(nettyClient));
	}

	private Duration modelHttpTimeout() {
		return requirePositiveTimeout(dataAgentProperties.getRuntime().getModelTimeout(), "runtime.model-timeout");
	}

	private Duration modelHttpConnectTimeout() {
		return requirePositiveTimeout(dataAgentProperties.getRuntime().getModelHttpConnectTimeout(),
				"runtime.model-http-connect-timeout");
	}

	private int connectTimeoutMillis(Duration timeout) {
		timeout = requirePositiveTimeout(timeout, "model-http-connect-timeout");
		long timeoutMillis = timeout.toMillis();
		if (timeoutMillis < 1 || timeoutMillis > Integer.MAX_VALUE) {
			throw new IllegalStateException("model-http-connect-timeout must be between 1 and "
					+ Integer.MAX_VALUE + " milliseconds");
		}
		return (int) timeoutMillis;
	}

	private Duration requirePositiveTimeout(Duration timeout, String propertyName) {
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalStateException(propertyName + " must be greater than zero");
		}
		return timeout;
	}

	/**
	 * Spring AI 1.1 filters OpenAiChatOptions.extraBody while merging options into
	 * ChatCompletionRequest. Inject the resolved provider fields at the API boundary
	 * so they remain top-level OpenAI-compatible request properties.
	 */
	private static final class ProviderFieldsOpenAiApi extends OpenAiApi {

		private final Map<String, Object> providerFields;

		private ProviderFieldsOpenAiApi(String baseUrl, String apiKey, String completionsPath, String embeddingsPath,
				RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder, Map<String, Object> providerFields) {
			super(baseUrl, new SimpleApiKey(apiKey), new LinkedMultiValueMap<>(), completionsPath, embeddingsPath,
				restClientBuilder, webClientBuilder, RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
			this.providerFields = Map.copyOf(providerFields);
		}

		@Override
		public ResponseEntity<OpenAiApi.ChatCompletion> chatCompletionEntity(OpenAiApi.ChatCompletionRequest request) {
			mergeProviderFields(request);
			return super.chatCompletionEntity(request);
		}

		@Override
		public ResponseEntity<OpenAiApi.ChatCompletion> chatCompletionEntity(OpenAiApi.ChatCompletionRequest request,
				MultiValueMap<String, String> additionalHttpHeader) {
			mergeProviderFields(request);
			return super.chatCompletionEntity(request, additionalHttpHeader);
		}

		@Override
		public Flux<OpenAiApi.ChatCompletionChunk> chatCompletionStream(OpenAiApi.ChatCompletionRequest request) {
			mergeProviderFields(request);
			return super.chatCompletionStream(request);
		}

		@Override
		public Flux<OpenAiApi.ChatCompletionChunk> chatCompletionStream(OpenAiApi.ChatCompletionRequest request,
				MultiValueMap<String, String> additionalHttpHeader) {
			mergeProviderFields(request);
			return super.chatCompletionStream(request, additionalHttpHeader);
		}

		private void mergeProviderFields(OpenAiApi.ChatCompletionRequest request) {
			request.extraBody().putAll(providerFields);
		}

	}

}
