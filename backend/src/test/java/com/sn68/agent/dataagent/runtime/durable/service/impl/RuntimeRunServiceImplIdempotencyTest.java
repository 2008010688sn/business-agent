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
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Run 幂等创建的租户隔离测试（H-4 回归，PR-1 起 owner 维度）。
 *
 * <p>以内存表复刻唯一索引 {@code agent_runtime_run_uk_tenant_owner_client_request} 的语义：
 * clientRequestId 由调用方给定，任务链路取值为 {@code SCHEDULE:{triggerId}:{计划时刻}} 这类可枚举值，
 * 唯一键不带租户时会被任意租户抢占，把幂等降级成对受害租户的永久拒绝服务。</p>
 */
class RuntimeRunServiceImplIdempotencyTest {

	/** 与任务调度链路同构的可枚举幂等键。 */
	private static final String ENUMERABLE_KEY = "SCHEDULE:1001:2026-08-13T00:00:00Z";

	/** create 对空 ownerType 的默认口径。 */
	private static final String DEFAULT_OWNER_TYPE = "CALLER";

	private final AtomicLong idSequence = new AtomicLong(100L);

	private final List<AgentRuntimeRun> table = new ArrayList<>();

	private AgentRuntimeRunMapper runMapper;

	private RuntimeRunServiceImpl service;

