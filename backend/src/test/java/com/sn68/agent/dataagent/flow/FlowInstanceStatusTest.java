/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlowInstanceStatusTest {

    @Test
    void processingIsAnActiveFlowStatus() {
        assertTrue(FlowInstanceStatus.PROCESSING.active());
        assertTrue(FlowInstanceStatus.activeStatuses().contains(FlowInstanceStatus.PROCESSING.name()));
    }

}
