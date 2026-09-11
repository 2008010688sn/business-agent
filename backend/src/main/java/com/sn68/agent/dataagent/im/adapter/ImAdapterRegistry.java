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
package com.sn68.agent.dataagent.im.adapter;

import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * IM 平台适配器注册表。
 */
@Component
public class ImAdapterRegistry {

	private final Map<String, ImAdapter> adapters;

	public ImAdapterRegistry(List<ImAdapter> adapters) {
		this.adapters = adapters.stream()
			.collect(Collectors.toMap(adapter -> adapter.provider().toUpperCase(), Function.identity()));
	}

	public ImAdapter get(String provider) {
		if (!StringUtils.hasText(provider)) {
			throw CheckedException.badRequest(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(),
					ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		ImAdapter adapter = adapters.get(provider.trim().toUpperCase());
		if (adapter == null) {
			throw CheckedException.badRequest(ImErrorDict.CONNECTOR_NOT_FOUND.getValue(),
					ImErrorDict.CONNECTOR_NOT_FOUND.getLabel());
		}
		return adapter;
	}

}
