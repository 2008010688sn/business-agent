/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { unwrapData } from './common';
import type { ApiResponse, XxCloudResult } from './common';

/**
 * 授权影子差异报告服务封装。
 *
 * 对应后端 AuthorizationShadowReportController（/authorization-shadow-reports）。
 * 聚合 AUTHORIZATION_DECISION 影子事件，按租户输出 MATCHED/MISMATCHED/ORIGINAL_ONLY
 * 与差异率，附 MISMATCHED 样本明细（按 decisionId 审计口径）与任务观测段。
 *
 * @author Jack (PR-8 切片 3 Frontend)
 */

/** MISMATCHED 差异样本明细（decisionId 审计定位行） */
export interface ShadowMismatchSample {
  /** 授权决策 ID（贯穿影子日志与 invocation 审计列的对账定位键） */
  decisionId?: string;
  /** 租户 ID（事件表数字口径） */
  tenantId?: number;
  /** 参与判定的策略哈希（决策重放口径之一） */
  policyHash?: string;
  /** PDP 原因码（现网放行但 PDP 拒绝的拒绝原因） */
  reasonCode?: string;
  /** 比对状态（样本恒为 MISMATCHED） */
  comparisonStatus?: string;
  /** 比对时间戳 */
  comparisonTimestamp?: string;
}

/** SKIPPED 台账行聚合（租户 × 跳过原因 → 行数） */
export interface TaskSkipAggregate {
  /** 租户 ID */
  tenantId?: string;
  /** 跳过原因归类：SLOT_CONFLICT / OTHER */
  skipReason?: string;
  /** 窗口内 SKIPPED 行数 */
  total?: number;
}

/** 任务侧观测段（SKIPPED / 槽位冲突 / 受理延迟） */
export interface ShadowTaskObservation {
  /** 窗口内 SKIPPED 台账行总数 */
  skippedTotal?: number;
  /** 槽位冲突跳过数（原因码 CONCURRENT_SLOT_LOCKED） */
  slotConflictTotal?: number;
  /** 其他原因跳过数 */
  otherSkipTotal?: number;
  /** 任务受理延迟 P95（秒；无样本为 0） */
  workItemCreateP95Seconds?: number;
  /** 受理延迟聚合样本数 */
  workItemSampleTotal?: number;
  /** 按租户 × 原因的 SKIPPED 明细 */
  skipAggregates?: TaskSkipAggregate[];
}

/** 租户级影子比对汇总 */
export interface ShadowTenantSummary {
  /** 租户 ID（事件表数字口径，0 表示未解析租户） */
  tenantId?: string;
  /** 窗口内事件总数 */
  total?: number;
  /** 可比对事件数（matched + mismatched） */
  comparable?: number;
  /** 判定一致数（MATCHED） */
  matched?: number;
  /** 判定不一致数（MISMATCHED） */
  mismatched?: number;
  /** 现网结论缺失数（ORIGINAL_ONLY，不进差异率分母） */
  originalOnly?: number;
  /** 差异率（mismatched / comparable；comparable=0 时记 0） */
  mismatchRate?: number;
  /** 是否通过 ENFORCE 门禁（差异率 < 阈值） */
  enforceGatePassed?: boolean;
}

/** 授权影子差异比对报告（ENFORCE 灰度门禁数据来源） */
export interface ShadowDiffReport {
  /** 聚合窗口起点（含） */
  windowFrom?: string;
  /** 聚合窗口终点（不含） */
  windowTo?: string;
  /** 报告生成时刻 */
  generatedAt?: string;
  /** ENFORCE 门禁差异率阈值（0~1，默认 0.001 即 0.1%） */
  enforceGateThreshold?: number;
  /** 窗口内授权决策影子事件总数 */
  totalEvents?: number;
  /** 租户级差异汇总（按差异率降序） */
  tenants?: ShadowTenantSummary[];
  /** MISMATCHED 差异样本明细（限量，按时间倒序） */
  mismatchSamples?: ShadowMismatchSample[];
  /** 任务侧观测段（关闭时为 null） */
  taskObservation?: ShadowTaskObservation | null;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/authorization-shadow-reports';

class AuthorizationShadowReportService {
  /**
   * 查询最新影子差异报告（GET /latest）。
   * 尚未生成时后端返回 404（提示等待观测作业首轮执行或先手动重建）。
   */
  async fetchLatestReport(): Promise<ShadowDiffReport> {
    const response = await Http.get(`${API_BASE_URL}/latest`);
    return unwrapData(response as ServiceResponse<ShadowDiffReport>);
  }

  /**
   * 手动重建影子差异报告（POST /rebuild）。
   * 按当前配置窗口（默认近 24h）立即重算并覆盖最新；幂等只读聚合。
   */
  async rebuildReport(): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/rebuild`);
    unwrapData(response as ServiceResponse<void>);
  }
}

export default new AuthorizationShadowReportService();
