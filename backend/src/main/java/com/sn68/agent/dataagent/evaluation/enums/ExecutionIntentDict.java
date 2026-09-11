/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.evaluation.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import org.springframework.util.StringUtils;

/**
 * 评估运行执行意图（方案第十四章）。DRY_RUN 为自进化候选离线评估的强制意图：
 * 禁写、禁外部副作用，命中即拦截并记违规。
 */
public enum ExecutionIntentDict implements DictEnum<String> {

	LIVE("LIVE", "在线执行"),

	DRY_RUN("DRY_RUN", "离线干跑（禁写、禁外部副作用）");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	ExecutionIntentDict(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

	/** 归一化外部传入的意图：空值回落 LIVE，非法值返回 null 由调用方失败关闭。 */
	public static ExecutionIntentDict normalize(String value) {
		if (!StringUtils.hasText(value)) {
			return LIVE;
		}
		String normalized = value.trim().toUpperCase();
		for (ExecutionIntentDict intent : values()) {
			if (intent.value.equals(normalized)) {
				return intent;
			}
		}
		return null;
	}

}
