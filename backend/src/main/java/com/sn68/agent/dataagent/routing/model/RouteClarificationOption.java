/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.model;

/** Business-facing option with its internal route kept server-side. */
public record RouteClarificationOption(String optionId, String label, String value, RouteSelection selection) {
}
