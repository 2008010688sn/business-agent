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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeApprovalState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeApprovalMapper;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService.ApprovalCreateRequest;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批服务测试（PR-1 起 owner 维度）：幂等创建（含并发唯一约束冲突回查）、作用域隔离
 * （跨 owner / 跨运行不得复用批复）、租户失败关闭、审批键作用域编码、状态机流转、懒惰过期与一次性消费。
 *
 * <p>PR-1 拆除 workspace 域：审批人 workspace 成员闸门随成员模型一并移除（平台级审批人校验由
 * 数字员工域 PR-5 重建，过渡期由 Sa-Token 权限码约束入口），agent_runtime_approval.workspace_id
 * 为遗留兼容列恒落 0。</p>
 */
class AgentApprovalServiceImplTest {

	private static final String TENANT = "7";

	private static final String OWNER_TYPE = "DIGITAL_EMPLOYEE";

	private static final long OWNER_ID = 3L;

	private static final long OTHER_OWNER_ID = 4L;

	private static final String APPROVER = "reviewer";

	/** 作用域入键后的审批幂等键：owner + run + 能力 + 参数指纹。 */
	private static final String SCOPED_KEY = "owner:DIGITAL_EMPLOYEE:3|run:none|cap-x:hash1";

	private AgentRuntimeApprovalMapper approvalMapper;

	private RuntimeOutboxService outboxService;

	private RuntimeEventService eventService;

	private AgentApprovalServiceImpl service;

	@BeforeEach
	void setUp() {
		approvalMapper = mock(AgentRuntimeApprovalMapper.class);
		outboxService = mock(RuntimeOutboxService.class);
		eventService = mock(RuntimeEventService.class);
		service = new AgentApprovalServiceImpl(approvalMapper, outboxService, eventService, new ObjectMapper(),
				directTransactionTemplate(), null);
	}

	@Test
	void createPendingReturnsExistingPendingWithoutInsert() {
		AgentRuntimeApproval existing = approval(RuntimeApprovalState.PENDING, Instant.now().plusSeconds(600));
		stubStore(existing);

		AgentRuntimeApproval result = service.createPending(request(TENANT, OWNER_TYPE, OWNER_ID, "cap-x", "hash1"));

		assertSame(existing, result);
		verify(approvalMapper, never()).insert(any(AgentRuntimeApproval.class));
		verify(outboxService, never()).append(any());
	}

	@Test
	void createPendingInsertsScopedKeyWithDefaultTtlAndOutboxEvent() {
		stubEmptyStore();
		stubInsertAssigningId(11L);

		AgentRuntimeApproval created = service.createPending(request(TENANT, OWNER_TYPE, OWNER_ID, "cap-x", "hash1"));

		assertEquals(11L, created.getId());
		assertEquals("PENDING", created.getState());
		assertEquals(SCOPED_KEY, created.getApprovalKey());
		// PR-1 遗留兼容列：workspace 域拆除后恒落 0，作用域由 approvalKey 编码
		assertEquals(0L, created.getWorkspaceId());
		assertEquals("hash1", created.getRequestDigest());
		assertNotNull(created.getExpiresAt());
		ArgumentCaptor<RuntimeOutboxService.OutboxAppend> outbox = ArgumentCaptor
			.forClass(RuntimeOutboxService.OutboxAppend.class);
		verify(outboxService).append(outbox.capture());
		assertEquals("APPROVAL_REQUESTED", outbox.getValue().eventType());
		assertEquals("approval-requested:11", outbox.getValue().eventKey());
	}

	/** 同一 Run 的审批键必须带 runId，不同 Run 即便能力与参数相同也不共用批复。 */
	@Test
	void createPendingKeyCarriesRunIdWhenRunPresent() {
		stubEmptyStore();
		stubInsertAssigningId(12L);

		AgentRuntimeApproval created = service.createPending(new ApprovalCreateRequest(TENANT, OWNER_TYPE, OWNER_ID,
				88L, null, "cap-x", "hash1", null, "user-1", null, "CAPABILITY_GATEWAY", null));

		assertEquals("owner:DIGITAL_EMPLOYEE:3|run:88|cap-x:hash1", created.getApprovalKey());
	}

	/** 无运行主体的管理动作：owner 段兑底 none，租户隔离仍由租户列与查询条件保证。 */
	@Test
	void createPendingKeyFallsBackToOwnerNoneWhenOwnerMissing() {
		stubEmptyStore();
		stubInsertAssigningId(13L);

		AgentRuntimeApproval created = service.createPending(request(TENANT, null, null, "cap-x", "hash1"));

		assertEquals("owner:none|run:none|cap-x:hash1", created.getApprovalKey());
	}

	@Test
	void createPendingRejectsMissingTenantInsteadOfFallingBackToZero() {
		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.createPending(request(null, OWNER_TYPE, OWNER_ID, "cap-x", "hash1")));

