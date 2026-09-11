/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import type { EvalRun } from './evaluation';

export type OptId = string;

export interface OptExperiment {
  id?: OptId;
  tenantId?: string;
  tenantCode?: string;
  experimentName?: string;
  subjectId?: OptId;
  baselineRunId?: OptId;
  status?: string;
  baselineMetricsJson?: string;
  diagnosisJson?: string;
  description?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface OptExperimentRequest {
  experimentName?: string;
  subjectId?: OptId | '';
  baselineRunId?: OptId | '';
  description?: string;
}

export interface OptExperimentQueryRequest {
  current?: number;
  size?: number;
  experimentName?: string;
  subjectId?: OptId | '';
  status?: string;
}

export interface OptCandidate {
  id?: OptId;
  tenantId?: string;
  tenantCode?: string;
  experimentId?: OptId;
  candidateName?: string;
  targetType?: string;
  targetId?: string;
  patchSchemaVersion?: string;
  targetVersion?: string;
  beforeHash?: string;
  afterHash?: string;
  changeReason?: string;
  expectedImpact?: string;
  patchJson?: string;
  beforeSnapshotJson?: string;
  rollbackPatchJson?: string;
  riskLevel?: string;
  generatedBy?: string;
  sandboxRunId?: OptId;
  gateResultJson?: string;
  status?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface OptCandidateRequest {
  experimentId?: OptId | '';
  candidateName?: string;
  targetType?: string;
  targetId?: string;
  patchSchemaVersion?: string;
  targetVersion?: string;
  beforeHash?: string;
  afterHash?: string;
  changeReason?: string;
  expectedImpact?: string;
  patchJson?: string;
  beforeSnapshotJson?: string;
  rollbackPatchJson?: string;
  riskLevel?: string;
  generatedBy?: string;
}

export interface OptCandidateEval {
  id?: OptId;
  experimentId?: OptId;
  candidateId?: OptId;
  baselineRunId?: OptId;
  sandboxRunId?: OptId;
  scoreDelta?: number;
  passRateDelta?: number;
  durationDeltaPct?: number;
  tokenDeltaPct?: number;
  /** 候选运行写副作用违规数（DRY_RUN 拦截，硬门禁须为 0） */
  writeViolationCount?: number;
  /** 候选运行权限或租户隔离违规数（硬门禁须为 0） */
  isolationViolationCount?: number;
  /** 分片评估结果 JSON（按租户/岗位/业务类型标签分片对比） */
  shardResultJson?: string;
  passed?: boolean;
  compareJson?: string;
  status?: string;
  createTime?: string;
}

export interface OptCandidateEvalRequest {
  candidateId?: OptId | '';
  sandboxRunId?: OptId | '';
}

/**
 * 分片评估结果行（后端按租户 tenant: / 岗位 position: / bizType: 标签分片对比）。
 * 用例未标注分片维度时后端如实降级：仅返回 degraded=true + reason，一行。
 */
export interface OptShardResult {
  shardKey?: string;
  baselineTotal?: number;
  baselinePassRate?: number | null;
  candidateTotal?: number;
  candidatePassRate?: number | null;
  regressed?: boolean;
  degraded?: boolean;
  reason?: string;
}

export interface OptCandidateCompare {
  candidateId?: OptId;
  baselineRunId?: OptId;
  sandboxRunId?: OptId;
  baselineScore?: number;
  candidateScore?: number;
  scoreDelta?: number;
  baselinePassRate?: number;
  candidatePassRate?: number;
  passRateDelta?: number;
  durationDeltaPct?: number;
  tokenDeltaPct?: number;
  /** 候选运行执行意图；离线评估必须为 DRY_RUN，否则后端记入门禁原因 */
  sandboxExecutionIntent?: string;
  /** 候选运行写副作用违规数（硬门禁须为 0） */
  writeViolationCount?: number;
  /** 候选运行权限或租户隔离违规数（硬门禁须为 0） */
  isolationViolationCount?: number;
  /** 分片评估结果；无分片维度时仅一行 degraded 记录 */
  shardResults?: OptShardResult[];
  passed?: boolean;
  gateReasons?: string[];
}

/** 候选离线评估发起请求：执行意图由服务端强制 DRY_RUN，前端不可传。 */
export interface OptOfflineEvalRequest {
  candidateId?: OptId | '';
  suiteId?: OptId | '';
  policyId?: OptId | '';
}

/**
 * LOOP 阶段项。stage 取值：OBSERVE / DIAGNOSE / GENERATE_CANDIDATE / OFFLINE_EVAL_DRY_RUN /
 * HUMAN_APPROVE / RELEASE_DRAFT / SHADOW / CANARY / MONITOR_ROLLBACK；
 * status 取值：DONE / IN_PROGRESS / PENDING / NOT_IMPLEMENTED / STANDBY / ROLLED_BACK。
 * detail 为后端 Map（键随阶段而异，如 baselineRunId / candidateCount / productionReleaseIds / note）。
 */
export interface OptLoopStage {
  stage?: string;
  status?: string;
  detail?: Record<string, unknown>;
}

/** 自进化 LOOP 编排状态（GET /loop/{experimentId}/status）。 */
export interface OptLoopStatus {
  experimentId?: OptId;
  experimentStatus?: string;
  stages?: OptLoopStage[];
  /** 后端给审批人的下一步人工操作指引，原样展示 */
  nextAction?: string;
}

export interface OptExperimentDetail {
  experiment?: OptExperiment;
  candidates?: OptCandidate[];
  latestCandidateEvaluations?: OptCandidateEval[];
}

export interface OptRelease {
  id?: OptId;
  experimentId?: OptId;
  candidateId?: OptId;
  sourceReleaseId?: OptId;
  /** 审批通过后创建的 DataAgentRelease（DRAFT）ID，生产发布载体，用于跳转发布管理 */
  productionReleaseId?: OptId;
  actionType?: string;
  status?: string;
  beforeHash?: string;
  afterHash?: string;
  operatorId?: string;
  reason?: string;
  approvalJson?: string;
  createTime?: string;
}

export interface OptReleaseApprovalRequest {
  candidateId?: OptId | '';
  currentHash?: string;
  reason?: string;
  approvalJson?: string;
}

export interface OptRollbackRequest {
  releaseId?: OptId | '';
  reason?: string;
  approvalJson?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent/optimizations';

class AgentOptimizationService {
  async createExperiment(payload: OptExperimentRequest): Promise<OptExperiment> {
    const response = await Http.post(`${API_BASE_URL}/experiments/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<OptExperiment>);
  }

  async queryExperimentsPage(query: OptExperimentQueryRequest): Promise<PageResponse<OptExperiment[]>> {
    const response = await Http.post(`${API_BASE_URL}/experiments/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<OptExperiment>>);
  }

