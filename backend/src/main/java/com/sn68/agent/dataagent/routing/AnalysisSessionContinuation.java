/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.sn68.agent.dataagent.routing;

import org.springframework.util.StringUtils;

/**
 * 同会话沿用上一轮只读技能。
 * <p>
 * 产品按钮会带固定文案「同一分析会话 / 更细粒度」，这是前端契约，不是用户说法词表。
 * 普通追问是否沿用，由路由分数「当前问句能否独立命中技能」决定，见
 * {@code HybridRouteCoordinator}，不在这里枚举中文指代。
 */
public final class AnalysisSessionContinuation {

	private AnalysisSessionContinuation() {
	}

	public static boolean matches(String query) {
		if (!StringUtils.hasText(query)) {
			return false;
		}
		String compact = query.replaceAll("\\s+", "");
		return compact.contains("同一分析会话") || compact.contains("更细粒度");
	}

}
