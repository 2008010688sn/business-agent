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
package com.sn68.agent.dataagent.agentscope.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeModelRequestException;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeToolMetrics;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.service.security.UntrustedContentBoundary;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageContext;
import com.sn68.agent.dataagent.service.tokenusage.AgentTokenUsageService;
import com.sn68.agent.dataagent.service.tokenusage.AgentUsageReservation;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeRetryPolicy.ModelStreamStartedException;
import com.sn68.agent.dataagent.agentscope.runtime.ModelHttpException;
import com.sn68.agent.dataagent.agentscope.memory.MsgUtils;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Minimal bridge that lets AgentScope reuse the project's Spring AI ChatModel.
 */
@Slf4j
public class SpringAiAgentScopeModel extends ChatModelBase {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private static final String PARTIAL_TOOL_NAME = "__fragment__";

	/** 上下文压缩摘要的来源标识，让模型知道这段数据是被摘要过的历史，而不是某个具体工具的返回。 */
	private static final String COMPRESSED_HISTORY_SOURCE = "context_compression";

	private final org.springframework.ai.chat.model.ChatModel delegate;

	private final String modelName;

	private final Map<String, ToolCallback> toolCallbacks;

	private final ObjectMapper objectMapper;

	private final AgentTokenUsageService tokenUsageService;

	private final AgentTokenUsageContext usageContext;

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final AgentRuntimeToolMetrics runtimeToolMetrics;

	private final boolean strictToolNameValidation;

	public SpringAiAgentScopeModel(org.springframework.ai.chat.model.ChatModel delegate, String modelName,
			Map<String, ToolCallback> toolCallbacks, ObjectMapper objectMapper) {
		this(delegate, modelName, toolCallbacks, objectMapper, null, null, null, null);
	}

	public SpringAiAgentScopeModel(org.springframework.ai.chat.model.ChatModel delegate, String modelName,
			Map<String, ToolCallback> toolCallbacks, ObjectMapper objectMapper,
			AgentTokenUsageService tokenUsageService, AgentTokenUsageContext usageContext) {
		this(delegate, modelName, toolCallbacks, objectMapper, tokenUsageService, usageContext, null, null);
	}

	public SpringAiAgentScopeModel(org.springframework.ai.chat.model.ChatModel delegate, String modelName,
			Map<String, ToolCallback> toolCallbacks, ObjectMapper objectMapper,
			AgentTokenUsageService tokenUsageService, AgentTokenUsageContext usageContext,
			DataAgentAsyncContextBridge asyncContextBridge) {
		this(delegate, modelName, toolCallbacks, objectMapper, tokenUsageService, usageContext, asyncContextBridge, null);
	}

	public SpringAiAgentScopeModel(org.springframework.ai.chat.model.ChatModel delegate, String modelName,
			Map<String, ToolCallback> toolCallbacks, ObjectMapper objectMapper,
			AgentTokenUsageService tokenUsageService, AgentTokenUsageContext usageContext,
			DataAgentAsyncContextBridge asyncContextBridge, AgentRuntimeToolMetrics runtimeToolMetrics) {
		this(delegate, modelName, toolCallbacks, objectMapper, tokenUsageService, usageContext, asyncContextBridge,
				runtimeToolMetrics, false);
	}

	public SpringAiAgentScopeModel(org.springframework.ai.chat.model.ChatModel delegate, String modelName,
			Map<String, ToolCallback> toolCallbacks, ObjectMapper objectMapper,
			AgentTokenUsageService tokenUsageService, AgentTokenUsageContext usageContext,
			DataAgentAsyncContextBridge asyncContextBridge, AgentRuntimeToolMetrics runtimeToolMetrics,
			boolean strictToolNameValidation) {
		this.delegate = delegate;
		this.modelName = modelName;
		this.toolCallbacks = toolCallbacks == null ? Collections.emptyMap() : Map.copyOf(toolCallbacks);
		this.objectMapper = objectMapper;
		this.tokenUsageService = tokenUsageService;
		this.usageContext = usageContext;
		this.asyncContextBridge = asyncContextBridge;
		this.runtimeToolMetrics = runtimeToolMetrics;
		this.strictToolNameValidation = strictToolNameValidation;
	}

