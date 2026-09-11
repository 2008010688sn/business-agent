import { Http } from '@/service/request';
import { unwrapData } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import { normalizeAgent, normalizeAgents, type Agent, type AgentId } from './agent';
import {
  normalizeModelConfigId,
  normalizeModelType,
  normalizeTokenCount,
  type ModelConfig,
  type ModelConfigId
} from './modelConfig';

export interface RuntimeChatModel {
  modelConfigId?: ModelConfigId;
  modelConfig?: ModelConfig;
  enabled?: boolean;
  userSelectable?: boolean;
  isDefault?: boolean;
  selectable?: boolean;
}

export interface AgentRunMeta {
  agent: Agent | null;
  chatModels: RuntimeChatModel[];
}

export interface AgentRunWorkbenchMeta {
  availableAgents: Agent[];
  currentAgent: Agent | null;
  chatModels: RuntimeChatModel[];
}

type RawTokenValue = number | string | null | undefined;
type RawModelConfig = Omit<ModelConfig, 'id' | 'modelType' | 'maxTokens' | 'contextWindowTokens'> & {
  id?: string;
  modelType?: string | { code?: string; name?: string };
  maxTokens?: RawTokenValue;
  contextWindowTokens?: RawTokenValue;
  max_tokens?: RawTokenValue;
  context_window_tokens?: RawTokenValue;
};
type RawRuntimeChatModel = Omit<RuntimeChatModel, 'modelConfigId' | 'modelConfig'> & {
  modelConfigId?: string;
  model_config_id?: string;
  modelConfig?: RawModelConfig | null;
  model_config?: RawModelConfig | null;
};
type RawAgentRunMeta = {
  agent?: Agent | null;
  chatModels?: RawRuntimeChatModel[];
  chat_models?: RawRuntimeChatModel[];
};
type RawAgentRunWorkbenchMeta = RawAgentRunMeta & {
  availableAgents?: Agent[];
  available_agents?: Agent[];
  currentAgent?: Agent | null;
  current_agent?: Agent | null;
};
type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent';

class AgentRunMetaService {
  async getRunMeta(agentId: AgentId): Promise<AgentRunMeta> {
    const response = await Http.post(`${API_BASE_URL}/run-meta/query`, { agentId: String(agentId) });
    return normalizeRunMeta(unwrapData(response as ServiceResponse<RawAgentRunMeta>));
  }

  async getRunWorkbenchMeta(agentId?: AgentId | null): Promise<AgentRunWorkbenchMeta> {
    const body = agentId === undefined || agentId === null ? {} : { agentId: String(agentId) };
    const response = await Http.post(`${API_BASE_URL}/run-workbench-meta/query`, body);
    return normalizeRunWorkbenchMeta(unwrapData(response as ServiceResponse<RawAgentRunWorkbenchMeta>));
  }

  async getUserWorkbenchMeta(agentId?: AgentId | null): Promise<AgentRunWorkbenchMeta> {
    const body = agentId === undefined || agentId === null ? {} : { agentId: String(agentId) };
    const response = await Http.post(`${API_BASE_URL}/user-workbench-meta/query`, body);
    return normalizeRunWorkbenchMeta(unwrapData(response as ServiceResponse<RawAgentRunWorkbenchMeta>));
  }
}

const normalizeRunMeta = (meta?: RawAgentRunMeta | null): AgentRunMeta => ({
  agent: meta?.agent ? normalizeAgent(meta.agent) : null,
  chatModels: normalizeRuntimeChatModels(meta?.chatModels ?? meta?.chat_models)
});

const normalizeRunWorkbenchMeta = (meta?: RawAgentRunWorkbenchMeta | null): AgentRunWorkbenchMeta => {
  const currentAgent = meta?.currentAgent ?? meta?.current_agent;
  return {
    availableAgents: normalizeAgents(meta?.availableAgents ?? meta?.available_agents ?? []),
    currentAgent: currentAgent ? normalizeAgent(currentAgent) : null,
    chatModels: normalizeRuntimeChatModels(meta?.chatModels ?? meta?.chat_models)
  };
};

const normalizeRuntimeChatModels = (items?: RawRuntimeChatModel[]): RuntimeChatModel[] =>
  (items || []).map(item => ({
    ...item,
    modelConfigId: normalizeModelConfigId(item.modelConfigId ?? item.model_config_id),
    modelConfig: normalizeModelConfig(item.modelConfig ?? item.model_config)
  }));

const normalizeModelConfig = (config?: RawModelConfig | null): ModelConfig | undefined => {
  if (!config) {
    return undefined;
  }
  return {
    ...config,
    id: normalizeModelConfigId(config.id),
    modelType: normalizeModelType(config.modelType),
    maxTokens: normalizeTokenCount(config.maxTokens ?? config.max_tokens),
    contextWindowTokens: normalizeTokenCount(config.contextWindowTokens ?? config.context_window_tokens)
  };
};

export default new AgentRunMetaService();
