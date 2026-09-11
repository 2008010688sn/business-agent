/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeStepState;
import java.time.Duration;
import java.time.Instant;

/**
 * 持久运行时状态机服务。
 *
 * <p>所有状态更新必须经由本服务的 CAS 原语：{@code UPDATE ... SET state = ?, state_version = state_version + 1
 * WHERE id = ? AND state_version = ?}，可选携带 fence_token 校验（旧 fence 不得覆盖新 fence 的写入），
 * 任何终态在 SQL 层面永不被覆盖，保证取消与成功竞态下终态只有一个有效结果。</p>
 *
 * <p>租约模型：{@code acquire} 在租约空缺/过期/属于自己时成功并原子递增 fence_token（两个节点同时认领
 * 只能一个获得有效 fence）；节点崩溃后 lease_until 过期，其他节点可接管并获得更大的 fence。</p>
 */
public interface RuntimeStateService {

	/**
	 * CAS 推进 Run 状态。返回 false 表示竞争失败（版本过期、fence 过期或已入终态）。
	 */
	boolean transitionRun(RunTransition transition);

	/**
	 * CAS 推进 Run 状态，读取当前版本重试（不带 fence 的系统路径使用，如镜像双写、API 状态机）。
	 * 终态保护仍然生效。返回 false 表示重试耗尽或已入终态。
	 */
	boolean transitionRunWithRetry(Long runId, RuntimeRunState toState, RunStateChange change, int maxRetries);

	/**
	 * 获取/接管 Run 租约。返回新 fence_token；返回 null 表示租约被他人有效持有或运行已终态。
	 */
	Long acquireRunLease(Long runId, String owner, Duration leaseDuration);

	boolean renewRunLease(Long runId, String owner, Long fenceToken, Duration leaseDuration);

	boolean releaseRunLease(Long runId, String owner, Long fenceToken);

	/**
	 * 取消纪元递增。返回递增后的纪元；返回 null 表示运行已终态或不存在。
	 */
	Long bumpCancellationEpoch(Long runId);

	/**
	 * CAS 推进 Step 状态。返回 false 表示竞争失败。
	 */
	boolean transitionStep(StepTransition transition);

	/**
	 * CAS 推进 Step 状态，读取当前版本重试（不带 fence 的系统路径使用）。终态保护仍然生效。
	 */
	boolean transitionStepWithRetry(Long stepId, RuntimeStepState toState, StepStateChange change, int maxRetries);

	/**
	 * 获取/接管 Step 租约。返回新 fence_token；返回 null 表示租约被他人有效持有或步骤已终态。
	 */
	Long acquireStepLease(Long stepId, String owner, Duration leaseDuration);

	boolean renewStepLease(Long stepId, String owner, Long fenceToken, Duration leaseDuration);

	/**
	 * 释放步骤租约（不回退 fence）。供「接管但不亲自执行」的路径使用：守护作业以更高 fence 夺走
	 * 崩溃节点的租约、把步骤退回 READY 后必须释放，否则调度器无法再认领该步骤。
	 */
	boolean releaseStepLease(Long stepId, String owner, Long fenceToken);

	/**
	 * 原子分配步骤尝试序号（attempt_count + 1 并返回），配合 (step_id, attempt_no) 唯一约束。
	 */
	Integer allocateAttemptNo(Long stepId);

	/**
	 * Run 状态 CAS 迁移命令。expectedVersion 必填；fenceToken 可空（空表示该路径不持租约，仅版本 CAS）。
	 */
	record RunTransition(Long runId, Long expectedVersion, Long fenceToken, RuntimeRunState toState,
			RunStateChange change) {
	}

	/**
	 * Run 状态迁移附带的业务字段（均可空，空则保留原值）。
	 */
	record RunStateChange(String finalAnswer, String errorCode, String errorMessage, Instant startedAt,
			Instant finishedAt, Instant deadlineAt) {

		public static RunStateChange none() {
			return new RunStateChange(null, null, null, null, null, null);
		}

		public static RunStateChange started(Instant startedAt) {
			return new RunStateChange(null, null, null, startedAt, null, null);
		}

		public static RunStateChange finished(String finalAnswer, String errorCode, String errorMessage) {
			return new RunStateChange(finalAnswer, errorCode, errorMessage, null, Instant.now(), null);
		}

		/** 仅重置执行截止时间，其余业务字段保留原值。 */
		public static RunStateChange deadline(Instant deadlineAt) {
			return new RunStateChange(null, null, null, null, null, deadlineAt);
		}

	}

	/**
	 * Step 状态 CAS 迁移命令。expectedVersion 必填；fenceToken 可空。
	 */
	record StepTransition(Long stepId, Long expectedVersion, Long fenceToken, RuntimeStepState toState,
			StepStateChange change) {
	}

	/**
	 * Step 状态迁移附带的业务字段（均可空，空则保留原值）。
	 */
	record StepStateChange(String errorCode, String errorMessage, Long outputArtifactId, Instant startedAt,
			Instant finishedAt) {

		public static StepStateChange none() {
			return new StepStateChange(null, null, null, null, null);
		}

		public static StepStateChange started(Instant startedAt) {
			return new StepStateChange(null, null, null, startedAt, null);
		}

		public static StepStateChange finished(String errorCode, String errorMessage, Long outputArtifactId) {
			return new StepStateChange(errorCode, errorMessage, outputArtifactId, null, Instant.now());
		}

	}

}
