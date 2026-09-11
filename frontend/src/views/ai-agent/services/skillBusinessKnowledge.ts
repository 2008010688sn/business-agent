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
import { getResponseStatus, normalizeApiResponse, unwrapData, unwrapList } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { SkillId } from './skill';

interface BusinessKnowledgeVO {
  id?: string;
  businessTerm: string;
  description: string;
  synonyms: string;
  isRecall: boolean;
  createdTime?: string;
  updatedTime?: string;
  embeddingStatus?: string;
  errorMsg?: string;
}

interface CreateBusinessKnowledgeDTO {
  businessTerm: string;
  description: string;
  synonyms: string;
  isRecall: boolean;
}

interface UpdateBusinessKnowledgeDTO {
  businessTerm: string;
  description: string;
  synonyms: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const resourceUrl = (skillId: SkillId): string =>
  `/ai/skills/${encodeURIComponent(String(skillId))}/resources/business-knowledge`;

class SkillBusinessKnowledgeService {
  async list(skillId: SkillId, keyword?: string): Promise<BusinessKnowledgeVO[]> {
    const response = await Http.get(resourceUrl(skillId), keyword ? { keyword } : {});
    return unwrapList<BusinessKnowledgeVO>(response);
  }

  async get(skillId: SkillId, id: string): Promise<BusinessKnowledgeVO | null> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/${id}`);
      return unwrapData(response as ServiceResponse<BusinessKnowledgeVO>) || null;
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return null;
      }
      throw error;
    }
  }

  async create(skillId: SkillId, knowledge: CreateBusinessKnowledgeDTO): Promise<BusinessKnowledgeVO> {
    const response = await Http.post(resourceUrl(skillId), knowledge);
    return unwrapData(response as ServiceResponse<BusinessKnowledgeVO>);
  }

  async update(
    skillId: SkillId,
    id: string,
    knowledge: UpdateBusinessKnowledgeDTO
  ): Promise<BusinessKnowledgeVO | null> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${id}`, knowledge);
      return unwrapData(response as ServiceResponse<BusinessKnowledgeVO>) || null;
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return null;
      }
      throw error;
    }
  }

  async delete(skillId: SkillId, id: string): Promise<boolean> {
    try {
      const response = await Http.delete(`${resourceUrl(skillId)}/${id}`);
      return normalizeApiResponse(response as ServiceResponse<void>).success;
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return false;
      }
      throw error;
    }
  }

  async setRecall(skillId: SkillId, id: string, isRecall: boolean): Promise<boolean> {
    const response = await Http.put(`${resourceUrl(skillId)}/${id}/recall`, { isRecall });
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }

  async retryEmbedding(skillId: SkillId, id: string): Promise<boolean> {
    const response = await Http.post(`${resourceUrl(skillId)}/${id}/embedding/retry`);
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }

  async refreshVectorStore(skillId: SkillId): Promise<boolean> {
    const response = await Http.post(`${resourceUrl(skillId)}/embedding/refresh`);
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }
}

export default new SkillBusinessKnowledgeService();
export type { BusinessKnowledgeVO, CreateBusinessKnowledgeDTO, UpdateBusinessKnowledgeDTO };
