/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing.v2;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 提案禁止字段防线(方案第四章):模型提案中出现数据库 ID、SQL 语句、JSONPath、凭据、
 * 幂等键、真实 URL、租户范围、风险等级、审批结论等字段名或可疑文本内容时直接编译失败。
 *
 * <p>在严格 schema 校验之前对完整 JSON 树扫描,因此即使字段本身属于未知字段,
 * 也会先以 PROPOSAL_FORBIDDEN_CONTENT 暴露,便于审计。黑名单与正则规则均可通过构造器替换。
 * 为避免把可疑值回显进日志,命中值规则时只报告 JSON 路径与规则名,不携带原文。
 */
@Component
public class ProposalForbiddenFieldGuard {

	/** 值内容规则:命名正则,命中即拒绝。 */
	public record GuardRule(String name, Pattern pattern) {

		public GuardRule {
			if (!StringUtils.hasText(name) || pattern == null) {
				throw new IllegalArgumentException("Guard rule requires name and pattern");
			}
			name = name.trim();
		}

	}

	/** 默认字段名黑名单(比较前会做小写化并剔除非字母数字字符)。 */
	public static final Set<String> DEFAULT_FORBIDDEN_FIELD_NAMES = Set.of(
			"id", "ids", "pk", "sql", "sqlstatement", "jsonpath",
			"credential", "credentials", "password", "passwd", "secret", "secrets",
			"token", "accesstoken", "refreshtoken", "apikey", "accesskey", "privatekey",
			"idempotencykey", "idempotency", "url", "uri", "endpoint", "href",
			"tenant", "tenantid", "tenantscope", "datascope",
			"risk", "risklevel", "riskgrade", "approval", "approved", "approvalstatus", "approvalresult");

	/** 默认字段名形态规则:捕获 camelCase / snake_case 的 *Id / *Ids 后缀。 */
	public static final List<Pattern> DEFAULT_FORBIDDEN_FIELD_NAME_PATTERNS = List.of(
			Pattern.compile(".*(?:Id|Ids|ID|IDS)$"),
			Pattern.compile("(?i).*_ids?$"));

	/** 默认值内容规则。 */
	public static final List<GuardRule> DEFAULT_VALUE_RULES = List.of(
			new GuardRule("SQL_STATEMENT", Pattern.compile(
					"(?is)\\b(select|insert|update|delete|drop|alter|truncate|merge)\\b.{0,200}?\\b(from|into|table|set|where|join|values)\\b")),
			new GuardRule("JSON_PATH", Pattern.compile("\\$\\.[A-Za-z_*$]|\\$\\[")),
			new GuardRule("NETWORK_URL",
					Pattern.compile("(?i)\\b(?:https?|wss?|ftp|redis|mongodb)://\\S+|(?i)\\bjdbc:[a-z0-9]+://\\S+")),
			new GuardRule("CREDENTIAL_ASSIGNMENT", Pattern.compile(
					"(?i)\\b(password|passwd|secret|credential|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b\\s*[=:：]")),
			new GuardRule("BEARER_TOKEN", Pattern.compile("(?i)\\bbearer\\s+[a-z0-9._\\-]{8,}")),
			new GuardRule("AWS_ACCESS_KEY", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
			new GuardRule("PEM_PRIVATE_KEY", Pattern.compile("-----BEGIN\\s+[A-Z ]*KEY-----")),
			new GuardRule("DATABASE_ID_ASSIGNMENT", Pattern.compile("(?i)\\b(id|ids|pk)\\s*[=:：]\\s*\\d+")),
			new GuardRule("UUID_LITERAL", Pattern.compile(
					"\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b")),
			new GuardRule("IDEMPOTENCY_KEY", Pattern.compile("(?i)idempoten")),
			new GuardRule("TENANT_SCOPE", Pattern.compile("(?i)tenant[_\\- ]?(id|scope)|租户范围")),
			new GuardRule("RISK_CONCLUSION", Pattern.compile("(?i)risk[_\\- ]?level|风险等级")),
			new GuardRule("APPROVAL_CONCLUSION", Pattern.compile("审批(结论|通过|拒绝)|(?i)\\bapprov(al|ed)\\s*[=:：]")));

	private final Set<String> forbiddenFieldNames;

	private final List<Pattern> forbiddenFieldNamePatterns;

	private final List<GuardRule> valueRules;

	public ProposalForbiddenFieldGuard() {
		this(DEFAULT_FORBIDDEN_FIELD_NAMES, DEFAULT_FORBIDDEN_FIELD_NAME_PATTERNS, DEFAULT_VALUE_RULES);
	}

	/** 自定义配置构造器:按原样使用给定黑名单与规则,调用方可基于 DEFAULT_* 常量增删。 */
	public ProposalForbiddenFieldGuard(Set<String> forbiddenFieldNames, List<Pattern> forbiddenFieldNamePatterns,
			List<GuardRule> valueRules) {
		this.forbiddenFieldNames = forbiddenFieldNames == null ? Set.of()
				: forbiddenFieldNames.stream().map(ProposalForbiddenFieldGuard::normalize)
					.collect(Collectors.toUnmodifiableSet());
		this.forbiddenFieldNamePatterns = forbiddenFieldNamePatterns == null ? List.of()
				: List.copyOf(forbiddenFieldNamePatterns);
		this.valueRules = valueRules == null ? List.of() : List.copyOf(valueRules);
	}

	/** 扫描完整提案 JSON 树,命中禁止字段名或可疑值内容时抛出 PROPOSAL_FORBIDDEN_CONTENT。 */
	public void verify(JsonNode root) {
		if (root == null) {
			return;
		}
		scan(root, "$");
	}

	private void scan(JsonNode node, String path) {
		if (node.isObject()) {
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				checkFieldName(field.getKey(), path);
				scan(field.getValue(), path + "." + field.getKey());
			}
			return;
		}
		if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				scan(node.get(i), path + "[" + i + "]");
			}
			return;
		}
		if (node.isTextual()) {
			checkValue(node.textValue(), path);
		}
	}

	private void checkFieldName(String name, String path) {
		if (forbiddenFieldNames.contains(normalize(name))) {
			throw forbidden("Route proposal contains forbidden field '" + name + "' at " + path);
		}
		for (Pattern pattern : forbiddenFieldNamePatterns) {
			if (pattern.matcher(name).matches()) {
				throw forbidden("Route proposal contains forbidden field '" + name + "' at " + path);
			}
		}
	}

	private void checkValue(String value, String path) {
		if (value == null || value.isEmpty()) {
			return;
		}
		for (GuardRule rule : valueRules) {
			if (rule.pattern().matcher(value).find()) {
				throw forbidden("Route proposal contains forbidden content at " + path + " (rule=" + rule.name() + ")");
			}
		}
	}

	private static String normalize(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	private static PlanCompileException forbidden(String message) {
		return new PlanCompileException(PlanCompileException.PROPOSAL_FORBIDDEN_CONTENT, message);
	}

}
