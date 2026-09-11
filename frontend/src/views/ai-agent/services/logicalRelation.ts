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
import { normalizeApiResponse, unwrapData, unwrapList } from '@/views/ai-agent/services/common';
import type { ApiResponse, XxCloudResult } from '@/views/ai-agent/services/common';

export interface LogicalRelation {
  id?: string;
  datasourceId?: string;
  sourceTableName: string;
  sourceColumnName: string;
  targetTableName: string;
  targetColumnName: string;
  relationType?: string;
  description?: string;
  isDeleted?: boolean;
  createdTime?: string;
  updatedTime?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/datasource';

class LogicalRelationService {
  async getLogicalRelations(datasourceId: string): Promise<LogicalRelation[]> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${datasourceId}/logical-relations`);
      return unwrapList<LogicalRelation>(response);
    } catch (error) {
      console.error('Failed to get logical relations:', error);
      return [];
    }
  }

  async addLogicalRelation(
    datasourceId: string,
    logicalRelation: Omit<LogicalRelation, 'id' | 'datasourceId' | 'isDeleted' | 'createdTime' | 'updatedTime'>
  ): Promise<LogicalRelation | null> {
    try {
      const response = await Http.post(`${API_BASE_URL}/${datasourceId}/logical-relations`, logicalRelation);
      return unwrapData(response as ServiceResponse<LogicalRelation>) || null;
    } catch (error) {
      console.error('Failed to add logical relation:', error);
      throw error;
    }
  }

  async updateLogicalRelation(
    datasourceId: string,
    relationId: string,
    logicalRelation: Omit<LogicalRelation, 'id' | 'datasourceId' | 'isDeleted' | 'createdTime' | 'updatedTime'>
  ): Promise<ApiResponse<LogicalRelation>> {
    const response = await Http.put(`${API_BASE_URL}/${datasourceId}/logical-relations/update`, {
      ...logicalRelation,
      id: relationId
    });
    return normalizeApiResponse(response as ServiceResponse<LogicalRelation>);
  }

  async deleteLogicalRelation(datasourceId: string, relationId: string): Promise<ApiResponse<void>> {
    const response = await Http.delete(`${API_BASE_URL}/${datasourceId}/logical-relations/${relationId}`);
    return normalizeApiResponse(response as ServiceResponse<void>);
  }

  async saveLogicalRelations(
    datasourceId: string,
    logicalRelations: LogicalRelation[]
  ): Promise<ApiResponse<LogicalRelation[]>> {
    const response = await Http.put(`${API_BASE_URL}/${datasourceId}/logical-relations`, logicalRelations);
    return normalizeApiResponse(response as ServiceResponse<LogicalRelation[]>);
  }

  async getTableColumns(datasourceId: string, tableName: string): Promise<string[]> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${datasourceId}/tables/${encodeURIComponent(tableName)}/columns`);
      return unwrapList<string>(response);
    } catch (error) {
      console.error('Failed to get table columns:', error);
      return [];
    }
  }
}

export default new LogicalRelationService();
