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
package com.sn68.agent.dataagent.agentscope.tool.semantic;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 语义模型检索结果。
 */
@Schema(description = "语义模型检索结果")
@Data
@Builder
public class SemanticModelSearchResult {

	@Schema(description = "匹配结论")
	private String resolution;

	@Schema(description = "检索摘要")
	private String summary;

	@Schema(description = "命中语义模型")
	@Builder.Default
	private List<SemanticModelSearchHit> hits = new ArrayList<>();

}
