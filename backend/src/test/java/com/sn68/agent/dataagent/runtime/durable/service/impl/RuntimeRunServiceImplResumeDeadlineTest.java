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
package com.sn68.agent.dataagent.runtime.durable.service.impl;

import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import com.sn68.agent.dataagent.runtime.durable.support.InMemoryDurableRuntime;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * WAITING_* 恢复到 RUNNING 时必须重置执行 deadline，避免沿用等待前已过期的截止时间。
 */
class RuntimeRunServiceImplResumeDeadlineTest {

	@Test
	void resumeFromWaitingInputResetsDeadlineAt() {
		InMemoryDurableRuntime runtime = new InMemoryDurableRuntime();
		Long runId = runtime.seedChatRun("7", "88", RuntimeRunState.WAITING_INPUT);
		runtime.forceDeadline(runId, Instant.now().minus(Duration.ofHours(1)));
		doAnswer(invocation -> {
			Long id = invocation.getArgument(0);
			String tenantId = invocation.getArgument(1);
			AgentRuntimeRun run = runtime.runById(id);
			if (run == null || !Objects.equals(run.getTenantId(), tenantId)) {
				return null;
			}
			return run;
		}).when(runtime.runMapper).findByIdAndTenantId(anyLong(), anyString());
		RuntimeRunServiceImpl service = new RuntimeRunServiceImpl(runtime.runMapper,
				mock(AgentRuntimeStepMapper.class), mock(AgentRuntimePlanMapper.class), runtime.stateService,
				runtime.eventService, mock(RuntimeCancellationService.class), mock(DigitalEmployeeMapper.class),
				mock(DigitalEmployeeReleaseMapper.class), new DataAgentProperties());

		Instant before = Instant.now();
		service.resume("7", "user-a", runId);

		AgentRuntimeRun after = runtime.runById(runId);
		assertEquals(RuntimeRunState.RUNNING.getValue(), after.getState());
		assertNotNull(after.getDeadlineAt());
		assertTrue(after.getDeadlineAt().isAfter(before.plus(Duration.ofMinutes(19))),
				() -> "deadlineAt=" + after.getDeadlineAt());
		assertTrue(after.getDeadlineAt().isBefore(before.plus(Duration.ofMinutes(21))),
				() -> "deadlineAt=" + after.getDeadlineAt());
	}

}
