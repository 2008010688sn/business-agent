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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 快照装配器测试：冻结语义（装配后草稿变更不影响既有快照）与 spec_hash 确定性。
 */
class EmployeeReleaseSnapshotAssemblerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final EmployeeReleaseSnapshotAssembler assembler = new EmployeeReleaseSnapshotAssembler(objectMapper,
			mock(com.sn68.agent.dataagent.repository.DataAgentSkillVersionMapper.class),
			mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeModelConfigMapper.class));

	private DigitalEmployee employee() {
		return DigitalEmployee.builder()
			.id(9L)
			.tenantId("7")
			.employeeCode("EMP-001")
			.employeeName("测试员工")
			.systemInstruction("你是测试员工")
			.build();
	}

	@Test
	@DisplayName("冻结语义：快照装配后修改草稿字段不影响既有快照内容")
	void frozenSnapshotIsImmuneToLaterDraftChanges() throws Exception {
		DigitalEmployee employee = employee();
		DigitalEmployeeCapability capability = DigitalEmployeeCapability.builder()
			.id(1L)
			.employeeId(9L)
			.skillVersionId(5L)
			.enabled(true)
			.build();

		String frozen = assembler.assemble(employee, List.of(capability), Instant.parse("2026-08-18T00:00:00Z"));
		employee.setEmployeeName("改名的员工");
		employee.setSystemInstruction("改过的提示词");

		JsonNode node = objectMapper.readTree(frozen);
		assertEquals("测试员工", node.get("employeeName").asText());
		assertEquals("你是测试员工", node.get("systemInstruction").asText());
		assertEquals("5", node.get("capabilities").get(0).get("skillVersionId").asText());
		assertEquals("2026-08-18T00:00:00Z", node.get("frozenAt").asText());
		assertEquals(EmployeeReleaseSnapshotAssembler.SNAPSHOT_SCHEMA_VERSION, node.get("schemaVersion").asText());
	}

	@Test
	@DisplayName("spec_hash 确定性：相同输入（含冻结时间）产生相同 SHA-256，能力变更即变化")
	void specHashIsDeterministicAndSensitiveToCapabilityChange() throws Exception {
		DigitalEmployee employee = employee();
		DigitalEmployeeCapability capability = DigitalEmployeeCapability.builder()
			.skillVersionId(5L)
			.build();
		Instant frozenAt = Instant.parse("2026-08-18T00:00:00Z");

		String snapshotA = assembler.assemble(employee, List.of(capability), frozenAt);
		String snapshotB = assembler.assemble(employee, List.of(capability), frozenAt);
		String hashA = assembler.computeSpecHash(snapshotA);
		String hashB = assembler.computeSpecHash(snapshotB);

		assertEquals(hashA, hashB, "相同输入的 spec_hash 必须一致（发布门禁比对依据）");
		assertEquals(64, hashA.length(), "SHA-256 hex 长度固定 64");

		DigitalEmployeeCapability another = DigitalEmployeeCapability.builder()
			.skillVersionId(6L)
			.build();
		String changed = assembler.assemble(employee, List.of(another), frozenAt);
		assertNotEquals(hashA, assembler.computeSpecHash(changed), "能力清单变化必须改变 spec_hash");
		assertTrue(assembler.computeSpecHash(changed).matches("[0-9a-f]{64}"));
	}

	@Test
	@DisplayName("spec_hash 对对象键序和空白不敏感，数组顺序变化必须变哈希")
	void specHashCanonicalizesObjectKeysAndKeepsArrayOrder() {
		String insertionOrder = "{\"schemaVersion\":\"1\",\"employeeName\":\"运营\",\"capabilities\":[{\"skillVersionId\":\"5\"},{\"skillVersionId\":\"6\"}]}";
		String reordered = "{ \"employeeName\" : \"运营\", \"schemaVersion\" : \"1\", \"capabilities\" : [ { \"skillVersionId\" : \"5\" }, { \"skillVersionId\" : \"6\" } ] }";
		String arraySwapped = "{\"schemaVersion\":\"1\",\"employeeName\":\"运营\",\"capabilities\":[{\"skillVersionId\":\"6\"},{\"skillVersionId\":\"5\"}]}";
		String renamed = "{\"schemaVersion\":\"1\",\"employeeName\":\"客服\",\"capabilities\":[{\"skillVersionId\":\"5\"},{\"skillVersionId\":\"6\"}]}";

		assertEquals(assembler.computeSpecHash(insertionOrder), assembler.computeSpecHash(reordered));
		assertNotEquals(assembler.computeSpecHash(insertionOrder), assembler.computeSpecHash(arraySwapped));
		assertNotEquals(assembler.computeSpecHash(insertionOrder), assembler.computeSpecHash(renamed));
		assertEquals(assembler.computeSpecHash("{ not-json"), assembler.computeSpecHash("{ not-json"));
	}

}
