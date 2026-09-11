/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.tool;

import java.util.Map;

/**
 * Versioned execution-resource invocation boundary.
 */
public interface ToolInvoker {

	Map<String, Object> invoke(ToolInvocationContext context);

}
