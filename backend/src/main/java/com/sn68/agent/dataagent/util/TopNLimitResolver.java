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
package com.sn68.agent.dataagent.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.apache.commons.lang3.StringUtils;

/**
 * 统一解析用户 TopN 诉求和 SQL 最外层结果限制。
 */
public final class TopNLimitResolver {

	private static final Pattern TOP_N_QUERY_PATTERN = Pattern.compile(
			"(?i)(?:top\\s*(\\d+)|前\\s*(\\d+)\\s*(?:个|名|条)?|排名前\\s*(\\d+)\\s*(?:个|名|条)?|排行前\\s*(\\d+)\\s*(?:个|名|条)?)");

	private static final Pattern RANKING_HINT_PATTERN = Pattern.compile("排行|排名|(?i)top\\s*\\d+");

	private TopNLimitResolver() {
	}

	public static boolean isRankingQuery(String query) {
		String text = StringUtils.defaultString(query);
		return extractRequestedRows(text).isPresent() || RANKING_HINT_PATTERN.matcher(text).find();
	}

	public static Optional<Integer> extractRequestedRows(String query) {
		Matcher matcher = TOP_N_QUERY_PATTERN.matcher(StringUtils.defaultString(query));
		if (!matcher.find()) {
			return Optional.empty();
		}
		for (int index = 1; index <= matcher.groupCount(); index++) {
			Integer value = positiveInteger(matcher.group(index));
			if (value != null) {
				return Optional.of(value);
			}
		}
		return Optional.empty();
	}

	public static Optional<Integer> extractOuterLimit(Select select) {
		if (select == null) {
			return Optional.empty();
		}
		if (select.getLimit() != null && !select.getLimit().isLimitAll() && !select.getLimit().isLimitNull()) {
			Integer value = expressionValue(select.getLimit().getRowCount());
			if (value != null) {
				return Optional.of(value);
			}
		}
		if (select.getFetch() != null) {
			Integer value = expressionValue(select.getFetch().getExpression());
			long rowCount = select.getFetch().getRowCount();
			if (value == null && rowCount > 0 && rowCount <= Integer.MAX_VALUE) {
				value = (int) rowCount;
			}
			if (value != null) {
				return Optional.of(value);
			}
		}
		if (select instanceof PlainSelect plainSelect && plainSelect.getTop() != null
				&& !plainSelect.getTop().isPercentage()) {
			return Optional.ofNullable(expressionValue(plainSelect.getTop().getExpression()));
		}
		return Optional.empty();
	}

	public static boolean hasOuterLimit(Select select) {
		return select != null
				&& ((select.getLimit() != null && !select.getLimit().isLimitAll() && !select.getLimit().isLimitNull())
						|| select.getFetch() != null || (select instanceof PlainSelect plainSelect
								&& plainSelect.getTop() != null && !plainSelect.getTop().isPercentage()));
	}

	private static Integer expressionValue(Expression expression) {
		if (!(expression instanceof LongValue longValue)) {
			return null;
		}
		long value = longValue.getValue();
		return value > 0 && value <= Integer.MAX_VALUE ? (int) value : null;
	}

	private static Integer positiveInteger(String value) {
		if (StringUtils.isBlank(value)) {
			return null;
		}
		try {
			int parsed = Integer.parseInt(value.trim());
			return parsed > 0 ? parsed : null;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
