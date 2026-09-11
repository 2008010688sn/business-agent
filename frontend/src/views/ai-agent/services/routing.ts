/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import type { AgentId } from './agent';
import type { ApiResponse, XxCloudResult } from './common';
import { unwrapData } from './common';

export type RouteProfileId = string;
export type RouteTargetType = 'SKILL' | 'COLLABORATOR';
export type RouteRisk = 'READ_ONLY' | 'WRITE' | 'FLOW' | 'DELEGATED' | 'UNKNOWN';
export type RouteDecisionType =
  | 'SELECT'
  | 'MULTI_SELECT'
  | 'CLARIFY'
  | 'CONFIRM_REQUIRED'
  | 'DIRECT'
  | 'NO_MATCH'
  | 'ROUTE_UNAVAILABLE';
export type RouteProfileStatus = 'DRAFT' | 'BUILDING' | 'READY' | 'ACTIVE' | 'RETIRED' | 'FAILED';
export type RouteProfileBuildState = 'NOT_BUILT' | 'RUNNING' | 'READY' | 'FAILED' | 'SKIPPED';
export type RouteCapabilityState = 'NOT_PROBED' | 'SUPPORTED' | 'UNSUPPORTED' | 'UNAVAILABLE' | 'STALE';
export type RouteModelProtocol = 'STRICT_SCHEMA' | 'JSON_OBJECT' | 'PROMPT_JSON' | 'NONE';

export interface RouteCapability {
  state: RouteCapabilityState;
  protocol: RouteModelProtocol | 'NONE';
  latencyMs?: number | null;
  checkedAt?: string | null;
  failureCode?: string | null;
  runtimeReady?: boolean | null;
}

export interface RouteProfileInput {
  profileName: string;
  routeModelConfigId: string;
  embeddingModelConfigId: string;
  lexicalAutoSelectEnabled: boolean;
  semanticRecallEnabled: boolean;
  semanticAutoSelectEnabled: boolean;
  modelDisambiguationEnabled: boolean;
  lexicalMinScore: number;
  lexicalMinGap: number;
  vectorRecallThreshold: number;
  vectorAutoSelectThreshold: number;
  vectorMinGap: number;
  modelConfidenceThreshold: number;
}

export interface RouteProfile extends Omit<RouteProfileInput, 'routeModelConfigId' | 'embeddingModelConfigId'> {
  id: RouteProfileId;
  routeModelConfigId: string | null;
  embeddingModelConfigId: string | null;
  status: RouteProfileStatus;
  embeddingFingerprint?: string;
  embeddingDimension?: number;
  strictSchemaStatus?: string;
  probeCheckedAt?: string;
  buildStatus?: RouteProfileBuildState;
  buildTotal?: number;
  buildReady?: number;
  buildFailed?: number;
  lastErrorCode?: string;
  revision: number;
  modelCapability?: RouteCapability;
  embeddingCapability?: RouteCapability;
}

export interface RouteProfileBuildStatus {
  profileId: RouteProfileId;
  status: RouteProfileStatus;
  buildStatus?: RouteProfileBuildState;
  total: number;
  ready: number;
  failed: number;
  errorCode?: string;
  revision: number;
}

export interface RouteProfileDefaults {
  lexicalAutoSelectEnabled: boolean;
  semanticRecallEnabled: boolean;
  semanticAutoSelectEnabled: boolean;
  modelDisambiguationEnabled: boolean;
  lexicalMinScore: number;
  lexicalMinGap: number;
  vectorRecallThreshold: number;
  vectorAutoSelectThreshold: number;
  vectorMinGap: number;
  modelConfidenceThreshold: number;
}

export interface RouteProfileConstraints {
  profileNameMaxLength: number;
  lexicalThresholdMin: number;
  lexicalThresholdMax: number;
  ratioMin: number;
  ratioMax: number;
  routeModelRequiredWhenDisambiguationEnabled: boolean;
  embeddingModelRequiredWhenSemanticEnabled: boolean;
  vectorRecallThresholdMustNotExceedAutoSelectThreshold: boolean;
}

