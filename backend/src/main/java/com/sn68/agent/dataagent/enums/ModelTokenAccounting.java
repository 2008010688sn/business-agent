/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.enums;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * Describes how a provider accounts for hidden reasoning tokens relative to
 * the configured output limit.
 *
 * <p>This is deliberately separate from the wire dialect. Providers can share
 * a request shape while counting reasoning tokens differently.</p>
 */
public enum ModelTokenAccounting implements DictEnum<String> {

	/** A separate provider reasoning budget is available and must be reserved. */
	SEPARATE_REASONING_BUDGET,

	/** The provider's output limit includes hidden reasoning tokens. */
	MAX_TOKENS_INCLUDES_REASONING,

	/** Hidden reasoning tokens do not consume the configured output limit. */
	OUTPUT_ONLY,

	/** The provider contract is not known well enough to calculate a safe route budget. */
	UNKNOWN;

	@Override
	public String getValue() {
		return name();
	}

	@Override
	public String getLabel() {
		return name();
	}

}
