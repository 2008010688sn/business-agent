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
import { extractApiErrorMessage, getResponseStatus, normalizeApiResponse, unwrapData, unwrapList } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { SkillId } from './skill';

interface SemanticModel {
  id?: string;
  datasourceId?: string;
  tableName: string;
  columnName: string;
  businessName: string;
  synonyms: string;
  businessDescription: string;
  columnComment: string;
  dataType: string;
  status: boolean;
  createdTime?: string;
  updateTime?: string;
}

interface SemanticModelAddDto {
  datasourceId: string;
  tableName: string;
  columnName: string;
  businessName: string;
  synonyms: string;
  businessDescription: string;
  columnComment: string;
  dataType: string;
}

interface SemanticModelUpdateDto {
  businessName: string;
  synonyms: string;
  businessDescription: string;
  columnComment: string;
  dataType: string;
}

interface SemanticModelImportItem {
  tableName: string;
  columnName: string;
  businessName: string;
  synonyms?: string;
  businessDescription?: string;
  columnComment?: string;
  dataType: string;
}

interface SemanticModelBatchImportDTO {
  datasourceId: string;
  items: SemanticModelImportItem[];
}

interface BatchImportResult {
  total: number;
  successCount: number;
  failCount: number;
  errors: string[];
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const resourceUrl = (skillId: SkillId): string =>
  `/ai/skills/${encodeURIComponent(String(skillId))}/resources/semantic-models`;

const defaultBatchImportResult = (): BatchImportResult => ({
  total: 0,
  successCount: 0,
  failCount: 0,
  errors: []
});

const unwrapBatchImportResult = (response: unknown, fallbackMessage: string): BatchImportResult => {
  const result = normalizeApiResponse(response as ServiceResponse<BatchImportResult>);
  if (result.success) {
    return result.data || defaultBatchImportResult();
  }
  throw new Error(result.message || fallbackMessage);
};

class SkillSemanticModelService {
  async list(skillId: SkillId, keyword?: string): Promise<SemanticModel[]> {
    const response = await Http.get(resourceUrl(skillId), keyword ? { keyword } : {});
    return unwrapList<SemanticModel>(response);
  }

  async get(skillId: SkillId, id: string): Promise<SemanticModel | null> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/${id}`);
      return unwrapData(response as ServiceResponse<SemanticModel>) || null;
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return null;
      }
      throw error;
    }
  }

  async create(skillId: SkillId, model: SemanticModelAddDto): Promise<boolean> {
    try {
      const response = await Http.post(resourceUrl(skillId), model);
      const result = normalizeApiResponse(response as ServiceResponse<unknown>);
      if (result.success) {
        return true;
      }
      throw new Error(result.message || 'Create semantic model failed');
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Create semantic model failed'));
    }
  }

  async update(skillId: SkillId, id: string, model: SemanticModelUpdateDto): Promise<boolean> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${id}`, model);
      const result = normalizeApiResponse(response as ServiceResponse<SemanticModel>);
      if (result.success) {
        return true;
      }
      throw new Error(result.message || 'Update semantic model failed');
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return false;
      }
      throw new Error(extractApiErrorMessage(error, 'Update semantic model failed'));
    }
  }

  async delete(skillId: SkillId, id: string): Promise<boolean> {
    try {
      const response = await Http.delete(`${resourceUrl(skillId)}/${id}`);
      return normalizeApiResponse(response as ServiceResponse<unknown>).success;
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return false;
      }
      throw error;
    }
  }

  async batchDelete(skillId: SkillId, ids: string[]): Promise<boolean> {
    const response = await Http.delete(`${resourceUrl(skillId)}/batch`, ids);
    return normalizeApiResponse(response as ServiceResponse<unknown>).success;
  }

  async setEnabled(skillId: SkillId, ids: string[], enabled: boolean): Promise<boolean> {
    const response = await Http.put(`${resourceUrl(skillId)}/status`, { ids, enabled });
    return normalizeApiResponse(response as ServiceResponse<unknown>).success;
  }

  async batchImport(skillId: SkillId, dto: SemanticModelBatchImportDTO): Promise<BatchImportResult> {
    try {
      const response = await Http.post(`${resourceUrl(skillId)}/batch-import`, dto);
      return unwrapBatchImportResult(response, 'Batch import semantic models failed');
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Batch import semantic models failed'));
    }
  }

  async importExcel(skillId: SkillId, file: File, datasourceId: string): Promise<BatchImportResult> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('datasourceId', datasourceId.toString());

    try {
      const response = await Http.post(`${resourceUrl(skillId)}/import/excel`, formData);
      return unwrapBatchImportResult(response, 'Import semantic models from Excel failed');
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Import semantic models from Excel failed'));
    }
  }

  async downloadTemplate(skillId: SkillId): Promise<void> {
    const response = await Http.get(`${resourceUrl(skillId)}/template/download`, {}, { responseType: 'blob' });
    const url = window.URL.createObjectURL(new Blob([response as BlobPart]));
    const link = document.createElement('a');
    link.href = url;
    link.setAttribute('download', 'semantic_model_template.xlsx');
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    window.URL.revokeObjectURL(url);
  }
}

export default new SkillSemanticModelService();
export type {
  SemanticModel,
  SemanticModelAddDto,
  SemanticModelUpdateDto,
  SemanticModelImportItem,
  SemanticModelBatchImportDTO,
  BatchImportResult
};
