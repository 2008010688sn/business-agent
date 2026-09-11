/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.event;

import java.time.Clock;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Signals that a persisted model configuration changed and dependent runtime
 * capability snapshots must be refreshed.
 */
@Getter
public class ModelConfigChangedEvent extends ApplicationEvent {

	private final Long modelConfigId;

	public ModelConfigChangedEvent(Object source, Long modelConfigId) {
		super(source, Clock.systemDefaultZone());
		this.modelConfigId = modelConfigId;
	}

}
