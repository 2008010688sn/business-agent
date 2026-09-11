/*
 * Copyright 2024-2025 the original author or authors.
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
import type { ApiResponse } from './common';
import { unwrapData } from './common';
import type { ModelConfigId } from './modelConfig';

export type AgentId = string;

export interface AgentTemporalPolicy {
  zoneId?: string;
  locale?: string;
  weekStartsOn?: string;
  ambiguityStrategy?: string;
}

export interface Agent {
  id?: AgentId;
  name?: string;
  description?: string;
  avatar?: string;
  avatarPreviewUrl?: string;
  agentType?: string;
  status?: string;
  apiKey?: string | null;
  apiKeyEnabled?: boolean;
  prompt?: string;
  adminId?: string;
  tags?: string;
  runtimeTimeoutSeconds?: number;
  reactMaxIterations?: number;
  maxModelCalls?: number;
  maxToolCalls?: number;
  maxPromptTokens?: number;
  chatModelConfigId?: ModelConfigId;
  createTime?: Date;
  // 服务端 DataAgent 继承 SuperEntity，更新时间字段名为 lastModifyTime；updateTime 服务端从未下发
  lastModifyTime?: Date;
  updateTime?: Date;
  humanReviewEnabled?: boolean;
  temporalPolicy?: AgentTemporalPolicy;
}

const API_BASE_URL = '/ai/data-agent';

export interface AgentApiKeyResponse {
  apiKey: string | null;
  apiKeyEnabled: boolean;
}

export type AgentApiKeyApiResult = ApiResponse<AgentApiKeyResponse>;

class AgentService {
  private listCache = new Map<string, Agent[]>();

  private listRequestCache = new Map<string, Promise<Agent[]>>();

  private getListCacheKey(status?: string, keyword?: string) {
    return `${status ?? ''}::${keyword ?? ''}`;
  }

  private clearListCache() {
    this.listCache.clear();
    this.listRequestCache.clear();
  }
  /**
   * 获取 Agent 列表
   * @param status 状态筛选
   * @param keyword 关键词搜索
   */
  async list(status?: string, keyword?: string): Promise<Agent[]> {
    const cacheKey = this.getListCacheKey(status, keyword);
    const cachedList = this.listCache.get(cacheKey);
    if (cachedList) {
      return cachedList.map(agent => ({ ...agent }));
    }

    const pendingRequest = this.listRequestCache.get(cacheKey);
    if (pendingRequest) {
      const agents = await pendingRequest;
      return agents.map(agent => ({ ...agent }));
    }

    const request = this.fetchList(status, keyword, cacheKey);
    this.listRequestCache.set(cacheKey, request);
    const agents = await request;
    return agents.map(agent => ({ ...agent }));
  }

  async refreshList(status?: string, keyword?: string): Promise<Agent[]> {
    this.clearListCache();
    return this.list(status, keyword);
  }

  private async fetchList(status: string | undefined, keyword: string | undefined, cacheKey: string): Promise<Agent[]> {
    const params: Record<string, string> = {};
    if (status) params.status = status;
    if (keyword) params.keyword = keyword;

    try {
      const response = await Http.post(`${API_BASE_URL}/query`, params);
      const agents = normalizeAgents(unwrapData(response as ApiResponse<Agent[]>));
      this.listCache.set(cacheKey, agents);
      return agents;
    } finally {
      this.listRequestCache.delete(cacheKey);
    }
  }
  /**
   * 根据 ID 获取 Agent 详情
   * @param id Agent ID
   */
  async get(id: AgentId): Promise<Agent | null> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${id}/detail`);
      return normalizeAgent(unwrapData(response as ApiResponse<Agent>));
    } catch (error) {
      if (isNotFoundError(error)) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 创建 Agent
   * @param agent Agent 对象
   */
  async create(agent: Omit<Agent, 'id'>): Promise<Agent> {
    // 设置默认状态为 draft
    const agentData = {
      ...agent,
      status: agent.status || 'draft'
    };

    const response = await Http.post(`${API_BASE_URL}/create`, agentData);
    const createdAgent = normalizeAgent(unwrapData(response as ApiResponse<Agent>));
    this.clearListCache();
    return createdAgent;
  }

  /**
   * 更新 Agent
   * @param id Agent ID
   * @param agent Agent 对象
   */
  async update(id: AgentId, agent: Partial<Agent>): Promise<Agent | null> {
    try {
      // 只传递可以修改的字段
      const agentData = {
        id: agent.id,
        name: agent.name,
        description: agent.description,
        avatar: agent.avatar,
        agentType: agent.agentType,
        chatModelConfigId: agent.chatModelConfigId,
        status: agent.status,
        prompt: agent.prompt,
        tags: agent.tags,
        runtimeTimeoutSeconds: agent.runtimeTimeoutSeconds,
        reactMaxIterations: agent.reactMaxIterations,
        maxModelCalls: agent.maxModelCalls,
        maxToolCalls: agent.maxToolCalls,
        maxPromptTokens: agent.maxPromptTokens,
        humanReviewEnabled: Boolean(agent.humanReviewEnabled),
        temporalPolicy: agent.temporalPolicy
      };
      const response = await Http.put(`${API_BASE_URL}/${id}/modify`, agentData);
      const updatedAgent = normalizeAgent(unwrapData(response as ApiResponse<Agent>));
      this.clearListCache();
      return updatedAgent;
    } catch (error) {
      if (isNotFoundError(error)) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 删除 Agent
   * @param id Agent ID
   */
  async delete(id: AgentId): Promise<boolean> {
    try {
      await Http.delete(`${API_BASE_URL}/${id}`);
      this.clearListCache();
      return true;
    } catch (error) {
      if (isNotFoundError(error)) {
        return false;
      }
      throw error;
    }
  }

  /**
   * 发布 Agent
   * @param id Agent ID
   */
  async publish(id: AgentId): Promise<Agent | null> {
    try {
      const response = await Http.put(`${API_BASE_URL}/${id}/status`, { status: 'published' });
      const publishedAgent = normalizeAgent(unwrapData(response as ApiResponse<Agent>));
      this.clearListCache();
      return publishedAgent;
    } catch (error) {
      if (isNotFoundError(error)) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 下线 Agent
   * @param id Agent ID
   */
  async offline(id: AgentId): Promise<Agent | null> {
    try {
      const response = await Http.put(`${API_BASE_URL}/${id}/status`, { status: 'offline' });
      const offlineAgent = normalizeAgent(unwrapData(response as ApiResponse<Agent>));
      this.clearListCache();
      return offlineAgent;
    } catch (error) {
      if (isNotFoundError(error)) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 获取 API Key（遮罩态）
   */
  async getApiKey(id: AgentId): Promise<AgentApiKeyResponse | null> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${id}/api-key`);
      return unwrapData(response as AgentApiKeyApiResult) ?? null;
    } catch (error) {
      if (isNotFoundError(error)) {
        return null;
      }
      throw error;
    }
  }

  /**
   * 生成/重置 API Key
   */
  async generateApiKey(id: AgentId): Promise<AgentApiKeyResponse> {
    const response = await Http.post(`${API_BASE_URL}/${id}/api-key/create`);
    return unwrapData(response as AgentApiKeyApiResult);
  }

  async resetApiKey(id: AgentId): Promise<AgentApiKeyResponse> {
    const response = await Http.put(`${API_BASE_URL}/${id}/api-key/modify`);
    return unwrapData(response as AgentApiKeyApiResult);
  }

  /**
   * 删除 API Key
   */
  async deleteApiKey(id: AgentId): Promise<AgentApiKeyResponse> {
    const response = await Http.delete(`${API_BASE_URL}/${id}/api-key`);
    return unwrapData(response as AgentApiKeyApiResult);
  }

  /**
   * 启用/禁用 API Key
   */
  async toggleApiKey(id: AgentId, enabled: boolean): Promise<AgentApiKeyResponse> {
    const response = await Http.put(`${API_BASE_URL}/${id}/api-key/status`, { enabled });
    return unwrapData(response as AgentApiKeyApiResult);
  }
}