	@Override
	protected Flux<ChatResponse> doStream(List<Msg> messages, List<ToolSchema> toolSchemas,
			GenerateOptions generateOptions) {
		validateToolNames(toolSchemas);
		List<String> availableToolNames = resolveToolNames(toolSchemas);
		List<Message> promptMessages = new ArrayList<>();
		for (Msg message : messages) {
			Message springMessage = toSpringMessage(message, availableToolNames);
			if (springMessage != null) {
				promptMessages.add(springMessage);
			}
		}
		Prompt prompt = new Prompt(promptMessages, buildChatOptions(toolSchemas, generateOptions));
		long estimatedPromptTokens = estimatePromptTokens(messages);
		DataAgentAsyncContextBridge.Snapshot asyncContext = captureAsyncContext();
		return Flux.defer(() -> {
			long start = System.nanoTime();
			AtomicBoolean streamStarted = new AtomicBoolean(false);
			String modelSignature = modelInputSignature(messages);
			if (runtimeToolMetrics != null
					&& !runtimeToolMetrics.tryRecordModelCall(modelSignature, estimatedPromptTokens)) {
				return Flux.error(runtimeToolMetrics.modelBudgetExceededException(estimatedPromptTokens));
			}
			AtomicBoolean recorded = new AtomicBoolean(false);
			AtomicReference<Usage> latestUsage = new AtomicReference<>();
			AgentUsageReservation reservation;
			try {
				reservation = supplyWithAsyncContext(asyncContext, () -> preCheckAndReserve(estimatedPromptTokens));
			}
			catch (RuntimeException ex) {
				if (runtimeToolMetrics != null) {
					runtimeToolMetrics.recordModelFailure(modelSignature, estimatedPromptTokens, elapsedMs(start));
				}
				runWithAsyncContext(asyncContext,
						() -> recordUnknownUsage(estimatedPromptTokens, ex, AgentUsageReservation.empty(),
								elapsedMs(start)));
				return Flux.error(ex);
			}
			AgentUsageReservation finalReservation = reservation;
			return this.delegate.stream(prompt)
				.publishOn(Schedulers.boundedElastic())
				.doOnNext(ignored -> streamStarted.set(true))
				.map(response -> {
					Usage usage = springUsage(response);
					if (usage != null) {
						latestUsage.set(usage);
					}
					return toAgentScopeResponse(response, availableToolNames);
				})
				.doOnComplete(() -> {
					if (runtimeToolMetrics != null) {
						Usage usage = latestUsage.get();
						runtimeToolMetrics.recordModelUsage(usage == null ? estimatedPromptTokens : safeToken(usage.getPromptTokens()),
								usage == null ? 0L : safeToken(usage.getCompletionTokens()), elapsedMs(start));
					}
					if (recorded.compareAndSet(false, true)) {
						runWithAsyncContext(asyncContext, () -> {
							Usage usage = latestUsage.get();
							if (usage != null) {
								recordActualUsage(estimatedPromptTokens, usage, AgentTokenUsageService.STATUS_SUCCESS,
										finalReservation, elapsedMs(start));
							}
							else {
								recordEstimatedUsage(estimatedPromptTokens, finalReservation, elapsedMs(start));
							}
						});
					}
				})
				.doOnError(error -> {
					if (runtimeToolMetrics != null) {
						runtimeToolMetrics.recordModelFailure(modelSignature, estimatedPromptTokens, elapsedMs(start));
					}
					if (recorded.compareAndSet(false, true)) {
						runWithAsyncContext(asyncContext, () -> {
							Usage usage = latestUsage.get();
							if (usage != null) {
								recordActualUsage(estimatedPromptTokens, usage, AgentTokenUsageService.STATUS_FAILED,
										finalReservation, elapsedMs(start));
							}
							else {
								recordUnknownUsage(estimatedPromptTokens, error, finalReservation, elapsedMs(start));
							}
						});
					}
				})
				.onErrorMap(ModelHttpException::wrapIfHttp)
				.onErrorMap(error -> streamStarted.get() && !(error instanceof ModelStreamStartedException)
						? new ModelStreamStartedException(error) : error);
		});
	}

