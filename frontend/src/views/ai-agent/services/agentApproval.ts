/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * 运行时审批（agent-approvals）服务：高风险能力调用与 ASSISTED 任务的人工审批。
 *
 * 后端契约：agent-backend AgentApprovalController（/agent-approvals/*），
 * TypeScript 类型按 runtime/durable/dto 下 RuntimeApprovalPageQueryReq / RuntimeApprovalResp /
 * RuntimeApprovalDecisionReq 定义；Long 类 ID 后端 ToStringSerializer 序列化为字符串。
 *
 * 状态机：PENDING → APPROVED / REJECTED / EXPIRED / CANCELLED；APPROVED → CONSUMED（一次性消费）或 EXPIRED。
 * 过期为懒惰判定（读取时才置 EXPIRED），列表里的 PENDING 可能已实际过期，
 * 操作失败时以后端返回的中文报错为准展示。
 *
 * 权限码（需 IAM 注册）：ai-agent:approval:query（分页/详情）、ai-agent:approval:review（通过/驳回）。
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

/** 后端 Long ID 经 ToStringSerializer 序列化为字符串 */
export type RuntimeApprovalId = string;

/** 审批状态，对应后端 RuntimeApprovalState */
export type RuntimeApprovalState = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED' | 'CONSUMED';

/** 风险等级，对应后端审批记录 riskLevel 字段 */
export type RuntimeApprovalRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

/** 分页查询请求，对应后端 RuntimeApprovalPageQueryReq（继承 PageRequest：current/size） */
export interface RuntimeApprovalPageQueryReq {
  current?: number;
  size?: number;
  state?: RuntimeApprovalState | '';
  /** 所属运行ID，"0" 表示无关联运行 */
  runId?: RuntimeApprovalId;
  stepKey?: string;
  riskLevel?: RuntimeApprovalRiskLevel | '';
  /** 审批幂等键（能力编码 + 参数指纹） */
  approvalKey?: string;
}

/** 审批响应，对应后端 RuntimeApprovalResp */
export interface RuntimeApprovalResp {
  id: RuntimeApprovalId;
  /** 所属运行ID，"0" 表示无关联运行 */
  runId?: RuntimeApprovalId | null;
  stepKey?: string | null;
  /** 审批幂等键（能力编码 + 参数指纹），业务操作凭证可展示 */
  approvalKey?: string | null;
  riskLevel?: RuntimeApprovalRiskLevel | string | null;
  state: RuntimeApprovalState | string;
  /** 请求参数摘要，参数变化后旧审批失效 */
  requestDigest?: string | null;
  /** 审批展示负载 JSON 字符串（脱敏后的操作说明），需 JSON.parse 后按白名单提取 */
  payload?: string | null;
  requestedBy?: string | null;
  approver?: string | null;
  decidedAt?: string | null;
  expiresAt?: string | null;
  decisionComment?: string | null;
  createTime?: string | null;
}

export const RUNTIME_APPROVAL_STATE_LABELS: Record<RuntimeApprovalState, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  CANCELLED: '已取消',
  CONSUMED: '已消费'
};

export const RUNTIME_APPROVAL_RISK_LABELS: Record<RuntimeApprovalRiskLevel, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险'
};

export const isPendingApprovalState = (state?: RuntimeApprovalState | string | null): boolean => state === 'PENDING';

export interface ToolConfirmDecisionReq {
  toolCallId: string;
  toolName?: string;
  paramFingerprint: string;
  replyId?: string;
  comment?: string;
}

const BASE_URL = '/ai/agent-approvals';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

class AgentApprovalService {
  /** 分页查询审批记录（state/runId/stepKey/riskLevel/approvalKey 过滤，字段以后端 dto 为准） */
  async page(query: RuntimeApprovalPageQueryReq): Promise<PageResponse<RuntimeApprovalResp[]>> {
    const response = await Http.post(`${BASE_URL}/page`, query);
    return toPageResponse(response as ServiceResponse<MybatisPage<RuntimeApprovalResp>>);
  }

  /** 查询审批详情：读取时后端执行懒惰过期判定，已过期记录置为 EXPIRED */
  async detail(id: RuntimeApprovalId): Promise<RuntimeApprovalResp> {
    const response = await Http.get(`${BASE_URL}/${id}/detail`);
    return unwrapData(response as ServiceResponse<RuntimeApprovalResp>);
  }

  /** 审批通过：PENDING → APPROVED，意见选填；批复与参数指纹绑定且一次性消费 */
  async approve(id: RuntimeApprovalId, comment?: string): Promise<void> {
    const trimmed = comment?.trim();
    const response = await Http.post(`${BASE_URL}/${id}/approve`, trimmed ? { comment: trimmed } : {});
    unwrapData(response as ServiceResponse<void>);
  }

  /** 审批驳回：PENDING → REJECTED，意见必填（服务端强校验） */
  async reject(id: RuntimeApprovalId, comment: string): Promise<void> {
    const response = await Http.post(`${BASE_URL}/${id}/reject`, { comment: comment.trim() });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 写工具 ASK 确认通过：绑定 toolCallId + 参数指纹，审批表一次性消费 */
  async approveToolConfirm(body: ToolConfirmDecisionReq): Promise<void> {
    const response = await Http.post(`${BASE_URL}/tool-confirms/approve`, body);
    unwrapData(response as ServiceResponse<void>);
  }

  /** 写工具 ASK 确认驳回：意见必填 */
  async rejectToolConfirm(body: ToolConfirmDecisionReq): Promise<void> {
    const response = await Http.post(`${BASE_URL}/tool-confirms/reject`, body);
    unwrapData(response as ServiceResponse<void>);
  }
}

export default new AgentApprovalService();
