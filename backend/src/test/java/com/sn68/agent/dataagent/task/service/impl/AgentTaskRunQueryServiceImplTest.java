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
package com.sn68.agent.dataagent.task.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeArtifactResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.runtime.durable.support.RuntimeWorkProductAssembler;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunDetailResp;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.AgentTaskDeliveryService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskRunQueryServiceImplTest {

	private AuthenticationContext authenticationContext;

	private AgentTaskRunMapper taskRunMapper;

	private AgentTaskDefinitionService definitionService;

	private RuntimeRunService runtimeRunService;

	private RuntimeWorkProductAssembler assembler;

	private AgentTaskDeliveryService deliveryService;

	private AgentTaskRunQueryServiceImpl service;

	@BeforeEach
	void setUp() {
		authenticationContext = mock(AuthenticationContext.class);
		taskRunMapper = mock(AgentTaskRunMapper.class);
		definitionService = mock(AgentTaskDefinitionService.class);
		runtimeRunService = mock(RuntimeRunService.class);
		assembler = mock(RuntimeWorkProductAssembler.class);
		deliveryService = mock(AgentTaskDeliveryService.class);
		service = new AgentTaskRunQueryServiceImpl(authenticationContext, taskRunMapper, definitionService,
				runtimeRunService, assembler, deliveryService);
		when(authenticationContext.tenantId()).thenReturn("7");
	}

	@Test
	void missingRunIsNotFound() {
		when(taskRunMapper.findByTenantAndId("7", 5L)).thenReturn(null);
		assertThrows(CheckedException.class, () -> service.findDetail(5L));
	}

	@Test
	void aggregatesFinalAnswerFromRuntimeRun() {
		AgentTaskRun taskRun = AgentTaskRun.builder()
			.tenantId("7")
			.definitionId(2L)
			.runStatus("SUCCESS")
			.runtimeRunId(88L)
			.triggerType("SCHEDULE")
			.build();
		taskRun.setId(5L);
		when(taskRunMapper.findByTenantAndId("7", 5L)).thenReturn(taskRun);
		when(definitionService.requireOwned(2L)).thenReturn(AgentTaskDefinition.builder().id(2L).build());
		RuntimeRunResp run = new RuntimeRunResp(88L, "DIGITAL_EMPLOYEE", 9L, 9L, null, 9L, "k", null, null, "SCHEDULE",
				"AGENT_LOOP", "每日快报", "SUCCEEDED", 1L, 0L, null, null, null, null, null, null, null, null, null,
				"DIGITAL_EMPLOYEE", null);
		when(runtimeRunService.detail("7", 88L))
			.thenReturn(new RuntimeRunDetailResp(run, null, null, null, List.of(), 2L));
		List<RuntimeArtifactResp> artifacts = List.of(
				new RuntimeArtifactResp(1L, "single-turn", "single-turn-answer/v1", "{\"answer\":\"今日库存 12\"}",
						"INTERNAL"));
		when(assembler.listArtifacts(88L)).thenReturn(artifacts);
		when(assembler.resolveFinalAnswer(null, artifacts)).thenReturn("今日库存 12");
		when(deliveryService.listByTaskRun("7", 5L)).thenReturn(List.of());

		AgentTaskRunDetailResp detail = service.findDetail(5L);

		assertEquals(5L, detail.taskRunId());
		assertEquals("今日库存 12", detail.finalAnswer());
		assertEquals(1, detail.artifacts().size());
		verify(definitionService).requireOwned(2L);
	}

}
