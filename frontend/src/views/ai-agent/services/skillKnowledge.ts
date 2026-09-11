import { Http } from '@/service/request';
import { getResponseStatus, normalizeApiResponse, toPageResponse, unwrapData } from './common';
import type { ApiResponse, MybatisPage, XxCloudResult } from './common';

export interface SkillKnowledge {
  id?: string;
  skillId?: string;
  title?: string;
  type?: 'DOCUMENT' | 'QA' | 'FAQ' | string;
  question?: string;
  content?: string;
  isRecall?: boolean;
  splitterType?: string;
  sourceFilename?: string;
  filePath?: string;
  fileSize?: number;
  fileType?: string;
  filePreviewUrl?: string;
  publishedReferenced?: boolean;
  embeddingStatus?: string;
  errorMsg?: string;
  createdTime?: string;
  updatedTime?: string;
}

export interface SkillKnowledgeQuery {
  title?: string;
  type?: string;
  embeddingStatus?: string;
  current?: number;
  size?: number;
  pageNum?: number;
  pageSize?: number;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const base = (skillId: string) => `/ai/skills/${skillId}/resources/knowledge-base`;

class SkillKnowledgeService {
  async page(skillId: string, query: SkillKnowledgeQuery = {}) {
    const response = await Http.post(`${base(skillId)}/query/page`, {
      ...query,
      pageNum: query.pageNum ?? query.current ?? 1,
      pageSize: query.pageSize ?? query.size ?? 10
    });
    return toPageResponse(response as ServiceResponse<MybatisPage<SkillKnowledge>>);
  }

  async create(skillId: string, payload: SkillKnowledge) {
    const response = await Http.post(base(skillId), payload);
    return unwrapData(response as ServiceResponse<SkillKnowledge>);
  }

  async update(skillId: string, id: string, payload: Partial<SkillKnowledge>) {
    try {
      const response = await Http.put(`${base(skillId)}/${id}`, payload);
      return unwrapData(response as ServiceResponse<SkillKnowledge>);
    } catch (error) {
      if (getResponseStatus(error) === 404) return null;
      throw error;
    }
  }

  async updateRecallStatus(skillId: string, id: string, isRecall: boolean) {
    const response = await Http.put(`${base(skillId)}/${id}/status`, { id, isRecall });
    return unwrapData(response as ServiceResponse<SkillKnowledge>);
  }

  async remove(skillId: string, id: string) {
    try {
      await Http.delete(`${base(skillId)}/${id}`);
      return true;
    } catch (error) {
      if (getResponseStatus(error) === 404) return false;
      throw error;
    }
  }

  async retry(skillId: string, id: string) {
    const response = await Http.post(`${base(skillId)}/${id}/embedding/retry`);
    return normalizeApiResponse(response as ServiceResponse<void>).success;
  }
}

export default new SkillKnowledgeService();