	private void validateToolNames(List<ToolSchema> toolSchemas) {
		if (!strictToolNameValidation || toolSchemas == null) {
			return;
		}
		for (ToolSchema toolSchema : toolSchemas) {
			if (toolSchema == null || !AgentModelToolName.isValid(toolSchema.getName())) {
				throw new AgentRuntimeModelRequestException("DeepSeek tool name is invalid");
			}
		}
	}

	private String modelInputSignature(List<Msg> messages) {
		return messages == null ? "" : messages.stream().map(this::extractMessageText)
				.collect(Collectors.joining("\n"));
	}

	@Override
	public String getModelName() {
		return this.modelName;
	}

	private AgentUsageReservation preCheckAndReserve(long estimatedPromptTokens) {
		if (!meteringEnabled()) {
			return AgentUsageReservation.empty();
		}
		return tokenUsageService.preCheckAndReserve(usageContext(estimatedPromptTokens), estimatedPromptTokens);
	}

	private void recordActualUsage(long estimatedPromptTokens, Usage usage, String status,
			AgentUsageReservation reservation, Long durationMs) {
		if (meteringEnabled()) {
			tokenUsageService.recordActualUsage(usageContext(estimatedPromptTokens), usage, status, reservation,
					durationMs);
		}
	}

	private void recordEstimatedUsage(long estimatedPromptTokens, AgentUsageReservation reservation, Long durationMs) {
		if (meteringEnabled()) {
			tokenUsageService.recordEstimatedUsage(usageContext(estimatedPromptTokens), estimatedPromptTokens,
					AgentTokenUsageService.STATUS_SUCCESS, reservation, durationMs);
		}
	}

	private void recordUnknownUsage(long estimatedPromptTokens, Throwable error, AgentUsageReservation reservation,
			Long durationMs) {
		if (meteringEnabled()) {
			tokenUsageService.recordUnknownUsage(usageContext(estimatedPromptTokens), AgentTokenUsageService.STATUS_FAILED,
					error, reservation, durationMs);
		}
	}

	private AgentTokenUsageContext usageContext(long estimatedPromptTokens) {
		return usageContext == null ? null : usageContext.toBuilder().estimatedPromptTokens(estimatedPromptTokens).build();
	}

	private boolean meteringEnabled() {
		return tokenUsageService != null && usageContext != null;
	}

	private DataAgentAsyncContextBridge.Snapshot captureAsyncContext() {
		return asyncContextBridge == null ? null : asyncContextBridge.capture();
	}

	private <T> T supplyWithAsyncContext(DataAgentAsyncContextBridge.Snapshot asyncContext, Supplier<T> supplier) {
		return asyncContextBridge == null ? supplier.get() : asyncContextBridge.supplyWith(asyncContext, supplier);
	}

	private void runWithAsyncContext(DataAgentAsyncContextBridge.Snapshot asyncContext, Runnable runnable) {
		if (asyncContextBridge == null) {
			runnable.run();
			return;
		}
		asyncContextBridge.runWith(asyncContext, runnable);
	}

	private Usage springUsage(org.springframework.ai.chat.model.ChatResponse response) {
		return response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
	}

	private long estimatePromptTokens(List<Msg> messages) {
		if (tokenUsageService == null || messages == null || messages.isEmpty()) {
			return 1L;
		}
		String text = messages.stream()
			.map(this::extractMessageText)
			.filter(StringUtils::hasText)
			.collect(Collectors.joining(System.lineSeparator()));
		return tokenUsageService.estimateTokens(text);
	}

