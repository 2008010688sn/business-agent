/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.schedule;

import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeStep;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeEventType;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeStepMapper;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeDagScheduler;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeEventService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeOutboxService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.RunStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepStateChange;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeStateService.StepTransition;
import com.sn68.agent.dataagent.properties.DataAgentProperties;
import com.sn68.agent.dataagent.runtime.durable.service.impl.SingleTurnRuntimeStepExecutor;
import com.sn68.agent.dataagent.task.enums.TaskRunStatus;
import com.sn68.agent.dataagent.task.service.AgentTaskRunService;
import com.sn68.agent.framework.redis.plus.exception.RedisLockException;
import com.sn68.agent.framework.redis.plus.lock.RedisLockHelper;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运行时守护作业（Snail Job）。
 *
 * <p>由启动代码注册为 Snail Cluster 作业（多实例只派一台）。扫描间隔是基础设施心跳，
 * 不是业务 cron。作业本身可安全重复触发。一个作业做两件事，都是 agent_runtime_run /
 * agent_runtime_step 上「该跑却没在跑」的行，合并扫描避免两个作业各扫一遍同一批数据：</p>
 * <ol>
 * <li>待启动（PENDING）与停摆（RUNNING 但无在途步骤）的单步任务型运行 → 物化单步行后交
 * {@link RuntimeDagScheduler#start} 拉起。审批通过后 {@code runtimeRunService.resume} 只把运行
 * 置回 RUNNING、并不驱动执行，续跑正是由这一路兜住；start 对 RUNNING 运行是重算就绪步骤的幂等入口。</li>
 * <li>RUNNING 且租约已过期的步骤（执行节点崩溃遗留） → 以更高 fence 接管：重取租约后把步骤
 * CAS 回 READY，再交调度器重新认领执行。</li>
 * <li>已过 {@code deadline_at} 仍非终态、且无节点在执行的运行 → 收敛为 TIMED_OUT，并把关联的
 * {@code agent_task_run} 一并推进为失败终态，避免运行与任务台账双双悬停。</li>
 * </ol>
 *
 * <p>多实例安全由两层保证，缺一不可：</p>
 * <ul>
 * <li><b>权威层（正确性）</b>：运行 PENDING→RUNNING 是 state_version CAS，步骤认领是
 * 租约 + fence_token 递增 + READY→RUNNING CAS，多副本同抢只有一个成功，落后者的写入被
 * fence 判废。即使锁完全失效，也不会出现同一步骤被执行两次并双双落终态。</li>
 * <li><b>调度层（开销）</b>：本轮扫描外套一把 Redis 互斥锁（参照 {@code RuntimeOutboxDispatcher}），
 * 抢不到直接跳过本轮，避免每个副本都对同一批行发起注定失败的 CAS。锁只省开销，不承担正确性。</li>
 * </ul>
 *
 * <p>授权模型: 服务主体
 * Snail Job 守护作业以平台身份扫运行时表并续跑/接管/超时收敛，不接受终端用户会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeRunDaemonJob {

	private static final String DAEMON_LOCK = "dataagent:runtime:run-daemon";

	/** 单轮拉起上限：拉起动作只是提交到编排线程池，批量偏大反而会让线程池瞬间饱和。 */
	private static final int PENDING_SCAN_BATCH = 100;

	/** 单轮接管上限：租约过期是异常路径，小批量足够，避免一次接管压垮执行池。 */
	private static final int LEASE_SCAN_BATCH = 50;

	/** 单轮超时收敛上限：收敛只是若干条 CAS，与拉起同量级即可，避免单轮长时间占用扫描锁。 */
	private static final int DEADLINE_SCAN_BATCH = 100;

	/** 超时收敛写入的错误码，沿用运行时既有错误码风格（大写下划线）。 */
	private static final String DEADLINE_ERROR_CODE = "RUN_DEADLINE_EXCEEDED";

	/** CHAT 等待态独立超时原因码。 */
	private static final String WAIT_TIMEOUT_ERROR_CODE = "WAIT_TIMEOUT";

	private static final Duration DEFAULT_CHAT_WAIT_TIMEOUT = Duration.ofDays(7);

	/** 不带 fence 的系统路径 CAS 重试次数，与调度器同口径。 */
	private static final int CAS_RETRY = 3;

	/**
	 * RUNNING 运行的停摆判定窗口：晚于该时刻被改动过的运行视为仍在推进，不重复拉起，
	 * 避开「其他副本刚把运行置为 RUNNING、步骤尚未物化」的瞬时窗口。
	 */
	private static final Duration RUNNING_STALE_AFTER = Duration.ofMinutes(1);

	/** 接管时先取得的租约时长：只用于把步骤 CAS 回 READY，真正执行时调度器会重新认领。 */
	private static final Duration TAKEOVER_LEASE_DURATION = Duration.ofSeconds(30);

	/** 本节点接管租约时的持有者标识（进程级唯一，与调度器的节点标识同口径）。 */
	private static final String NODE_ID = buildNodeId();

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeStepMapper stepMapper;

	private final RuntimeStateService runtimeStateService;

	private final RuntimeDagScheduler runtimeDagScheduler;

	private final SingleTurnRuntimeStepExecutor singleTurnExecutor;

	private final RuntimeEventService runtimeEventService;

	private final RuntimeOutboxService runtimeOutboxService;

	private final AgentTaskRunService taskRunService;

	private final RedisLockHelper redisLockHelper;

	private final DataAgentProperties dataAgentProperties;

	/**
	 * Snail Job 回调入口。
	 */
	public void jobExecute() {
		log.debug("运行时守护作业开始扫描");
		try {
			redisLockHelper.execute(DAEMON_LOCK, 0L, TimeUnit.SECONDS, this::scanOnce);
		}
		catch (RedisLockException ex) {
			log.debug("运行时守护作业跳过, 其他副本持有扫描锁");
		}
		catch (Exception ex) {
			// 单轮失败不吞：记录后等待下次触发重扫，未拉起的运行仍停在 PENDING 可追溯
			log.warn("运行时守护作业本轮异常, 等待下次触发重试", ex);
		}
	}

	private int scanOnce() {
		// 先收敛超时运行：过期运行不会被后两轮扫描命中，早收敛可让任务台账更快看到终态
		int expired = expireDeadlineRuns();
		int waitExpired = expireWaitingChatRuns();
		int started = startStalledRuns();
		int reclaimed = reclaimExpiredSteps();
		if (expired > 0 || waitExpired > 0 || started > 0 || reclaimed > 0) {
			log.info("运行时守护作业完成, 本轮收敛 {} 个超时运行, {} 个等待超时会话, 拉起 {} 个待启动/停摆运行, 接管 {} 个租约过期步骤",
					expired, waitExpired, started, reclaimed);
		}
		return expired + waitExpired + started + reclaimed;
	}

	/**
	 * 拉起待启动（PENDING）与停摆（RUNNING 但无在途步骤，如审批恢复后无人续跑）的运行。
	 * 单条失败不中断整批：记录后继续，该运行状态未推进，下一轮会重扫。
	 *
	 * @return 本轮成功拉起的运行数
	 */
	public int startStalledRuns() {
		Instant now = Instant.now();
		List<AgentRuntimeRun> startable = runMapper.listStartableRuns(now, now.minus(RUNNING_STALE_AFTER),
				PENDING_SCAN_BATCH);
		int started = 0;
		for (AgentRuntimeRun run : startable) {
			try {
				started += startRun(run);
			}
			catch (Exception ex) {
				log.warn("待启动运行拉起失败, 本轮跳过. runId={}, tenantId={}", run.getId(), run.getTenantId(), ex);
			}
		}
		return started;
	}

	/**
	 * 收敛已过绝对截止时间仍未终态的运行。单条失败不中断整批：记录后继续，该运行状态未推进，
	 * 下一轮会重扫（收敛全程幂等，重复扫描不会重复推进）。
	 *
	 * @return 本轮成功收敛为超时终态的运行数
	 */
	public int expireWaitingChatRuns() {
		Instant staleBefore = Instant.now().minus(chatWaitTimeout());
		List<AgentRuntimeRun> expired = runMapper.listExpiredWaitingChatRuns(staleBefore, DEADLINE_SCAN_BATCH);
		int converged = 0;
		for (AgentRuntimeRun run : expired) {
			try {
				converged += expireWaitingChatRun(run);
			}
			catch (Exception ex) {
				log.warn("等待超时会话收敛失败, 本轮跳过. runId={}, tenantId={}", run.getId(), run.getTenantId(), ex);
			}
		}
		return converged;
	}

	public int expireDeadlineRuns() {
		Instant now = Instant.now();
		List<AgentRuntimeRun> expired = runMapper.listExpiredDeadlineRuns(now, DEADLINE_SCAN_BATCH);
		int converged = 0;
		for (AgentRuntimeRun run : expired) {
			try {
				converged += expireRun(run, now);
			}
			catch (Exception ex) {
				log.warn("过期运行超时收敛失败, 本轮跳过. runId={}, tenantId={}", run.getId(), run.getTenantId(), ex);
			}
		}
		return converged;
	}

	/**
	 * 接管租约过期的 RUNNING 步骤。单条失败不中断整批。
	 *
	 * @return 本轮成功接管的步骤数
	 */
	public int reclaimExpiredSteps() {
		List<Long> expiredStepIds = stepMapper.listExpiredLeaseStepIds(Instant.now(), LEASE_SCAN_BATCH);
		int reclaimed = 0;
		for (Long stepId : expiredStepIds) {
			try {
				reclaimed += reclaimStep(stepId);
			}
			catch (Exception ex) {
				log.warn("租约过期步骤接管失败, 本轮跳过. stepId={}", stepId, ex);
			}
		}
		return reclaimed;
	}

	/**
	 * 拉起单个运行：先幂等物化单步行（任务链路建 Run 时不落步骤，而调度器只调度步骤），
	 * 再交调度器 start。start 内部对 PENDING→RUNNING 做 state_version CAS，多副本同抢只有一个
	 * 真正启动，落后者退化为一次幂等的就绪重算；步骤是否真被执行仍由租约 + fence 唯一决定。
	 */
	private int startRun(AgentRuntimeRun run) {
		AgentRuntimeStep step = singleTurnExecutor.ensureSingleStep(run);
		if (step == null) {
			log.warn("待启动运行的单步行准备失败, 本轮跳过. runId={}", run.getId());
			return 0;
		}
		runtimeDagScheduler.start(run.getId(), singleTurnExecutor);
		return 1;
	}

	/**
	 * 接管单个步骤：重取租约取得更高 fence（原持有者的后续写入随即失效），以新 fence 把步骤
	 * CAS 回 READY，随后立即释放租约再交调度器重新认领 —— 守护作业只做接管不亲自执行，
	 * 攥着租约会让调度器认领不到这一步。执行本身仍走调度器既有的认领与状态机。
	 */
	private int reclaimStep(Long stepId) {
		AgentRuntimeStep step = stepMapper.selectById(stepId);
		if (step == null || RuntimeStepState.of(step.getState()) != RuntimeStepState.RUNNING) {
			// 扫描到现在这段时间内原持有者已收敛或他人已接管
			return 0;
		}
		Long fenceToken = runtimeStateService.acquireStepLease(stepId, NODE_ID, TAKEOVER_LEASE_DURATION);
		if (fenceToken == null) {
			log.debug("租约过期步骤已被他人接管, 跳过. stepId={}", stepId);
			return 0;
		}
		AgentRuntimeStep claimed = stepMapper.selectById(stepId);
		if (claimed == null || RuntimeStepState.of(claimed.getState()) != RuntimeStepState.RUNNING) {
			return 0;
		}
		boolean requeued = runtimeStateService.transitionStep(new StepTransition(stepId, claimed.getStateVersion(),
				fenceToken, RuntimeStepState.READY, StepStateChange.none()));
		if (!requeued) {
			log.debug("租约过期步骤回 READY 的 CAS 竞争失败, 跳过. stepId={}", stepId);
			return 0;
		}
		if (!runtimeStateService.releaseStepLease(stepId, NODE_ID, fenceToken)) {
			// 释放失败说明租约已被更高 fence 接管，本次接管作废，交由新持有者推进
			log.debug("接管租约释放未生效, 已被更高 fence 接管. stepId={}", stepId);
		}
		log.warn("步骤租约过期已接管, 重新交调度器执行. stepId={}, runId={}, stepKey={}, previousOwner={}, newFence={}",
				stepId, claimed.getRunId(), claimed.getStepKey(), step.getLeaseOwner(), fenceToken);
		runtimeDagScheduler.start(claimed.getRunId(), singleTurnExecutor);
		return 1;
	}

	/**
	 * 收敛单个过期运行。顺序刻意如此：先推进权威 Run（唯一的正确性来源，CAS + 终态保护），
	 * 成功后再做三件附属写入 —— 任务台账同步、残留步骤收敛、终态事件与 Outbox。
	 *
	 * <p>Run 先行还避免了一个竞态：若先收敛步骤，恰好在执行的僵尸线程回写终态时会触发调度器的
	 * {@code maybeFinishRun}，把运行抢先收敛为 FAILED；Run 已是终态时该逻辑直接返回。</p>
	 *
	 * <p>任务台账同步刻意保留同步直调，与 RUN_TIMED_OUT 事件的 Outbox 消费方
	 * （{@code TaskRunTerminalSyncListener}）职责重叠但不冲突：台账更新的
	 * {@code run_status IN ('PENDING','RUNNING')} 谓词只放行先到者，后到者命中 0 行。
	 * 直调不依赖派发器在线，Outbox 写入或派发失败时台账仍能收敛。</p>
	 *
	 * @return 1 表示本次收敛生效；0 表示 CAS 竞争失败（他人已推进终态）
	 */
	private int expireRun(AgentRuntimeRun run, Instant now) {
		String message = "运行超过绝对截止时间仍未完成, 已收敛为超时, deadlineAt=" + run.getDeadlineAt();
		boolean converged = runtimeStateService.transitionRunWithRetry(run.getId(), RuntimeRunState.TIMED_OUT,
				RunStateChange.finished(null, DEADLINE_ERROR_CODE, message), CAS_RETRY);
		if (!converged) {
			log.debug("过期运行超时收敛 CAS 未生效, 已由他人推进终态. runId={}", run.getId());
			return 0;
		}
		taskRunService.markTerminalByRuntimeRun(run.getId(), TaskRunStatus.FAILED,
				"[" + DEADLINE_ERROR_CODE + "] " + message);
		expireRunSteps(run);
		appendTimeoutSignalsQuietly(run);
		log.warn("运行已过截止时间, 收敛为超时终态. runId={}, tenantId={}, previousState={}, deadlineAt={}, overdueSeconds={}",
				run.getId(), run.getTenantId(), run.getState(), run.getDeadlineAt(),
				Duration.between(run.getDeadlineAt(), now).toSeconds());
		return 1;
	}

	private int expireWaitingChatRun(AgentRuntimeRun run) {
		String message = "等待输入超过时限, 已取消以释放会话槽, lastModifyTime=" + run.getLastModifyTime();
		boolean converged = runtimeStateService.transitionRunWithRetry(run.getId(), RuntimeRunState.CANCELLED,
				RunStateChange.finished(null, WAIT_TIMEOUT_ERROR_CODE, message), CAS_RETRY);
		if (!converged) {
			return 0;
		}
		log.warn("CHAT 等待超时已取消. runId={}, tenantId={}, threadId={}, lastModifyTime={}", run.getId(),
				run.getTenantId(), run.getThreadId(), run.getLastModifyTime());
		try {
			runtimeEventService.append(run.getTenantId(), run.getId(), "run-wait-timeout",
					RuntimeEventType.RUN_CANCELLED, null, Map.of("errorCode", WAIT_TIMEOUT_ERROR_CODE));
		}
		catch (RuntimeException ex) {
			log.error("等待超时事件追加失败, 终态已收敛不受影响. runId={}", run.getId(), ex);
		}
		return 1;
	}

	private Duration chatWaitTimeout() {
		if (dataAgentProperties == null || dataAgentProperties.getRuntime() == null
				|| dataAgentProperties.getRuntime().getChatWaitTimeout() == null
				|| dataAgentProperties.getRuntime().getChatWaitTimeout().isZero()
				|| dataAgentProperties.getRuntime().getChatWaitTimeout().isNegative()) {
			return DEFAULT_CHAT_WAIT_TIMEOUT;
		}
		return dataAgentProperties.getRuntime().getChatWaitTimeout();
	}

	/**
	 * 把过期运行下仍非终态的步骤一并收敛为超时终态，避免运行已终态却留下悬空步骤行。
	 * 扫描已排除持有效租约的在途步骤，这里剩下的只会是未开始（PENDING/READY）、等待交互（WAITING）
	 * 或崩溃遗留（租约已过期的 RUNNING）；走不带 fence 的系统路径 CAS，终态保护仍然生效，
	 * 僵尸线程稍后的回写会被终态谓词判废。
	 */
	private void expireRunSteps(AgentRuntimeRun run) {
		for (AgentRuntimeStep step : stepMapper.listByRunId(run.getId())) {
			RuntimeStepState state = RuntimeStepState.of(step.getState());
			if (state == null || state.terminal()) {
				continue;
			}
			boolean converged = runtimeStateService.transitionStepWithRetry(step.getId(), RuntimeStepState.TIMED_OUT,
					StepStateChange.finished(DEADLINE_ERROR_CODE, "所属运行已超过绝对截止时间", null), CAS_RETRY);
			if (!converged) {
				log.warn("过期运行的残留步骤收敛未生效, 需人工核查. runId={}, stepId={}, stepKey={}", run.getId(),
						step.getId(), step.getStepKey());
			}
		}
	}

	/**
	 * 终态事件与 Outbox 旁路写入，与调度器收敛终态时同一口径（eventKey 幂等，消费方按此去重）。
	 * 失败只记 error：运行终态已由 CAS 落库，附属记录缺失不回滚业务状态。
	 */
	private void appendTimeoutSignalsQuietly(AgentRuntimeRun run) {
		try {
			runtimeEventService.append(run.getTenantId(), run.getId(), "run-timed-out",
					RuntimeEventType.RUN_TIMED_OUT, null, Map.of("errorCode", DEADLINE_ERROR_CODE));
		}
		catch (RuntimeException ex) {
			log.error("运行超时事件追加失败, 终态已收敛不受影响. runId={}", run.getId(), ex);
		}
		try {
			runtimeOutboxService.append(new RuntimeOutboxService.OutboxAppend(run.getTenantId(), null,
					run.getId(), null, RuntimeEventType.RUN_TIMED_OUT.getValue(),
					"run-terminal:" + run.getId() + ":" + RuntimeEventType.RUN_TIMED_OUT.getValue(),
					Map.of("state", RuntimeRunState.TIMED_OUT.getValue(), "errorCode", DEADLINE_ERROR_CODE)));
		}
		catch (RuntimeException ex) {
			log.error("运行超时 Outbox 写入失败, 终态已收敛不受影响. runId={}", run.getId(), ex);
		}
	}

	private static String buildNodeId() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (Exception ex) {
			host = "node";
		}
		return "daemon-" + host + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
