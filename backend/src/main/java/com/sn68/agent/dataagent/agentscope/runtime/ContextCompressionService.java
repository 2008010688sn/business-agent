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
package com.sn68.agent.dataagent.agentscope.runtime;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.SessionContextCompressionStatus;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.agentscope.session.AgentScopeNativeSessionService;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextConfig;
import com.sn68.agent.dataagent.agentscope.memory.AutoContextMemory;
import com.sn68.agent.dataagent.agentscope.memory.TokenCounterUtil;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * ContextCompression组件，封装 DataAgent 对应业务入口。
 */
@Service
public class ContextCompressionService {

	private static final long DEFAULT_CONTEXT_WINDOW_TOKENS = 32768L;

	private static final long DEFAULT_MAX_TOKENS = 2000L;

	private static final long MIN_AVAILABLE_CONTEXT_TOKENS = 1000L;

	private final DataAgentProperties dataAgentProperties;

	private final AgentScopeNativeSessionService nativeSessionService;

	@Autowired
	public ContextCompressionService(DataAgentProperties dataAgentProperties,
			AgentScopeNativeSessionService nativeSessionService) {
		this.dataAgentProperties = dataAgentProperties;
		this.nativeSessionService = nativeSessionService;
	}

	ContextCompressionService(DataAgentProperties dataAgentProperties) {
		this(dataAgentProperties, null);
	}

	/**
	 * 创建ContextCompression。
	 */
	public PreparedMemory prepareMemory(PreparedMemory preparedMemory, ModelConfigDTO modelConfig, Model model) {
		return prepareMemory(preparedMemory, modelConfig, model, null);
	}

	/**
	 * 创建ContextCompression。
	 */
	public PreparedMemory prepareMemory(PreparedMemory preparedMemory, ModelConfigDTO modelConfig, Model model,
			String sessionId) {
		if (!isAutoContextEnabled() || preparedMemory == null || preparedMemory.memory() == null
				|| model == null) {
			return preparedMemory;
		}
		Memory source = preparedMemory.memory();
		if (source instanceof AutoContextMemory) {
			return preparedMemory;
		}
		AutoContextMemory memory = new AutoContextMemory(buildConfig(modelConfig), model);
		if (StringUtils.hasText(sessionId) && nativeSessionService != null
				&& nativeSessionService.loadStateIfExists(memory, sessionId)) {
			return new PreparedMemory(memory, true, true);
		}
		List<Msg> messages = source.getMessages();
		if (messages != null) {
			messages.forEach(memory::addMessage);
		}
		return new PreparedMemory(memory, preparedMemory.loadedFromNative(), true);
	}

	/**
	 * 处理ContextCompression。
	 */
	public CompressionResult compressMemoryManually(Memory source, ModelConfigDTO modelConfig, Model model) {
		List<Msg> beforeMessages = source == null ? List.of() : source.getMessages();
		long beforeTokens = countTokens(beforeMessages);
		int beforeMessageCount = beforeMessages.size();
		if (!isManualContextEnabled()) {
			return CompressionResult.of(SessionContextCompressionStatus.DISABLED, false, beforeTokens, beforeTokens,
					beforeMessageCount, beforeMessageCount, "手动上下文压缩未启用");
		}
		if (source == null || model == null) {
			return CompressionResult.of(SessionContextCompressionStatus.FAILED, false, beforeTokens, beforeTokens,
					beforeMessageCount, beforeMessageCount, "上下文压缩失败");
		}
		AutoContextConfig manualConfig = buildManualConfig(modelConfig);
		if (beforeMessageCount <= manualConfig.getLastKeep()) {
			return CompressionResult.of(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, false, beforeTokens,
					beforeTokens, beforeMessageCount, beforeMessageCount, "当前无需压缩");
		}
		AutoContextMemory memory;
		if (source instanceof AutoContextMemory autoContextMemory) {
			memory = autoContextMemory;
		}
		else {
			memory = new AutoContextMemory(manualConfig, model);
			beforeMessages.forEach(memory::addMessage);
		}
		boolean compressed = memory.compressIfNeeded();
		List<Msg> afterMessages = memory.getMessages();
		long afterTokens = countTokens(afterMessages);
		int afterMessageCount = afterMessages.size();
		if (!compressed) {
			return CompressionResult.of(SessionContextCompressionStatus.NO_COMPRESSIBLE_CONTENT, false, beforeTokens,
					afterTokens, beforeMessageCount, afterMessageCount, "当前无需压缩");
		}
		return CompressionResult.of(SessionContextCompressionStatus.COMPRESSED, true, beforeTokens, afterTokens,
				beforeMessageCount, afterMessageCount, "已压缩上下文").withMemory(memory);
	}

