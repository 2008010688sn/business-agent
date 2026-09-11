/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import { toPageResponse, unwrapData, unwrapList } from './common';

export interface ToolResource {
  id?: string;
  resourceType: string;
  resourceKey: string;
  resourceName?: string;
  source?: string;
  sourceType?: string;
  serverCode?: string;
  serviceName?: string;
  baseUrl?: string;
  toolName?: string;
  endpointUrl?: string;
  httpMethod?: string;
  headerTemplate?: Record<string, unknown>;
  authType?: string;
  credentialRef?: string;
  paramMapping?: Record<string, unknown>;
  requestTemplate?: Record<string, unknown>;
  responseMapping?: Record<string, unknown>;
  enabled?: boolean;
  status?: string;
  displayOrder?: number;
  extConfig?: Record<string, unknown>;
}

export interface McpServer {
  id?: string;
  serverCode: string;
  serviceName?: string;
  endpointPath?: string;
  transportType?: string;
  baseUrl?: string;
  status?: string;
  metadata?: string;
}

export interface ToolVersion {
  id: string;
  resourceId: string;
  resourceKey: string;
  versionNo: number;
  status: string;
  accessMode: 'READ' | 'WRITE' | string;
  exposureMode: 'MODEL' | 'FLOW_ONLY' | string;
  permissionCode?: string;
  confirmRequired?: boolean;
  idempotencyRequired?: boolean;
  timeoutMs?: number;
  inputSchema?: string | Record<string, unknown>;
  outputSchema?: string | Record<string, unknown>;
  runtimeParamMappings?: string | Record<string, unknown>;
  responseMappings?: string | Record<string, unknown>;
  sensitiveFields?: string | string[];
  publishedAt?: string;
}

export interface ToolDetail {
  resource: ToolResource;
  versions: ToolVersion[];
}

export interface ToolPageQuery {
  current?: number;
  size?: number;
  keyword?: string;
  resourceType?: string;
  status?: string;
}

export interface ToolVersionPublishPayload {
  accessMode: 'READ' | 'WRITE';
  exposureMode: 'MODEL' | 'FLOW_ONLY';
  permissionCode?: string;
  confirmRequired: boolean;
  idempotencyRequired: boolean;
  timeoutMs: number;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  runtimeParamMappings: Record<string, string>;
  responseMappings: Record<string, unknown>;
  sensitiveFields: string[];
}

export interface ToolTestPayload {
  resourceVersionId: string;
  arguments: Record<string, unknown>;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

class ToolService {
  async page(query: ToolPageQuery): Promise<PageResponse<ToolResource[]>> {
    const response = await Http.post('/ai/tools/page', query);
    return toPageResponse(response as ServiceResponse<MybatisPage<ToolResource>>);
  }

  async detail(resourceKey: string): Promise<ToolDetail> {
    const response = await Http.get(`/ai/tools/${encodeURIComponent(resourceKey)}/detail`);
    return unwrapData(response as ServiceResponse<ToolDetail>);
  }

  async create(payload: ToolResource): Promise<ToolResource> {
    const response = await Http.post('/ai/tools/create', payload);
    return unwrapData(response as ServiceResponse<ToolResource>);
  }

  async modify(resourceKey: string, payload: ToolResource): Promise<ToolResource> {
    const response = await Http.put(`/ai/tools/${encodeURIComponent(resourceKey)}/modify`, payload);
    return unwrapData(response as ServiceResponse<ToolResource>);
  }

  async publish(resourceKey: string, payload: ToolVersionPublishPayload): Promise<ToolVersion> {
    const response = await Http.put(`/ai/tools/${encodeURIComponent(resourceKey)}/publish`, payload);
    return unwrapData(response as ServiceResponse<ToolVersion>);
  }

  async test(resourceKey: string, payload: ToolTestPayload): Promise<Record<string, unknown>> {
    const response = await Http.post(`/ai/tools/${encodeURIComponent(resourceKey)}/test`, payload);
    return unwrapData(response as ServiceResponse<Record<string, unknown>>);
  }

  async delete(resourceKey: string): Promise<void> {
    await Http.delete(`/ai/tools/${encodeURIComponent(resourceKey)}`);
  }

  async syncMcp(): Promise<void> {
    await Http.post('/ai/tools/mcp/sync', {});
  }

  async listMcpServers(): Promise<McpServer[]> {
    const response = await Http.get('/ai/tools/mcp/servers');
    return unwrapList<McpServer>(response as ServiceResponse<unknown>);
  }
}

export default new ToolService();
