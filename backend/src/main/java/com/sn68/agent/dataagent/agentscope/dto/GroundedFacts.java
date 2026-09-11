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
package com.sn68.agent.dataagent.agentscope.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 本轮链接解析结果。仅服务端写入根请求，不进协作者可见性拷贝。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroundedFacts {

	@Builder.Default
	private List<GroundedKey> keys = new ArrayList<>();

	private boolean ownOrigin;

	private String failOpenReason;

	public static GroundedFacts failOpen(String reason) {
		return GroundedFacts.builder().failOpenReason(reason).keys(new ArrayList<>()).build();
	}

	public boolean isEmpty() {
		return CollectionUtils.isEmpty(keys) && !StringUtils.hasText(failOpenReason);
	}

	public List<GroundedKey> trustedKeys() {
		if (CollectionUtils.isEmpty(keys)) {
			return List.of();
		}
		List<GroundedKey> trusted = new ArrayList<>();
		for (GroundedKey key : keys) {
			if (key != null && key.trustedForLookup()) {
				trusted.add(key);
			}
		}
		return List.copyOf(trusted);
	}

}
