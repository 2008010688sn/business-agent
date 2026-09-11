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
import { extractApiErrorMessage, normalizeApiResponse, unwrapData, unwrapList } from '@/views/ai-agent/services/common';
import type { ApiResponse, XxCloudResult } from '@/views/ai-agent/services/common';
import type { Datasource, SkillDatasource } from '@/views/ai-agent/services/datasource';
import type { LogicalRelation } from '@/views/ai-agent/services/logicalRelation';
import type { SkillId } from '@/views/ai-agent/services/skill';

interface ToggleDatasourceDto {
  enabled: boolean;
}

interface UpdateDatasourceTablesDto {
  tables: string[];
}

interface TableColumnsSelectionDto {
  tableName: string;
  columns?: string[];
}

interface UpdateDatasourceColumnsDto {
  tables: TableColumnsSelectionDto[];
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const resourceUrl = (skillId: SkillId): string =>
  `/ai/skills/${encodeURIComponent(String(skillId))}/resources/datasources`;

class SkillDatasourceService {
  async initSchema(skillId: SkillId): Promise<ApiResponse<null>> {
    try {
      const response = await Http.post(`${resourceUrl(skillId)}/init`, {}, { timeout: 5 * 60 * 1000 });
      return normalizeApiResponse(response as ServiceResponse<null>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Initialize datasource schema failed'));
    }
  }

  async list(skillId: SkillId): Promise<SkillDatasource[]> {
    try {
      const response = await Http.get(resourceUrl(skillId));
      return unwrapList<SkillDatasource>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Load skill datasources failed'));
    }
  }

  async candidates(skillId: SkillId): Promise<Datasource[]> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/candidates`);
      return unwrapList<Datasource>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Load datasource candidates failed'));
    }
  }

  async getActive(skillId: SkillId): Promise<SkillDatasource> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/active`);
      return unwrapData(response as ServiceResponse<SkillDatasource>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Load active skill datasource failed'));
    }
  }

  async bind(skillId: SkillId, datasourceId: string): Promise<ApiResponse<SkillDatasource>> {
    try {
      const response = await Http.post(resourceUrl(skillId), { datasourceId });
      return normalizeApiResponse(response as ServiceResponse<SkillDatasource>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Bind datasource failed'));
    }
  }

  async remove(skillId: SkillId, datasourceId: string): Promise<ApiResponse<null>> {
    try {
      const response = await Http.delete(`${resourceUrl(skillId)}/${datasourceId}`);
      return normalizeApiResponse(response as ServiceResponse<null>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Remove datasource failed'));
    }
  }

  async setEnabled(skillId: SkillId, datasourceId: string, enabled: boolean): Promise<ApiResponse<SkillDatasource>> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${datasourceId}/enabled`, {
        enabled
      } satisfies ToggleDatasourceDto);
      return normalizeApiResponse(response as ServiceResponse<SkillDatasource>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Update datasource status failed'));
    }
  }

  async testConnection(skillId: SkillId, datasourceId: string): Promise<boolean> {
    try {
      const response = await Http.post(`${resourceUrl(skillId)}/${datasourceId}/connection-test`);
      return unwrapData(response as ServiceResponse<boolean>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Test datasource connection failed'));
    }
  }

  async availableTables(skillId: SkillId, datasourceId: string): Promise<string[]> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/${datasourceId}/available-tables`);
      return unwrapList<string>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Load datasource tables failed'));
    }
  }

  async availableColumns(skillId: SkillId, datasourceId: string, tableName: string): Promise<string[]> {
    try {
      const response = await Http.get(
        `${resourceUrl(skillId)}/${datasourceId}/available-tables/${encodeURIComponent(tableName)}/columns`
      );
      return unwrapList<string>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, `Load columns for ${tableName} failed`));
    }
  }

  async logicalRelations(skillId: SkillId, datasourceId: string): Promise<LogicalRelation[]> {
    try {
      const response = await Http.get(`${resourceUrl(skillId)}/${datasourceId}/logical-relations`);
      return unwrapList<LogicalRelation>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Load logical relations failed'));
    }
  }

  async saveLogicalRelations(
    skillId: SkillId,
    datasourceId: string,
    relations: LogicalRelation[]
  ): Promise<LogicalRelation[]> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${datasourceId}/logical-relations`, relations);
      return unwrapList<LogicalRelation>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Save logical relations failed'));
    }
  }

  async updateTables(
    skillId: SkillId,
    datasourceId: string,
    tables: string[]
  ): Promise<ApiResponse<SkillDatasource>> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${datasourceId}/tables`, {
        tables
      } satisfies UpdateDatasourceTablesDto);
      return normalizeApiResponse(response as ServiceResponse<SkillDatasource>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Update datasource tables failed'));
    }
  }

  async updateColumns(
    skillId: SkillId,
    datasourceId: string,
    tables: TableColumnsSelectionDto[]
  ): Promise<ApiResponse<SkillDatasource>> {
    try {
      const response = await Http.put(`${resourceUrl(skillId)}/${datasourceId}/columns`, {
        tables
      } satisfies UpdateDatasourceColumnsDto);
      return normalizeApiResponse(response as ServiceResponse<SkillDatasource>);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, 'Update datasource columns failed'));
    }
  }

  async getVisibleTableColumns(skillId: SkillId, datasourceId: string, tableName: string): Promise<string[]> {
    try {
      const response = await Http.get(
        `${resourceUrl(skillId)}/${datasourceId}/tables/${encodeURIComponent(tableName)}/columns`
      );
      return unwrapList<string>(response);
    } catch (error) {
      throw new Error(extractApiErrorMessage(error, `Load columns for ${tableName} failed`));
    }
  }
}

export default new SkillDatasourceService();
export type { TableColumnsSelectionDto };
