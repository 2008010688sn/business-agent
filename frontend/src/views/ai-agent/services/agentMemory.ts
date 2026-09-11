import { Http } from '@/service/request';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { AgentId } from './agent';

export type AgentMemoryType = 'PREFERENCE' | 'SEMANTIC' | 'EPISODIC' | 'PROCEDURAL';

/** PENDING_REVIEW：程序性记忆默认落库状态，人工审核通过后才转为 ACTIVE 参与召回 */
export type AgentMemoryStatus = 'ACTIVE' | 'PENDING_REVIEW' | 'DISABLED' | 'EXPIRED';

/** 记忆范围（主体类型），与记忆内容类型是独立维度，跨 scope 严格隔离 */
export type MemoryScope = 'SESSION' | 'EMPLOYEE_USER' | 'WORKSPACE' | 'EPISODIC' | 'PROCEDURAL';

/** 记忆敏感级别，HIGH 需用户同意（GRANTED）才允许写入 */
export type MemorySensitivity = 'LOW' | 'MEDIUM' | 'HIGH';

export type MemoryConsentStatus = 'UNSPECIFIED' | 'GRANTED' | 'DENIED' | 'REVOKED';

/** 记忆导出权限码（后端 AgentMemoryController.PERMISSION_MEMORY_EXPORT） */
export const MEMORY_EXPORT_PERMISSION = 'agent:memory:export';

/** 程序性记忆人工审核权限码（后端 AgentMemoryController.PERMISSION_MEMORY_REVIEW） */
export const MEMORY_REVIEW_PERMISSION = 'agent:memory:review';

export interface AgentMemoryConfig {
  id?: string;
  agentId?: AgentId;
  userId?: string;
  recallEnabled: boolean;
  writeEnabled: boolean;
  recallTypes: AgentMemoryType[];
  writeTypes: AgentMemoryType[];
  topK: number;
  similarityThreshold: number;
  injectionTokenBudget: number;
  minImportance: number;
  confirmedOnly: boolean;
}

export interface AgentMemoryItem {
  id: string;
  agentId?: AgentId;
  userId?: string;
  memoryType: AgentMemoryType;
  summary: string;
  sourceSessionId?: string;
  confidence?: number;
  importance?: number;
  useCount?: number;
  lastUsedTime?: string;
  status: AgentMemoryStatus;
  createTime?: string;
  /**
   * 以下治理字段来自 AgentMemory 实体（方案第十二章），列表端点 POST /items/query 的
   * AgentMemoryItemResp 与 GET /export 同名同义地完整回传；历史数据可能为空，故声明为可选。
   */
  subjectType?: MemoryScope;
  subjectId?: string;
  factKey?: string;
  revision?: number;
  sensitivity?: MemorySensitivity;
  consentStatus?: MemoryConsentStatus;
  validFrom?: string;
  validTo?: string;
}

