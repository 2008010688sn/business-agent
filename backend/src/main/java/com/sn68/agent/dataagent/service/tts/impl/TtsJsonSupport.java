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
package com.sn68.agent.dataagent.service.tts.impl;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * TTS JSON 辅助：统一 options 字段的容错读取与序列化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TtsJsonSupport {

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final ObjectMapper objectMapper;

	public Map<String, Object> readObject(String json) {
		if (!StringUtils.hasText(json)) {
			return new LinkedHashMap<>();
		}
		try {
			Map<String, Object> value = objectMapper.readValue(json, MAP_TYPE);
			return value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
		}
		catch (Exception ex) {
			log.warn("Failed to parse TTS JSON object, falling back to an empty map. length={}", json.length(), ex);
			return new LinkedHashMap<>();
		}
	}

	public String writeObject(Map<String, Object> value) {
		try {
			Map<String, Object> normalized = value == null ? Map.of() : value;
			return objectMapper.writeValueAsString(normalized);
		}
		catch (Exception ex) {
			// "{}" is persisted as if it were a real config, so the loss has to be traceable.
			log.warn("Failed to serialize TTS JSON object, persisting an empty object instead. keyCount={}",
					value == null ? 0 : value.size(), ex);
			return "{}";
		}
	}

}
