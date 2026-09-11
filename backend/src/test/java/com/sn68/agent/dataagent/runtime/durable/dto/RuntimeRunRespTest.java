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
package com.sn68.agent.dataagent.runtime.durable.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import org.junit.jupiter.api.Test;

/**
 * RuntimeRunResp 发起人 / 执行主体投影。
 */
class RuntimeRunRespTest {

	@Test
	void fromCopiesInitiatorAndSubjectKind() {
		AgentRuntimeRun run = AgentRuntimeRun.builder()
			.ownerType("DIGITAL_EMPLOYEE")
			.ownerId(9L)
			.digitalEmployeeId(9L)
			.releaseId(3L)
			.createBy("user-1")
			.createName("张三")
			.build();
		run.setId(11L);

		RuntimeRunResp resp = RuntimeRunResp.from(run, "sp_abc", "deadbeef");

		assertEquals(11L, resp.id());
		assertEquals("user-1", resp.initiatorUserId());
		assertEquals("张三", resp.initiatorUserName());
		assertEquals("sp_abc", resp.executionPrincipalId());
		assertEquals("DIGITAL_EMPLOYEE", resp.subjectKind());
		assertEquals("deadbeef", resp.specHash());
		assertEquals(3L, resp.releaseId());
	}

	@Test
	void fromWithoutEnrichmentKeepsPrincipalAndHashNull() {
		AgentRuntimeRun run = AgentRuntimeRun.builder().ownerType("CALLER").build();
		RuntimeRunResp resp = RuntimeRunResp.from(run);
		assertEquals("CALLER", resp.subjectKind());
		assertNull(resp.executionPrincipalId());
		assertNull(resp.specHash());
	}

}
