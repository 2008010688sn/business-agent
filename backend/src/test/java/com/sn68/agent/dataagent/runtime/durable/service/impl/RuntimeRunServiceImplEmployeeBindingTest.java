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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunCreateReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimePlanMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeCancellationService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.framework.db.mybatisplus.wrap.query.LbqWrapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 运行记录的数字员工归属维度测试（PR-1：owner 维度 + digitalEmployeeId 归集）。
 *
 * <p>任务的精确归集不再依赖 workspace 命名空间：(ownerType + ownerId) 构成幂等与审批的作用域，
 * digitalEmployeeId 用于运行台账按数字员工精确过滤。查询侧要在传入时生效、在缺省时完全不出现该谓词
 * （退化成 {@code digital_employee_id IS NULL} 会把全部无归属运行之外的行滤光）。</p>
 */
class RuntimeRunServiceImplEmployeeBindingTest {

	private static final String TENANT_ID = "7";

	private static final Long DIGITAL_EMPLOYEE_ID = 9L;

	private static final Long EMPLOYEE_RELEASE_ID = 1L;

	@BeforeAll
	static void initTableInfo() {
		// page 走 Wraps.<AgentRuntimeRun>lbQ()，lambda 列名解析依赖 MP 的 TableInfo 缓存；
		// 纯单测没有 Spring 做 mapper 扫描，按 CompiledPlanPersistenceServiceImplTest 同款写法手动注册。
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				AgentRuntimeRun.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				com.sn68.agent.dataagent.employee.entity.DigitalEmployee.class);
		TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
				com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease.class);
	}

	private AgentRuntimeRunMapper runMapper;

	private RuntimeStateService runtimeStateService;

	private RuntimeRunServiceImpl service;

	@BeforeEach
	void setUp() {
		runMapper = mock(AgentRuntimeRunMapper.class);
		runtimeStateService = mock(RuntimeStateService.class);
		// 分页插件不参与单测，原样回传 buildPage() 的空页即可让 convert 走通
		when(runMapper.selectPage(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
		service = new RuntimeRunServiceImpl(runMapper, mock(AgentRuntimeStepMapper.class),
				mock(AgentRuntimePlanMapper.class), runtimeStateService, mock(RuntimeEventService.class),
				mock(RuntimeCancellationService.class), mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper.class),
				mock(com.sn68.agent.dataagent.employee.repository.DigitalEmployeeReleaseMapper.class),
				new DataAgentProperties());
	}

	@Test
	void pageFiltersByDigitalEmployeeId() {
		RuntimeRunPageQueryReq query = new RuntimeRunPageQueryReq();
		query.setOwnerType("DIGITAL_EMPLOYEE");
		query.setDigitalEmployeeId(DIGITAL_EMPLOYEE_ID);

		service.page(TENANT_ID, query);

		LbqWrapper<AgentRuntimeRun> wrapper = capturedPageWrapper();
		String sqlSegment = wrapper.getSqlSegment();
		assertTrue(sqlSegment.contains("digital_employee_id"), sqlSegment);
		assertTrue(sqlSegment.contains("owner_type"), sqlSegment);
		assertTrue(wrapper.getParamNameValuePairs().containsValue(DIGITAL_EMPLOYEE_ID), sqlSegment);
	}

	/** 不按数字员工筛选时该谓词必须整体消失，否则租户运行列表只剩无归属的运行。 */
	@Test
	void pageWithoutDigitalEmployeeIdOmitsPredicate() {
		RuntimeRunPageQueryReq query = new RuntimeRunPageQueryReq();
		query.setOwnerType("DIGITAL_EMPLOYEE");

		service.page(TENANT_ID, query);

		String sqlSegment = capturedPageWrapper().getSqlSegment();
		assertFalse(sqlSegment.contains("digital_employee_id"), sqlSegment);
		assertTrue(sqlSegment.contains("owner_type"), sqlSegment);
	}

	@Test
	void pageWithoutRequestBodyOmitsPredicate() {
		service.page(TENANT_ID, null);

		String sqlSegment = capturedPageWrapper().getSqlSegment();
		assertFalse(sqlSegment.contains("digital_employee_id"), sqlSegment);
		assertTrue(sqlSegment.contains("tenant_id"), sqlSegment);
	}

	/** PR-1：任务链路建 Run 必须落 owner 三元组与 tenant_id 双写列，台账归集不靠 agentId 近似。 */
	@Test
	void createPersistsOwnerAndDigitalEmployeeWithTenantIdStr() {
		service.create(TENANT_ID, "sp-1", createReq("DIGITAL_EMPLOYEE", DIGITAL_EMPLOYEE_ID));

		AgentRuntimeRun inserted = capturedInsertedRun();
		assertEquals("DIGITAL_EMPLOYEE", inserted.getOwnerType());
		assertEquals(DIGITAL_EMPLOYEE_ID, inserted.getOwnerId());
		assertEquals(DIGITAL_EMPLOYEE_ID, inserted.getDigitalEmployeeId());
		assertEquals(EMPLOYEE_RELEASE_ID, inserted.getReleaseId());
		// tenant_id 双写（PR-1 过渡期查询载体）
		assertEquals(String.valueOf(TENANT_ID), inserted.getTenantIdStr());
		assertEquals("API", inserted.getTriggerSource());
	}

	@Test
	void createPersistsExplicitTriggerSource() {
		service.create(TENANT_ID, "user-a", new RuntimeRunCreateReq("CHAT:88:req-1", "DIGITAL_EMPLOYEE",
				DIGITAL_EMPLOYEE_ID, DIGITAL_EMPLOYEE_ID, EMPLOYEE_RELEASE_ID, DIGITAL_EMPLOYEE_ID, "88", "今天有什么安排",
				"CHAT", null, "CHAT"));

		AgentRuntimeRun inserted = capturedInsertedRun();
		assertEquals("CHAT", inserted.getTriggerSource());
		assertEquals("CHAT", inserted.getRunMode());
		assertEquals(DIGITAL_EMPLOYEE_ID, inserted.getDigitalEmployeeId());
	}

	@Test
	void startInteractiveRunMovesPendingToRunning() {
		when(runtimeStateService.transitionRunWithRetry(eq(55L), eq(RuntimeRunState.RUNNING), any(), anyInt()))
			.thenReturn(true);
		when(runMapper.insert(any(AgentRuntimeRun.class))).thenAnswer(invocation -> {
			AgentRuntimeRun run = invocation.getArgument(0);
			run.setId(55L);
			return 1;
		});
		when(runMapper.selectById(55L)).thenReturn(run(55L, "PENDING"));
		when(runMapper.findByIdAndTenantId(55L, TENANT_ID)).thenReturn(run(55L, "RUNNING"));

		RuntimeRunResp resp = service.startInteractiveRun(TENANT_ID, "3", new RuntimeRunCreateReq("CHAT:88:req-1",
				"DIGITAL_EMPLOYEE", DIGITAL_EMPLOYEE_ID, DIGITAL_EMPLOYEE_ID, null, DIGITAL_EMPLOYEE_ID, "88",
				"今天有什么安排", "CHAT", null, "CHAT"));

		assertEquals(55L, resp.id());
		assertEquals("RUNNING", resp.state());
		verify(runtimeStateService).transitionRunWithRetry(eq(55L), eq(RuntimeRunState.RUNNING), any(), anyInt());
	}

	/** CHAT / 直接 API 等触发没有显式主体：ownerType 缺省 CALLER、ownerId 落 null 而不是编造归属。 */
	@Test
	void createWithoutOwnerFallsBackToCallerAndKeepsNullOwnerId() {
		service.create(TENANT_ID, "user-a", createReq(null, null));

		AgentRuntimeRun inserted = capturedInsertedRun();
		assertEquals("CALLER", inserted.getOwnerType());
		assertNull(inserted.getOwnerId());
		assertNull(inserted.getDigitalEmployeeId());
	}

	private RuntimeRunCreateReq createReq(String ownerType, Long ownerId) {
		return new RuntimeRunCreateReq("SCHEDULE:1001:2026-08-13T00:00:00Z", ownerType, ownerId, ownerId,
				EMPLOYEE_RELEASE_ID, null, null, "查询昨日发运量", "AGENT_LOOP", null, null);
	}

	@SuppressWarnings("unchecked")
	private LbqWrapper<AgentRuntimeRun> capturedPageWrapper() {
		ArgumentCaptor<Wrapper<AgentRuntimeRun>> captor = ArgumentCaptor.forClass(Wrapper.class);
		verify(runMapper).selectPage(any(), captor.capture());
		return (LbqWrapper<AgentRuntimeRun>) captor.getValue();
	}

	private AgentRuntimeRun capturedInsertedRun() {
		ArgumentCaptor<AgentRuntimeRun> captor = ArgumentCaptor.forClass(AgentRuntimeRun.class);
		verify(runMapper).insert(captor.capture());
		return captor.getValue();
	}

	private AgentRuntimeRun run(Long id, String state) {
		return AgentRuntimeRun.builder()
			.id(id)
			.tenantId(TENANT_ID)
			.ownerType("DIGITAL_EMPLOYEE")
			.ownerId(DIGITAL_EMPLOYEE_ID)
			.digitalEmployeeId(DIGITAL_EMPLOYEE_ID)
			.triggerSource("CHAT")
			.runMode("CHAT")
			.state(state)
			.stateVersion(0L)
			.build();
	}

}
