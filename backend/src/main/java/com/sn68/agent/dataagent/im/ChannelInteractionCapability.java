/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.im;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 通道支持的交互能力。能力由服务端连接器决定，客户端不能自行声明。枚举值恒等于 name，仅补充中文标签。
 */
public enum ChannelInteractionCapability implements DictEnum<String> {

	STRUCTURED_ACTIONS("结构化交互"),

	TEXT_COMMANDS("文本指令");

	private final String label;

	ChannelInteractionCapability(String label) {
		this.label = label;
	}

	@Override
	public String getValue() {
		return name();
	}

	@Override
	public String getLabel() {
		return label;
	}

}
