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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.agentscope.dto.AgentRequest;
import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.agent.CollaborateToolReq;
import com.sn68.agent.dataagent.dto.agent.OrchestrationTraceResp;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentOrchestrationPolicy;
import com.sn68.agent.dataagent.entity.AgentOrchestrationRun;
import com.sn68.agent.dataagent.entity.AgentOrchestrationStep;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.repository.AgentOrchestrationRunMapper;
import com.sn68.agent.dataagent.repository.AgentOrchestrationStepMapper;
import com.sn68.agent.dataagent.service.permission.DataAgentThinkingPermissionService;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataAgentOrchestrationServiceImplTest {

	private final DataAgentService agentService = mock(DataAgentService.class);

	private final AgentCollaboratorService collaboratorService = mock(AgentCollaboratorService.class);

	private final AgentInvocationService invocationService = mock(AgentInvocationService.class);

	private final AgentOrchestrationPolicyService policyService = mock(AgentOrchestrationPolicyService.class);

	private final AgentOrchestrationRunMapper runMapper = mock(AgentOrchestrationRunMapper.class);

	private final AgentOrchestrationStepMapper stepMapper = mock(AgentOrchestrationStepMapper.class);

	private final DataAgentThinkingPermissionService thinkingPermissionService =
			mock(DataAgentThinkingPermissionService.class);

	private final AgentOrchestrationServiceImpl service = new AgentOrchestrationServiceImpl(agentService,
			collaboratorService, invocationService, policyService, runMapper, stepMapper, thinkingPermissionService,
			new DataAgentProperties());

	@Test
	void getRunTrace_returnsRunAndStepTiming() {
		AgentOrchestrationRun run = run();
		AgentOrchestrationStep step = step();
		when(agentService.requireAgent(1L)).thenReturn(DataAgent.builder().id(1L).build());
		when(runMapper.findByIdAndAgentId(100L, 1L)).thenReturn(run);
		when(stepMapper.findByRunId(100L)).thenReturn(List.of(step));

		OrchestrationTraceResp trace = service.getRunTrace(1L, 100L);

		assertEquals(100L, trace.getRun().getId());
		assertEquals(37L, trace.getRun().getRouteMs());
		assertEquals(51L, trace.getSteps().get(0).getDurationMs());
		assertEquals("child-runtime-1", trace.getSteps().get(0).getChildRuntimeRequestId());
		assertEquals(3, trace.getSteps().get(0).getToolCount());
	}

	@Test
	void getRuntimeTrace_resolvesRunByRuntimeRequestId() {
		AgentOrchestrationRun run = run();
		when(agentService.requireAgent(1L)).thenReturn(DataAgent.builder().id(1L).build());
		when(runMapper.findByRuntimeRequestIdAndAgentId("runtime-1", 1L)).thenReturn(run);
		when(stepMapper.findByRunId(100L)).thenReturn(List.of(step()));

		OrchestrationTraceResp trace = service.getRuntimeTrace(1L, "runtime-1");

		assertEquals("runtime-1", trace.getRun().getRuntimeRequestId());
		assertEquals(100L, trace.getRun().getId());
	}

	@Test
	void collaborate_forwardsConfiguredDelegationModeToChildRequest() {
		AgentRequest parent = AgentRequest.builder()
			.agentId("1")
			.threadId("100")
			.runtimeRequestId("parent-run")
			.query("查询订单")
			.build();
		CollaborateToolReq request = new CollaborateToolReq();
		request.setCollaboratorAgentId(2L);
		request.setTask("查询订单");
		request.setExpectedOutput("订单汇总");
		DataAgent orchestrator = DataAgent.builder().id(1L).agentType(AgentTypeConstant.ORCHESTRATOR).build();
		DataAgent childAgent = DataAgent.builder().id(2L).runtimeTimeoutSeconds(30).build();
		AgentCollaborator collaborator = AgentCollaborator.builder().agentId(1L).collaboratorAgentId(2L)
			.delegationMode(DelegationMode.PLAN_CONFIRM.name()).enabled(true).build();
		AgentOrchestrationRun run = AgentOrchestrationRun.builder().id(100L).runtimeRequestId("parent-run").build();
		run.setId(100L);
		AgentOrchestrationStep persistedStep = AgentOrchestrationStep.builder().id(200L).build();
		persistedStep.setId(200L);
		when(agentService.requireAgent(1L)).thenReturn(orchestrator);
		when(agentService.requireAgent(2L)).thenReturn(childAgent);
		when(collaboratorService.requireEnabled(1L, 2L)).thenReturn(collaborator);
		when(runMapper.findByAgentId(1L)).thenReturn(List.of(run));
		when(stepMapper.findByRunId(100L)).thenReturn(List.of());
		doAnswer(invocation -> {
			invocation.<AgentOrchestrationStep>getArgument(0).setId(200L);
			return 1;
		}).when(stepMapper).insert(any(AgentOrchestrationStep.class));
		when(stepMapper.selectById(200L)).thenReturn(persistedStep);
		when(policyService.getOrCreate(1L))
			.thenReturn(AgentOrchestrationPolicy.builder().failureStrategy("continue").build());
		when(invocationService.invoke(any(AgentRequest.class))).thenReturn("订单汇总");

		service.collaborate(parent, request);

		ArgumentCaptor<AgentRequest> captor = ArgumentCaptor.forClass(AgentRequest.class);
		verify(invocationService).invoke(captor.capture());
		assertTrue(captor.getValue().isCollaboratorChild());
		assertEquals(DelegationMode.PLAN_CONFIRM.name(), captor.getValue().getCollaboratorDelegationMode());
	}

	private AgentOrchestrationRun run() {
		AgentOrchestrationRun run = AgentOrchestrationRun.builder()
			.id(100L)
			.agentId(1L)
			.threadId("200")
			.runtimeRequestId("runtime-1")
			.query("查询公司产品和本月需求最多产品")
			.status("success")
			.routeMs(37L)
			.collaboratorMs(51L)
			.summaryMs(24L)
			.totalMs(112L)
			.collaboratorCount(2)
			.startedAt(Instant.parse("2026-06-14T09:00:00Z"))
			.finishedAt(Instant.parse("2026-06-14T09:00:01Z"))
			.build();
		run.setId(100L);
		return run;
	}

	private AgentOrchestrationStep step() {
		AgentOrchestrationStep step = AgentOrchestrationStep.builder()
			.id(200L)
			.runId(100L)
			.stepNo(1)
			.collaboratorAgentId(10L)
			.task("查询产品")
			.reason("命中产品问题")
			.status("success")
			.childThreadId("child-thread-1")
			.childRuntimeRequestId("child-runtime-1")
			.durationMs(51L)
			.reactMs(43L)
			.toolCount(3)
			.toolFailCount(0)
			.build();
		step.setId(200L);
		return step;
	}

}
