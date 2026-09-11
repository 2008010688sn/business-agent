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
package com.sn68.agent.dataagent.service.memory;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pep.MemoryAuthorizationAdvisor;
import com.sn68.agent.dataagent.authorization.pep.PepDecisionContext;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryCandidateSaveReq;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryExportItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemResp;
import com.sn68.agent.dataagent.dto.memory.AgentMemoryItemsQueryReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.entity.AgentMemory;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.enums.MemoryErrorDict;
import com.sn68.agent.dataagent.enums.MemoryScope;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.repository.AgentMemoryConfigMapper;
import com.sn68.agent.dataagent.repository.AgentMemoryMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.service.agent.DataAgentService;
import com.sn68.agent.dataagent.service.chat.DataChatSessionService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-7 记忆域归属键收口测试：WORKSPACE 共享记忆数字员工键、expires_at 落库、
 * PR-3c PEP 写入/读取接缝保留性（owner 键按归属切换，接缝调用不缺失）。
 */
class AgentMemoryServiceImplTest {

	private static final Long EMPLOYEE_ID = 2090239488790712321L;

	private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);

	private final AgentMemoryConfigMapper configMapper = mock(AgentMemoryConfigMapper.class);

	private final DataAgentService agentService = mock(DataAgentService.class);

	private final DigitalEmployeeMapper digitalEmployeeMapper = mock(DigitalEmployeeMapper.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final DataChatSessionService chatSessionService = mock(DataChatSessionService.class);

	private final RuntimeRunService runtimeRunService = mock(RuntimeRunService.class);

	private final MemoryAuthorizationAdvisor memoryAuthorizationAdvisor = mock(MemoryAuthorizationAdvisor.class);

	private final AgentMemoryServiceImpl service = new AgentMemoryServiceImpl(memoryMapper, configMapper, agentService,
			digitalEmployeeMapper, new DataAgentProperties(), authenticationContext, chatSessionService,
			runtimeRunService, memoryAuthorizationAdvisor);

	@Test
	void workspaceMemoryRequiresNumericEmployeeSubject() {
		AgentMemoryCandidateSaveReq request = baseRequest(MemoryScope.WORKSPACE, "not-a-number");

		assertThrows(CheckedException.class, () -> service.saveLongTermCandidate(request));
		verify(memoryAuthorizationAdvisor, never()).checkMemoryAccess(any(), any());
	}

	@Test
	void workspaceMemoryRejectsMismatchedEmployeeId() {
		AgentMemoryCandidateSaveReq request = AgentMemoryCandidateSaveReq.builder()
			.agentId(1L)
			.digitalEmployeeId(66L)
			.scope(MemoryScope.WORKSPACE)
			.subjectId("55")
			.summary("数字员工共享记忆")
			.sourceRunId(100L)
			.rootRunSucceeded(Boolean.TRUE)
			.build();

		assertThrows(CheckedException.class, () -> service.saveLongTermCandidate(request));
	}

	@Test
	void nonWorkspaceScopeRejectsDigitalEmployeeId() {
		AgentMemoryCandidateSaveReq request = AgentMemoryCandidateSaveReq.builder()
			.agentId(1L)
			.digitalEmployeeId(55L)
			.scope(MemoryScope.EMPLOYEE_USER)
			.subjectId("u1")
			.summary("用户偏好汇总口径")
			.sourceRunId(100L)
			.rootRunSucceeded(Boolean.TRUE)
			.build();

		assertThrows(CheckedException.class, () -> service.saveLongTermCandidate(request));
	}

	/**
	 * PR-7 归属键切换：WORKSPACE 共享记忆 subjectId 即数字员工 ID，落库冗余 digital_employee_id，
	 * PEP 写入接缝 owner 键切到 DIGITAL_EMPLOYEE 主体（ownerType/ownerId/subjectKind 三键一致），
	 * action 仍为 WRITE_MEMORY（接缝语义不变）。
	 */
	@Test
	void workspaceMemoryResolvesDigitalEmployeeOwnerKeyOnWriteSeam() {
		AgentMemoryCandidateSaveReq request = AgentMemoryCandidateSaveReq.builder()
			.agentId(1L)
			.scope(MemoryScope.WORKSPACE)
			.subjectId("55")
			.summary("数字员工按客户维度出日报")
			.expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
			.sourceRunId(100L)
			.rootRunSucceeded(Boolean.TRUE)
			.build();
		stubTenantAndRunState();

		service.saveLongTermCandidate(request);

		ArgumentCaptor<AgentMemory> memoryCaptor = ArgumentCaptor.forClass(AgentMemory.class);
		verify(memoryMapper).insert(memoryCaptor.capture());
		AgentMemory saved = memoryCaptor.getValue();
		assertEquals(55L, saved.getDigitalEmployeeId());
		assertEquals(MemoryScope.WORKSPACE, saved.getSubjectType());
		assertEquals("55", saved.getSubjectId());
		assertEquals(Instant.parse("2099-01-01T00:00:00Z"), saved.getExpiresAt());

		PepDecisionContext context = capturedWriteContext();
		assertEquals(AuthorizationOwnerType.DIGITAL_EMPLOYEE, context.getOwnerType());
		assertEquals(55L, context.getOwnerId());
		assertEquals(SubjectKind.DIGITAL_EMPLOYEE, context.getSubjectKind());
		assertEquals(AuthorizationAction.WRITE_MEMORY, context.getAction());
	}

	/**
	 * PR-3c 接缝保留性：真人记忆写入保持 DATA_AGENT/CALLER 原口径，接缝调用不缺失。
	 */
	@Test
	void userMemoryKeepsDataAgentCallerOwnerKeyOnWriteSeam() {
		AgentMemoryCandidateSaveReq request = baseRequest(MemoryScope.EMPLOYEE_USER, "u1");
		stubTenantAndRunState();

		service.saveLongTermCandidate(request);

		ArgumentCaptor<AgentMemory> memoryCaptor = ArgumentCaptor.forClass(AgentMemory.class);
		verify(memoryMapper).insert(memoryCaptor.capture());
		assertNull(memoryCaptor.getValue().getDigitalEmployeeId());

		PepDecisionContext context = capturedWriteContext();
		assertEquals(AuthorizationOwnerType.DATA_AGENT, context.getOwnerType());
		assertEquals(1L, context.getOwnerId());
		assertEquals(SubjectKind.CALLER, context.getSubjectKind());
		assertEquals(AuthorizationAction.WRITE_MEMORY, context.getAction());
		assertEquals("u1", context.getSubjectId());
	}

	/**
	 * PR-3c 读取接缝保留性 + PR-7 owner 键：WORKSPACE 读取按 DIGITAL_EMPLOYEE 主体键求值，
	 * READ_MEMORY 动作不变。
	 */
	@Test
	void workspaceScopeReadUsesDigitalEmployeeOwnerKey() {
		stubTenantAndRunState();
		when(memoryMapper.findByScope(1L, MemoryScope.WORKSPACE, "55", "1")).thenReturn(List.of());

		service.listMemoriesByScope(1L, MemoryScope.WORKSPACE, "55");

		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(memoryAuthorizationAdvisor).checkMemoryAccess(captor.capture(), any());
		PepDecisionContext context = captor.getValue();
		assertEquals(AuthorizationOwnerType.DIGITAL_EMPLOYEE, context.getOwnerType());
		assertEquals(55L, context.getOwnerId());
		assertEquals(AuthorizationAction.READ_MEMORY, context.getAction());
	}

	@Test
	void exportRejectsOtherUsersMemoriesOnUserOwnedScope() {
		stubTenantAndRunState();

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(1L, MemoryScope.EMPLOYEE_USER, "other-user"));

		assertEquals(MemoryErrorDict.MEMORY_EXPORT_SUBJECT_FORBIDDEN.getLabel(), ex.getMessage());
		verify(memoryMapper, never()).findByScope(anyLong(), any(), anyString(), anyString());
	}

	@Test
	void exportAllowsCurrentUserMemoriesOnUserOwnedScope() {
		stubTenantAndRunState();
		when(memoryMapper.findByScope(1L, MemoryScope.EMPLOYEE_USER, "operator-1", "1")).thenReturn(List.of());

		List<AgentMemoryExportItemResp> exported = service.exportMemoriesBySubject(1L, MemoryScope.EMPLOYEE_USER,
				"operator-1");

		assertEquals(0, exported.size());
		verify(memoryMapper).findByScope(1L, MemoryScope.EMPLOYEE_USER, "operator-1", "1");
	}

	@Test
	void sessionExportRejectsMissingOrForeignSession() {
		stubTenantAndRunState();
		when(authenticationContext.userId()).thenReturn("7");
		when(chatSessionService.findBySessionId(100L)).thenReturn(null);

		CheckedException missing = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(1L, MemoryScope.SESSION, "100"));
		assertEquals(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel(), missing.getMessage());

		DataChatSession other = DataChatSession.builder().id(100L).userId(8L).build();
		when(chatSessionService.findBySessionId(100L)).thenReturn(other);
		CheckedException foreign = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(1L, MemoryScope.SESSION, "100"));
		assertEquals(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel(), foreign.getMessage());

		DataChatSession unowned = DataChatSession.builder().id(100L).userId(null).build();
		when(chatSessionService.findBySessionId(100L)).thenReturn(unowned);
		CheckedException noOwner = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(1L, MemoryScope.SESSION, "100"));
		assertEquals(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel(), noOwner.getMessage());

		CheckedException invalid = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(1L, MemoryScope.SESSION, "not-a-session"));
		assertEquals(MemoryErrorDict.MEMORY_EXPORT_SESSION_FORBIDDEN.getLabel(), invalid.getMessage());
		verify(memoryMapper, never()).findByScope(anyLong(), any(), anyString(), anyString());
	}

	@Test
	void sessionExportAllowsCurrentUserOwnedSession() {
		stubTenantAndRunState();
		when(authenticationContext.userId()).thenReturn("7");
		when(chatSessionService.findBySessionId(100L))
			.thenReturn(DataChatSession.builder().id(100L).userId(7L).build());
		when(memoryMapper.findByScope(1L, MemoryScope.SESSION, "100", "1")).thenReturn(List.of());

		List<AgentMemoryExportItemResp> exported = service.exportMemoriesBySubject(1L, MemoryScope.SESSION, "100");

		assertEquals(0, exported.size());
		verify(memoryMapper).findByScope(1L, MemoryScope.SESSION, "100", "1");
	}

	@Test
	void workspaceExportAcceptsDigitalEmployeeOwnerWhenAgentMissing() {
		stubTenantAndRunState();
		when(agentService.findById(EMPLOYEE_ID)).thenReturn(null);
		when(digitalEmployeeMapper.findByIdAndTenantId(EMPLOYEE_ID, "1"))
			.thenReturn(DigitalEmployee.builder().id(EMPLOYEE_ID).tenantId("1").build());
		when(memoryMapper.findByScope(EMPLOYEE_ID, MemoryScope.WORKSPACE, String.valueOf(EMPLOYEE_ID), "1"))
			.thenReturn(List.of(AgentMemory.builder()
				.id(9L)
				.agentId(EMPLOYEE_ID)
				.digitalEmployeeId(EMPLOYEE_ID)
				.subjectType(MemoryScope.WORKSPACE)
				.subjectId(String.valueOf(EMPLOYEE_ID))
				.summary("员工共享记忆")
				.build()));

		List<AgentMemoryExportItemResp> exported = service.exportMemoriesBySubject(EMPLOYEE_ID, MemoryScope.WORKSPACE,
				String.valueOf(EMPLOYEE_ID));

		assertEquals(1, exported.size());
		assertEquals("员工共享记忆", exported.get(0).summary());
		verify(agentService, never()).requireAgent(anyLong());
		verify(chatSessionService, never()).findBySessionId(any());
	}

	@Test
	void userScopeListAcceptsDigitalEmployeeOwnerWhenAgentMissing() {
		stubTenantAndRunState();
		when(agentService.findById(EMPLOYEE_ID)).thenReturn(null);
		when(digitalEmployeeMapper.findByIdAndTenantId(EMPLOYEE_ID, "1"))
			.thenReturn(DigitalEmployee.builder().id(EMPLOYEE_ID).tenantId("1").build());
		when(memoryMapper.findByAgentIdAndUserId(EMPLOYEE_ID, "operator-1", MemoryScope.EMPLOYEE_USER, null, null, "1"))
			.thenReturn(List.of());

		List<AgentMemoryItemResp> items = service.listMemories(
				new AgentMemoryItemsQueryReq(EMPLOYEE_ID, null, null, MemoryScope.EMPLOYEE_USER, null, null),
				"operator-1");

		assertNotNull(items);
		assertEquals(0, items.size());
		verify(agentService, never()).requireAgent(anyLong());
	}

	@Test
	void memoryQueryRejectsIdThatIsNeitherAgentNorEmployee() {
		stubTenantAndRunState();
		when(agentService.findById(99L)).thenReturn(null);
		when(digitalEmployeeMapper.findByIdAndTenantId(99L, "1")).thenReturn(null);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.exportMemoriesBySubject(99L, MemoryScope.WORKSPACE, "99"));
		assertEquals(MemoryErrorDict.MEMORY_OWNER_NOT_FOUND.getLabel(), ex.getMessage());
		verify(memoryMapper, never()).findByScope(anyLong(), any(), anyString(), anyString());
	}

	@Test
	void saveLongTermCandidateRejectsSessionScope() {
		AgentMemoryCandidateSaveReq request = baseRequest(MemoryScope.SESSION, "100");
		stubTenantAndRunState();

		assertThrows(CheckedException.class, () -> service.saveLongTermCandidate(request));
		verify(memoryMapper, never()).insert(any(AgentMemory.class));
	}

	@Test
	void expiredSourceRunIsRejected() {
		AgentMemoryCandidateSaveReq request = baseRequest(MemoryScope.EMPLOYEE_USER, "u1");
		stubTenantAndRunState();
		when(runtimeRunService.findRunState("1", 100L))
			.thenReturn(com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState.FAILED);

		assertThrows(CheckedException.class, () -> service.saveLongTermCandidate(request));
	}

	private PepDecisionContext capturedWriteContext() {
		ArgumentCaptor<PepDecisionContext> captor = ArgumentCaptor.forClass(PepDecisionContext.class);
		verify(memoryAuthorizationAdvisor).checkMemoryAccess(captor.capture(), any());
		PepDecisionContext context = captor.getValue();
		assertNotNull(context);
		return context;
	}

	private AgentMemoryCandidateSaveReq baseRequest(MemoryScope scope, String subjectId) {
		return AgentMemoryCandidateSaveReq.builder()
			.agentId(1L)
			.scope(scope)
			.subjectId(subjectId)
			.summary("提炼后的结论文本")
			.content("提炼后的结论文本")
			.sourceRunId(100L)
			.rootRunSucceeded(Boolean.TRUE)
			.build();
	}

	private void stubTenantAndRunState() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(authenticationContext.userId()).thenReturn("operator-1");
		when(agentService.findById(1L)).thenReturn(DataAgent.builder().id(1L).build());
		// 迁移期语义：Run 不在权威运行时中（state=null）时按参数校验放行
		when(runtimeRunService.findRunState("1", 100L)).thenReturn(null);
	}

}
