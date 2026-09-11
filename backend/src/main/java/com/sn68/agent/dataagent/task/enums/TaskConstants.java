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
package com.sn68.agent.dataagent.task.enums;

/**
 * 任务体系常量。启用/停用状态沿用模块 im 包既有的 enabled/disabled 字符串约定。
 */
public final class TaskConstants {

	/** 任务查询权限码：任务定义分页/详情、触发器列表、运行记录分页。 */
	public static final String PERMISSION_TASK_QUERY = "ai-agent:task:query";

	/**
	 * 任务维护权限码：任务定义与触发器的增删改，以及 API 触发签名密钥轮换。
	 * 密钥轮换归此档而非 trigger 档：签发密钥是配置动作，调用方持有 trigger 档不应能自行换钥。
	 */
	public static final String PERMISSION_TASK_MANAGE = "ai-agent:task:manage";

	/**
	 * API 触发权限码：外部系统按 API 触发器发起任务运行。
	 * 与 manage 分档，避免只需发起运行的对接账号连带获得删除任务定义、轮换密钥的能力。
	 */
	public static final String PERMISSION_TASK_TRIGGER = "ai-agent:task:trigger";

	/** Snail Cluster 扫描作业执行器名（与 @JobExecutor 一致）：扫到期触发器。 */
	public static final String SNAIL_JOB_SCHEDULE = "agentTaskScheduleJob";

	/** Snail Cluster 扫描作业执行器名：扫 PENDING 运行并拉起。 */
	public static final String SNAIL_JOB_DAEMON = "runtimeRunDaemonJob";

	/** Snail Cluster 扫描作业执行器名：任务运行状态对账与 FORBID 槽回收。 */
	public static final String SNAIL_JOB_STATUS_SYNC = "agentTaskRunStatusSyncJob";

	public static final String STATUS_ENABLED = "enabled";

	public static final String STATUS_DISABLED = "disabled";

	/** 触发配置（JSONB）中的 cron 表达式键。 */
	public static final String CONFIG_CRON = "cron";

	/** 触发配置（JSONB）中的事件主题键。 */
	public static final String CONFIG_EVENT_TOPIC = "eventTopic";

	/** 触发配置（JSONB）中的事件标签键（可选）。 */
	public static final String CONFIG_EVENT_TAG = "eventTag";

	/** API 触发的幂等键请求头。 */
	public static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

	/** API 触发签名请求头：HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody) 的小写 hex。 */
	public static final String HEADER_SIGNATURE = "X-Signature";

	/** API 触发时间戳请求头：epoch 毫秒，允许与服务端时钟偏差 ±5 分钟。 */
	public static final String HEADER_TIMESTAMP = "X-Timestamp";

	/** API 触发一次性随机串请求头：窗口内不得重复（Redis 去重防重放）。 */
	public static final String HEADER_NONCE = "X-Nonce";

	/** 触发配置（JSONB）中的 API 签名密钥键（密文存储，仅密钥轮换接口写入，读接口脱敏）。 */
	public static final String CONFIG_API_SECRET = "apiSecret";

	/** API 触发签名算法标识（返回给对接方）。 */
	public static final String SIGNATURE_ALGORITHM = "HMAC-SHA256";

	/** 默认时区（未配置时区的 SCHEDULE 触发按此计算）。 */
	public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

	/** 触发器并发策略：FORBID——同一任务定义同时最多一行活跃（PR-6 方案A 并发槽）。 */
	public static final String CONCURRENCY_FORBID = "FORBID";

	/** 触发器并发策略：ALLOW——允许同一任务定义多行活跃，不参与并发槽竞争。 */
	public static final String CONCURRENCY_ALLOW = "ALLOW";

	/** FORBID 并发槽竞争失败原因码：本次触发因定义级活跃槽被占用而跳过（落 SKIPPED）。 */
	public static final String REASON_CONCURRENT_SLOT_LOCKED = "CONCURRENT_SLOT_LOCKED";

	/** 拉起失败原因码：IAM 不可用或执行凭据签发失败，等待授权可重试（不创建可执行 Run）。 */
	public static final String REASON_WAITING_AUTH = "WAITING_AUTH";

	/** 拉起失败原因码：Principal 未就绪/被停用或授权策略拒绝，确定性失败（不创建可执行 Run）。 */
	public static final String REASON_AUTHORIZATION_DENIED = "AUTHORIZATION_DENIED";

	/** 任务参数快照（JSONB params_snapshot）中的授权模式键（PR-6 版本冻结清单）。 */
	public static final String PARAMS_AUTH_MODE = "authMode";

	/** 任务参数快照（JSONB params_snapshot）中的 Token 预算键（PR-6 版本冻结清单）。 */
	public static final String PARAMS_BUDGET = "budget";

	/** 任务参数快照（JSONB params_snapshot）中的授权策略版本ID键（PR-6 版本冻结清单）。 */
	public static final String PARAMS_AUTHORIZATION_POLICY_VERSION_ID = "authorizationPolicyVersionId";

	private TaskConstants() {
	}

}
