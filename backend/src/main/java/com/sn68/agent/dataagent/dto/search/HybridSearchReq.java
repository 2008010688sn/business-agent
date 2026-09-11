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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 混合检索请求：融合向量检索与关键词检索，按权重合并结果，支持可选重排序。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "混合检索请求")
public class HybridSearchReq implements Serializable {

	// === 基础参数 ===
	@Schema(description = "检索问题文本")
	private String query;

	@Schema(description = "召回数量")
	private Integer topK;

	@Schema(description = "相似度阈值")
	@Builder.Default
	private double similarityThreshold = 0.0;

	@Schema(description = "向量检索过滤表达式")
	private Filter.Expression filterExpression;

	// 向量检索权重
	@Schema(description = "向量检索权重")
	@Builder.Default
	private Double vectorWeight = 0.5;

	// 关键词检索权重
	@Schema(description = "关键词检索权重")
	@Builder.Default
	private Double keywordWeight = 0.5;

	// 是否开启重排序模型
	@Schema(description = "是否启用重排序模型")
	@Builder.Default
	private boolean useRerank = false;

	// 扩展参数包将来某种数据库的特有参数
	@Schema(description = "扩展参数（特定向量库的私有参数）")
	@Builder.Default
	private Map<String, Object> extraParams = new HashMap<>();

	/**
	 * 转换为 Spring AI 向量检索请求，仅携带向量检索相关参数。
	 */
	public org.springframework.ai.vectorstore.SearchRequest toVectorSearchRequest() {
		return org.springframework.ai.vectorstore.SearchRequest.builder()
			.query(this.query)
			.topK(this.topK)
			.similarityThreshold(this.similarityThreshold)
			.filterExpression(this.filterExpression)
			.build();
	}

}
