import { Http } from '@/service/request';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { AgentId } from './agent';
import { normalizeModelConfigId, normalizeModelType, type ModelConfig, type ModelConfigId } from './modelConfig';

export interface AgentModelConfigItem {
  id?: string;
  agentId?: AgentId;
  modelConfigId?: ModelConfigId;
  isDefault?: boolean;
  userSelectable?: boolean;
  enabled?: boolean;
  modelConfig?: ModelConfig;
}

type RawAgentModelConfigItem = Omit<AgentModelConfigItem, 'id' | 'agentId' | 'modelConfigId' | 'modelConfig'> & {
  id?: string;
  agentId?: string;
  modelConfigId?: string;
  modelConfig?: Omit<ModelConfig, 'id' | 'modelType'> & {
    id?: string;
    modelType?: string | { code?: string; name?: string };
  };
};

export interface UpdateAgentModelConfigRequest {
  agentId?: AgentId;
  defaultModelConfigId?: ModelConfigId;
  models: Array<{
    modelConfigId: ModelConfigId;
    userSelectable?: boolean;
    enabled?: boolean;
  }>;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const BASE_URL = '/ai/data-agent/model-configs';

class AgentModelConfigService {
  async availableModels(agentId: AgentId): Promise<ModelConfig[]> {
    const response = await Http.post(`${BASE_URL}/available-models/query`, { agentId });
    return unwrapDataOr(response as ServiceResponse<RawAgentModelConfigItem['modelConfig'][]>, [])
      .map(normalizeModelConfig)
      .filter((model): model is ModelConfig => Boolean(model));
  }

  async list(agentId: AgentId): Promise<AgentModelConfigItem[]> {
    const response = await Http.post(`${BASE_URL}/query`, { agentId });
    return unwrapDataOr(response as ServiceResponse<RawAgentModelConfigItem[]>, []).map(normalizeAgentModelConfigItem);
  }

  async update(agentId: AgentId, payload: UpdateAgentModelConfigRequest): Promise<AgentModelConfigItem[]> {
    const response = await Http.put(BASE_URL, { ...payload, agentId });
    return unwrapDataOr(response as ServiceResponse<RawAgentModelConfigItem[]>, []).map(normalizeAgentModelConfigItem);
  }
}

const normalizeStringId = (id?: string): string | undefined =>
  id === undefined || id === null ? undefined : String(id);

const normalizeModelConfig = (config?: RawAgentModelConfigItem['modelConfig'] | null): ModelConfig | undefined => {
  if (!config) {
    return undefined;
  }
  return {
    ...config,
    id: normalizeModelConfigId(config.id),
    modelType: normalizeModelType(config.modelType)
  };
};

const normalizeAgentModelConfigItem = (item: RawAgentModelConfigItem): AgentModelConfigItem => ({
  ...item,
  id: normalizeStringId(item.id),
  agentId: normalizeStringId(item.agentId),
  modelConfigId: normalizeModelConfigId(item.modelConfigId),
  modelConfig: normalizeModelConfig(item.modelConfig)
});

export default new AgentModelConfigService();
