/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RouteEmbeddingFingerprint {

	public String calculate(ModelConfigDTO config) {
		if (config == null) {
			return null;
		}
		return SecureUtil.sha256(String.join("\n", text(config.getProvider()), text(config.getBaseUrl()),
				text(config.getModelName()), text(config.getEmbeddingsPath()), text(config.getLastModifyTime())));
	}

	private String text(Object value) {
		return Objects.toString(value, "").trim();
	}
}