	private Long elapsedMs(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	private Message toSpringMessage(Msg message, List<String> availableToolNames) {
		if (message == null) {
			return null;
		}
		String text = extractMessageText(message);
		MsgRole role = message.getRole() == null ? MsgRole.USER : message.getRole();
		return switch (role) {
			case SYSTEM -> new SystemMessage(text);
			case ASSISTANT -> toAssistantMessage(message, wrapCompressedHistory(message, text), availableToolNames);
			case TOOL -> toToolResponseMessage(message, text, availableToolNames);
			case USER -> toUserMessage(message, text);
		};
	}

	/**
	 * 上下文压缩把工具结果、知识片段经 LLM 摘要后存成普通助手消息，原本在 {@link #toSpringToolResponse} 处
	 * 被包裹的检索内容会以未包裹的形式重新进入上下文，等于把边界洗掉。这里按 AgentScope 的压缩元数据
	 * （{@code _compress_meta}）识别压缩产物并重新包裹，自动压缩（{@code AutoContextHook}）与接口手动压缩
	 * （{@code ContextCompressionService}）两条路径同时覆盖——两者的摘要都落在同一份 AutoContextMemory 里，
	 * 也都只能经本方法回到模型。
	 * <p>
	 * 只处理 ASSISTANT 角色：压缩产物中 TOOL 角色仍保留 {@code ToolResultBlock}，已由工具出口包裹；
	 * USER 角色承载的是用户本人的输入，本来就不在不可信集合内，包裹它反而会让模型把用户自己的指令当数据。
	 * 换言之，这里要求的是「压缩不得解开已有的包裹」，而不是给从未包裹过的内容新加包裹。
	 */
	private String wrapCompressedHistory(Msg message, String text) {
		if (!StringUtils.hasText(text) || !MsgUtils.isCompressedMessage(message)) {
			return text;
		}
		return UntrustedContentBoundary.wrap(COMPRESSED_HISTORY_SOURCE, text);
	}

	private UserMessage toUserMessage(Msg message, String text) {
		List<Media> media = message.getContentBlocks(ImageBlock.class)
			.stream()
			.map(this::toSpringMedia)
			.filter(java.util.Objects::nonNull)
			.toList();
		if (media.isEmpty()) {
			return new UserMessage(text);
		}
		return UserMessage.builder().text(text).media(media).build();
	}

	private Media toSpringMedia(ImageBlock imageBlock) {
		if (imageBlock == null || !(imageBlock.getSource() instanceof Base64Source source)) {
			return null;
		}
		if (!StringUtils.hasText(source.getMediaType()) || !StringUtils.hasText(source.getData())) {
			throw new IllegalArgumentException("图片输入无效，无法作为模型输入");
		}
		try {
			return Media.builder()
				.mimeType(MimeType.valueOf(source.getMediaType()))
				.data(Base64.getDecoder().decode(source.getData()))
				.build();
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("图片解码失败，无法作为模型输入", ex);
		}
	}

	private AssistantMessage toAssistantMessage(Msg message, String text, List<String> availableToolNames) {
		List<AssistantMessage.ToolCall> toolCalls = message.getContentBlocks(ToolUseBlock.class)
			.stream()
			.map(toolUseBlock -> toSpringToolCall(toolUseBlock, availableToolNames))
			.toList();
		Map<String, Object> metadata = message.getMetadata();
		if (toolCalls.isEmpty() && (metadata == null || metadata.isEmpty())) {
			return new AssistantMessage(text);
		}
		AssistantMessage.Builder builder = AssistantMessage.builder();
		if (StringUtils.hasText(text) || toolCalls.isEmpty()) {
			builder.content(text);
		}
		if (!toolCalls.isEmpty()) {
			builder.toolCalls(toolCalls);
		}
		if (metadata != null && !metadata.isEmpty()) {
			builder.properties(metadata);
		}
		return builder.build();
	}

	private ToolResponseMessage toToolResponseMessage(Msg message, String fallbackText,
			List<String> availableToolNames) {
		List<ToolResponseMessage.ToolResponse> responses = message.getContentBlocks(ToolResultBlock.class)
			.stream()
			.map(toolResultBlock -> toSpringToolResponse(toolResultBlock, availableToolNames))
			.toList();
		if (responses.isEmpty()) {
			String toolName = resolveKnownToolName(message.getName(), message.getId(), availableToolNames, "tool");
			responses = List.of(new ToolResponseMessage.ToolResponse(defaultId(message.getId()), toolName,
					UntrustedContentBoundary.wrap(toolName, fallbackText)));
		}
		return ToolResponseMessage.builder().responses(responses).build();
	}

	private ToolCallingChatOptions buildChatOptions(List<ToolSchema> toolSchemas, GenerateOptions generateOptions) {
		ToolCallingChatOptions.Builder builder = ToolCallingChatOptions.builder();
		if (generateOptions != null) {
			if (StringUtils.hasText(generateOptions.getModelName())) {
				builder.model(generateOptions.getModelName());
			}
			if (generateOptions.getTemperature() != null) {
				builder.temperature(generateOptions.getTemperature().doubleValue());
			}
			if (generateOptions.getMaxTokens() != null) {
				builder.maxTokens(generateOptions.getMaxTokens());
			}
			if (generateOptions.getTopP() != null) {
				builder.topP(generateOptions.getTopP().doubleValue());
			}
			if (generateOptions.getTopK() != null) {
				builder.topK(generateOptions.getTopK());
			}
			if (generateOptions.getFrequencyPenalty() != null) {
				builder.frequencyPenalty(generateOptions.getFrequencyPenalty().doubleValue());
			}
			if (generateOptions.getPresencePenalty() != null) {
				builder.presencePenalty(generateOptions.getPresencePenalty().doubleValue());
			}
		}

		List<ToolCallback> selectedCallbacks = resolveToolCallbacks(toolSchemas);
		if (!selectedCallbacks.isEmpty()) {
			builder.toolCallbacks(selectedCallbacks.toArray(ToolCallback[]::new));
			builder.internalToolExecutionEnabled(false);
		}
		return applyThinkingProtocol(builder.build(), generateOptions);
	}

	/**
	 * L1 桥接：把 AgentScope 2.0 GenerateOptions 的 thinking 协议字段透传进 per-call options。
	 * 通用 {@link ToolCallingChatOptions.Builder} 没有这两个字段的 setter（Spring AI 1.1），只有
	 * {@link OpenAiChatOptions} 有，因此仅在字段非空时把已构建的 options 经 bean 拷贝转成
	 * OpenAiChatOptions 再补字段。
	 * <p>
	 * 安全规则：只透传非空字段，空字段绝不触碰 options——Spring AI 运行时合并（runtime 与
	 * defaultOptions 逐字段 merge）只覆盖非空项，留空即保护模型配置里用户设置的 thinking 档位。
	 * <p>
	 * thinkingBudget 不映射：它是协议无关的 AgentScope 字段，注入 wire 前必须先翻译成方言正确
	 * 形态（thinking 对象 / enable_thinking / reasoning_effort），统一由
	 * {@code V2ReasoningThrottleMiddleware} 以 additionalBodyParams 注入方言正确形态，桥接层不猜方言。
	 * <p>
	 * additionalBodyParams 与既有 extraBody 按 key 合并且 per-call 优先（同名 key 覆盖模型配置档位）。
	 * 注意 Spring AI 1.1 在合并 ChatCompletionRequest 时会过滤 OpenAiChatOptions.extraBody
	 * （见 {@code DynamicModelFactory.ProviderFieldsOpenAiApi} 注释），body 级 per-call 字段需经
	 * 该 API 边界回注才能上线；reasoningEffort 则经 reasoning_effort 字段直接生效。
	 */
	private ToolCallingChatOptions applyThinkingProtocol(ToolCallingChatOptions options,
			GenerateOptions generateOptions) {
		if (generateOptions == null) {
			return options;
		}
		String reasoningEffort = generateOptions.getReasoningEffort();
		Map<String, Object> additionalBodyParams = generateOptions.getAdditionalBodyParams();
		boolean hasReasoningEffort = StringUtils.hasText(reasoningEffort);
		boolean hasAdditionalBodyParams = additionalBodyParams != null && !additionalBodyParams.isEmpty();
		if (!hasReasoningEffort && !hasAdditionalBodyParams) {
			return options;
		}
		OpenAiChatOptions converted = ModelOptionsUtils.copyToTarget(options, ToolCallingChatOptions.class,
				OpenAiChatOptions.class);
		if (hasReasoningEffort) {
			converted.setReasoningEffort(reasoningEffort);
		}
		if (hasAdditionalBodyParams) {
			Map<String, Object> merged = new LinkedHashMap<>();
			if (converted.getExtraBody() != null) {
				merged.putAll(converted.getExtraBody());
			}
			merged.putAll(additionalBodyParams);
			converted.setExtraBody(Collections.unmodifiableMap(merged));
		}
		return converted;
	}

	private List<ToolCallback> resolveToolCallbacks(List<ToolSchema> toolSchemas) {
		List<ToolCallback> selectedCallbacks = new ArrayList<>();
		for (String toolName : resolveToolNames(toolSchemas)) {
			ToolCallback toolCallback = toolCallbacks.get(toolName);
			ToolSchema toolSchema = findToolSchema(toolSchemas, toolName);
			if (toolCallback != null) {
				selectedCallbacks.add(toolCallback);
			}
			else if (toolSchema != null) {
				selectedCallbacks.add(new ToolSchemaBackedToolCallback(toolSchema, objectMapper));
			}
		}
		return selectedCallbacks;
	}

	private List<String> resolveToolNames(List<ToolSchema> toolSchemas) {
		if (toolSchemas == null || toolSchemas.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> selectedNames = new LinkedHashSet<>();
		for (ToolSchema toolSchema : toolSchemas) {
			if (toolSchema != null && StringUtils.hasText(toolSchema.getName())) {
				selectedNames.add(toolSchema.getName());
			}
		}
		return List.copyOf(selectedNames);
	}

	private ToolSchema findToolSchema(List<ToolSchema> toolSchemas, String toolName) {
		if (toolSchemas == null || !StringUtils.hasText(toolName)) {
			return null;
		}
		return toolSchemas.stream()
			.filter(toolSchema -> toolSchema != null && toolName.equals(toolSchema.getName()))
			.findFirst()
			.orElse(null);
	}

	private static final class ToolSchemaBackedToolCallback implements ToolCallback {

		private final ToolDefinition toolDefinition;

		private ToolSchemaBackedToolCallback(ToolSchema toolSchema, ObjectMapper objectMapper) {
			this.toolDefinition = ToolDefinition.builder()
				.name(toolSchema.getName())
				.description(defaultDescription(toolSchema.getDescription()))
				.inputSchema(serializeSchema(toolSchema, objectMapper))
				.build();
		}

		@Override
		public ToolDefinition getToolDefinition() {
			return toolDefinition;
		}

		@Override
		public String call(String toolInput) {
			return "{\"error\":\"Tool execution is delegated to AgentScope runtime.\"}";
		}

		private static String serializeSchema(ToolSchema toolSchema, ObjectMapper objectMapper) {
			Map<String, Object> parameters = toolSchema.getParameters() == null
					? Map.of("type", "object", "properties", Map.of()) : toolSchema.getParameters();
			try {
				return objectMapper.writeValueAsString(parameters);
			}
			catch (Exception ex) {
				// An empty schema makes the model believe the tool takes no arguments, so it calls it wrongly.
				log.warn("Failed to serialize tool input schema, falling back to an empty schema. toolName={}",
						toolSchema.getName(), ex);
				return "{\"type\":\"object\",\"properties\":{}}";
			}
		}

		private static String defaultDescription(String description) {
			return StringUtils.hasText(description) ? description : "";
		}

	}

	private ChatResponse toAgentScopeResponse(org.springframework.ai.chat.model.ChatResponse response,
			List<String> availableToolNames) {
		org.springframework.ai.chat.model.Generation generation = response == null ? null : response.getResult();
		AssistantMessage output = generation == null ? null : generation.getOutput();
		String text = output == null ? "" : defaultText(output.getText());
		List<ContentBlock> contentBlocks = new ArrayList<>();
		String reasoningContent = extractReasoningContent(output);
		Map<String, Object> thinkingMetadata = extractThinkingMetadata(output);
		if (StringUtils.hasText(reasoningContent) || thinkingMetadata != null) {
			contentBlocks.add(
					ThinkingBlock.builder().thinking(defaultText(reasoningContent)).metadata(thinkingMetadata).build());
		}
		if (StringUtils.hasText(text)) {
			contentBlocks.add(TextBlock.builder().text(text).build());
		}
		if (output != null && output.hasToolCalls()) {
			output.getToolCalls()
				.stream()
				.map(toolCall -> toToolUseBlock(toolCall, availableToolNames))
				.forEach(contentBlocks::add);
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		String responseId = null;
		String finishReason = null;
		ChatUsage usage = null;
		if (response != null && response.getMetadata() != null) {
			responseId = response.getMetadata().getId();
			if (StringUtils.hasText(response.getMetadata().getModel())) {
				metadata.put("springAiModel", response.getMetadata().getModel());
			}
			usage = toChatUsage(response.getMetadata().getUsage());
		}
		if (generation != null && generation.getMetadata() != null) {
			finishReason = generation.getMetadata().getFinishReason();
		}
		return ChatResponse.builder()
			.id(responseId)
			.content(contentBlocks)
			.usage(usage)
			.metadata(metadata)
			.finishReason(finishReason)
			.build();
	}

	private ToolUseBlock toToolUseBlock(AssistantMessage.ToolCall toolCall, List<String> availableToolNames) {
		Map<String, Object> input = parseArguments(toolCall.arguments());
		Map<String, Object> metadata = new HashMap<>();
		if (StringUtils.hasText(toolCall.type())) {
			metadata.put("type", toolCall.type());
		}
		if (StringUtils.hasText(toolCall.arguments())) {
			metadata.put("arguments", toolCall.arguments());
		}
		return ToolUseBlock.builder()
			.id(toolCall.id())
			.name(resolveKnownToolName(toolCall.name(), toolCall.id(), availableToolNames, PARTIAL_TOOL_NAME))
			.input(input)
			.content(defaultText(toolCall.arguments()))
			.metadata(metadata.isEmpty() ? null : metadata)
			.build();
	}

	private AssistantMessage.ToolCall toSpringToolCall(ToolUseBlock toolUseBlock, List<String> availableToolNames) {
		return new AssistantMessage.ToolCall(defaultId(toolUseBlock.getId()), resolveToolCallType(toolUseBlock),
				resolveKnownToolName(toolUseBlock.getName(), toolUseBlock.getId(), availableToolNames, "tool"),
				resolveToolCallArguments(toolUseBlock));
	}

	private ToolResponseMessage.ToolResponse toSpringToolResponse(ToolResultBlock toolResultBlock,
			List<String> availableToolNames) {
		String toolName = resolveKnownToolName(toolResultBlock.getName(), toolResultBlock.getId(), availableToolNames,
				"tool");
		// 工具返回承载的是数据库行、知识库片段与文件正文，一律按不可信数据包裹后再进模型上下文。
		// 包裹放在这个出口而不是工具适配器，是为了让 SSE 推流、会话落库与失败归类继续看到原始文本。
		return new ToolResponseMessage.ToolResponse(defaultId(toolResultBlock.getId()), toolName,
				UntrustedContentBoundary.wrap(toolName, extractToolResultOutput(toolResultBlock)));
	}

	private String extractReasoningContent(AssistantMessage output) {
		if (output == null || output.getMetadata() == null) {
			return null;
		}
		Object reasoningContent = output.getMetadata().get("reasoningContent");
		if (reasoningContent == null) {
			return null;
		}
		return reasoningContent instanceof String text ? text : reasoningContent.toString();
	}

	private Map<String, Object> extractThinkingMetadata(AssistantMessage output) {
		if (output == null || output.getMetadata() == null || output.getMetadata().isEmpty()) {
			return null;
		}
		Object reasoningDetails = output.getMetadata().get(ThinkingBlock.METADATA_REASONING_DETAILS);
		if (reasoningDetails == null) {
			return null;
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put(ThinkingBlock.METADATA_REASONING_DETAILS, reasoningDetails);
		return metadata;
	}

	private ChatUsage toChatUsage(Usage usage) {
		if (usage == null) {
			return null;
		}
		return ChatUsage.builder()
			.inputTokens(safeToken(usage.getPromptTokens()))
			.outputTokens(safeToken(usage.getCompletionTokens()))
			.build();
	}

	private int safeToken(Integer value) {
		return value == null ? 0 : value;
	}

	private Map<String, Object> parseArguments(String arguments) {
		if (!StringUtils.hasText(arguments)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(arguments, MAP_TYPE);
		}
		catch (Exception ex) {
			// The payload itself is not logged: tool arguments routinely carry tenant business data.
			log.warn("Failed to parse model tool-call arguments as JSON, falling back to rawArguments. model={}, length={}",
					modelName, arguments.length(), ex);
			Map<String, Object> fallback = new LinkedHashMap<>();
			fallback.put("rawArguments", arguments);
			return fallback;
		}
	}

	private String defaultText(String text) {
		return text == null ? "" : text;
	}

	private String defaultId(String id) {
		return StringUtils.hasText(id) ? id : "tool-response";
	}

	private String defaultToolName(String name) {
		return StringUtils.hasText(name) ? name : "tool";
	}

	private String extractMessageText(Msg message) {
		String text = message.getTextContent();
		if (StringUtils.hasText(text)) {
			return text;
		}
		return message.getContentBlocks(TextBlock.class)
			.stream()
			.map(TextBlock::getText)
			.filter(StringUtils::hasText)
			.findFirst()
			.orElse("");
	}

	private String resolveToolCallType(ToolUseBlock toolUseBlock) {
		Map<String, Object> metadata = toolUseBlock.getMetadata();
		if (metadata == null) {
			return "function";
		}
		Object type = metadata.get("type");
		return type instanceof String value && StringUtils.hasText(value) ? value : "function";
	}

	private String resolveToolCallArguments(ToolUseBlock toolUseBlock) {
		if (StringUtils.hasText(toolUseBlock.getContent())) {
			return toolUseBlock.getContent();
		}
		try {
			return objectMapper
				.writeValueAsString(toolUseBlock.getInput() == null ? Map.of() : toolUseBlock.getInput());
		}
		catch (Exception ex) {
			// "{}" reaches the tool as "no arguments" rather than as an error, so the loss must be traceable.
			log.warn("Failed to serialize tool-call arguments, falling back to an empty object. toolCallId={}, toolName={}",
					toolUseBlock.getId(), toolUseBlock.getName(), ex);
			return "{}";
		}
	}

	private String extractToolResultOutput(ToolResultBlock toolResultBlock) {
		List<ContentBlock> output = toolResultBlock.getOutput();
		if (output == null || output.isEmpty()) {
			return "";
		}
		return output.stream()
			.filter(TextBlock.class::isInstance)
			.map(TextBlock.class::cast)
			.map(TextBlock::getText)
			.filter(StringUtils::hasText)
			.collect(Collectors.joining(System.lineSeparator()));
	}

	private String resolveKnownToolName(String name, String id, List<String> availableToolNames, String fallback) {
		if (StringUtils.hasText(name) && availableToolNames != null && availableToolNames.contains(name)) {
			return name;
		}
		String restoredName = restoreToolNameFromId(id, availableToolNames);
		if (StringUtils.hasText(restoredName)) {
			return restoredName;
		}
		return StringUtils.hasText(name) ? name : fallback;
	}

	private String restoreToolNameFromId(String id, List<String> availableToolNames) {
		if (!StringUtils.hasText(id) || availableToolNames == null || availableToolNames.isEmpty()) {
			return null;
		}
		for (String toolName : availableToolNames) {
			if (id.startsWith("functions." + toolName + ":")) {
				return toolName;
			}
		}
		return null;
	}

}
