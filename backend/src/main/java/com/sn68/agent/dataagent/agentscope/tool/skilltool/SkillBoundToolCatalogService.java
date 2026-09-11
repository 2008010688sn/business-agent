/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.agentscope.tool.skilltool;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按技能版本获取其绑定工具的回调目录：入参不完整时返回空集合，由版本化工厂完成实际装配。
 */
@Service
@RequiredArgsConstructor
public class SkillBoundToolCatalogService {

	private final VersionedSkillToolCallbackFactory versionedToolCallbackFactory;

	public Map<String, ToolCallback> getToolCallbacks(String agentId, String skillCode, Long skillVersionId) {
		if (!StringUtils.hasText(skillCode) || skillVersionId == null) {
			return Map.of();
		}
		return versionedToolCallbackFactory.create(agentId, skillCode.trim(), skillVersionId);
	}

}
