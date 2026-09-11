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
package com.sn68.agent.dataagent.authorization.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * 策略 JSON 校验器。
 *
 * <p>PDP 内核契约的一部分，负责严格的 schema 验证（不允许 SpEL/表达式）。采用手动解析而非 Spring 注解。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Slf4j
public class PolicyValidator {

	/** 支持的 schemaVersion 固定为 1 */
	private static final int SUPPORTED_SCHEMA_VERSION = 1;

	// 允许的顶层字段集合（严格模式）
	private static final Set<String> ALLOWED_TOP_LEVEL_FIELDS = new HashSet<>();
	static {
		ALLOWED_TOP_LEVEL_FIELDS.add("schemaVersion");
		ALLOWED_TOP_LEVEL_FIELDS.add("templateCode");
		ALLOWED_TOP_LEVEL_FIELDS.add("subjectMode");
		ALLOWED_TOP_LEVEL_FIELDS.add("iamUnavailableBehavior");
		ALLOWED_TOP_LEVEL_FIELDS.add("allowModelOnly");
		ALLOWED_TOP_LEVEL_FIELDS.add("rules");
	}

	// 规则对象的允许字段
	private static final Set<String> ALLOWED_RULE_FIELDS = new HashSet<>();
	static {
		ALLOWED_RULE_FIELDS.add("name");
		ALLOWED_RULE_FIELDS.add("effect");
		ALLOWED_RULE_FIELDS.add("capabilityCodes");
		ALLOWED_RULE_FIELDS.add("actions");
		ALLOWED_RULE_FIELDS.add("obligations");
		ALLOWED_RULE_FIELDS.add("maskFields");
	}

	/**
	 * 验证并反序列化为 AuthorizationPolicy。
	 *
	 * @param jsonStr JSON 字符串
	 * @return 校验通过的 AuthorizationPolicy 实例
	 * @throws CheckedException 校验失败时抛出（中文消息、精确定位字段）
	 */
	public static AuthorizationPolicy validateAndParse(String jsonStr) {
		if (jsonStr == null || jsonStr.trim().isEmpty()) {
			throw CheckedException.badRequest("策略 JSON 不能为空");
		}
		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode rootNode;
		try {
			rootNode = objectMapper.readTree(jsonStr);
		} catch (Exception e) {
			throw CheckedException.badRequest("策略 JSON 格式错误：" + e.getMessage());
		}

		if (!rootNode.isObject()) {
			throw CheckedException.badRequest("策略根节点必须是对象");
		}

		ObjectNode rootObj = (ObjectNode) rootNode;

		// 检查未知字段（严格模式）
		checkUnknownTopLevelFields(rootObj);

		// schemaVersion 必须为 1
		Integer schemaVersion = readOptionalInt(rootObj, "schemaVersion");
		if (schemaVersion == null) {
			throw CheckedException.badRequest("字段'schemaVersion'是必需的");
		}
		if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
			throw CheckedException.badRequest(
					String.format("不支持的 schemaVersion: %d（当前仅支持版本%d）", schemaVersion, SUPPORTED_SCHEMA_VERSION));
		}

		// templateCode 必填非空
		String templateCode = readOptionalString(rootObj, "templateCode");
		if (templateCode == null || templateCode.trim().isEmpty()) {
			throw CheckedException.badRequest("字段'templateCode'不能为空");
		}

		// subjectMode 必需
		SubjectMode subjectMode = readEnum(rootObj, "subjectMode", SubjectMode.class);
		if (subjectMode == null) {
			throw CheckedException.badRequest("字段'subjectMode'是必需的，且值必须在 [CALLER, EMPLOYEE] 范围内");
		}

		// iamUnavailableBehavior 必需
		IamUnavailableBehavior iamUnavailableBehavior = readEnum(rootObj, "iamUnavailableBehavior", IamUnavailableBehavior.class);
		if (iamUnavailableBehavior == null) {
			throw CheckedException.badRequest(
					"字段'iamUnavailableBehavior'是必需的，且值必须在 [ALLOW, DENY] 范围内");
		}