		assertTrue(ex.getMessage().contains("租户上下文缺失或非法"));
		verify(approvalMapper, never()).insert(any(AgentRuntimeApproval.class));
	}

	@Test
	void createPendingRejectsTenantZeroPool() {
		assertThrows(CheckedException.class, () -> service.createPending(request("0", OWNER_TYPE, OWNER_ID, "cap-x",
				"hash1")));
		verify(approvalMapper, never()).insert(any(AgentRuntimeApproval.class));
	}

	/** 并发下唯一索引只放行一条 PENDING，冲突方回查复用既有记录而不是报错或重复建。 */
	@Test
	void concurrentCreatePendingKeepsSinglePendingAndReusesWinner() {
		AgentRuntimeApproval winner = approval(RuntimeApprovalState.PENDING, Instant.now().plusSeconds(600));
		winner.setId(21L);
		// 首次查询（预检）未命中，插入时撞唯一索引，回查命中并发赢家
		when(approvalMapper.findLatestByRunAndApprovalKeyAndState(eq(TENANT), eq(0L), eq(SCOPED_KEY), eq("PENDING")))
			.thenReturn(null)
			.thenReturn(winner);
		doAnswer(invocation -> {
			throw new DuplicateKeyException("agent_runtime_approval_uk_tenant_approval_key_pending");
		}).when(approvalMapper).insert(any(AgentRuntimeApproval.class));

		AgentRuntimeApproval result = service.createPending(request(TENANT, OWNER_TYPE, OWNER_ID, "cap-x", "hash1"));

		assertSame(winner, result);
		verify(outboxService, never()).append(any());
	}

	@Test
	void createPendingExpiresStalePendingAndCreatesNewOne() {
		AgentRuntimeApproval stale = approval(RuntimeApprovalState.PENDING, Instant.now().minusSeconds(60));
		stale.setId(5L);
		stubStore(stale);
		stubInsertAssigningId(6L);

		service.createPending(request(TENANT, OWNER_TYPE, OWNER_ID, "cap-x", "hash1"));

		verify(approvalMapper).expire(eq(5L), any(Instant.class));
		verify(approvalMapper).insert(any(AgentRuntimeApproval.class));
	}

	@Test
	void approvePendingDecidesAndAppendsDecisionOutbox() {
		AgentRuntimeApproval pending = approval(RuntimeApprovalState.PENDING, Instant.now().plusSeconds(600));
		pending.setId(11L);
		when(approvalMapper.findByIdAndTenantId(11L, TENANT)).thenReturn(pending);
		when(approvalMapper.decide(eq(11L), eq("APPROVED"), eq(APPROVER), any(Instant.class), eq("ok"))).thenReturn(1);

		service.approve(TENANT, APPROVER, 11L, "ok");

		ArgumentCaptor<RuntimeOutboxService.OutboxAppend> outbox = ArgumentCaptor
			.forClass(RuntimeOutboxService.OutboxAppend.class);
		verify(outboxService).append(outbox.capture());
		assertEquals("APPROVAL_DECIDED", outbox.getValue().eventType());
		assertEquals("APPROVED", outbox.getValue().payload().get("state"));
		// PR-1：outbox 不再携带 workspace 维度
		assertNull(outbox.getValue().workspaceId());
	}

	/**
	 * PR-1 拆除 workspace 成员模型：审批决定不再依赖任何成员身份闸门（平台级审批人校验由
	 * 数字员工域 PR-5 重建，过渡期由 Sa-Token 权限码约束 Web/IM 入口）。
	 */
	@Test
	void approveWithoutWorkspaceMembershipStillDecides() {
		AgentRuntimeApproval pending = approval(RuntimeApprovalState.PENDING, Instant.now().plusSeconds(600));
		pending.setId(11L);
		when(approvalMapper.findByIdAndTenantId(11L, TENANT)).thenReturn(pending);
		when(approvalMapper.decide(eq(11L), eq("APPROVED"), eq(APPROVER), any(Instant.class), any())).thenReturn(1);

		service.approve(TENANT, APPROVER, 11L, "ok");

		verify(approvalMapper).decide(eq(11L), eq("APPROVED"), eq(APPROVER), any(Instant.class), any());
	}

	@Test
	void approveRejectedApprovalIsIllegalTransition() {
		AgentRuntimeApproval rejected = approval(RuntimeApprovalState.REJECTED, Instant.now().plusSeconds(600));
		rejected.setId(11L);
		when(approvalMapper.findByIdAndTenantId(11L, TENANT)).thenReturn(rejected);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.approve(TENANT, APPROVER, 11L, null));

		assertTrue(ex.getMessage().contains("非法审批状态流转"));
		verify(approvalMapper, never()).decide(anyLong(), anyString(), anyString(), any(), any());
	}

	@Test
	void approveExpiredPendingLazilyExpiresAndRejects() {
		AgentRuntimeApproval expired = approval(RuntimeApprovalState.PENDING, Instant.now().minusSeconds(60));
		expired.setId(11L);
		when(approvalMapper.findByIdAndTenantId(11L, TENANT)).thenReturn(expired);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.approve(TENANT, APPROVER, 11L, null));

		assertTrue(ex.getMessage().contains("审批已过期"));
		verify(approvalMapper).expire(eq(11L), any(Instant.class));
		verify(approvalMapper, never()).decide(anyLong(), anyString(), anyString(), any(), any());
	}

	@Test
	void rejectRequiresComment() {
		assertThrows(CheckedException.class, () -> service.reject(TENANT, APPROVER, 11L, " "));
		verify(approvalMapper, never()).decide(anyLong(), anyString(), anyString(), any(), any());
	}

	@Test
	void concurrentDecisionIsRejectedWhenCasMisses() {
		AgentRuntimeApproval pending = approval(RuntimeApprovalState.PENDING, Instant.now().plusSeconds(600));
		pending.setId(11L);
		when(approvalMapper.findByIdAndTenantId(11L, TENANT)).thenReturn(pending);
		when(approvalMapper.decide(eq(11L), anyString(), anyString(), any(Instant.class), any())).thenReturn(0);

		CheckedException ex = assertThrows(CheckedException.class,
				() -> service.approve(TENANT, APPROVER, 11L, null));

		assertTrue(ex.getMessage().contains("并发"));
		verify(outboxService, never()).append(any());
	}

	@Test
	void findConsumableApprovedExpiresStaleApprovalAndReturnsNull() {
		AgentRuntimeApproval staleApproved = approval(RuntimeApprovalState.APPROVED, Instant.now().minusSeconds(60));
		staleApproved.setId(11L);
		stubStore(staleApproved);

		assertNull(service.findConsumableApproved(TENANT, OWNER_TYPE, OWNER_ID, null, "cap-x", "hash1"));
		verify(approvalMapper).expire(eq(11L), any(Instant.class));
	}

	@Test
	void findConsumableApprovedReturnsValidApproval() {
		AgentRuntimeApproval approved = approval(RuntimeApprovalState.APPROVED, Instant.now().plusSeconds(600));
		stubStore(approved);

		assertSame(approved, service.findConsumableApproved(TENANT, OWNER_TYPE, OWNER_ID, null, "cap-x", "hash1"));
		verify(approvalMapper, never()).expire(anyLong(), any(Instant.class));
	}

	/** H-2 核心回归：owner A 的批复不得被 owner B 的调用匹配到并消费。 */
	@Test
	void approvalOfOneOwnerIsNotConsumableFromAnotherOwner() {
		AgentRuntimeApproval approved = approval(RuntimeApprovalState.APPROVED, Instant.now().plusSeconds(600));
		stubStore(approved);

		assertNotNull(service.findConsumableApproved(TENANT, OWNER_TYPE, OWNER_ID, null, "cap-x", "hash1"));
		assertNull(service.findConsumableApproved(TENANT, OWNER_TYPE, OTHER_OWNER_ID, null, "cap-x", "hash1"));
	}

	/** H-2 核心回归：同 owner 但不同 Run 的批复也不得互相复用。 */
	@Test
	void approvalOfOneRunIsNotConsumableFromAnotherRun() {
		AgentRuntimeApproval approved = approval(RuntimeApprovalState.APPROVED, Instant.now().plusSeconds(600));
		approved.setRunId(88L);
		approved.setApprovalKey("owner:DIGITAL_EMPLOYEE:3|run:88|cap-x:hash1");
		stubStore(approved);

		assertNotNull(service.findConsumableApproved(TENANT, OWNER_TYPE, OWNER_ID, 88L, "cap-x", "hash1"));
		assertNull(service.findConsumableApproved(TENANT, OWNER_TYPE, OWNER_ID, 99L, "cap-x", "hash1"));
	}

	@Test
	void findConsumableApprovedRejectsMissingTenant() {
		assertThrows(CheckedException.class,
				() -> service.findConsumableApproved(null, OWNER_TYPE, OWNER_ID, null, "cap-x", "hash1"));
	}

	@Test
	void consumeIsOneShotCas() {
		when(approvalMapper.consume(eq(11L), any(Instant.class))).thenReturn(1).thenReturn(0);

		assertTrue(service.consume(11L));
		assertFalse(service.consume(11L));
	}

	@Test
	void decideToolConfirmCreatesPendingThenApprovesBoundToToolCallAndFingerprint() {
		when(approvalMapper.findLatestByApprovalKey(eq(TENANT), anyString())).thenReturn(null);
		stubEmptyStore();
		stubInsertAssigningId(31L);
		AgentRuntimeApproval pending = AgentRuntimeApproval.builder()
			.id(31L)
			.tenantId(TENANT)
			.runId(0L)
			.state(RuntimeApprovalState.PENDING.getValue())
			.expiresAt(Instant.now().plusSeconds(600))
			.build();
		when(approvalMapper.findByIdAndTenantId(31L, TENANT)).thenReturn(pending);
		when(approvalMapper.decide(eq(31L), eq("APPROVED"), eq(APPROVER), any(Instant.class), any())).thenReturn(1);

		service.decideToolConfirm(TENANT, APPROVER, "call-9", "demand_create_execute", "fp-1", true, null);

		ArgumentCaptor<AgentRuntimeApproval> inserted = ArgumentCaptor.forClass(AgentRuntimeApproval.class);
		verify(approvalMapper).insert(inserted.capture());
		assertEquals("owner:none|run:ref:call-9|demand_create_execute:fp-1", inserted.getValue().getApprovalKey());
		assertEquals("fp-1", inserted.getValue().getRequestDigest());
		verify(approvalMapper).decide(eq(31L), eq("APPROVED"), eq(APPROVER), any(Instant.class), any());
	}

	@Test
	void decideToolConfirmIsIdempotentWhenAlreadyApproved() {
		AgentRuntimeApproval approved = AgentRuntimeApproval.builder()
			.id(32L)
			.tenantId(TENANT)
			.runId(0L)
			.approvalKey("owner:none|run:ref:call-9|demand_create_execute:fp-1")
			.state(RuntimeApprovalState.APPROVED.getValue())
			.expiresAt(Instant.now().plusSeconds(600))
			.build();
		when(approvalMapper.findLatestByApprovalKey(TENANT, approved.getApprovalKey())).thenReturn(approved);

		service.decideToolConfirm(TENANT, APPROVER, "call-9", "demand_create_execute", "fp-1", true, null);

		verify(approvalMapper, never()).insert(any(AgentRuntimeApproval.class));
		verify(approvalMapper, never()).decide(anyLong(), anyString(), anyString(), any(), any());
	}

	/**
	 * 按 Mapper 的真实谓词（租户 + 运行 + 审批键 + 状态）匹配，
	 * 让作用域隔离在测试里按生产语义生效，而不是靠固定桩返回。
	 */
	private void stubStore(AgentRuntimeApproval stored) {
		when(approvalMapper.findLatestByRunAndApprovalKeyAndState(anyString(), anyLong(), anyString(), anyString()))
			.thenAnswer(invocation -> matches(stored, invocation.getArgument(0), invocation.getArgument(1),
					invocation.getArgument(2), invocation.getArgument(3)) ? stored : null);
	}

	private void stubEmptyStore() {
		when(approvalMapper.findLatestByRunAndApprovalKeyAndState(anyString(), anyLong(), anyString(), anyString()))
			.thenReturn(null);
	}

	private void stubInsertAssigningId(long id) {
		doAnswer(invocation -> {
			invocation.getArgument(0, AgentRuntimeApproval.class).setId(id);
			return 1;
		}).when(approvalMapper).insert(any(AgentRuntimeApproval.class));
	}

	private boolean matches(AgentRuntimeApproval stored, String tenantId, Long runId, String approvalKey, String state) {
		return Objects.equals(stored.getTenantId(), tenantId) && Objects.equals(stored.getRunId(), runId)
				&& Objects.equals(stored.getApprovalKey(), approvalKey) && Objects.equals(stored.getState(), state);
	}

	/** 直连事务模板：回调直接执行，异常原样抛出，等价于「事务回滚后由外层回查」。 */
	private TransactionTemplate directTransactionTemplate() {
		TransactionTemplate template = mock(TransactionTemplate.class);
		when(template.execute(any())).thenAnswer(invocation -> invocation
			.getArgument(0, TransactionCallback.class)
			.doInTransaction(mock(TransactionStatus.class)));
		return template;
	}

	private ApprovalCreateRequest request(String tenantId, String ownerType, Long ownerId, String capabilityCode,
			String paramsHash) {
		return new ApprovalCreateRequest(tenantId, ownerType, ownerId, null, null, capabilityCode, paramsHash, null,
				"user-1", null, "CAPABILITY_GATEWAY", null);
	}

	private AgentRuntimeApproval approval(RuntimeApprovalState state, Instant expiresAt) {
		return AgentRuntimeApproval.builder()
			.tenantId(TENANT)
			.runId(0L)
			.approvalKey(SCOPED_KEY)
			.state(state.getValue())
			.requestDigest("hash1")
			.payload("{\"capabilityCode\":\"cap-x\"}")
			.expiresAt(expiresAt)
			.build();
	}

}