const isNotFoundError = (error: unknown): boolean => {
  const status =
    (error as { response?: { status?: number }; status?: number })?.response?.status ??
    (error as { status?: number })?.status;
  return status === 404;
};

const normalizeOptionalNumber = (value?: number | null): number | undefined => {
  if (value === undefined || value === null) {
    return undefined;
  }
  const normalized = Number(value);
  return Number.isFinite(normalized) ? normalized : undefined;
};

export const normalizeAgent = (agent: Agent): Agent => ({
  ...agent,
  id: agent.id === undefined || agent.id === null ? undefined : String(agent.id),
  runtimeTimeoutSeconds:
    agent.runtimeTimeoutSeconds === undefined || agent.runtimeTimeoutSeconds === null
      ? 120
      : Number(agent.runtimeTimeoutSeconds),
  reactMaxIterations: normalizeOptionalNumber(agent.reactMaxIterations),
  maxModelCalls: normalizeOptionalNumber(agent.maxModelCalls),
  maxToolCalls: normalizeOptionalNumber(agent.maxToolCalls),
  maxPromptTokens: normalizeOptionalNumber(agent.maxPromptTokens),
  chatModelConfigId:
    agent.chatModelConfigId === undefined || agent.chatModelConfigId === null
      ? undefined
      : String(agent.chatModelConfigId),
  temporalPolicy: agent.temporalPolicy || {}
});

export const normalizeAgents = (agents: Agent[] = []): Agent[] => agents.map(normalizeAgent);

export default new AgentService();
