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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.task.entity.AgentTaskDefinition;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.entity.AgentTaskTrigger;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskErrorDict;
import com.sn68.agent.dataagent.task.enums.TaskTriggerType;
import com.sn68.agent.dataagent.task.repository.AgentTaskTriggerMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskVersionMapper;
import com.sn68.agent.dataagent.task.schedule.TaskRuntimeKick;
import com.sn68.agent.dataagent.task.service.AgentTaskDefinitionService;
import com.sn68.agent.dataagent.task.service.ApiTriggerSecurityService;
import com.sn68.agent.dataagent.task.service.TaskLaunchRequest;
import com.sn68.agent.dataagent.task.service.TaskRunLauncher;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制台立即执行：SCHEDULE/EVENT/API 可拉起，CHAT/IM 拒绝；SCHEDULE 不占用 cron 计划时刻。
 */
class AgentTaskTriggerManualTriggerTest {

	private static final Long DEFINITION_ID = 10L;

	private static final Long TRIGGER_ID = 20L;

	private static final String TENANT_ID = "1";

	private final AgentTaskDefinitionService definitionService = mock(AgentTaskDefinitionService.class);

	private final AgentTaskTriggerMapper triggerMapper = mock(AgentTaskTriggerMapper.class);

	private final TaskRunLauncher taskRunLauncher = mock(TaskRunLauncher.class);

	@SuppressWarnings("unchecked")
	private final ObjectProvider<TaskRuntimeKick> runtimeKick = mock(ObjectProvider.class);

	private final TaskRuntimeKick kick = mock(TaskRuntimeKick.class);

	private final AgentTaskTriggerServiceImpl service = new AgentTaskTriggerServiceImpl(definitionService,
			mock(AgentTaskVersionMapper.class), taskRunLauncher, mock(ApiTriggerSecurityService.class),
			new ObjectMapper(), mock(DigitalEmployeeMapper.class), runtimeKick);

	@BeforeEach
	void setUp() {
		ReflectionTestUtils.setField(service, "baseMapper", triggerMapper);
		when(definitionService.requireOwned(DEFINITION_ID)).thenReturn(AgentTaskDefinition.builder()
			.id(DEFINITION_ID)
			.tenantId(TENANT_ID)
			.status(TaskConstants.STATUS_ENABLED)
			.build());
		when(taskRunLauncher.launch(any(TaskLaunchRequest.class)))
			.thenReturn(AgentTaskRun.builder().id(99L).definitionId(DEFINITION_ID).build());
		when(runtimeKick.getIfAvailable()).thenReturn(kick);
	}

	@Test
	void scheduleManualTriggerUsesNowAndDoesNotReuseCronSlot() {
		stubTrigger(TaskTriggerType.SCHEDULE);

		AgentTaskRun run = service.manualTrigger(DEFINITION_ID, TRIGGER_ID);

		assertEquals(99L, run.getId());
		ArgumentCaptor<TaskLaunchRequest> captor = ArgumentCaptor.forClass(TaskLaunchRequest.class);
		verify(taskRunLauncher).launch(captor.capture());
		TaskLaunchRequest request = captor.getValue();
		assertEquals(TaskTriggerType.SCHEDULE, request.triggerType());
		assertNull(request.externalEventId());
		assertNotNull(request.scheduledTime());
		assertTrue(request.scheduledTime().isAfter(Instant.now().minusSeconds(5)));
		verify(kick).kickDaemonScan();
	}

	@Test
	void eventManualTriggerGeneratesUniqueExternalId() {
		stubTrigger(TaskTriggerType.EVENT);

		service.manualTrigger(DEFINITION_ID, TRIGGER_ID);

		ArgumentCaptor<TaskLaunchRequest> captor = ArgumentCaptor.forClass(TaskLaunchRequest.class);
		verify(taskRunLauncher).launch(captor.capture());
		assertEquals(TaskTriggerType.EVENT, captor.getValue().triggerType());
		assertTrue(captor.getValue().externalEventId().startsWith("manual-"));
	}

	@Test
	void chatManualTriggerIsRejected() {
		stubTrigger(TaskTriggerType.CHAT);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.manualTrigger(DEFINITION_ID, TRIGGER_ID));
		assertEquals(TaskErrorDict.MANUAL_TRIGGER_UNSUPPORTED.getLabel(), ex.getMessage());
		verify(taskRunLauncher, never()).launch(any());
		verify(kick, never()).kickDaemonScan();
	}

	private void stubTrigger(TaskTriggerType type) {
		when(triggerMapper.findByTenantAndId(TENANT_ID, TRIGGER_ID)).thenReturn(AgentTaskTrigger.builder()
			.id(TRIGGER_ID)
			.tenantId(TENANT_ID)
			.definitionId(DEFINITION_ID)
			.triggerType(type.getValue())
			.status(TaskConstants.STATUS_ENABLED)
			.build());
	}

}