	AutoContextConfig buildConfig(ModelConfigDTO modelConfig) {
		DataAgentProperties.AutoContext properties = autoContextProperties();
		return AutoContextConfig.builder()
			.maxToken(availableContextTokens(modelConfig, properties))
			.tokenRatio(properties.getTokenRatio())
			.msgThreshold(properties.getMsgThreshold())
			.lastKeep(properties.getLastKeep())
			.largePayloadThreshold(properties.getLargePayloadThreshold())
			.offloadSinglePreview(properties.getOffloadSinglePreview())
			.minConsecutiveToolMessages(properties.getMinConsecutiveToolMessages())
			.currentRoundCompressionRatio(properties.getCurrentRoundCompressionRatio())
			.minCompressionTokenThreshold(properties.getMinCompressionTokenThreshold())
			.build();
	}

	/**
	 * 创建ContextCompression。
	 */
	public AutoContextConfig buildManualConfig(ModelConfigDTO modelConfig) {
		DataAgentProperties.AutoContext properties = autoContextProperties();
		int lastKeep = Math.max(1, properties.getManualLastKeep());
		return AutoContextConfig.builder()
			.maxToken(availableContextTokens(modelConfig, properties))
			.tokenRatio(0.01)
			.msgThreshold(Math.max(2, lastKeep + 2))
			.lastKeep(lastKeep)
			.largePayloadThreshold(properties.getLargePayloadThreshold())
			.offloadSinglePreview(properties.getOffloadSinglePreview())
			.minConsecutiveToolMessages(properties.getMinConsecutiveToolMessages())
			.currentRoundCompressionRatio(properties.getCurrentRoundCompressionRatio())
			.minCompressionTokenThreshold(0)
			.build();
	}

	/**
	 * 校验ContextCompression。
	 */
	public boolean isAutoContextEnabled() {
		return autoContextProperties().isEnabled();
	}

	/**
	 * 校验ContextCompression。
	 */
	public boolean isManualContextEnabled() {
		return autoContextProperties().isManualEnabled();
	}

	/**
	 * 校验ContextCompression。
	 */
	public boolean isContextCompressionEnabled() {
		DataAgentProperties.AutoContext properties = autoContextProperties();
		return properties.isEnabled() || properties.isManualEnabled();
	}

	/**
	 * 处理ContextCompression。
	 */
	public long countTokens(List<Msg> messages) {
		return TokenCounterUtil.calculateToken(messages);
	}

	private long availableContextTokens(ModelConfigDTO modelConfig, DataAgentProperties.AutoContext properties) {
		long contextWindowTokens = modelConfig == null || modelConfig.getContextWindowTokens() == null
				? DEFAULT_CONTEXT_WINDOW_TOKENS : modelConfig.getContextWindowTokens();
		long maxTokens = modelConfig == null || modelConfig.getMaxTokens() == null ? DEFAULT_MAX_TOKENS
				: modelConfig.getMaxTokens();
		int reserveTokens = properties == null ? 0 : properties.getReserveTokens();
		return Math.max(MIN_AVAILABLE_CONTEXT_TOKENS, contextWindowTokens - maxTokens - reserveTokens);
	}

	private DataAgentProperties.AutoContext autoContextProperties() {
		if (dataAgentProperties == null || dataAgentProperties.getMemory() == null
				|| dataAgentProperties.getMemory().getAutoContext() == null) {
			return new DataAgentProperties.AutoContext();
		}
		return dataAgentProperties.getMemory().getAutoContext();
	}

	/**
	 * 处理ContextCompression。
	 */
	public record CompressionResult(SessionContextCompressionStatus status, boolean compressed, long beforeRuntimeTokens,
			long afterRuntimeTokens, int beforeRuntimeMessageCount, int afterRuntimeMessageCount, String message,
			Memory memory) {

		static CompressionResult of(SessionContextCompressionStatus status, boolean compressed, long beforeRuntimeTokens,
				long afterRuntimeTokens, int beforeRuntimeMessageCount, int afterRuntimeMessageCount, String message) {
			return new CompressionResult(status, compressed, beforeRuntimeTokens, afterRuntimeTokens,
					beforeRuntimeMessageCount, afterRuntimeMessageCount, message, null);
		}

		CompressionResult withMemory(Memory memory) {
			return new CompressionResult(status, compressed, beforeRuntimeTokens, afterRuntimeTokens,
					beforeRuntimeMessageCount, afterRuntimeMessageCount, message, memory);
		}

	}

}
