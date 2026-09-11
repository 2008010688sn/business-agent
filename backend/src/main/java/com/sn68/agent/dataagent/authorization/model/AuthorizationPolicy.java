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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/**
 * 授权策略实体。
 *
 * <p>PDP 内核契约的一部分，表示完整的授权策略定义。策略在发布后不可变（JSON 字符串层面）。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
@Getter
@Builder(toBuilder = true)
public class AuthorizationPolicy {

	/**
	 * 模式版本：当前固定为 1。
	 */
	@Schema(description = "模式版本（当前固定为 1）", requiredMode = Schema.RequiredMode.REQUIRED, defaultValue = "1")
	private final Integer schemaVersion;

	/**
	 * 模板代码（引用预设模板）。
	 */
	@Schema(description = "模板代码（如 MODEL_ONLY/DIGITAL_WORKER 等）")
	private final String templateCode;

	/**
	 * 主体模式：CALLER（真人调用者）或 EMPLOYEE（数字员工）。
	 */
	@Schema(description = "主体模式：CALLER/EMPLOYEE", requiredMode = Schema.RequiredMode.REQUIRED)
	private final SubjectMode subjectMode;

	/**
	 * IAM 不可用时的行为：允许或拒绝。
	 */
	@Schema(description = "IAM 不可用行为：ALLOW/DENY", requiredMode = Schema.RequiredMode.REQUIRED)
	private final IamUnavailableBehavior iamUnavailableBehavior;

	/**
	 * 是否仅允许模型对话能力（无业务动作）。
	 */
	@Schema(description = "是否仅允许模型对话能力", requiredMode = Schema.RequiredMode.REQUIRED)
	private final Boolean allowModelOnly;

	/**
	 * 规则列表（有序，首条命中）。
	 */
	@Schema(description = "规则列表（有序）")
	private final List<AuthorizationRule> rules;

	/**
	 * 构造默认实例（空策略，默认拒绝）。
	 */
	public static AuthorizationPolicy emptyDeny() {
		return builder()
				.schemaVersion(1)
				.subjectMode(SubjectMode.CALLER)
				.iamUnavailableBehavior(IamUnavailableBehavior.DENY)
				.allowModelOnly(false)
				.rules(Collections.emptyList())
				.build();
	}

	/**
	 * 计算策略哈希值（用于版本追踪和完整性校验）。
	 * 规范方式：按 schemaVersion、templateCode、subjectMode、iamUnavailableBehavior、allowModelOnly、rules（保持顺序）排序后 JSON 序列化，再 SHA-256。
	 *
	 * @return SHA-256 哈希字符串（小写 hex）
	 */
	@JsonIgnore
	public String computeHash() {
		try {
			ObjectMapper objectMapper = new ObjectMapper();
			Map<String, Object> canonical = new LinkedHashMap<>();
			canonical.put("schemaVersion", schemaVersion);
			if (templateCode != null) {
				canonical.put("templateCode", templateCode);
			}
			if (subjectMode != null) {
				canonical.put("subjectMode", subjectMode.getCode());
			}
			if (iamUnavailableBehavior != null) {
				canonical.put("iamUnavailableBehavior", iamUnavailableBehavior.getCode());
			}
			canonical.put("allowModelOnly", allowModelOnly);
			if (rules != null && !rules.isEmpty()) {
				List<Map<String, Object>> ruleList = new ArrayList<>();
				for (AuthorizationRule rule : rules) {
					Map<String, Object> ruleMap = new LinkedHashMap<>();
					ruleMap.put("name", rule.getName());
					ruleMap.put("effect", rule.getEffect().getCode());
					if (rule.getCapabilityCodes() != null && !rule.getCapabilityCodes().isEmpty()) {
						ruleMap.put("capabilityCodes", rule.getCapabilityCodes());
					}
					if (rule.getActions() != null && !rule.getActions().isEmpty()) {
						List<String> actionCodes = new ArrayList<>();
						for (AuthorizationAction action : rule.getActions()) {
							actionCodes.add(action.getCode());
						}
						ruleMap.put("actions", actionCodes);
					}
					if (rule.getObligations() != null && !rule.getObligations().isEmpty()) {
						List<String> obligationCodes = new ArrayList<>();
						for (AuthorizationObligation oblg : rule.getObligations()) {
							obligationCodes.add(oblg.getCode());
						}
						ruleMap.put("obligations", obligationCodes);
					}
					if (rule.getMaskFields() != null && !rule.getMaskFields().isEmpty()) {
						ruleMap.put("maskFields", rule.getMaskFields());
					}
					ruleList.add(ruleMap);
				}
				canonical.put("rules", ruleList);
			}
			String normalizedJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonical);
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(normalizedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder hexString = new StringBuilder();
			for (byte b : hashBytes) {
				hexString.append(String.format("%02x", b));
			}
			return hexString.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to compute policy hash: " + e.getMessage(), e);
		}
	}

}
