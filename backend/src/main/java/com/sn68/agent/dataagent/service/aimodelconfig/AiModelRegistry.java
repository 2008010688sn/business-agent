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
import com.sn68.agent.dataagent.routing.RouteEmbeddingFingerprint;
import com.sn68.agent.dataagent.service.permission.PlatformScopePermissionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Ai模型Registry组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelRegistry {

	private final DynamicModelFactory modelFactory;

	private final ModelConfigDataService modelConfigDataService;

	private final RouteEmbeddingFingerprint embeddingFingerprint;

	private final PlatformScopePermissionService platformScopePermissionService;

	private final ConcurrentHashMap<String, ChatClient> chatClients = new ConcurrentHashMap<>();

	private volatile EmbeddingModel currentEmbeddingModel;

	/**
	 * 当前全局 embedding 模型的身份，与 {@link #currentEmbeddingModel} 同生命周期。
	 * 只在模型来自真实配置时有值；Dummy 兜底期为 null，避免给出会误导排查的指纹。
	 */
	private volatile EmbeddingIdentity currentEmbeddingIdentity;

	private final ThreadLocal<EmbeddingModel> scopedEmbeddingModel = new ThreadLocal<>();

	// =========================================================
	// 1. 获取 ChatClient (懒加载 + 缓存)
	// =========================================================

	/**
	 * 按当前登录租户取 ChatClient；无租户或该租户未启用 CHAT 模型时失败关闭，不回落其它租户。
	 */
	public ChatClient getChatClient() {
		return chatClientForTenant(platformScopePermissionService.requireCurrentTenantId("CHAT 模型"));
	}

	/**
	 * 按租户启用中的 CHAT 配置构建（并缓存）ChatClient。
	 */
	public ChatClient chatClientForTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.fail("缺少租户上下文，无法使用 CHAT 模型");
		}
		String tenant = tenantId.trim();
		return chatClients.computeIfAbsent(tenant, this::createChatClient);
	}

	private ChatClient createChatClient(String tenantId) {
		log.info("Initializing ChatClient for tenant {}", tenantId);
		try {
			ModelConfigDTO config = modelConfigDataService.getActiveRuntimeConfigByType(ModelType.CHAT, tenantId);
			if (config != null) {
				ChatModel chatModel = modelFactory.createChatModel(config);
				return ChatClient.builder(chatModel).build();
			}
		}
		catch (CheckedException ex) {
			throw ex;
		}
		catch (Exception e) {
			log.error("Failed to initialize ChatClient for tenant {}: {}", tenantId, e.getMessage(), e);
			throw CheckedException.fail("CHAT 模型已配置但初始化失败，请检查该模型配置后重新保存以重试，原因: " + e.getMessage());
		}
		throw CheckedException.fail("未配置启用中的 CHAT 模型，请先在模型配置中启用一个 CHAT 模型");
	}

	// =========================================================
	// 2. 获取 EmbeddingModel (懒加载 + Dummy 兜底)
	// =========================================================
	public EmbeddingModel getEmbeddingModel() {
		EmbeddingModel scoped = scopedEmbeddingModel.get();
		if (scoped != null) {
			return scoped;
		}
		return globalEmbeddingModel();
	}

	/**
	 * 按租户启用中的 EMBEDDING 配置构建模型；该租户未配置时返回 null，不使用 Dummy、不回落其它租户。
	 */
	public EmbeddingModel embeddingModelForTenant(String tenantId) {
		if (!StringUtils.hasText(tenantId)) {
			return null;
		}
		ModelConfigDTO config = modelConfigDataService.getActiveRuntimeConfigByType(ModelType.EMBEDDING, tenantId.trim());
		if (config == null) {
			return null;
		}
		return modelFactory.createEmbeddingModel(config);
	}

	private EmbeddingModel globalEmbeddingModel() {
		if (currentEmbeddingModel == null) {
			synchronized (this) {
				if (currentEmbeddingModel == null) {
					initGlobalEmbeddingModel();
				}
			}
		}
		return currentEmbeddingModel;
	}

	/**
	 * 仅在持有锁时调用：按启用中的 EMBEDDING 配置构建全局模型，失败或未配置时退到 Dummy 兜底。
	 */
	private void initGlobalEmbeddingModel() {
		log.info("Initializing global EmbeddingModel...");
		// 「没配模型」和「配了但构造失败」的处置一样（都退到 Dummy），但排查方向完全相反，
		// 必须分开记，否则运维只会看到「未配置」并跑去配一个已经配好的模型
		String unavailableReason = "未配置启用中的 EMBEDDING 模型，请先在模型配置中启用一个 EMBEDDING 模型";
		try {
			ModelConfigDTO config = modelConfigDataService.getActiveRuntimeConfigByType(ModelType.EMBEDDING);
			if (config != null) {
				currentEmbeddingModel = modelFactory.createEmbeddingModel(config);
				currentEmbeddingIdentity = new EmbeddingIdentity(embeddingFingerprint.calculate(config));
			}
		}
		catch (Exception e) {
			log.error("Failed to initialize EmbeddingModel: {}", e.getMessage(), e);
			unavailableReason = "EMBEDDING 模型已配置但初始化失败，请检查该模型配置后重新保存以重试，原因: " + e.getMessage();
		}

		// 兜底：为了防止 VectorStore Starter 启动时调用 dimensions() 报错
		// 我们必须返回一个"哑巴"模型，而不是 null 或 抛异常
		if (currentEmbeddingModel == null) {
			log.warn("Using DummyEmbeddingModel for fallback. reason={}", unavailableReason);
			currentEmbeddingModel = new DummyEmbeddingModel(unavailableReason);
		}
	}

	/**
	 * 返回当前全局 embedding 模型的身份，用于写入向量时记录「这批向量是哪个模型产生的」。
	 * <p>
	 * 无可用配置（Dummy 兜底）时返回 {@code null}：此时写入本身就会失败，不该再落一个假身份。
	 * 维度按需探一次并缓存——构建模型时就探会把一次网络抖动放大成「整个 embedding 退化为 Dummy」。
	 */
	public EmbeddingIdentity getEmbeddingIdentity() {
		EmbeddingModel model = globalEmbeddingModel();
		EmbeddingIdentity identity = currentEmbeddingIdentity;
		if (identity == null || identity.dimensions() > 0) {
			return identity;
		}
		identity = identity.withDimensions(model.dimensions());
		currentEmbeddingIdentity = identity;
		return identity;
	}

	/**
	 * Executes one synchronous VectorStore operation with a request-scoped
	 * embedding model. Nested scopes are restored in LIFO order.
	 */
	public <T> T withEmbeddingModel(EmbeddingModel embeddingModel, Supplier<T> action) {
		if (embeddingModel == null || action == null) {
			throw new IllegalArgumentException("Embedding model and action are required");
		}
		EmbeddingModel previous = scopedEmbeddingModel.get();
		scopedEmbeddingModel.set(embeddingModel);
		try {
			return action.get();
		}
		finally {
			if (previous == null) {
				scopedEmbeddingModel.remove();
			}
			else {
				scopedEmbeddingModel.set(previous);
			}
		}
	}

	// =========================================================
	// 3. 刷新/重置缓存 (用于热切换)
	// =========================================================

	/**
	 * 清空各租户 ChatClient 缓存，下次获取时按最新启用配置重建（模型热切换入口）。
	 */
	public void refreshChat() {
		this.chatClients.clear();
		log.info("Chat cache cleared.");
	}

	/**
	 * 清空全局 EmbeddingModel 缓存与身份指纹，下次获取时按最新启用配置重建。
	 */
	public void refreshEmbedding() {
		this.currentEmbeddingModel = null;
		this.currentEmbeddingIdentity = null;
		log.info("Embedding cache cleared.");
	}

	/**
	 * 当前生效 embedding 模型的身份。指纹沿用 ROUTE 链路那一套算法，全库只有一种「向量由哪个模型产生」的表达。
	 *
	 * @param fingerprint 模型配置指纹
	 * @param dimensions 实际向量维度；{@code 0} 表示尚未探测
	 */
	public record EmbeddingIdentity(String fingerprint, int dimensions) {

		public EmbeddingIdentity(String fingerprint) {
			this(fingerprint, 0);
		}

		public EmbeddingIdentity withDimensions(int dimensions) {
			return new EmbeddingIdentity(this.fingerprint, dimensions);
		}

		public boolean isUsable() {
			return StringUtils.hasText(this.fingerprint) && this.dimensions > 0;
		}

	}

	// =========================================================
	// 4. 内部类：哑巴嵌入模型 (仅用于启动时防崩)
	// =========================================================
	private static class DummyEmbeddingModel implements EmbeddingModel {

		private final String unavailableReason;

		DummyEmbeddingModel(String unavailableReason) {
			this.unavailableReason = unavailableReason;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			throw CheckedException.fail(unavailableReason);
		}

		// 以下 embed 系列必须和 call() 一样抛异常：SimpleVectorStore.doAdd 走的正是 embed(Document)，
		// 返回空向量会让零长度向量被静默写入并持久化，事后无法与正常向量区分
		@Override
		public float[] embed(Document document) {
			throw CheckedException.fail(unavailableReason);
		}

		@Override
		public float[] embed(String text) {
			throw CheckedException.fail(unavailableReason);
		}

		@Override
		public List<float[]> embed(List<String> texts) {
			throw CheckedException.fail(unavailableReason);
		}

		@Override
		public EmbeddingResponse embedForResponse(List<String> texts) {
			throw CheckedException.fail(unavailableReason);
		}

		// 唯一保留返回值的方法：VectorStore starter 启动期会调它，抛异常会直接让应用起不来。
		// 这里的 1536 只是一个不会崩的占位维度，不代表任何真实模型，也不该被当作向量库的维度依据。
		@Override
		public int dimensions() {
			return 1536;
		}

	}

}
