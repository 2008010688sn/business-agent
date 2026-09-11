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
package com.sn68.agent.dataagent.service.analysis;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sn68.agent.dataagent.service.analysis.AnalysisConfigParser.ParseResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Binding-layer policy for QUERY analysis skills: multiple JDBC when analysisConfig is valid,
 * and FILE_ONLY does not require a JDBC.
 */
public final class AnalysisBindingSupport {

	public enum Mode {
		STANDARD,
		MULTI_JDBC,
		FILE_ONLY
	}

	private AnalysisBindingSupport() {
	}

	public static Mode mode(String skillKind, String analysisConfigJson) {
		if (!"QUERY".equals(skillKind) || !StringUtils.hasText(analysisConfigJson)) {
			return Mode.STANDARD;
		}
		ParseResult parsed = new AnalysisConfigParser().parse(readMap(analysisConfigJson));
		if (!parsed.valid()) {
			return Mode.STANDARD;
		}
		return parsed.config().fileOnly() ? Mode.FILE_ONLY : Mode.MULTI_JDBC;
	}

	public static boolean allowsMultipleJdbc(Mode mode) {
		return mode == Mode.MULTI_JDBC || mode == Mode.FILE_ONLY;
	}

	public static boolean requiresJdbc(String skillKind, Mode mode) {
		return "QUERY".equals(skillKind) && mode != Mode.FILE_ONLY;
	}

	private static Map<String, Object> readMap(String json) {
		try {
			JSONObject object = JSONUtil.parseObj(json);
			if (object == null || object.isEmpty()) {
				return Map.of();
			}
			Map<String, Object> result = new LinkedHashMap<>();
			object.forEach((key, value) -> result.put(String.valueOf(key), value));
			return result;
		}
		catch (Exception ignored) {
			return Map.of();
		}
	}
}
