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

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import com.sn68.agent.dataagent.task.dto.AgentTaskRunPageQueryReq;
import com.sn68.agent.dataagent.task.entity.AgentTaskRun;
import com.sn68.agent.dataagent.task.enums.TaskConstants;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.repository.AgentTaskDefinitionMapper;
import com.sn68.agent.dataagent.task.repository.AgentTaskRunMapper;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Agent任务运行服务。幂等落库依赖 agent_task_run.idempotency_key 唯一索引，
 * 与 IM 消息幂等（ImCallbackService.claimInbound）同一套「插入抢占 + 冲突取回」模式。
 *
 * <p>PR-6 起 FORBID 并发策略改走 {@link #claimSlot}：方案A 下 idx_forbid_slot
 * 部分唯一索引是并发仲裁的唯一正确性来源，{@code FOR UPDATE SKIP LOCKED} 预检与
 * {@code agent_task_definition.active_run_id} 镜像只负责减少无效插入与可观测性，
 * 镜像不一致时一律以 run 表唯一索引为准。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskRunServiceImpl extends SuperServiceImpl<AgentTaskRunMapper, AgentTaskRun>
		implements AgentTaskRunService {

	private final AuthenticationContext authenticationContext;

	private final AgentTaskDefinitionMapper definitionMapper;

	private final TransactionTemplate transactionTemplate;

	private final RuntimeRunService runtimeRunService;

	@Override
	public ClaimResult claimRun(AgentTaskRun run) {
		if (run == null || !StringUtils.hasText(run.getIdempotencyKey())) {
			throw CheckedException.badRequest("任务运行幂等键不能为空");
		}
		try {
			baseMapper.insert(run);
			return new ClaimResult(run, true);
		}
		catch (DuplicateKeyException ex) {
			AgentTaskRun existing = baseMapper.findByIdempotencyKey(run.getIdempotencyKey());
			if (existing != null) {
				log.info("任务运行触发命中幂等键, 返回已有运行。idempotencyKey={}, existingRunId={}",
						run.getIdempotencyKey(), existing.getId());
				return new ClaimResult(existing, false);
			}
			// 冲突却查不到已存在记录（如对方事务未提交），如实抛出让触发方重试，不能伪装成功。
			throw ex;
		}
	}

	@Override
	public SlotClaim claimSlot(AgentTaskRun run) {
		if (run == null || !StringUtils.hasText(run.getIdempotencyKey())) {
			throw CheckedException.badRequest("任务运行幂等键不能为空");
		}
		if (!TaskConstants.CONCURRENCY_FORBID.equals(run.getConcurrencyPolicy())) {
			// ALLOW（或未落策略快照的存量链路）不参与槽竞争，退化为幂等落库
			ClaimResult result = claimRun(run);
			return new SlotClaim(result.run(), result.claimed() ? SlotOutcome.CLAIMED : SlotOutcome.IDEMPOTENT_HIT);
		}
		return claimForbidSlot(run);
	}

	/**
	 * FORBID 槽竞争：编程式事务内完成 SKIP LOCKED 预检 + PENDING 行插入 + 镜像 CAS 占用。
	 *
	 * <p>不用 {@code @Transactional} 声明式事务：DuplicateKeyException 后 PG 事务已 abort，
	 * 冲突源区分必须回到事务外的新语句执行（与 RuntimeRunServiceImpl.create 同一哲学）。</p>
	 */
	private SlotClaim claimForbidSlot(AgentTaskRun run) {
		try {
			transactionTemplate.executeWithoutResult(status -> {
				List<Long> activeIds = baseMapper.lockActiveRunIdsByDefinition(run.getTenantId(),
						run.getDefinitionId());
				if (!activeIds.isEmpty()) {
					throw new SlotOccupiedException(activeIds.get(0));
				}
				baseMapper.insert(run);
				occupyMirrorQuietly(run);
			});
			log.info("FORBID 并发槽占用成功。tenantId={}, definitionId={}, runId={}", run.getTenantId(),
					run.getDefinitionId(), run.getId());
			return new SlotClaim(run, SlotOutcome.CLAIMED);
		}
		catch (SlotOccupiedException ex) {
			return resolveSlotConflict(run, ex.activeRunId, null);
		}
		catch (DuplicateKeyException ex) {
			return resolveSlotConflict(run, null, ex);
		}
	}

	/** 镜像 CAS 占用：镜像不承担正确性，竞态未命中或异常仅告警（正确性由 run 表唯一索引保证）。 */
	private void occupyMirrorQuietly(AgentTaskRun run) {
		try {
			if (definitionMapper.occupySlot(run.getDefinitionId(), run.getId()) == 0) {
				log.warn("并发槽镜像占用未命中(已被占用或已回收), 以 run 表唯一索引为准。definitionId={}, runId={}",
						run.getDefinitionId(), run.getId());
			}
		}
		catch (Exception ex) {
			log.warn("并发槽镜像占用异常, 不影响本次槽位判定。definitionId={}", run.getDefinitionId(), ex);
		}
	}

	/**
	 * 冲突源区分（必须在新语句中执行）：幂等命中优先——同键的 SKIPPED/终态行直接复用，
	 * 避免误判槽冲突后重复落 SKIPPED；其次按活跃行判定槽冲突；两者皆无则如实上抛触发方重试。
	 */
	private SlotClaim resolveSlotConflict(AgentTaskRun run, Long activeRunId, DuplicateKeyException cause) {
		AgentTaskRun existing = baseMapper.findByIdempotencyKey(run.getIdempotencyKey());
		if (existing != null) {
			log.info("任务运行触发命中幂等键, 返回已有运行。idempotencyKey={}, existingRunId={}",
					run.getIdempotencyKey(), existing.getId());
			return new SlotClaim(existing, SlotOutcome.IDEMPOTENT_HIT);
		}
		if (activeRunId == null) {
			List<AgentTaskRun> active = baseMapper.findActiveByDefinition(run.getTenantId(), run.getDefinitionId());
			activeRunId = active.isEmpty() ? null : active.get(0).getId();
		}
		if (activeRunId == null) {
			// 冲突却既无幂等行也无活跃行（对方事务未提交等），如实抛出让触发方重试，不能伪装成功
			throw cause != null ? cause : CheckedException.badRequest("任务运行并发槽判定异常, 请重试");
		}
		return persistSkippedRun(run, activeRunId);
	}

	/** 槽位被占：落 SKIPPED 台账行（终态，不占槽索引谓词），并发下他人已落同键行则幂等取回。 */
	private SlotClaim persistSkippedRun(AgentTaskRun original, Long activeRunId) {
		AgentTaskRun skipped = AgentTaskRun.builder()
			.tenantId(original.getTenantId())
			.definitionId(original.getDefinitionId())
			.taskVersionId(original.getTaskVersionId())
			.triggerId(original.getTriggerId())
			.triggerType(original.getTriggerType())
			.idempotencyKey(original.getIdempotencyKey())
			.externalEventId(original.getExternalEventId())
			.scheduledTime(original.getScheduledTime())
			.runStatus(TaskRunStatus.SKIPPED.getValue())
			.servicePrincipal(original.getServicePrincipal())
			.concurrencyPolicy(original.getConcurrencyPolicy())
			.errorMessage("[" + TaskConstants.REASON_CONCURRENT_SLOT_LOCKED
					+ "] FORBID 并发槽被其他运行占用, 本次触发已跳过(activeRunId=" + activeRunId + ")")
			.finishedTime(Instant.now())
			.build();
		ClaimResult result = claimRun(skipped);
		log.info("FORBID 并发槽被占, 本次触发落 SKIPPED。tenantId={}, definitionId={}, skippedRunId={}, activeRunId={}",
				original.getTenantId(), original.getDefinitionId(), result.run().getId(), activeRunId);
		return new SlotClaim(result.run(), SlotOutcome.SLOT_CONFLICT);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean markTerminalByRuntimeRun(Long runtimeRunId, TaskRunStatus status, String errorMessage) {
		if (runtimeRunId == null || status == null) {
			throw CheckedException.badRequest("同步任务运行终态缺少 runtimeRunId 或目标状态");
		}
		AgentTaskRun taskRun = baseMapper.findByRuntimeRunId(runtimeRunId);
		if (taskRun == null) {
			// 该 Run 不由任务链路发起（对话、评测等），按「无需同步」跳过
			return false;
		}
		int updated = baseMapper.markTerminalByRuntimeRunId(runtimeRunId, status.getValue(), errorMessage,
				Instant.now());
		if (updated == 0) {
			// 幂等或任务侧已终态，均属正常
			log.debug("任务运行终态同步未命中, 可能已是终态。runtimeRunId={}, status={}", runtimeRunId,
					status.getValue());
			return false;
		}
		releaseSlotQuietly(taskRun);
		log.info("任务运行已按运行时终态同步。runtimeRunId={}, status={}", runtimeRunId, status.getValue());
		return true;
	}

	@Override
	public boolean markRunningByRuntimeRun(Long runtimeRunId) {
		if (runtimeRunId == null) {
			return false;
		}
		boolean advanced = baseMapper.markRunningByRuntimeRunId(runtimeRunId, Instant.now()) > 0;
		if (advanced) {
			log.info("任务运行已推进 RUNNING。runtimeRunId={}", runtimeRunId);
		}
		return advanced;
	}

	@Override
	public boolean markTerminalById(Long id, TaskRunStatus status, String errorMessage) {
		if (id == null || status == null) {
			throw CheckedException.badRequest("任务运行终态落库缺少主键或目标状态");
		}
		AgentTaskRun taskRun = baseMapper.selectById(id);
		if (taskRun == null) {
			return false;
		}
		int updated = baseMapper.markTerminalById(id, status.getValue(), errorMessage, Instant.now());
		if (updated == 0) {
			log.debug("任务运行终态落库未命中, 可能已是终态。runId={}, status={}", id, status.getValue());
			return false;
		}
		releaseSlotQuietly(taskRun);
		log.info("任务运行已落终态。runId={}, status={}", id, status.getValue());
		return true;
	}

	/** 终态定向释放镜像：仅当镜像仍指向该运行时清空；0 行属正常（对账已回收或指向新运行）。 */
	private void releaseSlotQuietly(AgentTaskRun taskRun) {
		if (taskRun.getDefinitionId() == null) {
			return;
		}
		try {
			if (definitionMapper.releaseSlot(taskRun.getDefinitionId(), taskRun.getId()) > 0) {
				log.debug("FORBID 并发槽已随终态释放。definitionId={}, runId={}", taskRun.getDefinitionId(),
						taskRun.getId());
			}
		}
		catch (Exception ex) {
			log.warn("并发槽镜像释放异常, 等待对账作业回收。definitionId={}", taskRun.getDefinitionId(), ex);
		}
	}

	@Override
	public AgentTaskRun cancel(Long taskRunId, String reason) {
		if (taskRunId == null) {
			throw CheckedException.badRequest("任务运行ID不能为空");
		}
		String tenantId = requireCurrentTenantId();
		AgentTaskRun taskRun = baseMapper.findByTenantAndId(tenantId, taskRunId);
		if (taskRun == null) {
			throw CheckedException.notFound("任务运行不存在: " + taskRunId);
		}
		if (isTerminalStatus(taskRun.getRunStatus())) {
			// 台账已终态但 FORBID 镜像可能仍指向该运行：取消占用仍定向放槽。
			releaseSlotQuietly(taskRun);
			return taskRun;
		}
		if (taskRun.getRuntimeRunId() != null) {
			requestRuntimeCancelQuietly(tenantId, taskRun);
		}
		String message = StringUtils.hasText(reason) ? reason.trim() : "用户取消占用";
		markTerminalById(taskRun.getId(), TaskRunStatus.CANCELLED, "[USER_CANCEL] " + message);
		AgentTaskRun latest = baseMapper.findByTenantAndId(tenantId, taskRunId);
		return latest == null ? taskRun : latest;
	}

	private void requestRuntimeCancelQuietly(String tenantId, AgentTaskRun taskRun) {
		try {
			runtimeRunService.cancel(tenantId, currentUserId(), taskRun.getRuntimeRunId(),
					"用户在任务中心取消占用");
		}
		catch (CheckedException ex) {
			log.info("任务运行关联 Runtime 无法取消（可能已终态或不存在），改为直接释放槽。taskRunId={}, runtimeRunId={}",
					taskRun.getId(), taskRun.getRuntimeRunId(), ex);
		}
	}

	private boolean isTerminalStatus(String status) {
		return TaskRunStatus.SUCCESS.getValue().equals(status) || TaskRunStatus.FAILED.getValue().equals(status)
				|| TaskRunStatus.SKIPPED.getValue().equals(status)
				|| TaskRunStatus.CANCELLED.getValue().equals(status);
	}

	private String currentUserId() {
		try {
			return authenticationContext.userId();
		}
		catch (Exception ex) {
			return null;
		}
	}

	@Override
	public IPage<AgentTaskRun> pageByDefinition(Long definitionId, AgentTaskRunPageQueryReq request) {
		AgentTaskRunPageQueryReq query = request == null ? new AgentTaskRunPageQueryReq() : request;
		return baseMapper.selectPageByDefinition(query.buildPage(), requireCurrentTenantId(), definitionId, query);
	}

	private String requireCurrentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			log.warn("解析当前租户上下文失败, 将按缺失租户拒绝本次任务运行查询", ex);
			tenantId = null;
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.badRequest("租户上下文缺失, 无法查询任务运行");
		}
		return tenantId;
	}

	/** 事务内预检发现槽位被占的内部信号（回滚本次事务，外层转 SKIPPED 落库）。 */
	private static final class SlotOccupiedException extends RuntimeException {

		private final Long activeRunId;

		private SlotOccupiedException(Long activeRunId) {
			super("forbid slot occupied");
			this.activeRunId = activeRunId;
		}

	}

}
