/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.runtime.durable.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeApprovalResp;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeApproval;
import java.time.Instant;

/**
 * 持久运行时审批服务：高风险能力（写操作 / 需人工确认）与 ASSISTED 任务的人工审批链路。
 *
 * <p>核心语义（方案十八章验收第 14 条「过期审批、参数变化、权限变化后必须重新审批」）：
 * <ul>
 * <li>审批与作用域 + 参数指纹绑定（PR-1 起 owner 维度）：approvalKey =
 * {@code owner:{ownerType:ownerId|none}|run:{runId|ref:{sourceRefId}|none}|{capabilityCode}:{paramsHash}}，
 * 运行主体、运行、参数任一变化即键变化，旧审批天然不匹配，批复不可跨 owner / 跨运行复用；</li>
 * <li>租户必填：解析不到租户一律失败关闭，不落 0 号池——落 0 的审批既无人可批（管理端按
 * 当前租户查询），又会让所有解析失败的请求共用一个批复池；</li>
 * <li>过期懒惰判定：读取 / 决定 / 消费时发现过期即置 EXPIRED，不建定时任务；</li>
 * <li>APPROVED 一次性消费：放行即 CAS 迁移 CONSUMED，用后失效，不允许二次放行；</li>
 * <li>审批人身份：PR-1 拆除 Workspace 成员校验（workspace 域已删除），平台级审批人闸门由数字员工域
 * （PR-5）重建，过渡期仅靠 Sa-Token 权限码约束；</li>
 * <li>权限变化无需感知：消费方（能力网关）在审批检查前已重新执行授权检查链，
 * 权限收回后即便持有 APPROVED 也过不了前置检查。</li>
 * </ul>
 */
public interface AgentApprovalService {

	/**
	 * 创建待审批记录。幂等：同（租户 + approvalKey）已有未过期 PENDING 时返回既有记录，不重复建；
	 * 并发插入冲突时由部分唯一索引兜底，回查复用既有 PENDING。创建成功同事务写 outbox 事件
	 * APPROVAL_REQUESTED。
	 *
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 租户为空（失败关闭，不落 0 号池）
	 */
	AgentRuntimeApproval createPending(ApprovalCreateRequest request);

	/**
	 * 审批通过（PENDING → APPROVED），记录审批人、意见与时间；非法流转 / 已过期时抛 CheckedException。
	 * 同事务写 outbox 事件 APPROVAL_DECIDED。
	 */
	void approve(String tenantId, String approver, Long id, String comment);

	/**
	 * 审批驳回（PENDING → REJECTED），意见必填；非法流转 / 已过期时抛 CheckedException。
	 * 同事务写 outbox 事件 APPROVAL_DECIDED。
	 */
	void reject(String tenantId, String approver, Long id, String comment);

	/**
	 * 查可消费的已通过审批（按 租户 + owner 作用域 + 运行 + 能力编码 + 参数指纹）。
	 * 命中但已过期时懒惰置 EXPIRED 并返回 null。
	 *
	 * @throws com.sn68.agent.framework.commons.exception.CheckedException 租户为空（失败关闭，不查 0 号池）
	 */
	AgentRuntimeApproval findConsumableApproved(String tenantId, String ownerType, Long ownerId, Long runId,
			String capabilityCode, String paramsHash);

	/**
	 * 一次性消费已通过审批（APPROVED → CONSUMED，CAS 保证并发下只放行一次）。
	 *
	 * @return true 消费成功；false 已被消费 / 已过期 / 状态不符
	 */
	boolean consume(Long id);

	/**
	 * 按审批 ID 消费已通过批复，并核对 owner / sourceRef / 能力 / 参数指纹。
	 * 键不匹配或状态不是 APPROVED 时返回 false，不放行。
	 */
	boolean consumeMatching(String tenantId, Long approvalId, String ownerType, Long ownerId, String sourceRefId,
			String capabilityCode, String paramsHash);

	/**
	 * 作废尚未消费的审批（PENDING 或 APPROVED → CANCELLED）。已消费 / 已驳回的记录拒绝。
	 */
	void cancel(String tenantId, String actor, Long id, String comment);

	IPage<RuntimeApprovalResp> page(String tenantId, RuntimeApprovalPageQueryReq req);

	/** 查询详情，读取时执行懒惰过期判定。 */
	RuntimeApprovalResp detail(String tenantId, Long id);

	/**
	 * 审批创建载体。
	 *
	 * @param tenantId 租户ID，必填；为空即失败关闭，不落 0
	 * @param ownerType 运行主体类型（DIGITAL_EMPLOYEE / CALLER / PLATFORM），可空；入 approvalKey owner 段
	 * @param ownerId 运行主体 ID，可空；入 approvalKey owner 段，两者缺一按无主体作用域处理
	 * @param runId 所属运行ID，可空（表列 NOT NULL，空按 0 落库表示无关联运行）；入 approvalKey 作用域
	 * @param stepKey 关联步骤键，可空
	 * @param capabilityCode 能力编码，必填
	 * @param paramsHash 参数指纹（与能力网关幂等键同源的参数 SHA-256 摘要），必填
	 * @param planHash 编译计划哈希，可空，入展示负载
	 * @param requestedBy 请求人ID，可空
	 * @param expiresAt 过期时间，空按默认时长常量计算
	 * @param source 审批来源（如 CAPABILITY_GATEWAY、TASK_RUN），入负载供消费方过滤
	 * @param sourceRefId 来源关联ID（如 taskRunId），可空
	 */
	record ApprovalCreateRequest(String tenantId, String ownerType, Long ownerId, Long runId, String stepKey,
			String capabilityCode, String paramsHash, String planHash, String requestedBy, Instant expiresAt,
			String source, String sourceRefId) {
	}

	/**
	 * 写工具 ASK 确认：按 toolCallId + 参数指纹建或复用审批，再一次性决定。同一指纹双击不新开单。
	 * 同时写入 v2 Redis 确认凭证（flag 开时），下一回合按 toolName + 参数指纹跳过 ASK。
	 */
	void decideToolConfirm(String tenantId, String actor, String toolCallId, String toolName, String paramFingerprint,
			boolean approved, String comment);

}