export interface RouteRuntimeBudget {
  totalTimeoutMs: number;
  vectorTimeoutMs: number;
  modelTimeoutMs: number;
  modelMinStartMs: number;
  finishBufferMs: number;
}

export interface RouteProbeTimeouts {
  modelTimeoutMs: number;
  modelConnectTimeoutMs: number;
  embeddingTimeoutMs: number;
}

export interface RouteProfileConfiguration {
  defaults: RouteProfileDefaults;
  constraints: RouteProfileConstraints;
  runtimeBudget: RouteRuntimeBudget;
  probeTimeouts: RouteProbeTimeouts;
}

export interface RouteProfileCurrent {
  active?: RouteProfile | null;
  working?: RouteProfile | null;
  rollback?: RouteProfile | null;
  configuration: RouteProfileConfiguration;
}

export interface RoutePreviewRequest {
  query: string;
  profileId?: RouteProfileId;
  targetTypes?: RouteTargetType[];
}

export interface RoutePreviewCandidate {
  rank: number;
  targetType: RouteTargetType;
  targetId: string;
  targetVersionId?: string;
  routeArtifactId?: string;
  name: string;
  lexicalScore: number;
  vectorScore?: number;
  matchedSignals: string[];
  riskLevel: RouteRisk;
}

export interface RoutePreviewResponse {
  decision: RouteDecisionType;
  reasonCode: string;
  degradeMode?: string;
  profileId: RouteProfileId;
  modelInvoked: boolean;
  candidates: RoutePreviewCandidate[];
  timing?: Record<string, number>;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;
const PROFILE_BASE_URL = '/ai/data-agent/routing-profiles';

class RoutingService {
  async currentProfile(): Promise<RouteProfileCurrent> {
    const response = await Http.get(`${PROFILE_BASE_URL}/current`);
    return unwrapData(response as ServiceResponse<RouteProfileCurrent>);
  }

  async createProfile(payload: RouteProfileInput): Promise<RouteProfile> {
    const response = await Http.post(`${PROFILE_BASE_URL}/create`, payload);
    return unwrapData(response as ServiceResponse<RouteProfile>);
  }

  async modifyProfile(id: RouteProfileId, payload: RouteProfileInput & { revision: number }): Promise<RouteProfile> {
    const response = await Http.put(`${PROFILE_BASE_URL}/${id}/modify`, payload);
    return unwrapData(response as ServiceResponse<RouteProfile>);
  }

  async probeProfile(id: RouteProfileId): Promise<RouteProfile> {
    const response = await Http.post(`${PROFILE_BASE_URL}/${id}/probe`, {});
    return unwrapData(response as ServiceResponse<RouteProfile>);
  }

  async rebuildProfile(id: RouteProfileId): Promise<RouteProfileBuildStatus> {
    const response = await Http.post(`${PROFILE_BASE_URL}/${id}/rebuild`, {});
    return unwrapData(response as ServiceResponse<RouteProfileBuildStatus>);
  }

  async getBuildStatus(id: RouteProfileId, signal?: AbortSignal): Promise<RouteProfileBuildStatus> {
    const response = await Http.get(`${PROFILE_BASE_URL}/${id}/build-status`, {}, { signal });
    return unwrapData(response as ServiceResponse<RouteProfileBuildStatus>);
  }

  async activateProfile(id: RouteProfileId): Promise<RouteProfile> {
    const response = await Http.put(`${PROFILE_BASE_URL}/${id}/activate`, {});
    return unwrapData(response as ServiceResponse<RouteProfile>);
  }

  async preview(agentId: AgentId, payload: RoutePreviewRequest): Promise<RoutePreviewResponse> {
    const response = await Http.post(`/ai/data-agent/${agentId}/routing/preview`, payload);
    return unwrapData(response as ServiceResponse<RoutePreviewResponse>);
  }
}

export default new RoutingService();
