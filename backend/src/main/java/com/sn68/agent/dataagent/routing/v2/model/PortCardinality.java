/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 能力端口基数:SINGLE 至多一条绑定,MULTI 允许多条绑定。
 */
public enum PortCardinality {

	SINGLE,

	MULTI

}
