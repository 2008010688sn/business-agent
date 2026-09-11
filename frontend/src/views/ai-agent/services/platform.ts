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

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapList } from '@/views/ai-agent/services/common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from '@/views/ai-agent/services/common';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

export interface RuntimeHook {
  id?: string;
  hookCode?: string;
  hookName?: string;
  eventType?: string;
  agentId?: string | null;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  actionType?: string;
  actionConfig?: Record<string, unknown>;
  asyncEnabled?: boolean;
  continueOnError?: boolean;
  status?: string;
  displayOrder?: number;
  extConfig?: Record<string, unknown>;
}

export interface RuntimeHookPageQuery {
  current?: number;
  size?: number;
  eventType?: string;
  agentId?: string | null;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
}

export interface RuntimeHookLogPageQuery {
  current?: number;
  size?: number;
  hookCode?: string;
  eventType?: string;
  status?: string;
  agentId?: string | null;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  runtimeRequestId?: string;
}

export interface RuntimeHookLog {
  id?: string;
  hookCode?: string;
  eventType?: string;
  status?: string;
  actionType?: string;
  toolKey?: string;
  agentId?: string;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  idempotencyKey?: string;
  requestSummary?: string;
  responseSummary?: string;
  errorMessage?: string;
  elapsedMs?: number;
  createTime?: string;
}

export interface McpExposure {
  id?: string;
  exposureCode?: string;
  exposureName?: string;
  toolKey?: string;
  exposedToolName?: string;
  exposureType?: string;
  riskLevel?: string;
  requireUserContext?: boolean;
  status?: string;
  displayOrder?: number;
  extConfig?: Record<string, unknown>;
}

export interface McpExposurePageQuery {
  current?: number;
  size?: number;
  toolKey?: string;
  status?: string;
  keyword?: string;
}

export interface McpExposureRuntimeSyncResult {
  localSynced: boolean;
  notifiedPeerCount: number;
  eventId: string;
  syncedAt: string;
}

const HOOK_BASE = '/ai/runtime-hooks';
const MCP_EXPOSURE_BASE = '/ai/mcp-exposures';

const compact = (params?: Record<string, unknown>) =>
  Object.entries(params || {}).reduce<Record<string, unknown>>((result, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      result[key] = value;
    }
    return result;
  }, {});

const normalizePageQuery = <T extends object>(query: T): Partial<T> =>
  Object.entries(query || {}).reduce<Partial<T>>((result, [key, value]) => {
    if (value === undefined || value === null) {
      return result;
    }
    if (typeof value === 'string') {
      const normalized = value.trim();
      if (!normalized) {
        return result;
      }
      result[key as keyof T] = normalized as T[keyof T];
      return result;
    }
    result[key as keyof T] = value as T[keyof T];
    return result;
  }, {});

class PlatformService {
  async listRuntimeHooks(params?: Record<string, unknown>): Promise<RuntimeHook[]> {
    const response = await Http.post(`${HOOK_BASE}/query`, compact(params));
    return unwrapList<RuntimeHook>(response as ServiceResponse<unknown>);
  }

  async pageRuntimeHooks(query: RuntimeHookPageQuery): Promise<PageResponse<RuntimeHook[]>> {
    const response = await Http.post(`${HOOK_BASE}/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<RuntimeHook>>);
  }

  async createRuntimeHook(request: RuntimeHook): Promise<RuntimeHook> {
    const response = await Http.post(`${HOOK_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<RuntimeHook>);
  }

  async updateRuntimeHook(id: string, request: RuntimeHook): Promise<RuntimeHook> {
    const response = await Http.put(`${HOOK_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<RuntimeHook>);
  }

  async deleteRuntimeHook(id: string): Promise<void> {
    await Http.delete(`${HOOK_BASE}/${id}`);
  }

  async pageRuntimeHookLogs(query: RuntimeHookLogPageQuery): Promise<PageResponse<RuntimeHookLog[]>> {
    const response = await Http.post(`${HOOK_BASE}/logs/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<RuntimeHookLog>>);
  }

  async pageMcpExposures(query: McpExposurePageQuery): Promise<PageResponse<McpExposure[]>> {
    const response = await Http.post(`${MCP_EXPOSURE_BASE}/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<McpExposure>>);
  }

  async createMcpExposure(request: McpExposure): Promise<McpExposure> {
    const response = await Http.post(`${MCP_EXPOSURE_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<McpExposure>);
  }

  async updateMcpExposure(id: string, request: McpExposure): Promise<McpExposure> {
    const response = await Http.put(`${MCP_EXPOSURE_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<McpExposure>);
  }

  async deleteMcpExposure(id: string): Promise<void> {
    await Http.delete(`${MCP_EXPOSURE_BASE}/${id}`);
  }

  async syncMcpExposureRuntime(): Promise<McpExposureRuntimeSyncResult> {
    const response = await Http.post(`${MCP_EXPOSURE_BASE}/runtime/sync`);
    return unwrapData(response as ServiceResponse<McpExposureRuntimeSyncResult>);
  }
}

export const platformService = new PlatformService();

export default platformService;
