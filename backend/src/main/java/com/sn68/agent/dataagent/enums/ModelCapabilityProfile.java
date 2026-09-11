/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.enums;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Explicit model-family capability profile. Profiles are selected by
 * configuration and are never inferred from a model name.
 */
public enum ModelCapabilityProfile implements DictEnum<String> {

	AUTO,

	/**
	 * Declares a model that does not produce reasoning tokens. This profile does
	 * not send a provider-specific field to disable reasoning.
	 */
	NO_REASONING,

	QWEN_HYBRID,

	QWEN_THINKING_ONLY,

	STEPFUN_REASONING,

	DEEPSEEK_THINKING,

	GLM_THINKING,

	KIMI_K3_REASONING,

	KIMI_K26_THINKING,

	KIMI_K27_CODE;

	@Override
	public String getValue() {
		return name();
	}

	@Override
	public String getLabel() {
		return name();
	}

}
