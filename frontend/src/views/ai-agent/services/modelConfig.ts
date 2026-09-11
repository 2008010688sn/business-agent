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
import { normalizeApiResponse, toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

export type ModelConfigId = string;
export type ModelEndpointDialect =
  | 'OPENAI_COMPATIBLE'
  | 'DASHSCOPE_NATIVE'
  | 'STEPFUN_NATIVE'
  | 'DEEPSEEK_NATIVE'
  | 'ZHIPU_NATIVE'
  | 'MOONSHOT_NATIVE'
  | 'CUSTOM';
export type ModelCapabilityProfile =
  | 'AUTO'
  | 'NO_REASONING'
  | 'QWEN_HYBRID'
  | 'QWEN_THINKING_ONLY'
  | 'STEPFUN_REASONING'
  | 'DEEPSEEK_THINKING'
  | 'GLM_THINKING'
  | 'KIMI_K3_REASONING'
  | 'KIMI_K26_THINKING'
  | 'KIMI_K27_CODE';
export type ModelReasoningProtocol =
  | 'AUTO'
  | 'REASONING_EFFORT'
  | 'THINKING_OBJECT'
  | 'THINKING_OBJECT_WITH_EFFORT'
  | 'ENABLE_THINKING'
  | 'ENABLE_THINKING_ONLY'
  | 'NONE';
export type ModelReasoningMode = 'AUTO' | 'ENABLED' | 'DISABLED';
export type ModelReasoningLevel = 'NONE' | 'MINIMAL' | 'LOW' | 'MEDIUM' | 'HIGH' | 'XHIGH' | 'MAX';
export type ModelTokenLimitMode = 'MAX_TOKENS' | 'MAX_COMPLETION_TOKENS';
export type ModelTemperaturePolicy = 'SEND' | 'OMIT';
export type ModelStructuredOutputMode = 'AUTO' | 'STRICT_JSON_SCHEMA' | 'JSON_OBJECT' | 'PROMPT_JSON';
export type ModelPreservedReasoningPolicy = 'DROP';

export interface ModelCapabilityDescriptorDefaults {
  reasoningProtocol: ModelReasoningProtocol;
  reasoningMode: ModelReasoningMode;
  reasoningLevel?: ModelReasoningLevel;
  tokenLimitMode?: ModelTokenLimitMode;
  temperaturePolicy?: ModelTemperaturePolicy;
  structuredOutputMode: ModelStructuredOutputMode;
  preservedReasoningPolicy: ModelPreservedReasoningPolicy;
}

export interface ModelCapabilityDescriptor {
  endpointDialect: ModelEndpointDialect;
  capabilityProfile: ModelCapabilityProfile;
  reasoningProtocols: ModelReasoningProtocol[];
  reasoningModes: ModelReasoningMode[];
  reasoningLevels: ModelReasoningLevel[];
  reasoningBudgetSupported: boolean;
  reasoningEffortSupported: boolean;
  reasoningDisableSupported: boolean;
  tokenLimitModes: ModelTokenLimitMode[];
  temperaturePolicies: ModelTemperaturePolicy[];
  structuredOutputModes: ModelStructuredOutputMode[];
  preservedReasoningPolicies: ModelPreservedReasoningPolicy[];
  defaults: ModelCapabilityDescriptorDefaults;
}

export type ModelCapabilityDescriptorIdentity = Pick<
  ModelCapabilityDescriptor,
  'endpointDialect' | 'capabilityProfile'
>;

export interface ModelCapabilityProfileOption {
  label: string;
  value: ModelCapabilityProfile;
}

export interface ModelEndpointDialectOption {
  label: string;
  value: ModelEndpointDialect;
}

export interface ModelCapabilitySelection {
  endpointDialect: ModelEndpointDialect;
  capabilityProfile: ModelCapabilityProfile;
  reasoningProtocol: ModelReasoningProtocol;
  reasoningMode: ModelReasoningMode;
  reasoningLevel?: ModelReasoningLevel;
  reasoningBudgetTokens?: number;
  tokenLimitMode?: ModelTokenLimitMode;
  temperaturePolicy?: ModelTemperaturePolicy;
  structuredOutputMode: ModelStructuredOutputMode;
  preservedReasoningPolicy: ModelPreservedReasoningPolicy;
}

const MODEL_CAPABILITY_PROFILE_LABELS: Record<ModelCapabilityProfile, string> = {
  AUTO: '自动（端点默认）',
  NO_REASONING: '非推理模型（不发送推理参数）',
  QWEN_HYBRID: 'Qwen 混合思考',
  QWEN_THINKING_ONLY: 'Qwen 纯思考',
  STEPFUN_REASONING: 'StepFun 推理',
  DEEPSEEK_THINKING: 'DeepSeek 思考',
  GLM_THINKING: 'GLM 思考',
  KIMI_K3_REASONING: 'Kimi K3 推理',
  KIMI_K26_THINKING: 'Kimi K2.6 思考',
  KIMI_K27_CODE: 'Kimi K2.7 Code'
};

const MODEL_ENDPOINT_DIALECT_LABELS: Record<ModelEndpointDialect, string> = {
  OPENAI_COMPATIBLE: 'OpenAI Compatible',
  DASHSCOPE_NATIVE: 'DashScope Native',
  STEPFUN_NATIVE: 'StepFun Native',
  DEEPSEEK_NATIVE: 'DeepSeek Native',
  ZHIPU_NATIVE: 'Zhipu Native',
  MOONSHOT_NATIVE: 'Moonshot Native',
  CUSTOM: 'Custom'
};

const MODEL_ENDPOINT_DIALECTS = Object.keys(MODEL_ENDPOINT_DIALECT_LABELS) as ModelEndpointDialect[];
const MODEL_CAPABILITY_PROFILES = Object.keys(MODEL_CAPABILITY_PROFILE_LABELS) as ModelCapabilityProfile[];

export const getModelEndpointDialectOptions = (
  descriptors: readonly ModelCapabilityDescriptor[] = []
): ModelEndpointDialectOption[] =>
  Array.from(new Set(descriptors.map(descriptor => descriptor.endpointDialect)))
    .filter((value): value is ModelEndpointDialect => MODEL_ENDPOINT_DIALECTS.includes(value))
    .map(value => ({ label: MODEL_ENDPOINT_DIALECT_LABELS[value], value }));

export const getModelCapabilityProfileOptions = (
  endpointDialect: ModelEndpointDialect = 'OPENAI_COMPATIBLE',
  descriptors: readonly ModelCapabilityDescriptorIdentity[] = []
): ModelCapabilityProfileOption[] =>
  Array.from(
    new Set(
      descriptors
        .filter(descriptor => descriptor.endpointDialect === endpointDialect)
        .map(descriptor => descriptor.capabilityProfile)
        .filter((value): value is ModelCapabilityProfile => MODEL_CAPABILITY_PROFILES.includes(value))
    )
  ).map(value => ({ label: MODEL_CAPABILITY_PROFILE_LABELS[value], value }));

export const normalizeModelCapabilityProfileForDialect = (
  endpointDialect: ModelEndpointDialect,
  capabilityProfile: ModelCapabilityProfile = 'AUTO',
  descriptors: readonly ModelCapabilityDescriptorIdentity[] = []
): ModelCapabilityProfile =>
  getModelCapabilityProfileOptions(endpointDialect, descriptors).some(option => option.value === capabilityProfile)
    ? capabilityProfile
    : 'AUTO';

export const findModelCapabilityDescriptor = (
  endpointDialect: ModelEndpointDialect,
  capabilityProfile: ModelCapabilityProfile,
  descriptors: readonly ModelCapabilityDescriptor[] = []
): ModelCapabilityDescriptor | undefined =>
  descriptors.find(
    descriptor => descriptor.endpointDialect === endpointDialect && descriptor.capabilityProfile === capabilityProfile
  ) ||
  descriptors.find(
    descriptor => descriptor.endpointDialect === endpointDialect && descriptor.capabilityProfile === 'AUTO'
  );

export interface ModelConfigPageQuery {
  current?: number;
  size?: number;
  modelType?: string;
  provider?: string;
  keyword?: string;
  isActive?: boolean;
}

export interface ModelConfigSummary {
  totalCount?: number;
  activeCount?: number;
  chatCount?: number;
  embeddingCount?: number;
  audioCount?: number;
  ttsCount?: number;
  realtimeVoiceCount?: number;
}

export interface ModelConfig {
  id?: ModelConfigId;
  provider: string; // e.g. "openai", "deepseek"
  apiKey?: string;
  apiKeyConfigured?: boolean;
  baseUrl: string;
  modelName: string;
  modelType: string; // "CHAT", "EMBEDDING", "AUDIO_TRANSCRIPTION", "TEXT_TO_SPEECH" or "REALTIME_VOICE"
  temperature?: number;
  maxTokens?: number;
  contextWindowTokens?: number;
  supportVision?: boolean;
  isActive?: boolean;
  transcriptionsPath?: string;
  completionsPath?: string; // 对话模型路径
  endpointDialect?: ModelEndpointDialect;
  capabilityProfile?: ModelCapabilityProfile;
  reasoningProtocol?: ModelReasoningProtocol;
  reasoningMode?: ModelReasoningMode;
  reasoningLevel?: ModelReasoningLevel;
  reasoningBudgetTokens?: number;
  tokenLimitMode?: ModelTokenLimitMode;
  temperaturePolicy?: ModelTemperaturePolicy;
  structuredOutputMode?: ModelStructuredOutputMode;
  preservedReasoningPolicy?: ModelPreservedReasoningPolicy;
  embeddingsPath?: string; // 嵌入模型路径
  proxyEnabled?: boolean; // 代理开关，默认为关闭（直连）
  proxyHost?: string;
  proxyPort?: number;
  proxyUsername?: string;
  proxyPassword?: string;
  proxyPasswordConfigured?: boolean;
}

type RawTokenValue = number | string | null;

type RawModelCapabilityDescriptor = Partial<ModelCapabilityDescriptor> & {
  endpointDialect?: unknown;
  capabilityProfile?: unknown;
  reasoningProtocols?: unknown;
  reasoningModes?: unknown;
  reasoningLevels?: unknown;
  tokenLimitModes?: unknown;
  temperaturePolicies?: unknown;
  structuredOutputModes?: unknown;
  preservedReasoningPolicies?: unknown;
  defaults?: Partial<ModelCapabilityDescriptorDefaults> & Record<string, unknown>;
};

type RawModelConfig = Omit<
  ModelConfig,
  'id' | 'modelType' | 'maxTokens' | 'contextWindowTokens' | 'reasoningBudgetTokens' | 'preservedReasoningPolicy'
> & {
  id?: ModelConfigId;
  modelType?: string | { code?: string; name?: string };
  maxTokens?: RawTokenValue;
  contextWindowTokens?: RawTokenValue;
  max_tokens?: RawTokenValue;
  context_window_tokens?: RawTokenValue;
  reasoningBudgetTokens?: RawTokenValue;
  reasoning_budget_tokens?: RawTokenValue;
  preservedReasoningPolicy?: string | null;
};

const API_BASE_URL = '/ai/model-config';

class ModelConfigService {
  private capabilityCache?: ModelCapabilityDescriptor[];

  private capabilityRequest?: Promise<ModelCapabilityDescriptor[]>;

  async capabilities(): Promise<ModelCapabilityDescriptor[]> {
    if (this.capabilityCache) {
      return this.capabilityCache;
    }
    if (this.capabilityRequest) {
      return this.capabilityRequest;
    }

    this.capabilityRequest = Http.get(`${API_BASE_URL}/capabilities`)
      .then(response =>
        unwrapDataOr(
          response as ApiResponse<RawModelCapabilityDescriptor[]> | XxCloudResult<RawModelCapabilityDescriptor[]>,
          []
        )
      )
      .then(descriptors => {
        const normalized = descriptors.map(normalizeModelCapabilityDescriptor).filter(descriptor => {
          return (
            MODEL_ENDPOINT_DIALECTS.includes(descriptor.endpointDialect) &&
            MODEL_CAPABILITY_PROFILES.includes(descriptor.capabilityProfile)
          );
        });
        if (normalized.length === 0) {
          throw new Error('模型能力描述为空');
        }
        this.capabilityCache = normalized;
        return normalized;
      })
      .finally(() => {
        this.capabilityRequest = undefined;
      });
    return this.capabilityRequest;
  }

  clearCapabilitiesCache(): void {
    this.capabilityCache = undefined;
  }

  /**
   * 获取模型配置列表
   */
  async list(): Promise<ModelConfig[]> {
    const response = await Http.post(`${API_BASE_URL}/query`, {});
    return unwrapDataOr(response as ApiResponse<RawModelConfig[]> | XxCloudResult<RawModelConfig[]>, []).map(
      normalizeModelConfig
    );
  }

  async page(query: ModelConfigPageQuery): Promise<PageResponse<ModelConfig[]>> {
    const response = await Http.post(`${API_BASE_URL}/page`, normalizePageQuery(query));
    const page = toPageResponse(
      response as ApiResponse<MybatisPage<RawModelConfig>> | XxCloudResult<MybatisPage<RawModelConfig>>
    );
    return {
      ...page,
      data: page.data.map(normalizeModelConfig)
    };
  }

  async summary(): Promise<ModelConfigSummary> {
    const response = await Http.post(`${API_BASE_URL}/summary`);
    return unwrapData(response as ApiResponse<ModelConfigSummary> | XxCloudResult<ModelConfigSummary>);
  }

  /**
   * 新增模型配置
   * @param config 模型配置对象
   */
  async add(config: Omit<ModelConfig, 'id'>): Promise<ApiResponse<string>> {
    console.log(`config: ${config}`);
    const response = await Http.post(`${API_BASE_URL}/create`, config);
    return normalizeApiResponse(response as ApiResponse<string> | XxCloudResult<string>);
  }

  /**
   * 更新模型配置
   * @param config 模型配置对象
   */
  async update(config: ModelConfig): Promise<ApiResponse<string>> {
    const response = await Http.put(`${API_BASE_URL}/${config.id}/modify`, config);
    return normalizeApiResponse(response as ApiResponse<string> | XxCloudResult<string>);
  }

  /**
   * 删除模型配置
   * @param id 配置ID
   */
  async delete(id: ModelConfigId): Promise<ApiResponse<string>> {
    const response = await Http.delete(`${API_BASE_URL}/${id}`);
    return normalizeApiResponse(response as ApiResponse<string> | XxCloudResult<string>);
  }

  /**
   * 启用/切换模型配置
   * @param id 配置ID
   */
  async activate(id: ModelConfigId): Promise<ApiResponse<string>> {
    const response = await Http.put(`${API_BASE_URL}/${id}/status`, { status: 'active' });
    return normalizeApiResponse(response as ApiResponse<string> | XxCloudResult<string>);
  }

  /**
   * 测试模型配置连接
   * @param config 模型配置对象
   */
  async testConnection(config: Omit<ModelConfig, 'id'>): Promise<ApiResponse<string>> {
    const response = await Http.post(`${API_BASE_URL}/test`, config);
    return normalizeApiResponse(response as ApiResponse<string> | XxCloudResult<string>);
  }
}

export const normalizeModelConfigId = (id?: ModelConfigId): string | undefined =>
  id === undefined || id === null ? undefined : String(id);

export const normalizeModelType = (modelType?: string | { code?: string; name?: string }): string => {
  if (typeof modelType === 'string') {
    return modelType.trim().toUpperCase();
  }
  return (modelType?.code || modelType?.name || '').trim().toUpperCase();
};

export const normalizeTokenCount = (value?: number | string | null): number | undefined => {
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  const numericValue = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(numericValue) ? numericValue : undefined;
};

const normalizeOptionalEnum = <T extends string>(value: unknown): T | undefined => {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim().toUpperCase();
  return normalized ? (normalized as T) : undefined;
};

const normalizeEnumList = <T extends string>(value: unknown): T[] =>
  Array.isArray(value)
    ? value.filter(item => typeof item === 'string').map(item => item.trim().toUpperCase() as T)
    : [];

export const normalizeModelCapabilityDescriptor = (
  descriptor: RawModelCapabilityDescriptor
): ModelCapabilityDescriptor => {
  const defaults: Partial<ModelCapabilityDescriptorDefaults> = descriptor.defaults || {};
  return {
    endpointDialect: normalizeOptionalEnum<ModelEndpointDialect>(descriptor.endpointDialect) || 'OPENAI_COMPATIBLE',
    capabilityProfile: normalizeOptionalEnum<ModelCapabilityProfile>(descriptor.capabilityProfile) || 'AUTO',
    reasoningProtocols: normalizeEnumList<ModelReasoningProtocol>(descriptor.reasoningProtocols),
    reasoningModes: normalizeEnumList<ModelReasoningMode>(descriptor.reasoningModes),
    reasoningLevels: normalizeEnumList<ModelReasoningLevel>(descriptor.reasoningLevels),
    reasoningBudgetSupported: Boolean(descriptor.reasoningBudgetSupported),
    reasoningEffortSupported: Boolean(descriptor.reasoningEffortSupported),
    reasoningDisableSupported: Boolean(descriptor.reasoningDisableSupported),
    tokenLimitModes: normalizeEnumList<ModelTokenLimitMode>(descriptor.tokenLimitModes),
    temperaturePolicies: normalizeEnumList<ModelTemperaturePolicy>(descriptor.temperaturePolicies),
    structuredOutputModes: normalizeEnumList<ModelStructuredOutputMode>(descriptor.structuredOutputModes),
    preservedReasoningPolicies: normalizeEnumList<ModelPreservedReasoningPolicy>(descriptor.preservedReasoningPolicies),
    defaults: {
      reasoningProtocol: normalizeOptionalEnum<ModelReasoningProtocol>(defaults.reasoningProtocol) || 'AUTO',
      reasoningMode: normalizeOptionalEnum<ModelReasoningMode>(defaults.reasoningMode) || 'AUTO',
      reasoningLevel: normalizeOptionalEnum<ModelReasoningLevel>(defaults.reasoningLevel),
      tokenLimitMode: normalizeOptionalEnum<ModelTokenLimitMode>(defaults.tokenLimitMode),
      temperaturePolicy: normalizeOptionalEnum<ModelTemperaturePolicy>(defaults.temperaturePolicy),
      structuredOutputMode: normalizeOptionalEnum<ModelStructuredOutputMode>(defaults.structuredOutputMode) || 'AUTO',
      preservedReasoningPolicy:
        normalizeOptionalEnum<ModelPreservedReasoningPolicy>(defaults.preservedReasoningPolicy) || 'DROP'
    }
  };
};

const firstSupported = <T extends string>(
  values: readonly T[],
  preferred: T | undefined,
  fallback: T,
  defaultValue?: T
): T => {
  if (preferred && values.includes(preferred)) {
    return preferred;
  }
  if (defaultValue && values.includes(defaultValue)) {
    return defaultValue;
  }
  if (values.includes(fallback)) {
    return fallback;
  }
  return values[0] || fallback;
};

const optionalSupported = <T extends string>(values: readonly T[], preferred?: T, defaultValue?: T): T | undefined => {
  if (preferred && values.includes(preferred)) {
    return preferred;
  }
  return defaultValue && values.includes(defaultValue) ? defaultValue : undefined;
};

/**
 * Normalizes the complete persisted option tuple against one server descriptor.
 * It is intentionally fail-closed: unsupported optional values are removed and
 * protocol NONE can never carry reasoning level or budget overrides.
 */
export const normalizeModelCapabilitySelection = (
  selection: Partial<ModelCapabilitySelection> & Pick<ModelCapabilitySelection, 'endpointDialect'>,
  descriptors: readonly ModelCapabilityDescriptor[] = []
): ModelCapabilitySelection => {
  const endpointDialect = selection.endpointDialect;
  const capabilityProfile = normalizeModelCapabilityProfileForDialect(
    endpointDialect,
    selection.capabilityProfile || 'AUTO',
    descriptors
  );
  const descriptor = findModelCapabilityDescriptor(endpointDialect, capabilityProfile, descriptors);

  if (!descriptor) {
    return {
      endpointDialect,
      capabilityProfile: 'AUTO',
      reasoningProtocol: 'AUTO',
      reasoningMode: 'AUTO',
      structuredOutputMode: 'AUTO',
      preservedReasoningPolicy: 'DROP'
    };
  }

  const reasoningProtocol = firstSupported(
    descriptor.reasoningProtocols,
    selection.reasoningProtocol,
    'AUTO',
    descriptor.defaults.reasoningProtocol
  );
  let reasoningMode = firstSupported(
    descriptor.reasoningModes,
    selection.reasoningMode,
    'AUTO',
    descriptor.defaults.reasoningMode
  );
  if (reasoningProtocol === 'NONE') {
    reasoningMode = firstSupported(descriptor.reasoningModes, 'DISABLED', 'AUTO', descriptor.defaults.reasoningMode);
  }

  const reasoningDisabled = reasoningProtocol === 'NONE' || reasoningMode === 'DISABLED';
  const reasoningLevel = reasoningDisabled
    ? undefined
    : optionalSupported(descriptor.reasoningLevels, selection.reasoningLevel, descriptor.defaults.reasoningLevel);
  const normalizedReasoningBudget = normalizeTokenCount(selection.reasoningBudgetTokens);
  const reasoningBudgetTokens =
    descriptor.reasoningBudgetSupported &&
    !reasoningDisabled &&
    normalizedReasoningBudget &&
    normalizedReasoningBudget > 0
      ? normalizedReasoningBudget
      : undefined;
  const tokenLimitMode = optionalSupported(
    descriptor.tokenLimitModes,
    selection.tokenLimitMode,
    descriptor.defaults.tokenLimitMode
  );
  const temperaturePolicy = optionalSupported(
    descriptor.temperaturePolicies,
    selection.temperaturePolicy,
    descriptor.defaults.temperaturePolicy
  );
  const structuredOutputMode = firstSupported(
    descriptor.structuredOutputModes,
    selection.structuredOutputMode,
    'AUTO',
    descriptor.defaults.structuredOutputMode
  );
  const preservedReasoningPolicy =
    selection.preservedReasoningPolicy &&
    descriptor.preservedReasoningPolicies.includes(selection.preservedReasoningPolicy)
      ? selection.preservedReasoningPolicy
      : descriptor.defaults.preservedReasoningPolicy &&
          descriptor.preservedReasoningPolicies.includes(descriptor.defaults.preservedReasoningPolicy)
        ? descriptor.defaults.preservedReasoningPolicy
        : 'DROP';

  return {
    endpointDialect,
    capabilityProfile,
    reasoningProtocol,
    reasoningMode,
    reasoningLevel,
    reasoningBudgetTokens,
    tokenLimitMode,
    temperaturePolicy,
    structuredOutputMode,
    preservedReasoningPolicy
  };
};

export const normalizeModelConfig = (config: RawModelConfig): ModelConfig => ({
  ...config,
  id: normalizeModelConfigId(config.id),
  modelType: normalizeModelType(config.modelType),
  endpointDialect: normalizeOptionalEnum<ModelEndpointDialect>(config.endpointDialect) || 'OPENAI_COMPATIBLE',
  capabilityProfile: normalizeOptionalEnum<ModelCapabilityProfile>(config.capabilityProfile) || 'AUTO',
  reasoningProtocol: normalizeOptionalEnum<ModelReasoningProtocol>(config.reasoningProtocol) || 'AUTO',
  reasoningMode: normalizeOptionalEnum<ModelReasoningMode>(config.reasoningMode) || 'AUTO',
  maxTokens: normalizeTokenCount(config.maxTokens ?? config.max_tokens),
  contextWindowTokens: normalizeTokenCount(config.contextWindowTokens ?? config.context_window_tokens),
  reasoningLevel: normalizeOptionalEnum<ModelReasoningLevel>(config.reasoningLevel),
  reasoningBudgetTokens: normalizeTokenCount(config.reasoningBudgetTokens ?? config.reasoning_budget_tokens),
  tokenLimitMode: normalizeOptionalEnum<ModelTokenLimitMode>(config.tokenLimitMode),
  temperaturePolicy: normalizeOptionalEnum<ModelTemperaturePolicy>(config.temperaturePolicy),
  structuredOutputMode: normalizeOptionalEnum<ModelStructuredOutputMode>(config.structuredOutputMode),
  preservedReasoningPolicy: 'DROP'
});

const normalizePageQuery = <T extends object>(query: T): Partial<T> => {
  return Object.entries(query || {}).reduce<Partial<T>>((result, [key, value]) => {
    if (value === undefined || value === null) {
      return result;
    }
    if (typeof value === 'string') {
      const normalized = value.trim();
      if (!normalized) {
        return result;
      }
      result[key as keyof T] = normalized as T[keyof T];
      return result;
    }
    result[key as keyof T] = value as T[keyof T];
    return result;
  }, {});
};

export const isChatModelConfig = (
  config?: Pick<ModelConfig, 'id' | 'modelType'> | null
): config is ModelConfig & { id: ModelConfigId } => {
  return config?.id !== undefined && normalizeModelType(config.modelType) === 'CHAT';
};

export default new ModelConfigService();
