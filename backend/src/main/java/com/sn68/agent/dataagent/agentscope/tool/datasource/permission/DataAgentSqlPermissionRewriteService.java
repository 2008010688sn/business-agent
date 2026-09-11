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
package com.sn68.agent.dataagent.agentscope.tool.datasource.permission;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.agentscope.tool.datasource.DatasourceRuntimeContextCache;
import com.sn68.agent.dataagent.authorization.pep.PepAuthorizationProperties;
import com.sn68.agent.dataagent.entity.DatasourcePermissionRule;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.service.datasource.DatasourceService;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataRefType;
import com.sn68.agent.framework.commons.security.DataScopeType;
import com.sn68.agent.framework.db.mybatisplus.datascope.handler.DataPermissionRule;
import com.sn68.agent.framework.db.mybatisplus.datascope.util.DataPermissionHelper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.lang.Nullable;

/**
 * DataAgentSQL权限改写组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAgentSqlPermissionRewriteService {

	private static final String NO_DATA_PERMISSION_MESSAGE = "当前无数据权限";

	private static final String NO_PERMISSION_CONTEXT_MESSAGE = "无法解析执行身份的数据权限上下文";

	private final DatasourceService datasourceService;

	private final AuthenticationContext authenticationContext;

	private final DatasourceRuntimeContextCache datasourceRuntimeContextCache;

	/** PR-4 SQL 线开关载体（denyOnMissingSql；与 PEP 同一配置子树 spring.ai.agent.authorization.*）。 */
	private final DataAgentProperties dataAgentProperties;

	/** PR-4 模式判定（resolveMode 白名单优先），保证 SQL 线与 PEP 主链路 ENFORCE 口径一致。 */
	private final PepAuthorizationProperties pepAuthorizationProperties;

	/**
	 * 处理DataAgentSQL权限改写。
	 */
	public PermissionRewriteResult rewrite(Long datasourceId, String sql) {
		return rewrite(datasourceId, sql, null);
	}

	/**
	 * 处理DataAgentSQL权限改写。
	 */
	public PermissionRewriteResult rewrite(Long datasourceId, String sql, @Nullable AgentRequest request) {
		List<DatasourcePermissionRule> rules = datasourceRuntimeContextCache.getOrLoadPermissionRules(datasourceId,
				request, () -> loadEnabledPermissionRules(datasourceId, request));
		if (rules.isEmpty()) {
			if (shouldDenyOnMissingSql(request)) {
				// PR-4：ENFORCE 租户 denyOnMissingSql=true——数据源无任何权限映射按 DENY_ON_MISSING 拒绝；
				// SHADOW/默认（开关 false）维持 ALLOW_ON_MISSING 放行，现网行为零变化。
				return PermissionRewriteResult.denied(sql, List.of(), List.of(),
						List.of("ENFORCE 租户 denyOnMissingSql 已开启：数据源未配置数据权限映射，按 DENY_ON_MISSING 拒绝。"),
						NO_DATA_PERMISSION_MESSAGE);
			}
			return PermissionRewriteResult.unchanged(sql, List.of(), List.of(), List.of("当前数据源未配置数据权限映射，按表缺省策略放行。"));
		}

		Statement statement;
		try {
			statement = CCJSqlParserUtil.parse(sql);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("数据权限 SQL 解析失败，无法安全注入权限条件", ex);
		}
		if (!(statement instanceof Select select)) {
			throw new IllegalArgumentException("数据权限改写仅支持 SELECT / WITH 查询");
		}
		RuleIndex ruleIndex = buildRuleIndex(rules);
		List<String> referencedTables = referencedTables(statement);
		boolean hasMappedTable = referencedTables.stream().anyMatch(table -> !ruleIndex.findRules(table).isEmpty());
		if (!hasMappedTable) {
			return PermissionRewriteResult.unchanged(sql, List.of(), referencedTables,
					List.of("本次查询未引用已配置行级权限映射的表，按 ALLOW_ON_MISSING 放行。"));
		}
		DataPermission dataPermission = resolveDataPermission(request);
		if (dataPermission == null) {
			log.warn("SQL 数据权限改写拒绝: 无法解析执行身份权限上下文. datasourceId={}, skillScoped={}, userIdSnapshot={}, tenantIdSnapshot={}",
					datasourceId, isSkillScoped(request), request == null ? null : request.getUserIdSnapshot(),
					request == null ? null : request.getTenantIdSnapshot());
			return PermissionRewriteResult.denied(sql, List.of(), List.of(),
					List.of("查询命中行级权限映射，但无法解析执行身份的数据权限上下文"),
					NO_PERMISSION_CONTEXT_MESSAGE);
		}
		if (dataPermission.getScopeType() == DataScopeType.ALL) {
			return PermissionRewriteResult.unchanged(sql, List.of(), List.of(), List.of("当前用户数据权限范围为 ALL，未追加数据权限条件。"));
		}

		RewriteTrace trace = new RewriteTrace();
		AuthenticationContext permissionContext = permissionContext(request, dataPermission);
		boolean denyMissingPolicy = shouldDenyOnMissing(rules, request);
		rewriteSelect(select, new LinkedHashSet<>(), ruleIndex, permissionContext, trace, true, denyMissingPolicy);
		if (trace.denied()) {
			return PermissionRewriteResult.denied(sql, trace.appliedTables(), trace.skippedTables(), trace.messages(),
					NO_DATA_PERMISSION_MESSAGE);
		}
		boolean rewritten = trace.rewritten();
		String rewrittenSql = rewritten ? statement.toString() : sql;
		return new PermissionRewriteResult(rewrittenSql, rewritten, trace.appliedTables(), trace.skippedTables(),
				trace.messages(), false, null);
	}

	private void rewriteSelect(Select select, Set<String> cteNames, RuleIndex ruleIndex,
			AuthenticationContext permissionContext, RewriteTrace trace, boolean failClosed, boolean denyMissingPolicy) {
		Set<String> nextCteNames = new LinkedHashSet<>(cteNames);
		if (select.getWithItemsList() != null) {
			for (WithItem withItem : select.getWithItemsList()) {
				if (withItem.getAlias() != null && StringUtils.isNotBlank(withItem.getAlias().getName())) {
					nextCteNames.add(normalizeIdentifier(withItem.getAlias().getName()));
				}
			}
			for (WithItem withItem : select.getWithItemsList()) {
				rewriteSelect(withItem.getSelect(), nextCteNames, ruleIndex, permissionContext, trace, failClosed,
						denyMissingPolicy);
			}
		}
		if (select instanceof PlainSelect plainSelect) {
			rewritePlainSelect(plainSelect, nextCteNames, ruleIndex, permissionContext, trace, failClosed,
					denyMissingPolicy);
			return;
		}
		if (select instanceof SetOperationList setOperationList) {
			for (Select childSelect : Optional.ofNullable(setOperationList.getSelects()).orElse(List.of())) {
				rewriteSelect(childSelect, nextCteNames, ruleIndex, permissionContext, trace, failClosed,
						denyMissingPolicy);
			}
			return;
		}
		if (select instanceof ParenthesedSelect parenthesedSelect) {
			rewriteSelect(parenthesedSelect.getSelect(), nextCteNames, ruleIndex, permissionContext, trace, failClosed,
					denyMissingPolicy);
		}
	}

	private void rewritePlainSelect(PlainSelect plainSelect, Set<String> cteNames, RuleIndex ruleIndex,
			AuthenticationContext permissionContext, RewriteTrace trace, boolean failClosed, boolean denyMissingPolicy) {
		List<Expression> conditions = new ArrayList<>();
		collectConditions(plainSelect.getFromItem(), cteNames, ruleIndex, permissionContext, trace, conditions,
				failClosed, denyMissingPolicy);
		for (Join join : Optional.ofNullable(plainSelect.getJoins()).orElse(List.of())) {
			collectConditions(join.getRightItem(), cteNames, ruleIndex, permissionContext, trace, conditions,
					failClosed, denyMissingPolicy);
		}
		Expression mergedCondition = mergeConditions(conditions);
		if (mergedCondition != null) {
			plainSelect.setWhere(plainSelect.getWhere() == null ? mergedCondition
					: new AndExpression(new Parenthesis(plainSelect.getWhere()), new Parenthesis(mergedCondition)));
			trace.markRewritten();
		}
	}

	private void collectConditions(FromItem fromItem, Set<String> cteNames, RuleIndex ruleIndex,
			AuthenticationContext permissionContext, RewriteTrace trace, List<Expression> conditions, boolean failClosed,
			boolean denyMissingPolicy) {
		if (fromItem == null) {
			return;
		}
		if (fromItem instanceof Table table) {
			String tableName = extractTableReference(table);
			if (cteNames.contains(normalizeIdentifier(tableName))) {
				return;
			}
			List<DatasourcePermissionRule> tableRules = ruleIndex.findRules(tableName);
			if (tableRules.isEmpty()) {
				if (denyMissingPolicy) {
					// PR-4：ENFORCE 租户缺映射拒绝策略（全局 denyOnMissingSql 或规则级 missingPolicy=DENY_ON_MISSING，
					// 取更严者）——引用表无权限映射按 DENY_ON_MISSING 拒绝
					trace.deny(tableName,
							"ENFORCE 租户缺映射拒绝策略已生效（全局 denyOnMissingSql 或规则级 missingPolicy=DENY_ON_MISSING）：表未配置数据权限映射，按 DENY_ON_MISSING 拒绝");
					return;
				}
				trace.skip(tableName, "未配置数据权限映射，按 ALLOW_ON_MISSING 放行");
				return;
			}
			for (DatasourcePermissionRule rule : tableRules) {
				Expression condition = buildRuleCondition(table, rule, permissionContext, trace);
				if (condition != null) {
					conditions.add(condition);
				}
			}
			return;
		}
		if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
			rewriteSelect(parenthesedSelect.getSelect(), cteNames, ruleIndex, permissionContext, trace, failClosed,
					denyMissingPolicy);
			return;
		}
		if (fromItem instanceof LateralSubSelect lateralSubSelect) {
			rewriteSelect(lateralSubSelect.getSelect(), cteNames, ruleIndex, permissionContext, trace, failClosed,
					denyMissingPolicy);
			return;
		}
		if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
			collectConditions(parenthesedFromItem.getFromItem(), cteNames, ruleIndex, permissionContext, trace,
					conditions, failClosed, denyMissingPolicy);
			for (Join join : Optional.ofNullable(parenthesedFromItem.getJoins()).orElse(List.of())) {
				collectConditions(join.getRightItem(), cteNames, ruleIndex, permissionContext, trace, conditions,
					failClosed, denyMissingPolicy);
			}
			return;
		}
		if (fromItem instanceof TableFunction) {
			if (failClosed) {
				trace.deny("TABLE_FUNCTION", "Routed Skill query cannot authorize a table function");
			}
			return;
		}
		if (failClosed) {
			trace.deny(fromItem.getClass().getSimpleName(), "Routed Skill query contains an unsupported FROM structure");
			return;
		}
		trace.message("暂未识别的 FROM 结构未执行数据权限注入: " + fromItem.getClass().getSimpleName());
	}

	private Expression buildRuleCondition(Table table, DatasourcePermissionRule rule, AuthenticationContext permissionContext,
			RewriteTrace trace) {
		DataRefType dataRefType = DataRefType.of(rule.getDataRefType());
		if (dataRefType == null || dataRefType == DataRefType.ALL) {
			trace.message("权限规则 dataRefType 无效，已跳过: " + rule.getDataRefType());
			return null;
		}
		DataScopeType ruleScopeType = DataScopeType.of(rule.getScopeType());
		DataPermissionRule.Column column = DataPermissionRule.Column.builder()
			.alias(table.getAlias() == null ? "" : table.getAlias().getName())
			.name(rule.getColumnName())
			.javaClass(resolveJavaClass(rule.getJavaType()))
			.dataType(dataRefType)
			.scopeType(ruleScopeType == null ? DataScopeType.IGNORE : ruleScopeType)
			.build();
		List<Expression> conditions = DataPermissionHelper.buildConditions(permissionContext, table, List.of(column));
		if (conditions.isEmpty()) {
			if (isDeniedByXxCloudPermission(permissionContext, column)) {
				trace.deny(rule.getTableName(), "当前用户在维度 %s 下无可用数据权限，已拒绝执行查询。".formatted(dataRefType.getType()));
				return null;
			}
			trace.message("已按 xx-cloud 数据权限口子处理，未追加维度 %s 条件。".formatted(dataRefType.getType()));
			return null;
		}
		trace.apply(rule.getTableName(), "已按 xx-cloud 数据权限口子注入维度 %s".formatted(dataRefType.getType()));
		return mergeConditions(conditions);
	}

	private boolean isDeniedByXxCloudPermission(AuthenticationContext permissionContext, DataPermissionRule.Column column) {
		DataPermission permission = permissionContext.dataPermission();
		DataScopeType scopeType = column.getScopeType() == DataScopeType.IGNORE ? permission.getScopeType()
				: column.getScopeType();
		if (scopeType == DataScopeType.ALL || column.getDataType() == DataRefType.USER) {
			return false;
		}
		List<Object> values = Optional.ofNullable(permission.getDataPermissionMap())
			.orElse(Map.of())
			.get(column.getDataType());
		if (values == null || values.isEmpty()) {
			return true;
		}
		return !values.contains(DataRefType.ALL.getType()) && scopeType == DataScopeType.SELF;
	}

	private Expression mergeConditions(List<Expression> conditions) {
		Expression merged = null;
		for (Expression condition : conditions) {
			merged = merged == null ? condition : new AndExpression(new Parenthesis(merged), new Parenthesis(condition));
		}
		return merged;
	}

	private RuleIndex buildRuleIndex(List<DatasourcePermissionRule> rules) {
		Map<String, List<DatasourcePermissionRule>> exact = new LinkedHashMap<>();
		Map<String, List<DatasourcePermissionRule>> leaf = new LinkedHashMap<>();
		for (DatasourcePermissionRule rule : rules) {
			if (rule == null || StringUtils.isBlank(rule.getTableName())) {
				continue;
			}
			exact.computeIfAbsent(normalizeIdentifier(rule.getTableName()), key -> new ArrayList<>()).add(rule);
			leaf.computeIfAbsent(normalizeLeafIdentifier(rule.getTableName()), key -> new ArrayList<>()).add(rule);
		}
		return new RuleIndex(exact, leaf);
	}

	private List<DatasourcePermissionRule> loadEnabledPermissionRules(Long datasourceId,
			@Nullable AgentRequest request) {
		String tenantId = resolveTenantId(request);
		List<DatasourcePermissionRule> rules = StringUtils.isNotBlank(tenantId)
				? datasourceService.getPermissionRulesForTenant(datasourceId, tenantId)
				: datasourceService.getPermissionRules(datasourceId);
		return rules.stream().filter(rule -> Boolean.TRUE.equals(rule.getEnabled())).toList();
	}

	private DataPermission resolveDataPermission(@Nullable AgentRequest request) {
		if (request != null && request.getDataPermissionSnapshot() != null) {
			return request.getDataPermissionSnapshot();
		}
		if (isSkillScoped(request) && !matchesLiveIdentity(request)) {
			return null;
		}
		try {
			return authenticationContext.dataPermission();
		}
		catch (Exception ex) {
			log.warn("Failed to resolve authentication data permission: {}", ex.getMessage());
			return null;
		}
	}

	private boolean matchesLiveIdentity(AgentRequest request) {
		String expectedUserId = StringUtils.trimToNull(request.getUserIdSnapshot());
		String expectedTenantId = StringUtils.trimToNull(request.getTenantIdSnapshot());
		if (expectedUserId == null || expectedTenantId == null) {
			return false;
		}
		try {
			if (!StringUtils.equals(expectedUserId, StringUtils.trimToNull(authenticationContext.userId()))) {
				return false;
			}
			return StringUtils.equals(expectedTenantId, StringUtils.trimToNull(authenticationContext.tenantId()));
		}
		catch (Exception ex) {
			log.warn("Failed to verify live authentication identity for data permission fallback: {}", ex.getMessage());
			return false;
		}
	}

	private boolean isSkillScoped(@Nullable AgentRequest request) {
		return request != null && request.getRoutedSkillId() != null && request.getRoutedSkillVersionId() != null;
	}

	/**
	 * PR-4 SQL 线开关消费：仅 ENFORCE 租户且 {@code authorization.deny-on-missing-sql=true} 时，
	 * 缺策略的 SQL 按拒绝处理；SHADOW/默认（开关 false）保持 ALLOW_ON_MISSING，现网行为零变化。
	 * 模式判定复用 {@link PepAuthorizationProperties#resolveMode(String)}（白名单优先），
	 * 保证与 PEP 主链路 ENFORCE 口径一致。
	 */
	private boolean shouldDenyOnMissingSql(@Nullable AgentRequest request) {
		return isGlobalDenyOnMissingSql() && isEnforceMode(request);
	}

	/**
	 * 有效缺映射拒绝口径（评审修复 C2：规则级 missingPolicy 消费）：全局 denyOnMissingSql 开关与
	 * 规则级 missingPolicy 字段取更严者（任一要求拒绝即拒绝），仅 ENFORCE 模式下生效；
	 * SHADOW 保持 ALLOW_ON_MISSING 记录行为不变。
	 *
	 * <p>规则级口径逐规则读取：任一启用规则声明 {@code DENY_ON_MISSING} 即视为要求拒绝——
	 * 缺映射的表自身无规则可读，规则级策略按数据源粒度生效（与 schema 注释「映射缺失时的
	 * 处理策略」语义一致）；空值 / 未知值按默认 ALLOW 处理（与保存侧 defaultIfBlank 同口径）。</p>
	 */
	private boolean shouldDenyOnMissing(List<DatasourcePermissionRule> rules, @Nullable AgentRequest request) {
		if (!isEnforceMode(request)) {
			return false;
		}
		if (isGlobalDenyOnMissingSql()) {
			return true;
		}
		return rules.stream().anyMatch(this::ruleDeniesOnMissing);
	}

	/** 全局 denyOnMissingSql 开关（配置缺失视同关闭）。 */
	private boolean isGlobalDenyOnMissingSql() {
		DataAgentProperties.Authorization authorization = dataAgentProperties.getAuthorization();
		return authorization != null && authorization.isDenyOnMissingSql();
	}

	/** ENFORCE 模式判定（白名单优先），保证 SQL 线与 PEP 主链路口径一致。 */
	private boolean isEnforceMode(@Nullable AgentRequest request) {
		return pepAuthorizationProperties.resolveMode(resolveTenantId(request)).enforce();
	}

	/** 规则级缺映射策略：声明 DENY_ON_MISSING（忽略大小写）才要求拒绝，其余按默认 ALLOW。 */
	private boolean ruleDeniesOnMissing(DatasourcePermissionRule rule) {
		return rule != null && DatasourcePermissionRule.MISSING_POLICY_DENY
				.equalsIgnoreCase(StringUtils.trimToEmpty(rule.getMissingPolicy()));
	}

	/**
	 * 租户解析（供 denyOnMissingSql 模式判定）：请求快照优先（协作者/异步链路），授权上下文回退。
	 */
	private String resolveTenantId(@Nullable AgentRequest request) {
		if (request != null && StringUtils.isNotBlank(request.getTenantIdSnapshot())) {
			return request.getTenantIdSnapshot().trim();
		}
		try {
			return authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("Failed to resolve tenant id for denyOnMissingSql decision: {}", ex.getMessage());
			return null;
		}
	}

	private List<String> referencedTables(Statement statement) {
		try {
			return new TablesNamesFinder().getTableList(statement)
				.stream()
				.filter(StringUtils::isNotBlank)
				.map(String::trim)
				.distinct()
				.toList();
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("数据权限 SQL 表引用解析失败，无法安全判断权限映射", ex);
		}
	}

	private String extractTableReference(Table table) {
		String fullyQualifiedName = table == null ? "" : table.getFullyQualifiedName();
		if (StringUtils.isNotBlank(fullyQualifiedName)) {
			return fullyQualifiedName;
		}
		return table == null ? "" : table.getName();
	}

	private AuthenticationContext permissionContext(@Nullable AgentRequest request, DataPermission dataPermission) {
		String userId = request == null ? null : StringUtils.trimToNull(request.getUserIdSnapshot());
		String tenantId = request == null ? null : StringUtils.trimToNull(request.getTenantIdSnapshot());
		String tenantCode = request == null ? null : StringUtils.trimToNull(request.getTenantCodeSnapshot());
		String clientId = request == null ? null : StringUtils.trimToNull(request.getClientIdSnapshot());
		List<String> teamIds = request == null || request.getTeamIdsSnapshot() == null ? List.of()
				: request.getTeamIdsSnapshot();
		if (userId == null) {
			try {
				userId = authenticationContext.userId();
			}
			catch (Exception ex) {
				log.warn("Failed to resolve authentication user id for data permission rewrite: {}", ex.getMessage());
			}
		}
		if (tenantId == null) {
			try {
				tenantId = authenticationContext.tenantId();
			}
			catch (Exception ex) {
				log.warn("Failed to resolve authentication tenant id for data permission rewrite", ex);
			}
		}
		if (tenantCode == null) {
			try {
				tenantCode = authenticationContext.tenantCode();
			}
			catch (Exception ex) {
				log.warn("Failed to resolve authentication tenant code for data permission rewrite", ex);
			}
		}
		if (clientId == null) {
			try {
				clientId = authenticationContext.clientId();
			}
			catch (Exception ex) {
				log.warn("Failed to resolve authentication client id for data permission rewrite", ex);
			}
		}
		if (teamIds.isEmpty()) {
			try {
				teamIds = authenticationContext.teamIds();
			}
			catch (Exception ex) {
				log.warn("Failed to resolve authentication team ids for data permission rewrite", ex);
			}
		}
		return new SnapshotAuthenticationContext(dataPermission, StringUtils.defaultString(userId), tenantId,
				tenantCode, clientId, teamIds == null ? List.of() : teamIds);
	}

	private Class<?> resolveJavaClass(String javaType) {
		if ("Long".equalsIgnoreCase(javaType)) {
			return Long.class;
		}
		if ("Integer".equalsIgnoreCase(javaType)) {
			return Integer.class;
		}
		return String.class;
	}

	private String normalizeIdentifier(String value) {
		String normalized = StringUtils.trimToEmpty(value);
		normalized = StringUtils.removeStart(normalized, "`");
		normalized = StringUtils.removeEnd(normalized, "`");
		normalized = StringUtils.removeStart(normalized, "\"");
		normalized = StringUtils.removeEnd(normalized, "\"");
		normalized = StringUtils.removeStart(normalized, "[");
		normalized = StringUtils.removeEnd(normalized, "]");
		return normalized.toLowerCase(Locale.ROOT);
	}

	private String normalizeLeafIdentifier(String value) {
		String normalized = normalizeIdentifier(value);
		if (normalized.contains(".")) {
			return normalized.substring(normalized.lastIndexOf('.') + 1);
		}
		return normalized;
	}

	private record RuleIndex(Map<String, List<DatasourcePermissionRule>> exact,
			Map<String, List<DatasourcePermissionRule>> leaf) {

		List<DatasourcePermissionRule> findRules(String tableName) {
			List<DatasourcePermissionRule> exactMatches = exact.getOrDefault(normalizeStatic(tableName), List.of());
			if (!exactMatches.isEmpty()) {
				return exactMatches;
			}
			return leaf.getOrDefault(normalizeLeafStatic(tableName), List.of());
		}

		private static String normalizeStatic(String value) {
			String normalized = StringUtils.trimToEmpty(value);
			normalized = StringUtils.removeStart(normalized, "`");
			normalized = StringUtils.removeEnd(normalized, "`");
			normalized = StringUtils.removeStart(normalized, "\"");
			normalized = StringUtils.removeEnd(normalized, "\"");
			normalized = StringUtils.removeStart(normalized, "[");
			normalized = StringUtils.removeEnd(normalized, "]");
			return normalized.toLowerCase(Locale.ROOT);
		}

		private static String normalizeLeafStatic(String value) {
			String normalized = normalizeStatic(value);
			if (normalized.contains(".")) {
				return normalized.substring(normalized.lastIndexOf('.') + 1);
			}
			return normalized;
		}
	}

	private static final class RewriteTrace {

		private final Set<String> appliedTables = new LinkedHashSet<>();

		private final Set<String> skippedTables = new LinkedHashSet<>();

		private final List<String> messages = new ArrayList<>();

		private boolean rewritten;

		private boolean denied;

		void markRewritten() {
			rewritten = true;
		}

		void apply(String tableName, String message) {
			appliedTables.add(tableName);
			messages.add(message);
		}

		void skip(String tableName, String message) {
			skippedTables.add(tableName);
			messages.add("%s: %s".formatted(tableName, message));
		}

		void deny(String tableName, String message) {
			appliedTables.add(tableName);
			messages.add(message);
			denied = true;
		}

		void message(String message) {
			messages.add(message);
		}

		List<String> appliedTables() {
			return List.copyOf(appliedTables);
		}

		List<String> skippedTables() {
			return List.copyOf(skippedTables);
		}

		List<String> messages() {
			return List.copyOf(messages);
		}

		boolean rewritten() {
			return rewritten;
		}

		boolean denied() {
			return denied;
		}

	}

	private record SnapshotAuthenticationContext(DataPermission dataPermission, String userId, String tenantId,
			String tenantCode, String clientId, List<String> teamIds)
			implements AuthenticationContext {

		@Override
		public String clientId() {
			return clientId;
		}

		@Override
		public String tenantId() {
			return tenantId;
		}

		@Override
		public String tenantCode() {
			return tenantCode;
		}

		@Override
		public String tenantName() {
			return null;
		}

		@Override
		public UserType userType() {
			return null;
		}

		@Override
		public String nickName() {
			return null;
		}

		@Override
		public String mobile() {
			return null;
		}

		@Override
		public boolean anonymous() {
			return false;
		}

		@Override
		public List<String> funcPermissionList() {
			return List.of();
		}

		@Override
		public List<String> rolePermissionList() {
			return List.of();
		}

		@Override
		public List<String> teamIds() {
			return teamIds == null ? List.of() : teamIds;
		}
	}

	/**
	 * 处理DataAgentSQL权限改写。
	 */
	public record PermissionRewriteResult(String sql, boolean rewritten, List<String> appliedTables,
			List<String> skippedTables, List<String> messages, boolean denied, String denialMessage) {

		static PermissionRewriteResult unchanged(String sql, List<String> appliedTables, List<String> skippedTables,
				List<String> messages) {
			return new PermissionRewriteResult(sql, false, appliedTables, skippedTables, messages, false, null);
		}

		static PermissionRewriteResult denied(String sql, List<String> appliedTables, List<String> skippedTables,
				List<String> messages, String denialMessage) {
			return new PermissionRewriteResult(sql, false, appliedTables, skippedTables, messages, true,
					denialMessage);
		}
	}

}