  async getExperimentDetail(id: OptId): Promise<OptExperimentDetail> {
    const response = await Http.get(`${API_BASE_URL}/experiments/${encodeURIComponent(String(id))}/detail`);
    return unwrapDataOr(response as ServiceResponse<OptExperimentDetail>, {});
  }

  async diagnoseExperiment(id: OptId): Promise<OptExperiment> {
    const response = await Http.post(`${API_BASE_URL}/experiments/diagnoses/create`, { experimentId: id });
    return unwrapData(response as ServiceResponse<OptExperiment>);
  }

  async createCandidate(experimentId: OptId, payload: OptCandidateRequest): Promise<OptCandidate> {
    const response = await Http.post(
      `${API_BASE_URL}/candidates/create`,
      normalizeObject({
        ...payload,
        experimentId
      })
    );
    return unwrapData(response as ServiceResponse<OptCandidate>);
  }

  async evaluateCandidate(candidateId: OptId, payload: OptCandidateEvalRequest): Promise<OptCandidateEval> {
    const response = await Http.post(
      `${API_BASE_URL}/candidate-evaluations/create`,
      normalizeObject({
        ...payload,
        candidateId
      })
    );
    return unwrapData(response as ServiceResponse<OptCandidateEval>);
  }

  async getCandidateCompare(candidateId: OptId): Promise<OptCandidateCompare> {
    const response = await Http.get(`${API_BASE_URL}/candidates/${encodeURIComponent(String(candidateId))}/compare`);
    return unwrapDataOr(response as ServiceResponse<OptCandidateCompare>, {});
  }

  async createReleaseApproval(candidateId: OptId, payload: OptReleaseApprovalRequest): Promise<OptRelease> {
    const response = await Http.post(
      `${API_BASE_URL}/release-approvals/create`,
      normalizeObject({
        ...payload,
        candidateId
      })
    );
    return unwrapData(response as ServiceResponse<OptRelease>);
  }

  async recordRollback(releaseId: OptId, payload: OptRollbackRequest): Promise<OptRelease> {
    const response = await Http.post(
      `${API_BASE_URL}/rollback-records/create`,
      normalizeObject({
        ...payload,
        releaseId
      })
    );
    return unwrapData(response as ServiceResponse<OptRelease>);
  }

  /**
   * 发起候选离线评估（LOOP OfflineEval 阶段）。
   * 执行意图由服务端强制 DRY_RUN（禁写、禁外部副作用，违规记入发布门禁），前端不传意图。
   * 权限码：ai-agent:optimization:loop。
   */
  async startCandidateOfflineEvaluation(payload: OptOfflineEvalRequest): Promise<EvalRun> {
    const response = await Http.post(`${API_BASE_URL}/loop/offline-evaluations/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<EvalRun>);
  }

  /**
   * 查询自进化 LOOP 编排状态（按实验）。权限码：ai-agent:optimization:loop。
   * Shadow / Canary 后端返回 NOT_IMPLEMENTED，前端如实展示，不伪装为已完成。
   */
  async getLoopStatus(experimentId: OptId): Promise<OptLoopStatus> {
    const response = await Http.get(`${API_BASE_URL}/loop/${encodeURIComponent(String(experimentId))}/status`);
    return unwrapDataOr(response as ServiceResponse<OptLoopStatus>, {});
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

export default new AgentOptimizationService();
