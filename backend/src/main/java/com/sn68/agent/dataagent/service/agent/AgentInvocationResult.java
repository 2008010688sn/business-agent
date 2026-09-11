/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.ui.AgentUiMessage;

/**
 * 同步调用的答案与本次运行产生的公开 UI 消息。
 */
public record AgentInvocationResult(String answer, AgentUiMessage uiMessage) {
}
