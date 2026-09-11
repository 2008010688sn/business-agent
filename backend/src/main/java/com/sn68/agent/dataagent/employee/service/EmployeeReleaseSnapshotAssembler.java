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
package com.sn68.agent.dataagent.employee.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeModelConfig;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper;
import com.sn68.agent.dataagent.entity.DataAgentSkillVersion;
import com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字员工 Release 快照装配器（纯函数：入参草稿 → 冻结 JSON + spec_hash）。
 *
 * <p>Seal 冻结语义：快照在 Seal 时刻一次性装配落库，此后员工草稿/能力绑定变更不影响已 Seal 的 Release
 * （运行时唯一能力来源是 snapshot，digital_employee_capability 不再被读取）。</p>
 */
@Component
@RequiredArgsConstructor
public class EmployeeReleaseSnapshotAssembler {

	/** 快照结构版本（结构演进时递增，消费方按 schemaVersion 兼容解析）。 */
	public static final String SNAPSHOT_SCHEMA_VERSION = "1";

	/**
	 * 仅用于 spec_hash：紧凑输出、不套业务模块。对象键排序在树上手动完成，避免跟随 Spring ObjectMapper。
	 */
	private static final ObjectMapper CANONICAL_JSON = new ObjectMapper();

	private final ObjectMapper objectMapper;

	private final DataAgentSkillVersionMapper skillVersionMapper;

	private final DigitalEmployeeModelConfigMapper employeeModelConfigMapper;

	/**
	 * 装配员工草稿 + 启用中能力清单为完整运行规范快照 JSON（冻结时间为当前时刻）。
	 */
	public String assemble(DigitalEmployee employee, List<DigitalEmployeeCapability> capabilities) {
		return assemble(employee, capabilities, Instant.now());
	}

	/**
	 * 装配快照（冻结时间可注入，保证单测确定性）。
	 */
	public String assemble(DigitalEmployee employee, List<DigitalEmployeeCapability> capabilities, Instant frozenAt) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION);
		snapshot.put("employeeId", String.valueOf(employee.getId()));
		snapshot.put("employeeCode", employee.getEmployeeCode());
		snapshot.put("employeeName", employee.getEmployeeName());
		snapshot.put("jobTitle", employee.getJobTitle());
		snapshot.put("systemInstruction", employee.getSystemInstruction());
		snapshot.put("greeting", employee.getGreeting());
		snapshot.put("modelConfigId", employee.getModelConfigId() == null ? null : String.valueOf(employee.getModelConfigId()));
		snapshot.put("routeProfileId", employee.getRouteProfileId() == null ? null : String.valueOf(employee.getRouteProfileId()));
		snapshot.put("sourceAgentId", employee.getSourceAgentId() == null ? null : String.valueOf(employee.getSourceAgentId()));
		snapshot.put("autonomyLevel", employee.getAutonomyLevel());
		snapshot.put("executionPolicy", parseJsonOrRaw(employee.getExecutionPolicy()));

		List<Map<String, Object>> capabilityList = new ArrayList<>();
		if (capabilities != null) {
			for (DigitalEmployeeCapability capability : capabilities) {
				Map<String, Object> item = new LinkedHashMap<>();
				item.put("skillVersionId", String.valueOf(capability.getSkillVersionId()));
				item.put("boundCapabilityId", capability.getId() == null ? null : String.valueOf(capability.getId()));
				DataAgentSkillVersion version = capability.getSkillVersionId() == null ? null
						: skillVersionMapper.selectById(capability.getSkillVersionId());
				if (version != null) {
					item.put("executionMode", version.getExecutionMode());
				}
				capabilityList.add(item);
			}
		}
		snapshot.put("capabilities", capabilityList);
		snapshot.put("availableModelConfigIds", listAvailableModelConfigIds(employee));
		snapshot.put("frozenAt", frozenAt.toString());
		try {
			return objectMapper.writeValueAsString(snapshot);
		}
		catch (Exception ex) {
			throw new IllegalStateException("序列化数字员工 Release 快照失败", ex);
		}
	}

	/**
	 * 计算快照 SHA-256（spec_hash，冻结语义的完整性锚点）。
	 * <p>合法 JSON 先规范化（对象键按名字排序、数组保持原序、紧凑、UTF-8）再哈希，
	 * 避免 PostgreSQL JSONB 往返改写键序/空白导致误报篡改。非法 JSON 按原文哈希。
	 */
	public String computeSpecHash(String snapshotJson) {
		if (!StringUtils.hasText(snapshotJson)) {
			throw new IllegalArgumentException("快照内容不能为空");
		}
		return sha256Hex(canonicalize(snapshotJson));
	}

	private static String canonicalize(String snapshotJson) {
		try {
			JsonNode tree = CANONICAL_JSON.readTree(snapshotJson);
			if (tree == null || tree.isMissingNode()) {
				return snapshotJson;
			}
			return CANONICAL_JSON.writeValueAsString(sortObjectKeys(tree));
		}
		catch (Exception ex) {
			return snapshotJson;
		}
	}

	private static JsonNode sortObjectKeys(JsonNode node) {
		if (node == null || !node.isContainerNode()) {
			return node;
		}
		if (node.isArray()) {
			ArrayNode sorted = CANONICAL_JSON.createArrayNode();
			for (JsonNode item : node) {
				sorted.add(sortObjectKeys(item));
			}
			return sorted;
		}
		ObjectNode sorted = CANONICAL_JSON.createObjectNode();
		TreeMap<String, JsonNode> ordered = new TreeMap<>();
		Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			ordered.put(field.getKey(), sortObjectKeys(field.getValue()));
		}
		ordered.forEach(sorted::set);
		return sorted;
	}

	private static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 摘要算法不可用", ex);
		}
	}

	private List<String> listAvailableModelConfigIds(DigitalEmployee employee) {
		if (employee == null || employee.getId() == null || !StringUtils.hasText(employee.getTenantId())) {
			return List.of();
		}
		List<String> ids = new ArrayList<>();
		for (DigitalEmployeeModelConfig config : employeeModelConfigMapper.findEnabledByEmployeeId(employee.getId(),
				employee.getTenantId())) {
			if (config.getModelConfigId() != null) {
				ids.add(String.valueOf(config.getModelConfigId()));
			}
		}
		if (ids.isEmpty() && employee.getModelConfigId() != null) {
			ids.add(String.valueOf(employee.getModelConfigId()));
		}
		return ids;
	}

	private Object parseJsonOrRaw(String json) {
		if (!StringUtils.hasText(json)) {
			return Map.of();
		}
		try {
			return objectMapper.readValue(json, Map.class);
		}
		catch (Exception ex) {
			// 非法 JSON 原样保留字符串，装配不因此失败（Seal 门禁由 spec_hash 兜底）。
			return json;
		}
	}

}
