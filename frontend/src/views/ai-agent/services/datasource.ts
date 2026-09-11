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
import { normalizeApiResponse, unwrapData, unwrapDataOr } from '@/views/ai-agent/services/common';
import type { ApiResponse, XxCloudResult } from '@/views/ai-agent/services/common';

export interface Datasource {
  id?: string;
  name?: string;
  type?: string;
  host?: string;
  port?: number;
  databaseName?: string;
  username?: string; // 读接口返回掩码串（**** + 后 4 位），不是真实账号
  password?: string;
  passwordConfigured?: boolean;
  connectionUrl?: string; // 只写字段：读接口一律返回 null，展示地址请用 host/port/databaseName 拼接
  connectionUrlConfigured?: boolean;
  status?: string;
  testStatus?: string;
  description?: string;
  creatorId?: string;
  createTime?: string;
  updateTime?: string;
}

export interface SkillDatasource {
  id?: string;
  skillId?: string;
  datasourceId?: string;
  isActive?: boolean;
  createTime?: string;
  updateTime?: string;
  datasource?: Datasource;
  selectTables?: string[];
  selectColumns?: Record<string, string[]>;
}

export interface DatasourceType {
  code: number;
  typeName: string;
  dialect: string;
  protocol: string;
  displayName: string;
}

export interface DatasourceTable {
  id?: string;
  datasourceId?: string;
  tableName?: string;
  tableComment?: string;
  businessName?: string;
  enabled?: boolean;
}

export interface DatasourceColumn {
  id?: string;
  datasourceId?: string;
  tableName?: string;
  columnName?: string;
  columnType?: string;
  columnComment?: string;
  businessName?: string;
  sampleValues?: string;
  enabled?: boolean;
}

export interface DatasourceCatalog {
  datasourceId?: string;
  tables?: DatasourceTable[];
  columnsByTable?: Record<string, DatasourceColumn[]>;
}

export interface DatasourcePermissionRule {
  id?: string;
  datasourceId?: string;
  tableName?: string;
  columnName?: string;
  dataRefType?: string;
  javaType?: string;
  scopeType?: number;
  enabled?: boolean;
  missingPolicy?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/datasource';

const getResponseStatus = (error: unknown): number | undefined => {
  if (!error || typeof error !== 'object') {
    return undefined;
  }
  const record = error as { status?: number; response?: { status?: number } };
  return record.response?.status ?? record.status;
};

const normalizeList = <T>(value: unknown): T[] => {
  if (Array.isArray(value)) {
    return value as T[];
  }

  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const candidates = [record.records, record.data, record.list, record.content, record.rows];
    const list = candidates.find(Array.isArray);

    if (list) {
      return list as T[];
    }
  }

  return [];
};

const unwrapList = <T>(response: unknown): T[] => {
  const data = unwrapDataOr<unknown>(response as ServiceResponse<unknown>, []);
  return normalizeList<T>(data);
};

const normalizeListResponse = <T>(response: unknown): ApiResponse<T[]> => {
  const result = normalizeApiResponse<unknown>(response as ServiceResponse<unknown>);
  return {
    ...result,
    data: normalizeList<T>(result.data)
  };
};

class DatasourceService {
  async getAllDatasource(status?: string, type?: string): Promise<Datasource[]> {
    const params: Record<string, string> = {};
    if (status) params.status = status;
    if (type) params.type = type;

    const response = await Http.post(`${API_BASE_URL}/query`, params);
    return unwrapList<Datasource>(response);
  }

  async getDatasourceById(id: string): Promise<Datasource | null> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${id}/detail`);
      return unwrapData(response as ServiceResponse<Datasource>);
    } catch (error) {
      if (getResponseStatus(error) === 404) {
        return null;
      }
      throw error;
    }
  }

  async getDatasourceTables(id: string): Promise<string[]> {
    try {
      const response = await Http.get(`${API_BASE_URL}/${id}/tables`);
      return unwrapList<string>(response);
    } catch (error) {
      if (getResponseStatus(error) === 400) {
        return [];
      }
      throw error;
    }
  }

  async getTableColumns(id: string, tableName: string): Promise<string[]> {
    try {
      const response = await Http.post(`${API_BASE_URL}/tables/columns/query`, { datasourceId: id, tableName });
      return unwrapList<string>(response);
    } catch (error) {
      if (getResponseStatus(error) === 400) {
        return [];
      }
      throw error;
    }
  }

  async createDatasource(datasource: Datasource): Promise<Datasource> {
    const response = await Http.post(`${API_BASE_URL}/create`, datasource);
    return unwrapData(response as ServiceResponse<Datasource>);
  }

  async updateDatasource(id: string, datasource: Datasource): Promise<Datasource> {
    const response = await Http.put(`${API_BASE_URL}/${id}/modify`, datasource);
    return unwrapData(response as ServiceResponse<Datasource>);
  }

  async deleteDatasource(id: string): Promise<ApiResponse<void>> {
    const response = await Http.delete(`${API_BASE_URL}/${id}`);
    return normalizeApiResponse(response as ServiceResponse<void>);
  }

  async testConnection(id: string): Promise<ApiResponse<boolean>> {
    const response = await Http.post(`${API_BASE_URL}/${id}/test`);
    return normalizeApiResponse(response as ServiceResponse<boolean>);
  }

  async getDatasourceTypes(): Promise<ApiResponse<DatasourceType[]>> {
    const response = await Http.get(`${API_BASE_URL}/types`);
    return normalizeListResponse<DatasourceType>(response);
  }

  async syncDatasourceCatalog(id: string): Promise<DatasourceCatalog> {
    const response = await Http.post(`${API_BASE_URL}/${id}/catalog/sync`);
    return unwrapData(response as ServiceResponse<DatasourceCatalog>);
  }

  async getDatasourceCatalog(id: string): Promise<DatasourceCatalog> {
    const response = await Http.get(`${API_BASE_URL}/${id}/catalog`);
    return unwrapData(response as ServiceResponse<DatasourceCatalog>);
  }

  async getCatalogTables(id: string): Promise<DatasourceTable[]> {
    const response = await Http.get(`${API_BASE_URL}/${id}/catalog/tables`);
    return unwrapList<DatasourceTable>(response);
  }

  async getCatalogColumns(id: string, tableName: string): Promise<DatasourceColumn[]> {
    const response = await Http.post(`${API_BASE_URL}/catalog/tables/columns/query`, { datasourceId: id, tableName });
    return unwrapList<DatasourceColumn>(response);
  }

  async getPermissionRules(id: string): Promise<DatasourcePermissionRule[]> {
    const response = await Http.get(`${API_BASE_URL}/${id}/permission-rules`);
    return unwrapList<DatasourcePermissionRule>(response);
  }

  async savePermissionRules(id: string, rules: DatasourcePermissionRule[]): Promise<DatasourcePermissionRule[]> {
    const response = await Http.put(`${API_BASE_URL}/${id}/permission-rules`, rules);
    return unwrapList<DatasourcePermissionRule>(response);
  }

  async inferPermissionRules(id: string): Promise<DatasourcePermissionRule[]> {
    const response = await Http.post(`${API_BASE_URL}/${id}/permission-rules/infer`);
    return unwrapList<DatasourcePermissionRule>(response);
  }

  async inferPermissionRulesByTable(id: string, tableName: string): Promise<DatasourcePermissionRule[]> {
    const response = await Http.post(`${API_BASE_URL}/permission-rules/infer`, { datasourceId: id, tableName });
    return unwrapList<DatasourcePermissionRule>(response);
  }
}

export default new DatasourceService();
