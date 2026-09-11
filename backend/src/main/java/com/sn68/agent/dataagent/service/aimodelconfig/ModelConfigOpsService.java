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

import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.ModelConfig;
import com.sn68.agent.dataagent.vo.ModelCheckVO;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 模型配置Ops组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@AllArgsConstructor
public class ModelConfigOpsService {

	private final ModelConfigDataService modelConfigDataService;

	private final DynamicModelFactory modelFactory;

	private final AiModelRegistry aiModelRegistry;

	private final EmbeddingDimensionGuard embeddingDimensionGuard;

	/**
	 * 专门处理：更新配置并热刷新的聚合逻辑
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateAndRefresh(ModelConfigDTO dto) {
		// 1. 更新数据库
		ModelConfig entity = modelConfigDataService.updateConfigInDb(dto);

		// 2. 检查是否是激活状态
		if (Boolean.TRUE.equals(entity.getIsActive())) {
			// 3. 维度准入：仍在事务内，不通过就连同这次更新一起回滚
			verifyEmbeddingDimension(entity.getId(), entity.getModelType());

			// 4. 提交后再刷新内存模型
			log.info("Detected update on active config [{}], refreshing memory after commit...",
					entity.getModelType());
			refreshMemoryModelAfterCommit(entity.getModelType());
		}
	}

	/**
	 * 激活指定配置
	 */
	@Transactional(rollbackFor = Exception.class)
	public void activateConfig(Long id) {
		// 1. 查数据
		ModelConfig entity = modelConfigDataService.findById(id);
		if (entity == null) {
			throw CheckedException.notFound("模型配置不存在, id=" + id);
		}

		// 2. 更新数据库状态 (调用数据层，内含平台管理员校验)
		log.info("Activating config ID={}, Type={}...", id, entity.getModelType());
		modelConfigDataService.switchActiveStatus(id, entity.getModelType());

		// 3. 维度准入：仍在事务内，不通过就连同这次激活一起回滚。
		// 放在写库之后是为了先过 switchActiveStatus 里的平台管理员校验——本接口没有 controller 层权限注解，
		// 提前探测等于让任何登录用户都能驱动一次真实的 embedding 调用。
		verifyEmbeddingDimension(id, entity.getModelType());

		// 4. 提交后再刷新内存模型
		refreshMemoryModelAfterCommit(entity.getModelType());

		log.info("Config ID={} activated successfully.", id);
	}

	/**
	 * EMBEDDING 模型生效前的维度准入；其余模型类型与向量列无关，直接放行。
	 */
	private void verifyEmbeddingDimension(Long id, ModelType type) {
		if (!ModelType.EMBEDDING.equals(type)) {
			return;
		}
		embeddingDimensionGuard.verifyActivatable(modelConfigDataService.getRuntimeConfigById(id, ModelType.EMBEDDING));
	}

