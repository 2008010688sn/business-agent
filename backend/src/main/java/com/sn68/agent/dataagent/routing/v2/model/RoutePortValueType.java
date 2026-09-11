/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 能力端口的取值类别。
 *
 * <p>TEXT 只允许作为终端展示或用户输入,不允许作为步骤间的 STEP_OUTPUT 绑定类型
 * (方案第五章:自然语言回答不能作为下游步骤输入)。
 */
public enum RoutePortValueType {

	ID,

	FILTER,

	STATISTIC,

	STRUCT,

	TEXT

}
