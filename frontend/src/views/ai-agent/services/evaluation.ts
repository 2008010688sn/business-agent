/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { normalizeApiResponse, toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

export type EvalId = string;

export interface EvalPolicy {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  policyCode?: string;
  policyName?: string;
  versionNo?: number;
  status?: string;
  defaultFlag?: boolean;
  subjectType?: string;
  scoreWeightsJson?: string;
  graderConfigJson?: string;
  efficiencyBudgetJson?: string;
  gateRuleJson?: string;
  hardFailRuleJson?: string;
  description?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalPolicyRequest {
  policyCode?: string;
  policyName?: string;
  status?: string;
  defaultFlag?: boolean;
  subjectType?: string;
  scoreWeightsJson?: string;
  graderConfigJson?: string;
  efficiencyBudgetJson?: string;
  gateRuleJson?: string;
  hardFailRuleJson?: string;
  description?: string;
}

export interface EvalPolicyQueryRequest {
  current?: number;
  size?: number;
  policyCode?: string;
  policyName?: string;
  status?: string;
  subjectType?: string;
  defaultFlag?: boolean | '';
}

export interface EvalSubject {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  subjectName?: string;
  subjectType?: string;
  subjectId?: string;
  adapterCode?: string;
  status?: string;
  description?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalSubjectRequest {
  subjectName?: string;
  subjectType?: string;
  subjectId?: string;
  adapterCode?: string;
  status?: string;
  description?: string;
}

export interface EvalSubjectQueryRequest {
  current?: number;
  size?: number;
  subjectName?: string;
  subjectType?: string;
  subjectId?: string;
  adapterCode?: string;
  status?: string;
}

export interface EvalSuite {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  suiteName?: string;
  subjectId?: EvalId;
  policyId?: EvalId;
  status?: string;
  description?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalSuiteRequest {
  suiteName?: string;
  subjectId?: EvalId | '';
  policyId?: EvalId | '';
  status?: string;
  description?: string;
}

export interface EvalSuiteQueryRequest {
  current?: number;
  size?: number;
  suiteName?: string;
  subjectId?: EvalId | '';
  policyId?: EvalId | '';
  status?: string;
}

export interface EvalCase {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  suiteId?: EvalId;
  caseName?: string;
  userInput?: string;
  expectedOutput?: string;
  safetyConstraintsJson?: string;
  efficiencyBudgetJson?: string;
  tagsJson?: string;
  traceSnapshotJson?: string;
  status?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalCaseRequest {
  suiteId?: EvalId | '';
  caseName?: string;
  userInput?: string;
  expectedOutput?: string;
  safetyConstraintsJson?: string;
  efficiencyBudgetJson?: string;
  tagsJson?: string;
  traceSnapshotJson?: string;
  status?: string;
}

/**
 * 用例导入请求。两种取材方式二选一：
 * 聊天链路给 sessionId + runtimeRequestId；数字员工任务链路给 runtimeRunId。
 */
export interface EvalCaseImportRequest {
  suiteId?: EvalId | '';
  sessionId?: EvalId | '';
  runtimeRequestId?: string;
  /** 持久运行时运行 ID，从数字员工任务运行取材时传 */
  runtimeRunId?: EvalId | '';
  caseName?: string;
  expectedOutput?: string;
  tags?: string[];
}

export interface EvalCaseQueryRequest {
  current?: number;
  size?: number;
  suiteId?: EvalId | '';
  caseName?: string;
  status?: string;
  keyword?: string;
}

export interface EvalRun {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  suiteId?: EvalId;
  subjectId?: EvalId;
  policyId?: EvalId;
  policyVersionNo?: number;
  policyHash?: string;
  policySnapshotJson?: string;
  userIdSnapshot?: string;
  clientIdSnapshot?: string;
  teamIdsJson?: string;
  dataPermissionSnapshotJson?: string;
  modelConfigSnapshotJson?: string;
  agentConfigSnapshotJson?: string;
  /** 执行意图：LIVE-在线执行 / DRY_RUN-离线干跑（禁写、禁外部副作用） */
  executionIntent?: string;
  /** 写副作用违规数（DRY_RUN 拦截的写能力与外部副作用调用） */
  writeViolationCount?: number;
  /** 权限或租户隔离违规数（DRY_RUN 发现的越权/跨租户拒绝） */
  isolationViolationCount?: number;
  status?: string;
  totalCount?: number;
  successCount?: number;
  failedCount?: number;
  hardFailCount?: number;
  averageScore?: number;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalRunCreateRequest {
  suiteId?: EvalId | '';
  subjectId?: EvalId | '';
  policyId?: EvalId | '';
  /** 执行意图：LIVE（默认）或 DRY_RUN（离线干跑，禁写、禁外部副作用；自进化候选离线评估必须使用） */
  executionIntent?: string;
  /** 评估模式：REPLAY（回放历史，默认）或 INVOKE（SANDBOX 真跑）；数字员工进化门禁必须 INVOKE */
  evalMode?: string;
}

export interface EvalBootstrapRequest {
  agentId?: EvalId | '';
  /** 数字员工 ID，与 agentId 二选一 */
  employeeId?: EvalId | '';
  runNow?: boolean;
  caseUserInput?: string;
  expectedOutput?: string;
}

export interface EvalBootstrapItemStatus {
  id?: EvalId;
  status?: 'created' | 'reused' | string;
}

export interface EvalBootstrapResult {
  policyId?: EvalId;
  subjectId?: EvalId;
  suiteId?: EvalId;
  caseId?: EvalId;
  runId?: EvalId;
  policy?: EvalBootstrapItemStatus;
  subject?: EvalBootstrapItemStatus;
  suite?: EvalBootstrapItemStatus;
  evalCase?: EvalBootstrapItemStatus;
}

export interface EvalRunQueryRequest {
  current?: number;
  size?: number;
  suiteId?: EvalId | '';
  subjectId?: EvalId | '';
  status?: string;
  startTime?: string;
  endTime?: string;
}

export interface EvalCaseResult {
  id?: EvalId;
  tenantId?: string;
  tenantCode?: string;
  runId?: EvalId;
  suiteId?: EvalId;
  caseId?: EvalId;
  subjectId?: EvalId;
  attemptNo?: number;
  sessionId?: EvalId;
  threadId?: string;
  runtimeRequestId?: string;
  status?: string;
  userInput?: string;
  agentOutput?: string;
  expectedOutput?: string;
  score?: number;
  qualityScore?: number;
  safetyScore?: number;
  efficiencyScore?: number;
  stabilityScore?: number;
  hardFail?: boolean;
  /** 写副作用违规数（DRY_RUN 拦截） */
  writeViolationCount?: number;
  /** 权限或租户隔离违规数（DRY_RUN 发现） */
  isolationViolationCount?: number;
  /** DRY_RUN 违规明细 JSON（后端已排除参数值等敏感 payload） */
  violationDetailJson?: string;
  scoreDetailJson?: string;
  failureReasonsJson?: string;
  efficiencyMetricsJson?: string;
  traceSnapshotJson?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
  errorMessage?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EvalResultQueryRequest {
  current?: number;
  size?: number;
  runId?: EvalId | '';
  suiteId?: EvalId | '';
  caseId?: EvalId | '';
  subjectId?: EvalId | '';
  status?: string;
  hardFail?: boolean | '';
  keyword?: string;
}

export interface EvalResultDetail {
  result?: EvalCaseResult | null;
  diagnostics?: unknown;
}

export interface EvalRunEfficiencySummary {
  runId?: EvalId;
  totalCount?: number;
  averageDurationMs?: number;
  p90DurationMs?: number;
  maxDurationMs?: number;
  timeoutCount?: number;
  slowCount?: number;
  slowReasons?: Record<string, unknown>[];
}

export interface EvalRunFailureSummary {
  runId?: EvalId;
  failedCount?: number;
  hardFailCount?: number;
  failureReasons?: Record<string, unknown>[];
}

export interface EvalResultTrace {
  result?: EvalCaseResult | null;
  traceSnapshotJson?: string;
  sourceTrace?: unknown;
  sourceVisible?: boolean;
  sourceMessage?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent/evaluations';

class AgentEvaluationService {
  async queryPoliciesPage(query: EvalPolicyQueryRequest): Promise<PageResponse<EvalPolicy[]>> {
    const response = await Http.post(`${API_BASE_URL}/policies/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalPolicy>>);
  }

  async createPolicy(payload: EvalPolicyRequest): Promise<EvalPolicy> {
    const response = await Http.post(`${API_BASE_URL}/policies/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalPolicy>);
  }

  async updatePolicy(id: EvalId, payload: EvalPolicyRequest): Promise<EvalPolicy> {
    const response = await Http.put(
      `${API_BASE_URL}/policies/${encodeURIComponent(String(id))}/modify`,
      normalizeObject(payload)
    );
    return unwrapData(response as ServiceResponse<EvalPolicy>);
  }

  async querySubjectsPage(query: EvalSubjectQueryRequest): Promise<PageResponse<EvalSubject[]>> {
    const response = await Http.post(`${API_BASE_URL}/subjects/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalSubject>>);
  }

  async createSubject(payload: EvalSubjectRequest): Promise<EvalSubject> {
    const response = await Http.post(`${API_BASE_URL}/subjects/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalSubject>);
  }

  async updateSubject(id: EvalId, payload: EvalSubjectRequest): Promise<EvalSubject> {
    const response = await Http.put(
      `${API_BASE_URL}/subjects/${encodeURIComponent(String(id))}/modify`,
      normalizeObject(payload)
    );
    return unwrapData(response as ServiceResponse<EvalSubject>);
  }

  async querySuitesPage(query: EvalSuiteQueryRequest): Promise<PageResponse<EvalSuite[]>> {
    const response = await Http.post(`${API_BASE_URL}/suites/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalSuite>>);
  }

  async createSuite(payload: EvalSuiteRequest): Promise<EvalSuite> {
    const response = await Http.post(`${API_BASE_URL}/suites/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalSuite>);
  }

  async updateSuite(id: EvalId, payload: EvalSuiteRequest): Promise<EvalSuite> {
    const response = await Http.put(
      `${API_BASE_URL}/suites/${encodeURIComponent(String(id))}/modify`,
      normalizeObject(payload)
    );
    return unwrapData(response as ServiceResponse<EvalSuite>);
  }

  async queryCasesPage(query: EvalCaseQueryRequest): Promise<PageResponse<EvalCase[]>> {
    const response = await Http.post(`${API_BASE_URL}/cases/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalCase>>);
  }

  async createCase(payload: EvalCaseRequest): Promise<EvalCase> {
    const response = await Http.post(`${API_BASE_URL}/cases/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalCase>);
  }

  async updateCase(id: EvalId, payload: EvalCaseRequest): Promise<EvalCase> {
    const response = await Http.put(
      `${API_BASE_URL}/cases/${encodeURIComponent(String(id))}/modify`,
      normalizeObject(payload)
    );
    return unwrapData(response as ServiceResponse<EvalCase>);
  }

  async importCase(payload: EvalCaseImportRequest): Promise<EvalCase> {
    const response = await Http.post(`${API_BASE_URL}/cases/import`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalCase>);
  }

  async createRun(payload: EvalRunCreateRequest): Promise<EvalRun> {
    const response = await Http.post(`${API_BASE_URL}/runs/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalRun>);
  }

  async bootstrapDefaults(payload: EvalBootstrapRequest): Promise<EvalBootstrapResult> {
    const response = await Http.post(`${API_BASE_URL}/bootstrap/defaults`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalBootstrapResult>);
  }

  async cancelRun(id: EvalId): Promise<boolean> {
    const response = await Http.put(`${API_BASE_URL}/runs/${encodeURIComponent(String(id))}/status`, {
      status: 'cancelled'
    });
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }

  async queryRunsPage(query: EvalRunQueryRequest): Promise<PageResponse<EvalRun[]>> {
    const response = await Http.post(`${API_BASE_URL}/runs/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalRun>>);
  }

  async getRun(id: EvalId): Promise<EvalRun> {
    const response = await Http.get(`${API_BASE_URL}/runs/${encodeURIComponent(String(id))}/detail`);
    return unwrapData(response as ServiceResponse<EvalRun>);
  }

  async listRunResults(id: EvalId): Promise<EvalCaseResult[]> {
    const response = await Http.post(`${API_BASE_URL}/runs/results/query`, { runId: id });
    return unwrapDataOr(response as ServiceResponse<EvalCaseResult[]>, []);
  }

  async getRunEfficiencySummary(id: EvalId): Promise<EvalRunEfficiencySummary> {
    const response = await Http.post(`${API_BASE_URL}/runs/efficiency-summary/query`, { runId: id });
    return unwrapDataOr(response as ServiceResponse<EvalRunEfficiencySummary>, {});
  }

  async getRunFailureSummary(id: EvalId): Promise<EvalRunFailureSummary> {
    const response = await Http.post(`${API_BASE_URL}/runs/failure-summary/query`, { runId: id });
    return unwrapDataOr(response as ServiceResponse<EvalRunFailureSummary>, {});
  }

  async queryResultsPage(query: EvalResultQueryRequest): Promise<PageResponse<EvalCaseResult[]>> {
    const response = await Http.post(`${API_BASE_URL}/results/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<EvalCaseResult>>);
  }

  async getResultDetail(id: EvalId): Promise<EvalResultDetail> {
    const response = await Http.get(`${API_BASE_URL}/results/${encodeURIComponent(String(id))}/detail`);
    return unwrapDataOr(response as ServiceResponse<EvalResultDetail>, {});
  }

  async getResultTrace(id: EvalId): Promise<EvalResultTrace> {
    const response = await Http.get(`${API_BASE_URL}/results/${encodeURIComponent(String(id))}/trace`);
    return unwrapDataOr(response as ServiceResponse<EvalResultTrace>, {});
  }
}

const normalizePageQuery = <T extends { current?: number; size?: number }>(query: T): Partial<T> => {
  return normalizeObject({
    ...query,
    current: query.current || 1,
    size: query.size || 20
  });
};

const normalizeObject = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== '' && item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

export default new AgentEvaluationService();
