/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.framework.commons.annotation.log.AccessLog;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AgentMemoryControllerExportGuardTest {

	@Test
	void exportOmitsResponseBodyFromAccessLog() throws NoSuchMethodException {
		Method export = AgentMemoryController.class.getDeclaredMethod("exportMemories", Long.class, MemoryScope.class,
				String.class);
		AccessLog accessLog = export.getAnnotation(AccessLog.class);
		assertFalse(accessLog.response());
	}

}
