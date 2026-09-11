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
import com.sn68.agent.framework.commons.exception.CheckedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * EMBEDDING 模型的维度准入校验。
 * <p>
 * 向量列是定宽的，而 embedding 模型可以在管理端随时切换。没有这道校验时，维度不符的模型会被正常启用，
 * 之后每一次向量写入都被 PostgreSQL 拒绝，整个 RAG 子系统在运行时才瘫痪。这里把失败点从「运行时」
 * 提前到「配置时」。
 */
@Slf4j
@Component
public class EmbeddingDimensionGuard {

	/**
	 * 向量列 DDL 里写死的维度：{@code embedding v4_ai.vector(1024)}，见
	 * {@code dataagent/sql/pg/20260618_pgvector_vector_store.sql} 与 {@code schema-v2.sql}。
	 * <p>
	 * 只在 {@code spring.ai.vectorstore.pgvector.dimensions} 未显式声明（Spring AI 默认 -1）时作为约定值使用。
	 * <b>改了 DDL 的向量列宽度就必须同步改这里</b>，否则准入校验会放行一个根本写不进去的模型。
	 */
	private static final int DDL_VECTOR_DIMENSIONS = 1024;

	private static final String PGVECTOR = "pgvector";

	/** Spring AI 官方属性，缺省值与 {@code local.properties} 保持一致。 */
	@Value("${spring.ai.vectorstore.type:pgvector}")
	private String vectorStoreType;

	/** Spring AI 官方属性；未声明时为 -1，表示「按 embedding 模型维度建表」，此时只能回退到 DDL 约定值。 */
	@Value("${spring.ai.vectorstore.pgvector.dimensions:-1}")
	private int configuredDimensions;

	private final DynamicModelFactory modelFactory;

	public EmbeddingDimensionGuard(DynamicModelFactory modelFactory) {
		this.modelFactory = modelFactory;
	}

	/**
	 * 在 EMBEDDING 配置真正生效之前调用：构造一次模型探出真实维度，与向量库维度比对，不一致直接拒绝。
	 * @param config 待生效的运行时配置（apiKey 等需已解密）
	 */
	public void verifyActivatable(ModelConfigDTO config) {
		if (config == null) {
			return;
		}
		if (!PGVECTOR.equalsIgnoreCase(vectorStoreType)) {
			// 只有 pgvector 的向量列是定宽的：simple 是进程内 float[]，elasticsearch 由索引 mapping 自己约束
			log.debug("Skip embedding dimension check for vectorStoreType={}", vectorStoreType);
			return;
		}
		int expected = expectedDimensions();
		int actual = probeDimensions(config);
		if (actual == expected) {
			log.info("Embedding dimension check passed. modelName={}, dimensions={}", config.getModelName(), actual);
			return;
		}
		log.warn("Rejected embedding model activation on dimension mismatch. modelName={}, actual={}, expected={}",
				config.getModelName(), actual, expected);
		throw CheckedException.badRequest("EMBEDDING 模型维度与向量库不一致，已拒绝启用：模型 " + config.getModelName() + " 输出 "
				+ actual + " 维，向量库为 " + expected + " 维。请先迁移向量库（重建向量列并全量重建存量向量）后再启用该模型。");
	}

	private int expectedDimensions() {
		return configuredDimensions > 0 ? configuredDimensions : DDL_VECTOR_DIMENSIONS;
	}

	/**
	 * 探不出维度就不能放行：宁可拒绝一次可重试的启用，也不能让未经校验的模型接管整个向量库。
	 */
	private int probeDimensions(ModelConfigDTO config) {
		int dimensions;
		try {
			EmbeddingModel model = modelFactory.createEmbeddingModel(config);
			dimensions = model.dimensions();
		}
		catch (RuntimeException ex) {
			// 静态工厂不接收 cause，先落日志保住原始堆栈
			log.error("Probe embedding dimensions failed. provider={}, modelName={}, baseUrl={}", config.getProvider(),
					config.getModelName(), config.getBaseUrl(), ex);
			throw CheckedException.fail("无法探测 EMBEDDING 模型维度，已拒绝启用, modelName=" + config.getModelName() + ", 原因: "
					+ ex.getMessage());
		}
		if (dimensions <= 0) {
			throw CheckedException.fail("EMBEDDING 模型未返回有效维度，已拒绝启用, modelName=" + config.getModelName());
		}
		return dimensions;
	}

}