		// allowModelOnly 必需且必须为 boolean
		Boolean allowModelOnly = readOptionalBoolean(rootObj, "allowModelOnly");
		if (allowModelOnly == null) {
			throw CheckedException.badRequest("字段'allowModelOnly'是必需的");
		}

		// rules 可选（空数组合法）
		List<AuthorizationRule> rules = parseRules(rootObj.get("rules"));

		return AuthorizationPolicy.builder()
				.schemaVersion(schemaVersion)
				.templateCode(templateCode)
				.subjectMode(subjectMode)
				.iamUnavailableBehavior(iamUnavailableBehavior)
				.allowModelOnly(allowModelOnly)
				.rules(rules)
				.build();
	}

	/**
	 * 检查顶层是否存在未知字段。
	 *
	 * @param node JSON 对象节点
	 */
	private static void checkUnknownTopLevelFields(ObjectNode node) {
		checkUnknownFields(node, ALLOWED_TOP_LEVEL_FIELDS, "策略");
	}

	/**
	 * 检查规则对象是否存在未知字段。
	 */
	private static void checkUnknownRuleFields(ObjectNode node, int ruleIndex) {
		java.util.Iterator<String> fieldNames = node.fieldNames();
		while (fieldNames.hasNext()) {
			String fieldName = fieldNames.next();
			if (!ALLOWED_RULE_FIELDS.contains(fieldName)) {
				throw CheckedException.badRequest(String.format("规则 #%d 的字段'%s'不允许出现在规则中", ruleIndex, fieldName));
			}
		}
	}

	/**
	 * 通用未知字段检查。
	 */
	private static void checkUnknownFields(ObjectNode node, Set<String> allowedFields, String scope) {
		java.util.Iterator<String> fieldNames = node.fieldNames();
		while (fieldNames.hasNext()) {
			String fieldName = fieldNames.next();
			if (!allowedFields.contains(fieldName)) {
				throw CheckedException.badRequest(String.format("字段'%s'不允许出现在%s中", fieldName, scope));
			}
		}
	}

	/**
	 * 从策略 JSON 读取可选整数。
	 */
	private static Integer readOptionalInt(ObjectNode node, String fieldName) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (fieldNode.isInt()) {
			return fieldNode.intValue();
		}
		throw CheckedException.badRequest(String.format("字段'%s'必须是整数类型", fieldName));
	}

	/**
	 * 从策略 JSON 读取可选字符串。
	 */
	private static String readOptionalString(ObjectNode node, String fieldName) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (fieldNode.isTextual()) {
			return fieldNode.asText();
		}
		throw CheckedException.badRequest(String.format("字段'%s'必须是字符串类型", fieldName));
	}

	/**
	 * 从策略 JSON 读取枚举。
	 */
	@SuppressWarnings("unchecked")
	private static <T extends Enum<T>> T readEnum(ObjectNode node, String fieldName, Class<T> enumType) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (!fieldNode.isTextual()) {
			throw CheckedException.badRequest(String.format("字段'%s'必须是字符串类型", fieldName));
		}
		try {
			return Enum.valueOf(enumType, fieldNode.asText());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 读取可选布尔值。
	 */
	private static Boolean readOptionalBoolean(ObjectNode node, String fieldName) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (fieldNode.isBoolean()) {
			return fieldNode.booleanValue();
		}
		throw CheckedException.badRequest(String.format("字段'%s'必须是布尔类型", fieldName));
	}

	/**
	 * 解析规则列表。
	 */
	private static List<AuthorizationRule> parseRules(JsonNode rulesNode) {
		if (rulesNode == null || rulesNode.isNull() || !rulesNode.isArray()) {
			return List.of();
		}

		List<AuthorizationRule> rules = new ArrayList<>();
		int index = 0;

		for (JsonNode ruleNode : rulesNode) {
			if (!ruleNode.isObject()) {
				throw CheckedException.badRequest(String.format("规则 #%d 必须是对象", index));
			}
			ObjectNode ruleObj = (ObjectNode) ruleNode;

			// 检查未知字段（严格模式，仅限规则允许字段）
			checkUnknownRuleFields(ruleObj, index);

			// name 必需
			String name = readRequiredString(ruleObj, "name", index);
			if (name == null || name.trim().isEmpty()) {
				throw CheckedException.badRequest(String.format("规则 #%d 的'name'字段不能为空", index));
			}

			// effect 必需
			AuthorizationEffect effect = readRequiredEnum(ruleObj, "effect", index, AuthorizationEffect.class);
			if (effect == null) {
				throw CheckedException.badRequest(
						String.format("规则 #%d 的 'effect'字段必须在 [%s,%s] 范围内", index, AuthorizationEffect.ALLOW,
								AuthorizationEffect.DENY));
			}

			// capabilityCodes 可选
			List<String> capabilityCodes = parseStringArray(ruleObj.get("capabilityCodes"), index, "capabilityCodes");

			// actions 可选
			List<AuthorizationAction> actions = parseEnumArray(ruleObj.get("actions"), index, "actions", AuthorizationAction.class);

			// obligations 可选
			List<AuthorizationObligation> obligations = parseEnumArray(ruleObj.get("obligations"), index, "obligations", AuthorizationObligation.class);

			// maskFields 可选（仅当 obligations 含 MASK_FIELDS 时生效）
			List<String> maskFields = parseStringArray(ruleObj.get("maskFields"), index, "maskFields");

			rules.add(AuthorizationRule.builder()
					.name(name)
					.effect(effect)
					.capabilityCodes(capabilityCodes)
					.actions(actions)
					.obligations(obligations)
					.maskFields(maskFields)
					.build());

			index++;
		}

		return rules;
	}

	/**
	 * 读取必填字符串。
	 */
	private static String readRequiredString(ObjectNode node, String fieldName, int contextIndex) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (!fieldNode.isTextual()) {
			throw CheckedException.badRequest(String.format("规则 #%d 的 '%s'字段必须是字符串类型", contextIndex, fieldName));
		}
		return fieldNode.asText();
	}

	/**
	 * 读取必填枚举。
	 */
	@SuppressWarnings("unchecked")
	private static <T extends Enum<T>> T readRequiredEnum(ObjectNode node, String fieldName, int contextIndex, Class<T> enumType) {
		JsonNode fieldNode = node.get(fieldName);
		if (fieldNode == null || fieldNode.isNull()) {
			return null;
		}
		if (!fieldNode.isTextual()) {
			throw CheckedException.badRequest(String.format("规则 #%d 的 '%s'字段必须是字符串类型", contextIndex, fieldName));
		}
		try {
			return Enum.valueOf(enumType, fieldNode.asText());
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/**
	 * 解析字符串数组，带字段名。
	 */
	private static List<String> parseStringArray(JsonNode arrayNode, int ruleIndex, String fieldName) {
		if (arrayNode == null || arrayNode.isNull() || !arrayNode.isArray()) {
			return List.of();
		}

		List<String> result = new ArrayList<>();
		for (int i = 0; i < arrayNode.size(); i++) {
			JsonNode item = arrayNode.get(i);
			if (item.isTextual()) {
				result.add(item.asText());
			} else {
				throw CheckedException.badRequest(String.format("规则 #%d 的 '%s[#%d]' 必须是字符串", ruleIndex, fieldName, i));
			}
		}

		return result;
	}

	/**
	 * 解析枚举数组。
	 */
	private static <T extends Enum<T>> List<T> parseEnumArray(JsonNode arrayNode, int ruleIndex, String fieldName, Class<T> enumType) {
		if (arrayNode == null || arrayNode.isNull() || !arrayNode.isArray()) {
			return List.of();
		}

		List<T> result = new ArrayList<>();
		for (int i = 0; i < arrayNode.size(); i++) {
			JsonNode item = arrayNode.get(i);
			if (item.isTextual()) {
				try {
					result.add(Enum.valueOf(enumType, item.asText()));
				} catch (IllegalArgumentException e) {
					throw CheckedException.badRequest(
							String.format("规则 #%d 的 '%s[#%d]'值为'%s'无效", ruleIndex, fieldName, i, item.asText()));
				}
			} else {
				throw CheckedException.badRequest(String.format("规则 #%d 的动作/义务数组元素必须是字符串", ruleIndex));
			}
		}

		return result;
	}
}
