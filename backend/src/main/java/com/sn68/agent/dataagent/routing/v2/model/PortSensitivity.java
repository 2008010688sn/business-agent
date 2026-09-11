/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2.model;

/**
 * 能力端口敏感级别,级别单调递增;编译期校验绑定不得越过上下文授权的敏感级别上限。
 */
public enum PortSensitivity {

	PUBLIC(0),

	INTERNAL(1),

	CONFIDENTIAL(2),

	SECRET(3);

	private final int level;

	PortSensitivity(int level) {
		this.level = level;
	}

	public int level() {
		return level;
	}

	/** 当前敏感级别是否在给定授权上限之内。 */
	public boolean allowedWithin(PortSensitivity clearance) {
		return clearance != null && level <= clearance.level;
	}

}
