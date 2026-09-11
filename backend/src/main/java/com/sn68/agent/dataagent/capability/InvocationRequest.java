/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import java.util.Map;
import lombok.Builder;

/**
 * 统一能力调用请求。所有能力入口（AgentScope 工具、Skill、Flow、Runtime Hook、任务、IM 指令、
 * API 触发器）统一收敛为本请求经 {@link CapabilityGateway} 调用。除能力种类与能力编码外字段均可空，
 * 网关按能力种类与可用上下文逐项失败关闭地检查。
 *
 * @param tenantId 租户ID，可空；为空时网关回退到当前授权上下文解析
 * @param ownerType 运行主体类型（DIGITAL_EMPLOYEE / CALLER / PLATFORM），可空；非空时与 ownerId 成对入高风险审批键的 owner 段
 * @param ownerId 运行主体 ID，可空；与 ownerType 成对，缺一按无主体作用域处理
 * @param releaseId Release ID，可空；非空时网关校验发布记录存在、同租户且处于 PUBLISHED 状态
 * @param runId 运行ID，可空；非空时调用经 agent_runtime_invocation 记录生命周期
 * @param stepKey 步骤键，可空，仅用于调用记录关联
 * @param capabilityKind 能力种类，必填
 * @param capabilityCode 能力编码（工具目录 resourceKey 或进程内工具名），必填
 * @param arguments 调用参数（JSON 对象语义），可空
 * @param source 调用来源（如 RUNTIME_HOOK、AGENT_SCOPE），参与幂等键生成
 * @param idempotencyKey 幂等键，可空；为空时网关按「来源+能力+参数 hash」生成
 * @param agentId 智能体ID，可空，用于限流范围与调用记录
 * @param userId 用户ID，可空，用于限流范围与调用记录
 * @param executionIntent 执行意图（LIVE / DRY_RUN），可空按 LIVE 处理；DRY_RUN 时网关
 *        失败关闭地拦截写能力与外部副作用并记违规（方案第十四章离线评估约束）
 * @param executionScopeKey DRY_RUN 违规采集作用域键，可空；网关按该键把违规投递回评估采集器
 * @param requireManagerApproval 技能是否要求管理端审批；缺省 false。仅 true 时走 PAP
 * @param confirmed FLOW 写节点是否已完成用户确认；仅审计，不单独触发 PAP
 * @param sourceRefId 来源关联 ID（FLOW 实例 ID）；管理端审批入键，防同租户串单
 * @param approvalId 续跑时要一次性消费的已通过审批 ID；首次调用为空
 */
@Builder(toBuilder = true)
public record InvocationRequest(
		String tenantId,
		String ownerType,
		Long ownerId,
		Long releaseId,
		Long runId,
		String stepKey,
		CapabilityKind capabilityKind,
		String capabilityCode,
		Map<String, Object> arguments,
		String source,
		String idempotencyKey,
		Long agentId,
		String userId,
		String executionIntent,
		String executionScopeKey,
		Boolean requireManagerApproval,
		Boolean confirmed,
		String sourceRefId,
		Long approvalId) {
}
