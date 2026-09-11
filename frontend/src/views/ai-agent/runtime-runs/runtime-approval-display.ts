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
 * 运行时审批展示辅助与共享交互流程（详情抽屉审批区与「待审批」Tab 共用）。
 *
 * 红线（方案第十七章）：不展示内部 Agent/Skill/Artifact/版本 ID 与模型原始计划。
 * 审批 payload 为后端脱敏元数据（capabilityCode、workspaceId、planHash、source、sourceRefId），
 * 其中 workspaceId / planHash / sourceRefId 属内部 ID 或计划指纹，一律不展示；
 * 仅提取能力编码与来源作为业务描述（后端暂无能力中文名映射，先以编码展示）。
 */

import { ElMessage, ElMessageBox } from 'element-plus';
import agentApprovalService, {
  RUNTIME_APPROVAL_RISK_LABELS,
  RUNTIME_APPROVAL_STATE_LABELS,
  type RuntimeApprovalResp,
  type RuntimeApprovalRiskLevel,
  type RuntimeApprovalState
} from '@/views/ai-agent/services/agentApproval';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

export const approvalStateLabel = (state?: RuntimeApprovalState | string | null): string =>
  (state && RUNTIME_APPROVAL_STATE_LABELS[state as RuntimeApprovalState]) || state || '-';

export const approvalStateTagType = (
  state?: RuntimeApprovalState | string | null
): 'success' | 'danger' | 'warning' | 'info' | 'primary' => {
  switch (state) {
    case 'PENDING':
      return 'warning';
    case 'APPROVED':
      return 'success';
    case 'CONSUMED':
      return 'primary';
    case 'REJECTED':
      return 'danger';
    default:
      // EXPIRED / CANCELLED 等只读终态
      return 'info';
  }
};

export const approvalRiskLabel = (riskLevel?: RuntimeApprovalRiskLevel | string | null): string =>
  (riskLevel && RUNTIME_APPROVAL_RISK_LABELS[riskLevel as RuntimeApprovalRiskLevel]) || riskLevel || '-';

export const approvalRiskTagType = (
  riskLevel?: RuntimeApprovalRiskLevel | string | null
): 'danger' | 'warning' | 'info' => {
  switch (riskLevel) {
    case 'HIGH':
      return 'danger';
    case 'MEDIUM':
      return 'warning';
    default:
      return 'info';
  }
};

/** 审批 payload 中允许展示的业务字段白名单（内部 ID / 计划指纹一律过滤） */
const APPROVAL_PAYLOAD_DISPLAY_KEYS: ReadonlyArray<{ key: string; label: string }> = [
  { key: 'capabilityCode', label: '能力编码' },
  { key: 'source', label: '来源' }
];

/**
 * 从审批 payload（JSON 字符串）提取业务描述。
 * payload 解析失败或无白名单字段时回退到 approvalKey（能力编码 + 参数指纹，业务操作凭证可展示）。
 */
export const approvalBusinessSummary = (approval: RuntimeApprovalResp): string => {
  let parsed: unknown = null;
  if (approval.payload) {
    try {
      parsed = JSON.parse(approval.payload);
    } catch {
      parsed = null;
    }
  }
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    const record = parsed as Record<string, unknown>;
    const parts = APPROVAL_PAYLOAD_DISPLAY_KEYS.map(({ key, label }) => ({ label, value: record[key] }))
      .filter(({ value }) => typeof value === 'string' && value.trim().length > 0)
      .map(({ label, value }) => `${label}: ${String(value).slice(0, 80)}`);
    if (parts.length > 0) {
      return parts.join('；');
    }
  }
  return approval.approvalKey ? `凭证: ${approval.approvalKey}` : '-';
};

/**
 * 审批通过交互：意见选填 → 调用 approve。
 * 返回是否已成功提交（取消输入返回 false，不弹错误）。
 */
export async function approveWithPrompt(approval: RuntimeApprovalResp): Promise<boolean> {
  let comment = '';
  try {
    const { value } = await ElMessageBox.prompt(
      '审批通过后由能力网关一次性消费放行，参数变化后需重新发起审批。',
      `审批通过（审批ID: ${approval.id}）`,
      {
        confirmButtonText: '确认通过',
        cancelButtonText: '取消',
        inputPlaceholder: '审批意见（选填）',
        inputType: 'textarea'
      }
    );
    comment = value ?? '';
  } catch {
    return false;
  }
  try {
    await agentApprovalService.approve(approval.id, comment);
    ElMessage.success('审批已通过');
    return true;
  } catch (error) {
    // 懒惰过期判定等失败场景按后端中文报错展示
    console.error('审批通过失败, approvalId=%s:', approval.id, error);
    ElMessage.error(extractApiErrorMessage(error, '审批通过失败，请稍后重试'));
    return false;
  }
}

/**
 * 审批驳回交互：意见必填 → 二次确认 → 调用 reject。
 * 返回是否已成功提交（取消输入/取消确认返回 false，不弹错误）。
 */
export async function rejectWithPrompt(approval: RuntimeApprovalResp): Promise<boolean> {
  let comment = '';
  try {
    const { value } = await ElMessageBox.prompt(
      '驳回后该审批终止，如需继续执行需重新发起审批。',
      `审批驳回（审批ID: ${approval.id}）`,
      {
        confirmButtonText: '下一步',
        cancelButtonText: '取消',
        inputPlaceholder: '驳回意见（必填）',
        inputType: 'textarea',
        inputValidator: value => (value && value.trim().length > 0 ? true : '驳回意见必填')
      }
    );
    comment = value ?? '';
  } catch {
    return false;
  }
  try {
    await ElMessageBox.confirm(`确认驳回该审批？驳回意见：${comment.trim()}`, '确认驳回', {
      type: 'warning',
      confirmButtonText: '确认驳回',
      cancelButtonText: '再想想'
    });
  } catch {
    return false;
  }
  try {
    await agentApprovalService.reject(approval.id, comment);
    ElMessage.success('审批已驳回');
    return true;
  } catch (error) {
    console.error('审批驳回失败, approvalId=%s:', approval.id, error);
    ElMessage.error(extractApiErrorMessage(error, '审批驳回失败，请稍后重试'));
    return false;
  }
}
