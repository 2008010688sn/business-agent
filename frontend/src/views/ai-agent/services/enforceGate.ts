/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { unwrapData } from './common';
import type { ApiResponse, XxCloudResult } from './common';

/**
 * ENFORCE 门禁服务封装（PR-9 灰度 ENFORCE 门禁）。
 *
 * 对应后端 EnforceGateController（/authorization-enforce-gates）。
 * 操作语义：准入评估只读；放行登记（admit）在门禁通过后写入进程级登记簿，
 * 供 ENFORCE 切换保护比对；checklist 汇总供上线操作前后各查一次。
 *
 * @author Jack (PR-8 切片 3 Frontend)
 */

/**
 * 门禁结论码。
 * - ADMITTED：放行（影子差异率达到门禁口径）。
 * - REJECTED_GATE_DISABLED：门禁未启用，fail-closed 拒绝。
 * - REJECTED_REPORT_MISSING：最新影子差异报告缺失（404 语义），fail-closed 拒绝。
 * - REJECTED_TENANT_MISSING：报告中无该租户样本，按 fail-closed 拒绝。
 * - REJECTED_INSUFFICIENT_SAMPLES：可比样本量低于下限，不构成放行依据。
 * - REJECTED_THRESHOLD_EXCEEDED：差异率超过门禁阈值，须先完成差异归零治理。
 */
export type EnforceGateDecision =
  | 'ADMITTED'
  | 'REJECTED_GATE_DISABLED'
  | 'REJECTED_REPORT_MISSING'
  | 'REJECTED_TENANT_MISSING'
  | 'REJECTED_INSUFFICIENT_SAMPLES'
  | 'REJECTED_THRESHOLD_EXCEEDED';

/** ENFORCE 门禁单租户评估结果（放行决策证据链） */
export interface EnforceGateEvaluation {
  /** 租户 ID（与 enforceTenantIds 同口径） */
  tenantId?: string;
  /** 是否放行（true=可进入 ENFORCE 白名单） */
  allowed?: boolean;
  /** 评估结论码（ADMITTED 或 REJECTED_*） */
  decision?: EnforceGateDecision;
  /** 结论中文说明（拒绝时为明确的失败原因） */
  reason?: string;
  /** 评估依据：报告窗口起点（报告缺失时为 null） */
  reportWindowFrom?: string;
  /** 评估依据：报告窗口终点（报告缺失时为 null） */
  reportWindowTo?: string;
  /** 租户可比样本数（matched+mismatched；无样本时为 0） */
  comparable?: number;
  /** 判定一致数（MATCHED；无样本时为 0） */
  matched?: number;
  /** 判定不一致数（MISMATCHED；无样本时为 0） */
  mismatched?: number;
  /** 现网结论缺失数（ORIGINAL_ONLY，不进差异率分母） */
  originalOnly?: number;
  /** 影子差异率（mismatched/comparable；报告缺失时为 0） */
  mismatchRate?: number;
  /** 报告口径的 ENFORCE 门禁阈值（0~1） */
  enforceGateThreshold?: number;
  /** 报告口径的差异率达标标记（enforceGatePassed） */
  reportGatePassed?: boolean;
}

/** 门禁结论计数（checklist 快读） */
export interface EnforceGateDecisionCount {
  /** 结论码 */
  decision?: EnforceGateDecision;
  /** 租户数量 */
  count?: number;
}

/** ENFORCE 门禁灰度 checklist 汇总（上线操作前后各查一次） */
export interface EnforceGateOverview {
  /** 门禁是否启用（enforce-gate.enabled） */
  gateEnabled?: boolean;
  /** 可比样本下限（enforce-gate.minComparable） */
  minComparable?: number;
  /** 最新影子差异报告是否就绪（false 对应 latest 404） */
  reportPresent?: boolean;
  /** 最新报告生成时刻（报告缺失时为 null） */
  reportGeneratedAt?: string;
  /** 当前 ENFORCE 租户白名单快照 */
  enforceTenantIds?: string[];
  /** 本进程内已通过门禁放行登记的租户（进程重启后清零，须重新登记） */
  admittedTenantIds?: string[];
  /** 白名单逐租户门禁评估明细 */
  tenantEvaluations?: EnforceGateEvaluation[];
  /** 已 ENFORCE 但未在本进程完成门禁登记的租户（重启/新进白名单后须逐租户 admit） */
  unverifiedEnforcedTenantIds?: string[];
  /** 已放行登记但当前已不受 ENFORCE 保护的租户（静默回退，禁止发生） */
  silentRollbackTenantIds?: string[];
  /** checklist 是否通过（无静默回退且无未验证 ENFORCE 租户） */
  rolloutIntegrityPassed?: boolean;
  /** checklist 不通过时的整改项（中文，逐条列明） */
  integrityActionRequired?: string;
  /** 评估覆盖的结论分布提示（各结论码×数量） */
  decisionCounts?: EnforceGateDecisionCount[];
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/authorization-enforce-gates';

class EnforceGateService {
  /**
   * 查询 ENFORCE 门禁灰度 checklist（GET /overview）。
   * 白名单逐租户门禁评估 + 放行登记簿 + ENFORCE 切换保护检测。
   */
  async fetchOverview(): Promise<EnforceGateOverview> {
    const response = await Http.get(`${API_BASE_URL}/overview`);
    return unwrapData(response as ServiceResponse<EnforceGateOverview>);
  }

  /**
   * 评估租户 ENFORCE 准入（GET /tenants/{tenantId}/admission）。
   * 只读评估；报告缺失、租户样本缺失一律 fail-closed 拒绝，不做放行登记。
   */
  async evaluateAdmission(tenantId: string): Promise<EnforceGateEvaluation> {
    if (!tenantId) {
      throw new Error('租户 ID 不能为空');
    }
    const response = await Http.get(`${API_BASE_URL}/tenants/${encodeURIComponent(tenantId)}/admission`);
    return unwrapData(response as ServiceResponse<EnforceGateEvaluation>);
  }

  /**
   * 登记租户 ENFORCE 放行（POST /tenants/{tenantId}/admission）。
   * 评估不通过时后端抛业务异常（fail-closed，不能误放）；
   * 通过后写入进程级登记簿供切换保护比对；进程重启后须重建影子差异报告并重新登记。
   */
  async admitTenant(tenantId: string): Promise<EnforceGateEvaluation> {
    if (!tenantId) {
      throw new Error('租户 ID 不能为空');
    }
    const response = await Http.post(`${API_BASE_URL}/tenants/${encodeURIComponent(tenantId)}/admission`);
    return unwrapData(response as ServiceResponse<EnforceGateEvaluation>);
  }
}

export default new EnforceGateService();
