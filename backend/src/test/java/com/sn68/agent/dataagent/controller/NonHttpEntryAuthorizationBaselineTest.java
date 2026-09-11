/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

/**
 * Standalone 已移除 Snail Job / RocketMQ 入口；本测试只确认 EventListener 仍可解析。
 */
class NonHttpEntryAuthorizationBaselineTest {

	@Test
	@DisplayName("EventListener 注解仍可用于非 HTTP 入口")
	void eventListenerAnnotationIsAvailable() {
		assertTrue(EventListener.class.isAnnotation());
	}

}
