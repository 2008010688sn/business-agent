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
import { normalizeApiResponse, unwrapList } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { AgentId } from './agent';

interface PresetQuestion {
  id?: string;
  agentId: AgentId;
  question: string;
  sortOrder?: number;
  isActive?: boolean;
  createTime?: string;
  updateTime?: string;
}

interface PresetQuestionDTO {
  question: string;
  isActive?: boolean;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent/preset-questions';

class PresetQuestionService {
  private listCache = new Map<string, PresetQuestion[]>();

  private listRequestCache = new Map<string, Promise<PresetQuestion[]>>();

  private getCacheKey(agentId: AgentId): string {
    return String(agentId);
  }

  private clearListCache(agentId: AgentId) {
    const cacheKey = this.getCacheKey(agentId);
    this.listCache.delete(cacheKey);
    this.listRequestCache.delete(cacheKey);
  }

  async list(agentId: AgentId): Promise<PresetQuestion[]> {
    const cacheKey = this.getCacheKey(agentId);
    const cachedList = this.listCache.get(cacheKey);
    if (cachedList) {
      return [...cachedList];
    }

    const pendingRequest = this.listRequestCache.get(cacheKey);
    if (pendingRequest) {
      const list = await pendingRequest;
      return [...list];
    }

    const request = this.fetchList(agentId, cacheKey);
    this.listRequestCache.set(cacheKey, request);
    const list = await request;
    return [...list];
  }

  private async fetchList(agentId: AgentId, cacheKey: string): Promise<PresetQuestion[]> {
    try {
      const response = await Http.post(`${API_BASE_URL}/query`, { agentId });
      const list = unwrapList<PresetQuestion>(response);
      this.listCache.set(cacheKey, list);
      return list;
    } catch (error) {
      console.error('获取预设问题列表失败:', error);
      throw error;
    } finally {
      this.listRequestCache.delete(cacheKey);
    }
  }

  async batchSave(agentId: AgentId, questions: PresetQuestionDTO[]): Promise<boolean> {
    try {
      const questionsData = questions.map(q => ({
        question: q.question,
        isActive: q.isActive ?? true
      }));
      const response = await Http.put(`${API_BASE_URL}`, { agentId, questions: questionsData });
      const success = normalizeApiResponse(response as ServiceResponse<void>).success;
      if (success) {
        this.clearListCache(agentId);
      }
      return success;
    } catch (error) {
      console.error('保存预设问题失败:', error);
      throw error;
    }
  }

  async delete(agentId: AgentId, questionId: string): Promise<boolean> {
    try {
      const response = await Http.delete(`${API_BASE_URL}`, { agentId, questionId });
      const success = normalizeApiResponse(response as ServiceResponse<void>).success;
      if (success) {
        this.clearListCache(agentId);
      }
      return success;
    } catch (error) {
      console.error('删除预设问题失败:', error);
      throw error;
    }
  }
}

export type { PresetQuestion, PresetQuestionDTO };
export default new PresetQuestionService();