	@BeforeAll
	static void initTableInfo() {
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentRuntimeRun.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DigitalEmployee.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				DigitalEmployeeRelease.class);
	}

	@BeforeEach
	void setUp() {
		runMapper = mock(AgentRuntimeRunMapper.class);
		stubUniqueIndexBackedTable();
		service = new RuntimeRunServiceImpl(runMapper, mock(AgentRuntimeStepMapper.class),
				mock(AgentRuntimePlanMapper.class), mock(RuntimeStateService.class), mock(RuntimeEventService.class),
				mock(RuntimeCancellationService.class), mock(DigitalEmployeeMapper.class),
				mock(DigitalEmployeeReleaseMapper.class), new DataAgentProperties());
	}

	/** H-4 核心回归：两个租户用同一个可枚举 clientRequestId 各自建 Run，互不影响。 */
	@Test
	void differentTenantsCanUseSameClientRequestId() {
		RuntimeRunResp first = service.create("7", "user-a", request(ENUMERABLE_KEY, null, null));
		RuntimeRunResp second = service.create("9", "user-b", request(ENUMERABLE_KEY, null, null));

		assertNotEquals(first.id(), second.id());
		assertEquals(2, table.size());
		assertEquals("7", table.get(0).getTenantId());
		assertEquals("9", table.get(1).getTenantId());
	}

	/** 无显式 owner（ownerType 缺省 CALLER、ownerId 空）时同样按租户隔离幂等，不再是全局共享命名空间。 */
	@Test
	void sameTenantSameOwnerIsIdempotent() {
		RuntimeRunResp first = service.create("7", "user-a", request(ENUMERABLE_KEY, null, null));
		RuntimeRunResp second = service.create("7", "user-a", request(ENUMERABLE_KEY, null, null));

		assertEquals(first.id(), second.id());
		assertEquals(1, table.size());
	}

	/** PR-1：同租户不同运行主体（数字员工）各成幂等键，同一可枚举键互不抢占。 */
	@Test
	void sameTenantDifferentOwnerCreatesSeparateRuns() {
		service.create("7", "user-a", request(ENUMERABLE_KEY, "DIGITAL_EMPLOYEE", 3L));
		service.create("7", "user-a", request(ENUMERABLE_KEY, "DIGITAL_EMPLOYEE", 4L));

		assertEquals(2, table.size());
	}

	/** 并发冲突：预检未命中但插入撞唯一键时回查本租户 winner 返回，不再抛「已被其他租户占用」。 */
	@Test
	void concurrentConflictReturnsOwnTenantWinner() {
		AgentRuntimeRun winner = AgentRuntimeRun.builder()
			.tenantId("7")
			.ownerType(DEFAULT_OWNER_TYPE)
			.ownerId(null)
			.clientRequestId(ENUMERABLE_KEY)
			.state("PENDING")
			.build();
		winner.setId(999L);
		AgentRuntimeRunMapper conflictMapper = mock(AgentRuntimeRunMapper.class);
		when(conflictMapper.findByOwnerAndClientRequest(eq("7"), eq(DEFAULT_OWNER_TYPE), isNull(),
				eq(ENUMERABLE_KEY))).thenReturn(null)
			.thenReturn(winner);
		doAnswer(invocation -> {
			throw new DuplicateKeyException("agent_runtime_run_uk_tenant_owner_client_request");
		}).when(conflictMapper).insert(any(AgentRuntimeRun.class));
		RuntimeRunServiceImpl conflictService = new RuntimeRunServiceImpl(conflictMapper,
				mock(AgentRuntimeStepMapper.class), mock(AgentRuntimePlanMapper.class), mock(RuntimeStateService.class),
				mock(RuntimeEventService.class), mock(RuntimeCancellationService.class), mock(DigitalEmployeeMapper.class),
				mock(DigitalEmployeeReleaseMapper.class), new DataAgentProperties());

		RuntimeRunResp resp = conflictService.create("7", "user-a", request(ENUMERABLE_KEY, null, null));

		assertEquals(999L, resp.id());
	}

	/** 冲突后回查不到属约束与查询口径不一致，必须失败关闭而不是静默复用他人记录。 */
	@Test
	void conflictWithoutWinnerFailsClosed() {
		AgentRuntimeRunMapper conflictMapper = mock(AgentRuntimeRunMapper.class);
		when(conflictMapper.findByOwnerAndClientRequest(anyString(), anyString(), isNull(), anyString()))
			.thenReturn(null);
		doAnswer(invocation -> {
			throw new DuplicateKeyException("unexpected");
		}).when(conflictMapper).insert(any(AgentRuntimeRun.class));
		RuntimeRunServiceImpl conflictService = new RuntimeRunServiceImpl(conflictMapper,
				mock(AgentRuntimeStepMapper.class), mock(AgentRuntimePlanMapper.class), mock(RuntimeStateService.class),
				mock(RuntimeEventService.class), mock(RuntimeCancellationService.class), mock(DigitalEmployeeMapper.class),
				mock(DigitalEmployeeReleaseMapper.class), new DataAgentProperties());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> conflictService.create("7", "user-a", request(ENUMERABLE_KEY, null, null)));

		assertTrue(ex.getMessage().contains("回查失败"));
	}

	@Test
	void chatThreadActiveConflictReturnsSessionBusy() {
		AgentRuntimeRun active = AgentRuntimeRun.builder()
			.tenantId("7")
			.threadId("88")
			.runMode("CHAT")
			.state("RUNNING")
			.clientRequestId("CHAT:88:other")
			.build();
		active.setId(501L);
		AgentRuntimeRunMapper conflictMapper = mock(AgentRuntimeRunMapper.class);
		when(conflictMapper.findByOwnerAndClientRequest(eq("7"), eq(DEFAULT_OWNER_TYPE), isNull(), anyString()))
			.thenReturn(null);
		when(conflictMapper.findActiveChatByTenantAndThread(eq("7"), eq("88"))).thenReturn(active);
		doAnswer(invocation -> {
			throw new DuplicateKeyException("agent_runtime_run_uk_chat_thread_active");
		}).when(conflictMapper).insert(any(AgentRuntimeRun.class));
		RuntimeRunServiceImpl conflictService = new RuntimeRunServiceImpl(conflictMapper,
				mock(AgentRuntimeStepMapper.class), mock(AgentRuntimePlanMapper.class), mock(RuntimeStateService.class),
				mock(RuntimeEventService.class), mock(RuntimeCancellationService.class), mock(DigitalEmployeeMapper.class),
				mock(DigitalEmployeeReleaseMapper.class), new DataAgentProperties());

		CheckedException ex = assertThrows(CheckedException.class,
				() -> conflictService.create("7", "user-a",
						new RuntimeRunCreateReq("CHAT:88:new", null, null, null, null, null, "88", "问数", "CHAT",
								null, "CHAT")));
		String message = String.valueOf(ex.getMessage());
		assertTrue(message.contains("会话") || message.contains("session_busy")
				|| message.contains("agent_runtime_session_busy"), message);
	}

	@Test
	void chatCreateReusesRuntimeRequestIdFromClientRequestId() {
		String uuid = "52c1e037-2d80-442a-9c77-37e387da23a0";
		RuntimeRunResp created = service.create("7", "user-a",
				new RuntimeRunCreateReq("CHAT:88:" + uuid, null, null, null, null, null, "88", "问数", "CHAT", null,
						"CHAT"));
		assertEquals(uuid, created.runtimeRequestId());
	}

	@Test
	void nonChatCreateStillMintsRuntimeRequestId() {
		RuntimeRunResp created = service.create("7", "user-a", request(ENUMERABLE_KEY, null, null));
		assertTrue(created.runtimeRequestId() != null && created.runtimeRequestId().length() == 36);
		assertNotEquals(ENUMERABLE_KEY, created.runtimeRequestId());
	}

	@Test
	void missingTenantIsRejected() {
		assertThrows(CheckedException.class, () -> service.create(null, "user-a", request(ENUMERABLE_KEY, null, null)));
	}

	@Test
	void blankClientRequestIdIsRejected() {
		assertThrows(CheckedException.class, () -> service.create("7", "user-a", request(" ", null, null)));
	}

	/** 以 (tenant_id, owner_type, owner_id, client_request_id) 为唯一键的内存表，复刻部分唯一索引行为。 */
	private void stubUniqueIndexBackedTable() {
		doAnswer(invocation -> {
			AgentRuntimeRun run = invocation.getArgument(0);
			boolean duplicated = table.stream().anyMatch(existing -> sameKey(existing, run));
			if (duplicated) {
				throw new DuplicateKeyException("agent_runtime_run_uk_tenant_owner_client_request");
			}
			run.setId(idSequence.incrementAndGet());
			table.add(run);
			return 1;
		}).when(runMapper).insert(any(AgentRuntimeRun.class));
		doAnswer(invocation -> {
			String tenantId = invocation.getArgument(0);
			String ownerType = invocation.getArgument(1);
			Long ownerId = invocation.getArgument(2);
			String clientRequestId = invocation.getArgument(3);
			return table.stream()
				.filter(run -> Objects.equals(run.getTenantId(), tenantId)
						&& Objects.equals(run.getOwnerType(), ownerType)
						&& Objects.equals(run.getOwnerId(), ownerId)
						&& Objects.equals(run.getClientRequestId(), clientRequestId))
				.findFirst()
				.orElse(null);
		}).when(runMapper).findByOwnerAndClientRequest(anyString(), anyString(), isNull(), anyString());
		doAnswer(invocation -> {
			Long id = invocation.getArgument(0);
			return table.stream().filter(run -> Objects.equals(run.getId(), id)).findFirst().orElse(null);
		}).when(runMapper).selectById(anyLong());
		when(runMapper.findActiveChatByTenantAndThread(anyString(), anyString())).thenReturn(null);
	}

	private boolean sameKey(AgentRuntimeRun left, AgentRuntimeRun right) {
		return Objects.equals(left.getTenantId(), right.getTenantId())
				&& Objects.equals(left.getOwnerType(), right.getOwnerType())
				&& Objects.equals(left.getOwnerId(), right.getOwnerId())
				&& Objects.equals(left.getClientRequestId(), right.getClientRequestId());
	}

	private RuntimeRunCreateReq request(String clientRequestId, String ownerType, Long ownerId) {
		return new RuntimeRunCreateReq(clientRequestId, ownerType, ownerId, null, null, null, null, "查询昨日发运量",
				"AGENT_LOOP", null, null);
	}

}
