/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { normalizeApiResponse, toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

export type TokenUsageId = string;

export interface AgentTokenUsageQuery {
  current?: number;
  size?: number;
  tenantId?: string;
  userId?: string;
  agentId?: TokenUsageId | '';
  modelConfigId?: TokenUsageId | '';
  provider?: string;
  modelName?: string;
  usageSource?: string;
  meteringMode?: string;
  status?: string;
  requestSource?: string;
  runtimeRequestId?: string;
  rootRuntimeRequestId?: string;
  startTime?: string;
  endTime?: string;
  groupBy?: string;
}

export interface AgentTokenUsageSummary {
  requestCount?: number;
  successCount?: number;
  failedCount?: number;
  totalTokens?: number;
  promptTokens?: number;
  completionTokens?: number;
  actualTokens?: number;
  estimatedTokens?: number;
  unknownCount?: number;
  blockedCount?: number;
  averageTokens?: number;
}

export interface AgentTokenUsageBreakdown {
  groupKey?: string;
  groupName?: string;
  requestCount?: number;
  totalTokens?: number;
  promptTokens?: number;
  completionTokens?: number;
  actualTokens?: number;
  estimatedTokens?: number;
  unknownCount?: number;
}

export interface AgentTokenUsageOverview {
  summary?: AgentTokenUsageSummary;
  userBreakdown?: AgentTokenUsageBreakdown[];
  agentBreakdown?: AgentTokenUsageBreakdown[];
  modelBreakdown?: AgentTokenUsageBreakdown[];
  dayBreakdown?: AgentTokenUsageBreakdown[];
}

export interface AgentTokenUsage {
  id?: TokenUsageId;
  tenantId?: string;
  tenantCode?: string;
  userId?: string;
  userNickName?: string;
  clientId?: string;
  teamIdsJson?: string;
  agentId?: TokenUsageId;
  agentName?: string;
  sessionId?: TokenUsageId;
  threadId?: string;
  runtimeRequestId?: string;
  rootRuntimeRequestId?: string;
  parentRuntimeRequestId?: string;
  orchestrationRunId?: TokenUsageId;
  orchestrationStepId?: TokenUsageId;
  modelConfigId?: TokenUsageId;
  provider?: string;
  modelName?: string;
  modelType?: string;
  usageSource?: string;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  meteringMode?: string;
  status?: string;
  durationMs?: number;
  errorCode?: string;
  errorMessage?: string;
  requestSource?: string;
  cacheHit?: boolean;
  rawUsageJson?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface AgentUsageLimitPolicy {
  id?: TokenUsageId;
  scopeType?: string;
  scopeId?: string;
  userNickName?: string;
  agentId?: TokenUsageId;
  agentName?: string;
  modelConfigId?: TokenUsageId;
  policyType?: string;
  windowType?: string;
  windowSeconds?: number;
  limitValue?: number;
  warnThresholdRatio?: number;
  action?: string;
  enabled?: boolean;
  description?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface AgentUsageLimitPolicyQuery {
  current?: number;
  size?: number;
  scopeType?: string;
  scopeId?: string;
  agentId?: TokenUsageId | '';
  modelConfigId?: TokenUsageId | '';
  policyType?: string;
  enabled?: boolean | '';
}

export interface AgentUsageLimitPolicyRequest {
  scopeType?: string;
  scopeId?: string;
  agentId?: TokenUsageId | '';
  modelConfigId?: TokenUsageId | '';
  policyType?: string;
  windowType?: string;
  windowSeconds?: number;
  limitValue?: number;
  warnThresholdRatio?: number;
  action?: string;
  enabled?: boolean;
  description?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent/token-usage';

class AgentTokenUsageService {
  async queryOverview(query: AgentTokenUsageQuery): Promise<AgentTokenUsageOverview> {
    const response = await Http.post(`${API_BASE_URL}/overview`, normalizeObject(query));
    return unwrapDataOr(response as ServiceResponse<AgentTokenUsageOverview>, {});
  }

  async querySummary(query: AgentTokenUsageQuery): Promise<AgentTokenUsageSummary> {
    const response = await Http.post(`${API_BASE_URL}/summary`, normalizeObject(query));
    return unwrapDataOr(response as ServiceResponse<AgentTokenUsageSummary>, {});
  }

  async queryBreakdown(query: AgentTokenUsageQuery): Promise<AgentTokenUsageBreakdown[]> {
    const response = await Http.post(`${API_BASE_URL}/breakdown`, normalizeObject(query));
    return unwrapDataOr(response as ServiceResponse<AgentTokenUsageBreakdown[]>, []);
  }

  async queryDetailsPage(query: AgentTokenUsageQuery): Promise<PageResponse<AgentTokenUsage[]>> {
    const response = await Http.post(`${API_BASE_URL}/details/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<AgentTokenUsage>>);
  }

  async queryPoliciesPage(query: AgentUsageLimitPolicyQuery): Promise<PageResponse<AgentUsageLimitPolicy[]>> {
    const response = await Http.post(`${API_BASE_URL}/policies/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<AgentUsageLimitPolicy>>);
  }

  async createPolicy(payload: AgentUsageLimitPolicyRequest): Promise<AgentUsageLimitPolicy> {
    const response = await Http.post(`${API_BASE_URL}/policies/create`, normalizeObject(payload));
    return unwrapData(response as ServiceResponse<AgentUsageLimitPolicy>);
  }

  async updatePolicy(id: TokenUsageId, payload: AgentUsageLimitPolicyRequest): Promise<AgentUsageLimitPolicy> {
    const response = await Http.put(
      `${API_BASE_URL}/policies/${encodeURIComponent(String(id))}/modify`,
      normalizeObject(payload)
    );
    return unwrapData(response as ServiceResponse<AgentUsageLimitPolicy>);
  }

  async updatePolicyStatus(id: TokenUsageId, enabled: boolean): Promise<boolean> {
    const response = await Http.put(`${API_BASE_URL}/policies/${encodeURIComponent(String(id))}/status`, { enabled });
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }

  async deletePolicy(id: TokenUsageId): Promise<boolean> {
    const response = await Http.delete(`${API_BASE_URL}/policies/${encodeURIComponent(String(id))}`);
    return normalizeApiResponse(response as ServiceResponse<void>).success;
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

export default new AgentTokenUsageService();
