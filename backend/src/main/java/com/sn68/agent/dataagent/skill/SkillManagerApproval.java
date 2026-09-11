/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 技能运行时配置 {@code requireManagerApproval}：缺省 / 缺键 / 非法值均为关闭。
 */
public final class SkillManagerApproval {

	public static final String CONFIG_KEY = "requireManagerApproval";

	private SkillManagerApproval() {
	}

	public static boolean enabled(Map<String, Object> runtimeConfig) {
		if (runtimeConfig == null || runtimeConfig.isEmpty()) {
			return false;
		}
		return asBoolean(runtimeConfig.get(CONFIG_KEY));
	}

	public static boolean enabled(String runtimeConfigJson, ObjectMapper objectMapper) {
		if (!StringUtils.hasText(runtimeConfigJson) || objectMapper == null) {
			return false;
		}
		try {
			JsonNode node = objectMapper.readTree(runtimeConfigJson);
			return node != null && asBoolean(node.get(CONFIG_KEY));
		}
		catch (Exception ex) {
			return false;
		}
	}

	private static boolean asBoolean(Object value) {
		if (value instanceof Boolean flag) {
			return flag;
		}
		if (value instanceof JsonNode node && node.isBoolean()) {
			return node.booleanValue();
		}
		return false;
	}

}
