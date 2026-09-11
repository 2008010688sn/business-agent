/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http, baseURL } from '@/service/request';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { ModelConfigId } from './modelConfig';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

export type RealtimeVoiceMode = 'PUSH_TO_TALK' | 'CONTINUOUS_VOICE';
export type RealtimeVoiceTransport = 'HTTP' | 'SSE' | 'WEBSOCKET' | 'WEBRTC';
export type RealtimeVoiceRuntimeMode = 'PIPELINE' | 'REALTIME';

export interface RealtimeVoiceSessionRequest {
  agentId?: ModelConfigId;
  threadId?: ModelConfigId;
  realtimeConfigId?: ModelConfigId;
  runtimeMode?: RealtimeVoiceRuntimeMode | string;
  mode?: RealtimeVoiceMode | string;
  transport?: RealtimeVoiceTransport | string;
  chatModelConfigId?: ModelConfigId;
  asrModelConfigId?: ModelConfigId;
  ttsModelConfigId?: ModelConfigId;
  realtimeVoiceModelConfigId?: ModelConfigId;
  voiceProfileId?: ModelConfigId;
  allowInterrupt?: boolean;
  vadEnabled?: boolean;
  sessionOptions?: Record<string, any>;
}

export interface RealtimeVoiceSession {
  id?: ModelConfigId;
  sessionId?: string;
  agentId?: ModelConfigId;
  threadId?: string;
  realtimeConfigId?: ModelConfigId;
  runtimeMode?: string;
  mode?: string;
  transport?: string;
  status?: string;
  chatModelConfigId?: ModelConfigId;
  asrModelConfigId?: ModelConfigId;
  ttsModelConfigId?: ModelConfigId;
  realtimeVoiceModelConfigId?: ModelConfigId;
  voiceProfileId?: ModelConfigId;
  allowInterrupt?: boolean;
  vadEnabled?: boolean;
  currentTurnId?: string;
  lastSequence?: number;
  runtimeRequestId?: string;
  wsToken?: string;
  wsTokenExpiresAt?: string;
  sessionOptions?: Record<string, any>;
  startedAt?: string;
  endedAt?: string;
  errorCode?: number;
  errorMessage?: string;
}

export interface RealtimeSignalRequest {
  type?: string;
  sdp?: string;
  candidate?: string;
  sdpMid?: string;
  sdpMLineIndex?: number;
  options?: Record<string, any>;
}

export interface RealtimeSignalResponse {
  status?: string;
  sessionId?: string;
  type?: string;
  sdp?: string;
  gatewayUrl?: string;
  message?: string;
  data?: Record<string, any>;
}

export interface ModelAsrConfig {
  id?: ModelConfigId;
  modelConfigId?: ModelConfigId;
  transcriptionPath?: string;
  transcriptionStreamPath?: string;
  asrProtocol?: string;
  streamingEnabled?: boolean;
  defaultAudioFormat?: string;
  defaultSampleRate?: number;
  vadSupported?: boolean;
  hotwordSupported?: boolean;
  options?: Record<string, any>;
}

export interface ModelRealtimeVoiceConfig {
  id?: ModelConfigId;
  modelConfigId?: ModelConfigId;
  websocketUrl?: string;
  webrtcUrl?: string;
  inputAudioFormat?: string;
  outputAudioFormat?: string;
  inputSampleRate?: number;
  outputSampleRate?: number;
  turnDetectionType?: string;
  voiceName?: string;
  transcriptEnabled?: boolean;
  toolCallEnabled?: boolean;
  interruptSupported?: boolean;
  options?: Record<string, any>;
}

export interface RealtimeVoiceConfig {
  id?: ModelConfigId;
  agentId?: ModelConfigId;
  configName?: string;
  runtimeMode?: string;
  transport?: string;
  chatModelConfigId?: ModelConfigId;
  asrModelConfigId?: ModelConfigId;
  ttsModelConfigId?: ModelConfigId;
  realtimeVoiceModelConfigId?: ModelConfigId;
  voiceProfileId?: ModelConfigId;
  inputAudioFormat?: string;
  inputSampleRate?: number;
  outputAudioFormat?: string;
  outputSampleRate?: number;
  vadEnabled?: boolean;
  allowInterrupt?: boolean;
  partialTranscriptEnabled?: boolean;
  textRecordEnabled?: boolean;
  fallbackEnabled?: boolean;
  fallbackStrategy?: string;
  connectTimeoutMs?: number;
  readTimeoutMs?: number;
  idleTimeoutMs?: number;
  maxTurnDurationMs?: number;
  heartbeatIntervalMs?: number;
  isDefault?: boolean;
  enabled?: boolean;
  options?: Record<string, any>;
}

export interface RealtimeVoiceReadiness {
  ready?: boolean;
  agentId?: ModelConfigId;
  chatModelReady?: boolean;
  asrModelReady?: boolean;
  ttsModelReady?: boolean;
  voiceProfileReady?: boolean;
  realtimeVoiceModelReady?: boolean;
  realtimeConfigReady?: boolean;
  realtimeConfigId?: ModelConfigId;
  runtimeMode?: string;
  missingItems?: string[];
  config?: RealtimeVoiceConfig | null;
}