	/**
	 * 缓存刷新必须等事务提交后再做。
	 * <p>
	 * 提交前清缓存会留下一个致命窗口：{@code AiModelRegistry} 重建缓存时直接查库，此刻读到的仍是旧的 active 行，
	 * 于是把<b>旧模型</b>重新缓存进去，而提交之后不会再清一次——旧模型会一直生效到下一次手动刷新。
	 */
	private void refreshMemoryModelAfterCommit(ModelType type) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
			refreshMemoryModel(type);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				try {
					refreshMemoryModel(type);
				}
				catch (Exception e) {
					// 事务已提交，回滚不了；如实报出「库里已生效但内存还是旧模型」，别把它伪装成更新失败
					log.error("Refresh memory model failed after commit. modelType={}", type, e);
					throw CheckedException.fail("配置已保存，但内存模型未刷新，请重新激活该配置: " + e.getMessage());
				}
			}
		});
	}

	/**
	 * 私有方法：根据实体创建并替换内存代理
	 */
	private void refreshMemoryModel(ModelType type) {
		if (ModelType.CHAT.equals(type)) {
			aiModelRegistry.refreshChat();
		}
		else if (ModelType.EMBEDDING.equals(type)) {
			aiModelRegistry.refreshEmbedding();
		}
		else if (ModelType.AUDIO_TRANSCRIPTION.equals(type)) {
			log.info("语音转写模型配置已激活。");
		}
		else if (ModelType.TEXT_TO_SPEECH.equals(type)) {
			log.info("文本转语音模型配置已激活。");
		}
		else if (ModelType.REALTIME_VOICE.equals(type)) {
			log.info("一体化实时语音模型配置已激活。");
		}
		else {
			// ModelType 枚举已被上面的分支穷举，走到这里只能是新增枚举值后漏加分支
			throw new IllegalStateException("Unhandled ModelType branch: " + type);
		}
	}

	/**
	 * 测试连接逻辑 注意：这里创建的模型是“临时”的，用完即丢，不会影响当前系统正在运行的模型
	 */
	public void testConnection(ModelConfigDTO config) {
		ModelConfigDTO runtimeConfig = modelConfigDataService.prepareRuntimeConfig(config);
		if (runtimeConfig != null) {
			config = runtimeConfig;
		}
		String modelType = config.getModelType();

		try {
			if (ModelType.CHAT.getCode().equalsIgnoreCase(modelType)) {
				testChatModel(config);
			}
			else if (ModelType.EMBEDDING.getCode().equalsIgnoreCase(modelType)) {
				testEmbeddingModel(config);
			}
			else if (ModelType.AUDIO_TRANSCRIPTION.getCode().equalsIgnoreCase(modelType)) {
				testAudioTranscriptionModel(config);
			}
			else if (ModelType.TEXT_TO_SPEECH.getCode().equalsIgnoreCase(modelType)) {
				testTextToSpeechModel(config);
			}
			else if (ModelType.REALTIME_VOICE.getCode().equalsIgnoreCase(modelType)) {
				testRealtimeVoiceModel(config);
			}
			else {
				throw CheckedException.badRequest("未知的模型类型: " + modelType);
			}
		}
		catch (Exception e) {
			log.error("Failed to test model connection. {}", safeConfigSummary(config), e);
			// 重新抛出异常，让 Controller 捕获并展示给前端
			// 如果是 OpenAiHttpException，通常包含具体的 API 错误信息
			throw CheckedException.fail(parseErrorMessage(e));
		}
	}

	/**
	 * 校验模型配置Ops。
	 */
	public ModelCheckVO checkReady() {
		ModelConfigDTO chatModel = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
		ModelConfigDTO embeddingModel = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
		ModelConfigDTO audioTranscriptionModel = modelConfigDataService
			.getActiveConfigByType(ModelType.AUDIO_TRANSCRIPTION);
		ModelConfigDTO textToSpeechModel = modelConfigDataService.getActiveConfigByType(ModelType.TEXT_TO_SPEECH);
		ModelConfigDTO realtimeVoiceModel = modelConfigDataService.getActiveConfigByType(ModelType.REALTIME_VOICE);

		boolean chatModelReady = chatModel != null;
		boolean embeddingModelReady = embeddingModel != null;
		boolean audioTranscriptionModelReady = audioTranscriptionModel != null;
		boolean textToSpeechModelReady = textToSpeechModel != null;
		boolean realtimeVoiceModelReady = realtimeVoiceModel != null;

		return ModelCheckVO.builder()
			.chatModelReady(chatModelReady)
			.embeddingModelReady(embeddingModelReady)
			.audioTranscriptionModelReady(audioTranscriptionModelReady)
			.textToSpeechModelReady(textToSpeechModelReady)
			.realtimeVoiceModelReady(realtimeVoiceModelReady)
			.ready(chatModelReady && embeddingModelReady)
			.build();
	}

	private void testChatModel(ModelConfigDTO config) {
		log.info("Testing Chat Model connection, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());

		// 1. 创建临时模型
		ChatModel tempModel = modelFactory.createChatModel(config);

		// 2. 发起最轻量的请求
		String promptText = "Hello";

		// 3. 调用
		String response = tempModel.call(promptText);

		// 4. 校验结果
		if (!StringUtils.hasText(response)) {
			throw CheckedException.fail("模型返回内容为空, modelName=" + config.getModelName());
		}
		log.info("Chat Model test passed. Response: {}", response);
	}

	private void testEmbeddingModel(ModelConfigDTO config) {
		log.info("Testing Embedding Model connection, provider: {} modelName: {}", config.getProvider(),
				config.getModelName());
		// 1. 创建临时模型
		EmbeddingModel tempModel = modelFactory.createEmbeddingModel(config);

		// 2. 发起请求
		float[] embedding = tempModel.embed("Test");

		// 3. 校验结果
		if (embedding == null || embedding.length == 0) {
			throw CheckedException.fail("模型生成的向量为空, modelName=" + config.getModelName());
		}
		log.info("Embedding Model test passed. Dimension: {}", embedding.length);
	}

	private void testAudioTranscriptionModel(ModelConfigDTO config) {
		log.info("Testing Audio Transcription Model config, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());
		OpenAiAudioTranscriptionModel tempModel = modelFactory.createAudioTranscriptionModel(config);
		if (tempModel == null) {
			throw CheckedException.fail("语音转写模型创建失败, modelName=" + config.getModelName());
		}
		log.info("Audio Transcription Model config test passed.");
	}

	private void testTextToSpeechModel(ModelConfigDTO config) {
		log.info("Testing Text To Speech Model config, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());
		if (!StringUtils.hasText(config.getBaseUrl())) {
			throw CheckedException.badRequest("TTS Base URL 不能为空, provider=" + config.getProvider());
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw CheckedException.badRequest("TTS 模型名称不能为空, provider=" + config.getProvider());
		}
		if (!"custom".equalsIgnoreCase(config.getProvider()) && !StringUtils.hasText(config.getApiKey())) {
			throw CheckedException.badRequest("TTS API Key 不能为空, provider=" + config.getProvider());
		}
		log.info("Text To Speech Model config test passed.");
	}

	private void testRealtimeVoiceModel(ModelConfigDTO config) {
		log.info("Testing Realtime Voice Model config, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());
		if (!StringUtils.hasText(config.getProvider())) {
			throw CheckedException.badRequest("Realtime provider 不能为空, modelName=" + config.getModelName());
		}
		if (!StringUtils.hasText(config.getModelName())) {
			throw CheckedException.badRequest("Realtime 模型名称不能为空, provider=" + config.getProvider());
		}
		if (!"custom".equalsIgnoreCase(config.getProvider()) && !StringUtils.hasText(config.getApiKey())) {
			throw CheckedException.badRequest("Realtime API Key 不能为空, provider=" + config.getProvider());
		}
		log.info("Realtime Voice Model config test passed.");
	}

	/**
	 * 辅助方法：提取更友好的错误信息 Spring AI 抛出的异常有时候嵌套很深
	 */
	private String parseErrorMessage(Exception e) {
		String message = e.getMessage();
		if (message != null
				&& message.matches("(?is).*(LLM Provider NOT provided|provider you are trying to call|upstream_error).*")) {
			return "模型名称不被当前供应商识别，请使用供应商支持的模型名称，并按供应商要求填写 provider/model 前缀。";
		}
		if (message != null && message.contains("model_not_found")) {
			return "模型不可用：当前供应商渠道未配置该模型或模型名称不匹配，请检查模型名称和供应商后台渠道。";
		}
		// 如果是 401，通常是 Key 错
		if (message != null && message.contains("401")) {
			return "鉴权失败 (401)，请检查 API Key 是否正确。";
		}
		// 如果是 404，通常是 BaseUrl 或 Path 错
		if (message != null && message.contains("404")) {
			return "接口未找到 (404)，请检查 Base URL 或者路径配置地址。";
		}
		// 如果是 429，额度没了
		if (message != null && message.contains("429")) {
			return "请求过多或余额不足 (429)，请检查厂商额度。";
		}
		// 其他错误直接返回原样
		return message;
	}

	private String safeConfigSummary(ModelConfigDTO config) {
		if (config == null) {
			return "config=null";
		}
		return "id=%s, provider=%s, modelType=%s, modelName=%s, baseUrl=%s, proxyEnabled=%s".formatted(config.getId(),
				config.getProvider(), config.getModelType(), config.getModelName(), config.getBaseUrl(),
				config.getProxyEnabled());
	}

}