/** GET /export 返回的全量导出条目（含治理字段），字段名与后端 AgentMemoryExportItemResp 对齐 */
export interface AgentMemoryExportItem {
  id: string;
  tenantId?: string;
  workspaceId?: string;
  namespaceId?: string;
  agentId?: AgentId;
  userId?: string;
  subjectType?: MemoryScope;
  subjectId?: string;
  factKey?: string;
  revision?: number;
  memoryType?: AgentMemoryType;
  summary?: string;
  content?: string;
  provenance?: string;
  sourceRunId?: string;
  sensitivity?: MemorySensitivity;
  consentStatus?: MemoryConsentStatus;
  validFrom?: string;
  validTo?: string;
  sourceSessionId?: string;
  sourceMessageId?: string;
  importance?: number;
  confidence?: number;
  status?: AgentMemoryStatus;
  useCount?: number;
  lastUsedTime?: string;
  expireTime?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface AgentMemoryQuery {
  memoryTypes?: AgentMemoryType[];
  status?: AgentMemoryStatus;
  /** 记忆范围筛选，请求侧字段名为 scope，对应响应侧 subjectType */
  scope?: MemoryScope;
  sensitivity?: MemorySensitivity;
  consentStatus?: MemoryConsentStatus;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const BASE_URL = '/ai/data-agent/memory';

class AgentMemoryService {
  async getConfig(agentId: AgentId): Promise<AgentMemoryConfig> {
    const response = await Http.post(`${BASE_URL}/config/query`, { agentId });
    return normalizeConfig(unwrapDataOr(response as ServiceResponse<AgentMemoryConfig>, defaultConfig(agentId)));
  }

  async saveConfig(agentId: AgentId, payload: AgentMemoryConfig): Promise<AgentMemoryConfig> {
    const response = await Http.put(`${BASE_URL}/config`, { ...payload, agentId });
    return normalizeConfig(unwrapDataOr(response as ServiceResponse<AgentMemoryConfig>, payload));
  }

  /**
   * 查询记忆清单。scope/sensitivity/consentStatus 由后端筛选，不传即不参与过滤；
   * scope 只在用户个人侧范围内生效，传 WORKSPACE/SESSION 查不到数据（记忆范围严格隔离）。
   */
  async listMemories(agentId: AgentId, query: AgentMemoryQuery = {}): Promise<AgentMemoryItem[]> {
    const response = await Http.post(`${BASE_URL}/items/query`, {
      agentId,
      memoryTypes: query.memoryTypes,
      status: query.status,
      scope: query.scope,
      sensitivity: query.sensitivity,
      consentStatus: query.consentStatus
    });
    return unwrapDataOr(response as ServiceResponse<AgentMemoryItem[]>, []).map(normalizeItem);
  }

  /**
   * 按单一 scope + subjectId 查询（走导出端点的只读清单，不触发下载）。
   * WORKSPACE 的 subjectId 为数字员工 ID；SESSION 为会话 ID。
   */
  async listMemoriesBySubject(agentId: AgentId, scope: MemoryScope, subjectId: string): Promise<AgentMemoryItem[]> {
    const items = await this.exportMemories(agentId, scope, subjectId);
    return items.map(item =>
      normalizeItem({
        id: item.id || '',
        agentId: item.agentId,
        userId: item.userId,
        memoryType: (item.memoryType || 'SEMANTIC') as AgentMemoryType,
        summary: item.summary || '',
        sourceSessionId: item.sourceSessionId,
        confidence: item.confidence,
        importance: item.importance,
        useCount: item.useCount,
        lastUsedTime: item.lastUsedTime,
        status: (item.status || 'ACTIVE') as AgentMemoryStatus,
        createTime: item.createTime,
        subjectType: item.subjectType,
        subjectId: item.subjectId,
        factKey: item.factKey,
        revision: item.revision,
        sensitivity: item.sensitivity,
        consentStatus: item.consentStatus,
        validFrom: item.validFrom,
        validTo: item.validTo
      })
    );
  }

  async updateMemoryStatus(agentId: AgentId, memoryId: string, status: AgentMemoryStatus): Promise<void> {
    await Http.put(`${BASE_URL}/items/status`, { agentId, memoryId, status });
  }

  async deleteMemory(agentId: AgentId, memoryId: string): Promise<void> {
    await Http.delete(`${BASE_URL}/items`, { agentId, memoryId });
  }

  async clearMyMemories(agentId: AgentId): Promise<void> {
    await Http.delete(`${BASE_URL}/items/clear`, { agentId });
  }

  /**
   * 程序性记忆人工审核通过（仅 PENDING_REVIEW 状态可执行），需要 agent:memory:review 权限。
   */
  async approveMemory(agentId: AgentId, memoryId: string): Promise<void> {
    await Http.put(`${BASE_URL}/items/${memoryId}/approve`, { agentId });
  }

  /**
   * 按记忆范围与主体全量导出记忆（含治理字段），需要 agent:memory:export 权限。
   */
  async exportMemories(agentId: AgentId, scope: MemoryScope, subjectId: string): Promise<AgentMemoryExportItem[]> {
    const response = await Http.get(`${BASE_URL}/export`, { agentId, scope, subjectId });
    return unwrapDataOr(response as ServiceResponse<AgentMemoryExportItem[]>, []);
  }

  /**
   * 导出并触发浏览器下载 JSON 文件（文件名带 agent 与时间戳），返回导出的条目数。
   */
  async downloadMemoryExport(agentId: AgentId, scope: MemoryScope, subjectId: string): Promise<number> {
    const items = await this.exportMemories(agentId, scope, subjectId);
    const timestamp = formatExportTimestamp(new Date());
    triggerJsonDownload(items, `agent-${agentId}-memory-${scope.toLowerCase()}-${timestamp}.json`);
    return items.length;
  }
}

const formatExportTimestamp = (date: Date): string => {
  const pad = (value: number) => String(value).padStart(2, '0');
  return (
    `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}` +
    `${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`
  );
};

/** 参照 chat.ts 的 blob 下载触发方式，数据请求走统一 Http 封装 */
const triggerJsonDownload = (data: unknown, filename: string): void => {
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
};

const defaultConfig = (agentId: AgentId): AgentMemoryConfig => ({
  agentId,
  recallEnabled: false,
  writeEnabled: false,
  recallTypes: ['PREFERENCE', 'SEMANTIC', 'EPISODIC', 'PROCEDURAL'],
  writeTypes: ['PREFERENCE', 'SEMANTIC', 'PROCEDURAL'],
  topK: 5,
  similarityThreshold: 0.75,
  injectionTokenBudget: 1200,
  minImportance: 0.5,
  confirmedOnly: false
});

const normalizeConfig = (config: Partial<AgentMemoryConfig>): AgentMemoryConfig => ({
  ...defaultConfig(config.agentId || ''),
  ...config,
  id: normalizeId(config.id),
  agentId: normalizeId(config.agentId),
  recallTypes: config.recallTypes || [],
  writeTypes: config.writeTypes || []
});

const normalizeItem = (item: AgentMemoryItem): AgentMemoryItem => ({
  ...item,
  id: normalizeId(item.id) || '',
  agentId: normalizeId(item.agentId)
});

const normalizeId = (id?: string): string | undefined => id;

export default new AgentMemoryService();
