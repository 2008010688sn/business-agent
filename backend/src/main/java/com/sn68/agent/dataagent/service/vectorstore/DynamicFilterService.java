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
package com.sn68.agent.dataagent.service.vectorstore;

import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.repository.BusinessKnowledgeMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 向量检索过滤表达式服务：按 Agent/技能与向量类型构建动态过滤条件。
 */
@Slf4j
@Component
@AllArgsConstructor
public class DynamicFilterService {

	private final BusinessKnowledgeMapper businessKnowledgeMapper;

	public Filter.Expression buildDynamicFilter(String agentId, String vectorType) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();

		// 必须条件
		conditions.add(b.eq(Constant.AGENT_ID, agentId).build());
		conditions.add(b.eq(DocumentMetadataConstant.VECTOR_TYPE, vectorType).build());

		// 组合所有条件
		return combineWithAnd(conditions);
	}

	/**
	 * Builds a filter for resources that are owned by a Skill. Agent knowledge uses a
	 * separate agentId namespace and must not be mixed into this filter.
	 */
	public Filter.Expression buildSkillDynamicFilter(String skillId, String vectorType) {
		return buildSkillDynamicFilter(skillId, vectorType, null);
	}

	/**
	 * 在 skillId + vectorType 之上再叠加一层「存活行白名单」。
	 *
	 * <p>数据库与向量库是两套存储，删除只能先后进行，中间必然存在窗口，向量清理本身也可能失败。只按
	 * skillId + vectorType 过滤时，已逻辑删除或已取消召回的记录，其残留向量仍会被检索到并挤占 topK。
	 * 把调用方已经从数据库算好的存活行主键下推成 IN 条件后，即便向量清理彻底失败，检索侧也不会返回这些
	 * 内容——这是失败安全的方向：向量库落后于数据库时结果依然正确。
	 * @param allowedResourceIds 存活且允许召回的业务主键。{@code null} 表示调用方不做行级约束（保留旧
	 * 行为）；空集合表示没有任何可召回内容，与既有约定一致返回 {@code null} 让调用方直接短路
	 */
	public Filter.Expression buildSkillDynamicFilter(String skillId, String vectorType,
			@Nullable Collection<Long> allowedResourceIds) {
		FilterExpressionBuilder builder = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();
		conditions.add(builder.eq(Constant.SKILL_ID, skillId).build());
		conditions.add(builder.eq(DocumentMetadataConstant.VECTOR_TYPE, vectorType).build());

		if (allowedResourceIds == null) {
			if (DocumentMetadataConstant.BUSINESS_TERM.equals(vectorType)
					&& businessKnowledgeMapper.selectRecalledKnowledgeIds(Long.valueOf(skillId)).isEmpty()) {
				log.warn("Skill {} has no recalled business terms. Returning empty filter signal.", skillId);
				return null;
			}
			return combineWithAnd(conditions);
		}
		if (allowedResourceIds.isEmpty()) {
			log.warn("Skill {} has no live recallable rows for vectorType={}. Returning empty filter signal.", skillId,
					vectorType);
			return null;
		}
		conditions.add(builder.in(resourceIdMetadataKey(vectorType), allowedResourceIds.stream()
			.filter(Objects::nonNull)
			.distinct()
			.map(id -> (Object) String.valueOf(id))
			.toList()).build());
		return combineWithAnd(conditions);
	}

	/**
	 * 文档元数据中承载业务主键的 key。写入侧统一以字符串存该值（见 {@code DocumentConverterUtil}），
	 * 因此下推白名单时也必须按字符串比较，否则雪花 ID 会因类型不符全部漏匹配。
	 */
	static String resourceIdMetadataKey(String vectorType) {
		if (DocumentMetadataConstant.BUSINESS_TERM.equals(vectorType)) {
			return DocumentMetadataConstant.DB_BUSINESS_TERM_ID;
		}
		if (DocumentMetadataConstant.SKILL_KNOWLEDGE.equals(vectorType)) {
			return DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID;
		}
		// 白名单无处可落等于失败开放，宁可让调用方在开发期直接失败，也不要静默退回无行级约束的检索
		throw new IllegalArgumentException("No resource id metadata key for vectorType: " + vectorType);
	}

	/**
	 * 将多个过滤条件用 AND 连接起来
	 * @param conditions 条件列表
	 * @return 组合后的 Expression
	 */
	public static Filter.Expression combineWithAnd(List<Filter.Expression> conditions) {
		// 1. 判空
		if (conditions == null || conditions.isEmpty()) {
			return null;
		}

		// 2. 如果只有一个条件，直接返回，不用拼 AND
		if (conditions.size() == 1) {
			return conditions.get(0);
		}

		// 3. 核心逻辑：循环两两拼接
		// 初始结果是第一个条件
		Filter.Expression result = conditions.get(0);

		// 从第二个条件开始遍历
		for (int i = 1; i < conditions.size(); i++) {
			Filter.Expression nextCondition = conditions.get(i);

			// 手动创建 Expression 对象
			// 结构：(Result AND Next)
			result = new Filter.Expression(Filter.ExpressionType.AND, // 指定操作符
					result, // 左节点(累加的结果)
					nextCondition // 右节点(当前条件)
			);
		}

		return result;
	}

	public static String buildFilterExpressionString(Map<String, Object> filterMap) {
		if (filterMap == null || filterMap.isEmpty()) {
			return null;
		}

		// 验证键名是否合法（只包含字母、数字和下划线）
		for (String key : filterMap.keySet()) {
			if (!key.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
				throw new IllegalArgumentException("Invalid key name: " + key
						+ ". Keys must start with a letter or underscore and contain only alphanumeric characters and underscores.");
			}
		}

		return filterMap.entrySet().stream().map(entry -> {
			String key = entry.getKey();
			Object value = entry.getValue();

			// 处理空值
			if (value == null) {
				return key + " == null";
			}

			// 根据值的类型决定如何格式化
			if (value instanceof String) {
				// 转义字符串中的特殊字符
				String escapedValue = escapeStringLiteral((String) value);
				return key + " == '" + escapedValue + "'";
			}
			else if (value instanceof Number) {
				// 数字类型直接使用
				return key + " == " + value;
			}
			else if (value instanceof Boolean) {
				// 布尔值使用小写形式
				return key + " == " + ((Boolean) value).toString().toLowerCase();
			}
			else if (value instanceof Enum) {
				// 枚举类型，转换为字符串并转义
				String enumValue = ((Enum<?>) value).name();
				String escapedValue = escapeStringLiteral(enumValue);
				return key + " == '" + escapedValue + "'";
			}
			else {
				// 其他类型尝试转换为字符串并转义
				String stringValue = value.toString();
				String escapedValue = escapeStringLiteral(stringValue);
				return key + " == '" + escapedValue + "'";
			}
		}).collect(Collectors.joining(" && "));
	}

	/**
	 * 转义字符串字面量中的特殊字符
	 */
	public static String escapeStringLiteral(String input) {
		if (input == null) {
			return "";
		}

		// 转义反斜杠和单引号
		String escaped = input.replace("\\", "\\\\").replace("'", "\\'");

		// 转义其他特殊字符
		escaped = escaped.replace("\n", "\\n")
			.replace("\r", "\\r")
			.replace("\t", "\\t")
			.replace("\b", "\\b")
			.replace("\f", "\\f");

		return escaped;
	}

	public static Filter.Expression buildFilterExpressionForSearchTables(String skillId, Long datasourceId,
			List<String> tableNames) {
		FilterExpressionBuilder b = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();

		// 1. 基础条件：agentId
		conditions.add(b.eq(Constant.SKILL_ID, skillId).build());

		// 2. 基础条件：datasourceId
		conditions.add(b.eq(Constant.DATASOURCE_ID, datasourceId.toString()).build());

		// 3. 基础条件：vectorType = TABLE
		conditions.add(b.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE).build());

		// 4. 动态条件：表名列表 IN 查询
		if (tableNames != null && !tableNames.isEmpty()) {
			conditions.add(b.in(DocumentMetadataConstant.NAME, tableNames.toArray()).build());
		}
		else {
			log.warn("Table names list is empty. Returning empty filter signal.");
			return null;
		}
		return combineWithAnd(conditions);
	}

	public Filter.Expression buildFilterExpressionForSearchColumns(String skillId, Long datasourceId,
			List<String> upstreamTableNames) {
		if (upstreamTableNames == null || upstreamTableNames.isEmpty()) {
			log.warn("Upstream table names list is empty. Returning empty filter signal.");
			return null;
		}

		FilterExpressionBuilder b = new FilterExpressionBuilder();
		List<Filter.Expression> conditions = new ArrayList<>();

		// 1. AgentId 条件
		conditions.add(b.eq(Constant.SKILL_ID, skillId).build());

		// 2. DatasourceId 条件
		conditions.add(b.eq(Constant.DATASOURCE_ID, datasourceId.toString()).build());

		// 3. VectorType 条件
		conditions.add(b.eq(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.COLUMN).build());

		// 4. TableName 条件
		conditions.add(b.in(DocumentMetadataConstant.TABLE_NAME, upstreamTableNames.toArray()).build());

		return combineWithAnd(conditions);
	}

}
