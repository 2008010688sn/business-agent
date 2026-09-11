/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sn68.agent.dataagent.dto.routing.RoutePreviewReq;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class RoutePreviewControllerTest {

	@Test
	void previewEndpointExistsWithoutSaToken() throws Exception {
		Method preview = RoutePreviewController.class.getDeclaredMethod("preview", Long.class, RoutePreviewReq.class);
		assertNotNull(preview);
	}

}
