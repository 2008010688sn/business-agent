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
package com.sn68.agent.dataagent.service.report;

import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.SemanticHitView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.util.StringUtils;

/**
 * 把 SEARCH 结果列定成业务语言：目录/语义名 + SQL 表达式语法，不按智能体写死字段表。
 */
public final class SearchResultColumnNamer {

	private static final Pattern CHINESE_TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]{2,16}");

	private static final Set<String> IGNORED_HINTS = Set.of("否则", "为空", "空值", "等于", "不等于", "大于", "小于", "或者",
			"并且", "条件", "真", "假");

	private static final Map<String, String> KNOWN_NAMES = Map.ofEntries(Map.entry("product_name", "产品名称"),
			Map.entry("product_no", "产品编号"), Map.entry("product_count", "产品数"), Map.entry("company_name", "客户名称"),
			Map.entry("customer_name", "客户名称"), Map.entry("name", "客户名称"), Map.entry("use_box_count", "用箱量"),
			Map.entry("project_name", "项目名称"), Map.entry("one_project_name", "一级项目"),
			Map.entry("two_project_name", "二级项目"), Map.entry("project_count", "项目数"), Map.entry("bill_num", "账单数"),
			Map.entry("bill_no", "账单编号"), Map.entry("amount", "金额"), Map.entry("amt", "金额"),
			Map.entry("total_amount", "总金额"), Map.entry("sum_amount", "总金额"), Map.entry("bill_amount", "账单金额"),
			Map.entry("fare", "运费"), Map.entry("total_fare", "总运费"), Map.entry("return_fare", "回箱运费"),
			Map.entry("total_return_fare", "回箱运费"), Map.entry("consumable_fare", "耗材费"),
			Map.entry("total_consumable_fare", "耗材费"), Map.entry("num", "数量"), Map.entry("quantity", "数量"),
			Map.entry("qty", "数量"), Map.entry("total_quantity", "总数量"), Map.entry("cnt", "数量"),
			Map.entry("count", "数量"), Map.entry("total_count", "总数"), Map.entry("bill_count", "账单数"),
			Map.entry("order_count", "订单数"), Map.entry("demand_count", "需求数"), Map.entry("price", "单价"),
			Map.entry("total_price", "租金/商品价"), Map.entry("settlement_date", "结算周期"),
			Map.entry("settlement_cycle", "结算周期"), Map.entry("create_time", "生成时间"), Map.entry("status", "状态"),
			Map.entry("contract_type", "账单类型"), Map.entry("settlement_node", "结算节点"),
			Map.entry("from_site_name", "发货网点"), Map.entry("to_site_name", "收货网点"), Map.entry("dispatch_num", "调度数"),
			Map.entry("dispatch_count", "调度数"), Map.entry("out_num", "出库数"), Map.entry("out_count", "出库数"),
			Map.entry("send_num", "发箱数"), Map.entry("send_count", "发箱数"), Map.entry("sign_num", "签收数"),
			Map.entry("sign_count", "签收数"), Map.entry("receive_num", "收箱数"), Map.entry("receive_count", "收箱数"),
			Map.entry("receiver_num", "收箱数"), Map.entry("receiver_count", "收箱数"), Map.entry("service_fee", "服务费"),
			Map.entry("total_service_fee", "服务费"), Map.entry("maintenance_fee", "维修基金"),
			Map.entry("total_maintenance_fee", "维修基金"), Map.entry("adjust_amount", "调整金额"), Map.entry("subsist", "预付款"),
			Map.entry("security_deposit_rate", "质保金比例"), Map.entry("bail", "押金"), Map.entry("site_name", "网点名称"),
			Map.entry("metric", "指标"), Map.entry("value", "金额"), Map.entry("deleted", "删除标识"));

	private static final Map<String, String> SUFFIX_NAMES = Map.ofEntries(Map.entry("name", "名称"),
			Map.entry("amount", "金额"), Map.entry("amt", "金额"), Map.entry("count", "数量"), Map.entry("cnt", "数量"),
			Map.entry("qty", "数量"), Map.entry("num", "数量"), Map.entry("quantity", "数量"), Map.entry("fee", "费用"),
			Map.entry("fare", "运费"), Map.entry("price", "单价"), Map.entry("rate", "比例"), Map.entry("status", "状态"),
			Map.entry("type", "类型"), Map.entry("time", "时间"), Map.entry("date", "日期"), Map.entry("days", "天数"),
			Map.entry("hours", "小时"));

	/**
	 * 列名尾部标识具备数值指标语义倾向的 token 集合，用于兜底名在"指标"与"分类"之间选择。
	 */
	private static final Set<String> METRIC_SUFFIX_TOKENS = Set.of("total", "sum", "amount", "amt", "num", "count",
			"cnt", "qty", "fee", "fare", "price", "rate", "avg", "max", "min");

	private SearchResultColumnNamer() {
	}

	public static Lexicon lexicon() {
		return new Lexicon();
	}

	public static Map<String, String> resolve(List<String> columns, String sql, Lexicon lexicon) {
		Lexicon names = lexicon == null ? new Lexicon() : lexicon;
		List<SelectItemSpec> specs = parseSelectItems(sql);
		String fromTable = parseFromTable(sql);
		Map<String, String> resolved = new LinkedHashMap<>();
		Set<String> used = new LinkedHashSet<>();
		if (columns == null) {
			return resolved;
		}
		for (int index = 0; index < columns.size(); index++) {
			String column = columns.get(index);
			if (!StringUtils.hasText(column)) {
				continue;
			}
			SelectItemSpec spec = matchSpec(specs, column, index);
			String display = uniqueName(resolveOne(column, spec, fromTable, names), used);
			resolved.put(column, display);
		}
		return resolved;
	}

	/**
	 * 用户面列名硬不变量的唯一出口：RESULT_SET 列头与 data keys 永不出现含 _/. 或驼峰的物理字段原文。
	 * 已是中文或安全短名的列原样返回；否则走已知链解析（含通用后缀合成），候选仍不安全时按数值指标
	 * 语义倾向兜底为"指标"，其余兜底为"分类"。方法幂等、无状态。
	 */
	public static String enforceUserFacing(String column) {
		if (!StringUtils.hasText(column)) {
			return "分类";
		}
		String trimmed = column.trim();
		if (isUserFacingName(trimmed)) {
			return trimmed;
		}
		String candidate = firstText(knownName(trimmed), composedSuffixName(trimmed), heuristicName(trimmed));
		if (isUserFacingName(candidate)) {
			return candidate;
		}
		return isMetricTendencyColumn(trimmed) ? "指标" : "分类";
	}

	public static boolean isMetricLabel(String name) {
		if (!StringUtils.hasText(name) || ReportColumnSemantics.isGenericPlaceholder(name)) {
			return false;
		}
		String trimmed = name.trim();
		return trimmed.contains("金额") || trimmed.contains("数量") || trimmed.contains("平均值") || trimmed.contains("合计")
				|| trimmed.contains("费用") || trimmed.contains("费") || trimmed.endsWith("数") || trimmed.endsWith("量")
				|| trimmed.contains("最大值") || trimmed.contains("最小值");
	}

	private static String resolveOne(String column, SelectItemSpec spec, String fromTable, Lexicon lexicon) {
		String header = lexicon.header(column);
		if (isSafeBusinessName(header)) {
			return header;
		}
		if (isSafeBusinessName(column) && !ReportColumnSemantics.isGenericPlaceholder(column)) {
			return column.trim();
		}
		if (spec != null && isSafeBusinessName(spec.alias) && !ReportColumnSemantics.isGenericPlaceholder(spec.alias)) {
			return spec.alias.trim();
		}
		String known = firstText(lexicon.label(column), knownName(column), composedSuffixName(column),
				heuristicName(column));
		if (spec != null) {
			String composed = compose(spec, fromTable, lexicon);
			if (isGenericFallback(composed) && isSafeBusinessName(known)) {
				return known;
			}
			if (isSafeBusinessName(composed)) {
				return composed;
			}
		}
		if (isSafeBusinessName(known)) {
			return known;
		}
		if (spec != null && spec.hasFunction()) {
			return functionFallback(spec.function);
		}
		// 兜底出口：物理原文（含 _/./驼峰）绝不作为最终 display，交由 uniqueName 落到"分类"系兜底名。
		return isUserFacingName(column) ? column.trim() : "";
	}

	private static String compose(SelectItemSpec spec, String fromTable, Lexicon lexicon) {
		String sourceLabel = sourceLabel(spec, lexicon);
		String hint = sanitizeHint(spec.caseHint);
		String function = spec.function == null ? "" : spec.function.toLowerCase(Locale.ROOT);
		if (StringUtils.hasText(hint)) {
			return combineHint(hint, firstText(sourceLabel, functionFallback(function)));
		}
		if (function.startsWith("avg") || "average".equals(function) || "mean".equals(function)) {
			return prefixMeasure("平均", firstText(sourceLabel, "值"), "平均值");
		}
		if (function.startsWith("sum")) {
			return prefixMeasure("总", firstText(sourceLabel, "计"), "合计");
		}
		if (function.startsWith("count")) {
			if (spec.countStar) {
				String tableLabel = firstText(lexicon.table(spec.table), lexicon.table(fromTable));
				return StringUtils.hasText(tableLabel) ? ensureCountSuffix(tableLabel) : "数量";
			}
			return StringUtils.hasText(sourceLabel) ? ensureCountSuffix(sourceLabel) : "数量";
		}
		if (function.startsWith("max")) {
			return prefixMeasure("最大", firstText(sourceLabel, "值"), "最大值");
		}
		if (function.startsWith("min")) {
			return prefixMeasure("最小", firstText(sourceLabel, "值"), "最小值");
		}
		return sourceLabel;
	}

	private static String sourceLabel(SelectItemSpec spec, Lexicon lexicon) {
		if (spec.sourceColumns.isEmpty()) {
			return "";
		}
		for (String source : spec.sourceColumns) {
			String label = firstText(lexicon.label(spec.table, source), lexicon.label(source), knownName(source),
					heuristicName(source));
			if (isSafeBusinessName(label)) {
				return label;
			}
		}
		return "";
	}

	private static String combineHint(String hint, String measure) {
		if (!StringUtils.hasText(measure) || hint.contains(measure) || measure.equals("分类") || measure.equals("值")) {
			return hint;
		}
		if (hint.endsWith("数") || hint.endsWith("量") || hint.endsWith("额") || hint.endsWith("费")) {
			return hint;
		}
		return hint + stripLeadingTotal(measure);
	}

	private static String prefixMeasure(String prefix, String label, String fallback) {
		if (!isSafeBusinessName(label) || "值".equals(label) || "计".equals(label)) {
			return fallback;
		}
		String measure = stripLeadingTotal(label);
		if (measure.startsWith(prefix)) {
			return measure;
		}
		return prefix + measure;
	}

	private static String stripLeadingTotal(String label) {
		if (label != null && label.startsWith("总") && label.length() > 1) {
			return label.substring(1);
		}
		return label == null ? "" : label;
	}

	private static String ensureCountSuffix(String label) {
		if (label.endsWith("数") || label.endsWith("量")) {
			return label;
		}
		return label + "数";
	}

	private static boolean isGenericFallback(String name) {
		return "数量".equals(name) || "合计".equals(name) || "平均值".equals(name) || "最大值".equals(name)
				|| "最小值".equals(name) || "分类".equals(name);
	}

	private static String functionFallback(String function) {
		String normalized = function == null ? "" : function.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("avg") || "average".equals(normalized) || "mean".equals(normalized)) {
			return "平均值";
		}
		if (normalized.startsWith("sum")) {
			return "合计";
		}
		if (normalized.startsWith("count")) {
			return "数量";
		}
		if (normalized.startsWith("max")) {
			return "最大值";
		}
		if (normalized.startsWith("min")) {
			return "最小值";
		}
		return "";
	}

	private static String heuristicName(String column) {
		String normalized = normalize(column);
		if (!StringUtils.hasText(normalized)) {
			return "";
		}
		String known = knownName(normalized);
		if (StringUtils.hasText(known)) {
			return known;
		}
		String prefix = "";
		String rest = normalized;
		if (rest.startsWith("avg_") || rest.startsWith("average_") || rest.startsWith("mean_")) {
			prefix = "平均";
			rest = rest.substring(rest.indexOf('_') + 1);
		}
		else if (rest.startsWith("sum_") || rest.startsWith("total_")) {
			prefix = "总";
			rest = rest.substring(rest.indexOf('_') + 1);
		}
		else if (rest.startsWith("max_")) {
			prefix = "最大";
			rest = rest.substring(4);
		}
		else if (rest.startsWith("min_")) {
			prefix = "最小";
			rest = rest.substring(4);
		}
		else if (rest.startsWith("count_")) {
			rest = rest.substring(6);
			String suffix = suffixName(rest);
			return StringUtils.hasText(suffix) ? ensureCountSuffix(suffix) : "数量";
		}
		if (rest.endsWith("_count") || rest.endsWith("_cnt") || rest.endsWith("_num")) {
			String stem = rest.substring(0, rest.lastIndexOf('_'));
			String suffix = firstText(knownName(stem), suffixName(stem), "数量");
			return ensureCountSuffix(suffix);
		}
		String suffix = suffixName(rest);
		if (!StringUtils.hasText(suffix)) {
			return "";
		}
		if (!StringUtils.hasText(prefix)) {
			return suffix;
		}
		return prefixMeasure(prefix, suffix, suffix);
	}

	private static String suffixName(String normalized) {
		if (!StringUtils.hasText(normalized)) {
			return "";
		}
		String known = knownName(normalized);
		if (StringUtils.hasText(known)) {
			return known;
		}
		int index = normalized.lastIndexOf('_');
		String token = index >= 0 && index + 1 < normalized.length() ? normalized.substring(index + 1) : normalized;
		return SUFFIX_NAMES.getOrDefault(token, "");
	}

	/**
	 * 通用后缀合成：对 snake_case 列名按尾部标识（_total/_count/_cnt/_amount/_amt/_fee）用基础段业务名
	 * 组合中文列名，不逐个枚举业务字段。基础段优先取已知映射，再尝试 X_num/X_amount/X_fee/X_count 变体，
	 * 最后退启发式；基础段求不出业务名时返回空串，交回既有启发式链。
	 */
	private static String composedSuffixName(String column) {
		String normalized = normalize(column);
		if (!StringUtils.hasText(normalized) || !normalized.contains("_")) {
			return "";
		}
		int index = normalized.lastIndexOf('_');
		String stem = normalized.substring(0, index);
		String suffix = normalized.substring(index + 1);
		if (!StringUtils.hasText(stem)) {
			return "";
		}
		String label = stemBusinessName(stem);
		if (!isSafeBusinessName(label)) {
			return "";
		}
		return switch (suffix) {
			case "total" -> prefixMeasure("总", label, "");
			case "count", "cnt" -> ensureCountSuffix(label);
			case "amount", "amt" -> label.endsWith("金额") ? label : label + "金额";
			case "fee" -> label.endsWith("费") ? label : label + "费用";
			default -> "";
		};
	}

	private static String stemBusinessName(String stem) {
		return firstText(knownName(stem), knownName(stem + "_num"), knownName(stem + "_amount"),
				knownName(stem + "_fee"), knownName(stem + "_count"), knownName(stem + "_cnt"),
				heuristicName(stem));
	}

	private static boolean isUserFacingName(String name) {
		if (!StringUtils.hasText(name)) {
			return false;
		}
		String trimmed = name.trim();
		if (trimmed.contains("_") || trimmed.contains(".")) {
			return false;
		}
		return !trimmed.matches(".*[a-z][A-Z].*");
	}

	private static boolean isMetricTendencyColumn(String column) {
		if (ReportColumnSemantics.isMetricColumn(column)) {
			return true;
		}
		String normalized = normalize(column);
		int index = normalized.lastIndexOf('_');
		String token = index >= 0 && index + 1 < normalized.length() ? normalized.substring(index + 1) : normalized;
		return METRIC_SUFFIX_TOKENS.contains(token);
	}

	private static String knownName(String column) {
		return KNOWN_NAMES.getOrDefault(normalize(column), "");
	}

	static boolean isSafeBusinessName(String name) {
		if (!StringUtils.hasText(name) || ReportColumnSemantics.isGenericPlaceholder(name)) {
			return false;
		}
		String trimmed = name.trim();
		if (trimmed.length() > 30 || trimmed.contains("_") || trimmed.contains(".")
				|| trimmed.toLowerCase(Locale.ROOT).contains("sql")) {
			return false;
		}
		return trimmed.matches(".*[\\u4e00-\\u9fa5].*");
	}

	private static SelectItemSpec matchSpec(List<SelectItemSpec> specs, String column, int index) {
		if (specs == null || specs.isEmpty()) {
			return null;
		}
		String normalized = normalize(column);
		for (SelectItemSpec spec : specs) {
			if (normalized.equals(normalize(spec.alias)) || spec.sourceColumns.stream().anyMatch(source -> normalized.equals(normalize(source)))) {
				return spec;
			}
		}
		if (index >= 0 && index < specs.size()) {
			return specs.get(index);
		}
		return null;
	}

	private static List<SelectItemSpec> parseSelectItems(String sql) {
		PlainSelect plainSelect = parsePlainSelect(sql);
		if (plainSelect == null || plainSelect.getSelectItems() == null) {
			return List.of();
		}
		List<SelectItemSpec> specs = new ArrayList<>();
		for (SelectItem<?> item : plainSelect.getSelectItems()) {
			if (item == null || item.getExpression() instanceof AllColumns) {
				return List.of();
			}
			SelectItemSpec spec = new SelectItemSpec();
			if (item.getAlias() != null) {
				spec.alias = item.getAlias().getName();
			}
			walk(item.getExpression(), spec);
			specs.add(spec);
		}
		return specs;
	}

	private static String parseFromTable(String sql) {
		PlainSelect plainSelect = parsePlainSelect(sql);
		if (plainSelect == null) {
			return "";
		}
		FromItem fromItem = plainSelect.getFromItem();
		if (fromItem instanceof Table table && StringUtils.hasText(table.getName())) {
			return table.getName();
		}
		return "";
	}

	private static PlainSelect parsePlainSelect(String sql) {
		if (!StringUtils.hasText(sql)) {
			return null;
		}
		try {
			Statement statement = CCJSqlParserUtil.parse(sql);
			if (statement instanceof Select select) {
				return select.getPlainSelect();
			}
			return null;
		}
		catch (Exception ignored) {
			return null;
		}
	}

	private static void walk(Expression expression, SelectItemSpec spec) {
		if (expression == null) {
			return;
		}
		if (expression instanceof Column column) {
			if (StringUtils.hasText(column.getColumnName())) {
				spec.sourceColumns.add(column.getColumnName());
			}
			if (column.getTable() != null && StringUtils.hasText(column.getTable().getName())) {
				spec.table = column.getTable().getName();
			}
			return;
		}
		if (expression instanceof Function function) {
			if (!StringUtils.hasText(spec.function)) {
				spec.function = function.getName();
			}
			spec.countStar = spec.countStar || isCountStar(function);
			if (function.getParameters() != null) {
				for (Expression parameter : function.getParameters()) {
					walk(parameter, spec);
				}
			}
			return;
		}
		if (expression instanceof AnalyticExpression analytic) {
			if (!StringUtils.hasText(spec.function)) {
				spec.function = analytic.getName();
			}
			walk(analytic.getExpression(), spec);
			return;
		}
		if (expression instanceof CaseExpression caseExpression) {
			spec.caseHint = firstText(spec.caseHint, extractBusinessHint(String.valueOf(caseExpression)));
			if (caseExpression.getWhenClauses() != null) {
				for (WhenClause whenClause : caseExpression.getWhenClauses()) {
					spec.caseHint = firstText(spec.caseHint,
							extractBusinessHint(String.valueOf(whenClause.getWhenExpression())));
					walk(whenClause.getThenExpression(), spec);
				}
			}
			walk(caseExpression.getElseExpression(), spec);
			walk(caseExpression.getSwitchExpression(), spec);
			return;
		}
		if (expression instanceof Parenthesis parenthesis) {
			walk(parenthesis.getExpression(), spec);
			return;
		}
		if (expression instanceof CastExpression castExpression) {
			walk(castExpression.getLeftExpression(), spec);
			return;
		}
		if (expression instanceof BinaryExpression binaryExpression) {
			walk(binaryExpression.getLeftExpression(), spec);
			walk(binaryExpression.getRightExpression(), spec);
		}
	}

	private static boolean isCountStar(Function function) {
		if (function == null || !"count".equalsIgnoreCase(function.getName())) {
			return false;
		}
		if (function.isAllColumns()) {
			return true;
		}
		return function.getParameters() != null && function.getParameters().size() == 1
				&& function.getParameters().get(0) instanceof AllColumns;
	}

	private static String extractBusinessHint(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		Matcher matcher = CHINESE_TOKEN.matcher(text);
		while (matcher.find()) {
			String token = matcher.group();
			if (!IGNORED_HINTS.contains(token)) {
				return token;
			}
		}
		return "";
	}

	private static String sanitizeHint(String hint) {
		return isSafeBusinessName(hint) ? hint.trim() : "";
	}

	private static String uniqueName(String name, Set<String> used) {
		String display = StringUtils.hasText(name) ? name.trim() : "分类";
		String candidate = display;
		int index = 2;
		while (!used.add(candidate)) {
			candidate = display + index++;
		}
		return candidate;
	}

	private static String firstText(String... values) {
		if (values == null) {
			return "";
		}
		for (String value : values) {
			if (isSafeBusinessName(value)) {
				return value.trim();
			}
		}
		return "";
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
	}

	public static final class Lexicon {

		private final Map<String, String> byColumn = new LinkedHashMap<>();

		private final Map<String, String> byTableColumn = new LinkedHashMap<>();

		private final Map<String, String> tables = new LinkedHashMap<>();

		private final Map<String, String> headers = new LinkedHashMap<>();

		public Lexicon addColumn(String table, String column, String... labels) {
			String label = firstLabel(labels);
			if (!StringUtils.hasText(column) || !isSafeBusinessName(label)) {
				return this;
			}
			byColumn.putIfAbsent(normalize(column), label.trim());
			if (StringUtils.hasText(table)) {
				byTableColumn.putIfAbsent(normalize(table) + "." + normalize(column), label.trim());
			}
			return this;
		}

		public Lexicon addTable(String table, String... labels) {
			String label = firstLabel(labels);
			if (StringUtils.hasText(table) && isSafeBusinessName(label)) {
				tables.putIfAbsent(normalize(table), label.trim());
			}
			return this;
		}

		public Lexicon addHeader(String column, String displayName) {
			if (StringUtils.hasText(column) && isSafeBusinessName(displayName)) {
				headers.putIfAbsent(normalize(column), displayName.trim());
			}
			return this;
		}

		public Lexicon addHeaders(List<Map<String, Object>> columns) {
			if (columns == null) {
				return this;
			}
			for (Map<String, Object> column : columns) {
				if (column == null) {
					continue;
				}
				addHeader(stringValue(column.get("name")), stringValue(column.get("displayName")));
			}
			return this;
		}

		public Lexicon addSemanticHits(List<SemanticHitView> hits) {
			if (hits == null) {
				return this;
			}
			for (SemanticHitView hit : hits) {
				if (hit == null) {
					continue;
				}
				addColumn(hit.getTableName(), hit.getColumnName(), hit.getBusinessName(), hit.getColumnComment(),
						firstSynonym(hit.getSynonyms()));
			}
			return this;
		}

		String header(String column) {
			return headers.getOrDefault(normalize(column), "");
		}

		String label(String column) {
			return byColumn.getOrDefault(normalize(column), "");
		}

		String label(String table, String column) {
			if (!StringUtils.hasText(table)) {
				return label(column);
			}
			return firstText(byTableColumn.get(normalize(table) + "." + normalize(column)), label(column));
		}

		String table(String table) {
			return tables.getOrDefault(normalize(table), "");
		}

		private static String firstLabel(String... labels) {
			if (labels == null) {
				return "";
			}
			for (String label : labels) {
				if (isSafeBusinessName(label)) {
					return label.trim();
				}
			}
			return "";
		}

		private static String firstSynonym(String synonyms) {
			if (!StringUtils.hasText(synonyms)) {
				return "";
			}
			for (String item : synonyms.split("[,，;；/|、\\s]+")) {
				if (isSafeBusinessName(item)) {
					return item.trim();
				}
			}
			return "";
		}

		private static String stringValue(Object value) {
			return value == null ? "" : String.valueOf(value);
		}

	}

	private static final class SelectItemSpec {

		private String alias = "";

		private String function = "";

		private String table = "";

		private String caseHint = "";

		private boolean countStar;

		private final List<String> sourceColumns = new ArrayList<>();

		private boolean hasFunction() {
			return StringUtils.hasText(function);
		}

	}

}