const SESSION_API_BASE_URL = '/ai/realtime-voice/sessions';
const CONFIG_API_BASE_URL = '/ai/realtime-voice';
const MODEL_CONFIG_API_BASE_URL = '/ai/model-config';

class RealtimeVoiceService {
  async create(request: RealtimeVoiceSessionRequest): Promise<RealtimeVoiceSession> {
    const response = await Http.post(`${SESSION_API_BASE_URL}/create`, request);
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceSession>, {});
  }

  async offer(id: ModelConfigId, request: RealtimeSignalRequest): Promise<RealtimeSignalResponse> {
    const response = await Http.post(`${SESSION_API_BASE_URL}/${id}/offer`, request);
    return unwrapDataOr(response as ServiceResponse<RealtimeSignalResponse>, {});
  }

  async iceCandidates(id: ModelConfigId, request: RealtimeSignalRequest): Promise<RealtimeSignalResponse> {
    const response = await Http.post(`${SESSION_API_BASE_URL}/${id}/ice-candidates`, request);
    return unwrapDataOr(response as ServiceResponse<RealtimeSignalResponse>, {});
  }

  async interrupt(id: ModelConfigId): Promise<RealtimeVoiceSession> {
    const response = await Http.post(`${SESSION_API_BASE_URL}/${id}/interrupt`);
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceSession>, {});
  }

  async delete(id: ModelConfigId): Promise<void> {
    await Http.delete(`${SESSION_API_BASE_URL}/${id}`);
  }

  async readiness(agentId?: ModelConfigId, realtimeConfigId?: ModelConfigId): Promise<RealtimeVoiceReadiness> {
    const response = await Http.post(`${CONFIG_API_BASE_URL}/readiness/query`, {
      ...(agentId !== undefined && agentId !== null ? { agentId: String(agentId) } : {}),
      ...(realtimeConfigId !== undefined && realtimeConfigId !== null
        ? { realtimeConfigId: String(realtimeConfigId) }
        : {})
    });
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceReadiness>, { ready: false, missingItems: [] });
  }

  async generateDefault(agentId?: ModelConfigId): Promise<RealtimeVoiceReadiness> {
    const response = await Http.post(
      `${CONFIG_API_BASE_URL}/configs-default`,
      agentId !== undefined && agentId !== null ? { agentId: String(agentId) } : {}
    );
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceReadiness>, { ready: false, missingItems: [] });
  }

  async listConfigs(agentId?: ModelConfigId): Promise<RealtimeVoiceConfig[]> {
    const response = await Http.post(
      `${CONFIG_API_BASE_URL}/configs/query`,
      agentId !== undefined && agentId !== null ? { agentId: String(agentId) } : {}
    );
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceConfig[]>, []);
  }

  async saveConfig(config: RealtimeVoiceConfig): Promise<RealtimeVoiceConfig> {
    const response = await Http.put(`${CONFIG_API_BASE_URL}/configs`, config);
    return unwrapDataOr(response as ServiceResponse<RealtimeVoiceConfig>, {});
  }

  async getAsrConfig(modelConfigId: ModelConfigId): Promise<ModelAsrConfig> {
    const response = await Http.post(`${MODEL_CONFIG_API_BASE_URL}/asr/query`, {
      modelConfigId: String(modelConfigId)
    });
    return unwrapDataOr(response as ServiceResponse<ModelAsrConfig>, {});
  }

  async saveAsrConfig(modelConfigId: ModelConfigId, config: ModelAsrConfig): Promise<ModelAsrConfig> {
    const response = await Http.put(`${MODEL_CONFIG_API_BASE_URL}/asr/modify`, {
      ...config,
      modelConfigId: String(modelConfigId)
    });
    return unwrapDataOr(response as ServiceResponse<ModelAsrConfig>, {});
  }

  async getRealtimeVoiceModelConfig(modelConfigId: ModelConfigId): Promise<ModelRealtimeVoiceConfig> {
    const response = await Http.post(`${MODEL_CONFIG_API_BASE_URL}/realtime-voice/query`, {
      modelConfigId: String(modelConfigId)
    });
    return unwrapDataOr(response as ServiceResponse<ModelRealtimeVoiceConfig>, {});
  }

  async saveRealtimeVoiceModelConfig(
    modelConfigId: ModelConfigId,
    config: ModelRealtimeVoiceConfig
  ): Promise<ModelRealtimeVoiceConfig> {
    const response = await Http.put(`${MODEL_CONFIG_API_BASE_URL}/realtime-voice/modify`, {
      ...config,
      modelConfigId: String(modelConfigId)
    });
    return unwrapDataOr(response as ServiceResponse<ModelRealtimeVoiceConfig>, {});
  }

  buildWebSocketUrl(session: RealtimeVoiceSession): string {
    const origin = baseURL && /^https?:\/\//i.test(baseURL) ? baseURL : window.location.origin;
    const url = new URL('/ws/ai/realtime/voice', origin);
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
    if (session.sessionId) {
      url.searchParams.set('sessionId', session.sessionId);
    }
    if (session.wsToken) {
      url.searchParams.set('token', session.wsToken);
    }
    return url.toString();
  }
}

export default new RealtimeVoiceService();
