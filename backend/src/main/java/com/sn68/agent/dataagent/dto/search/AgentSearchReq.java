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
package com.sn68.agent.dataagent.dto.search;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * AgentSearch请求。
 */
@Data
@Builder
@Schema(description = "AgentSearch请求")
public class AgentSearchReq implements java.io.Serializable {

	@java.io.Serial
	private static final long serialVersionUID = 1L;

	@Schema(description = "Agent ID")
	private String agentId;

	@Schema(description = "类型")
	private String docVectorType;

	@Schema(description = "相似度阈值")
	@Builder.Default
	private Double similarityThreshold = 0.2;

	@Schema(description = "召回数量")
	private String query;

	@Schema(description = "召回数量")
	private Integer topK;

}
