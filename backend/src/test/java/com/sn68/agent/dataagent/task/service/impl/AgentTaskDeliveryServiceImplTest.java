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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskDelivery;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskDeliveryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentTaskDeliveryServiceImplTest {

	private AgentTaskDeliveryMapper deliveryMapper;

	private AgentTaskDefinitionMapper definitionMapper;

	private AgentTaskDeliveryServiceImpl service;

	@BeforeEach
	void setUp() {
		deliveryMapper = mock(AgentTaskDeliveryMapper.class);
		definitionMapper = mock(AgentTaskDefinitionMapper.class);
		service = new AgentTaskDeliveryServiceImpl(deliveryMapper, definitionMapper);
	}

	@Test
	void insertsWebSuccessWhenAbsent() {
		AgentTaskRun taskRun = AgentTaskRun.builder()
			.tenantId("7")
			.definitionId(2L)
			.runtimeRunId(88L)
			.build();
		taskRun.setId(5L);
		when(deliveryMapper.selectOne(any(Wrapper.class))).thenReturn(null);
		when(definitionMapper.findByTenantAndId("7", 2L))
			.thenReturn(AgentTaskDefinition.builder().id(2L).digitalEmployeeId(9L).build());
		when(deliveryMapper.insert(any(AgentTaskDelivery.class))).thenReturn(1);

		service.recordWebInbox(taskRun);

		ArgumentCaptor<AgentTaskDelivery> captor = ArgumentCaptor.forClass(AgentTaskDelivery.class);
		verify(deliveryMapper).insert(captor.capture());
		AgentTaskDelivery row = captor.getValue();
		assertEquals("WEB", row.getChannel());
		assertEquals("SUCCESS", row.getDeliveryStatus());
		assertEquals("/ai-agent/digital-employees/detail?id=9&runtimeRunId=88", row.getTarget());
	}

	@Test
	void skipsWhenWebSuccessAlreadyExists() {
		AgentTaskRun taskRun = AgentTaskRun.builder().tenantId("7").definitionId(2L).build();
		taskRun.setId(5L);
		when(deliveryMapper.selectOne(any(Wrapper.class))).thenReturn(AgentTaskDelivery.builder().id(1L).build());

		service.recordWebInbox(taskRun);

		verify(deliveryMapper, never()).insert(any(AgentTaskDelivery.class));
	}

}
