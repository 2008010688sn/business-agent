/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.service.aimodelconfig.DynamicModelFactory;
import com.sn68.agent.dataagent.service.aimodelconfig.ModelConfigDataService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RouteEmbeddingModelResolver {

	private static final int CACHE_LIMIT = 16;

	private final ModelConfigDataService modelConfigDataService;

	private final DynamicModelFactory modelFactory;

	private final RouteEmbeddingFingerprint embeddingFingerprint;

	private final ConcurrentHashMap<ModelKey, EmbeddingModel> models = new ConcurrentHashMap<>();

	public RouteEmbeddingModelResolver(ModelConfigDataService modelConfigDataService, DynamicModelFactory modelFactory,
			RouteEmbeddingFingerprint embeddingFingerprint) {
		this.modelConfigDataService = modelConfigDataService;
		this.modelFactory = modelFactory;
		this.embeddingFingerprint = embeddingFingerprint;
	}

	public EmbeddingModel resolve(Long modelConfigId, String expectedFingerprint, Duration timeout) {
		if (modelConfigId == null || !StringUtils.hasText(expectedFingerprint) || timeout == null
				|| timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("Route embedding model request is invalid");
		}
		ModelConfigDTO config = modelConfigDataService.getRuntimeConfigById(modelConfigId, ModelType.EMBEDDING);
		String actualFingerprint = embeddingFingerprint.calculate(config);
		if (!expectedFingerprint.equals(actualFingerprint)) {
			throw new IllegalStateException("ROUTE_EMBEDDING_FINGERPRINT_MISMATCH");
		}
		ModelKey key = new ModelKey(modelConfigId, actualFingerprint, connectionFingerprint(config),
				timeout.toMillis());
		if (models.size() >= CACHE_LIMIT && !models.containsKey(key)) {
			models.clear();
		}
		return models.computeIfAbsent(key, ignored -> modelFactory.createRouteEmbeddingModel(config, timeout));
	}

	private String connectionFingerprint(ModelConfigDTO config) {
		return SecureUtil.sha256(String.join("\n", text(config.getApiKey()), text(config.getProxyEnabled()),
				text(config.getProxyHost()), text(config.getProxyPort()), text(config.getProxyUsername()),
				text(config.getProxyPassword())));
	}

	private String text(Object value) {
		return Objects.toString(value, "");
	}

	private record ModelKey(Long modelConfigId, String embeddingFingerprint, String connectionFingerprint,
			long timeoutMillis) {
	}

}
