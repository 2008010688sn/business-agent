/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import type { AgentId } from './agent';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { RouteRules, RouteRulesPayload } from '@/views/ai-agent/utils/routeRules';

export type SkillExecutionMode = 'KNOWLEDGE' | 'DETERMINISTIC' | 'REACT' | 'FLOW';
export type SkillKind = 'QUERY' | 'QA' | 'ACTION' | 'ORCHESTRATION';
export type SkillId = string;
export type SkillScope = 'TENANT';
export type SkillStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

export interface SkillCatalogItem {
  id: SkillId;
  tenantId?: string;
  skillCode: string;
  skillName: string;
  description?: string;
  category?: string;
  scope: SkillScope | string;
  skillKind: SkillKind | string;
  executionMode: SkillExecutionMode | string;
  status: SkillStatus | string;
  latestDraftVersionId?: string;
  publishedVersionId?: string;
  displayOrder?: number;
  createTime?: string;
  lastModifyTime?: string;
}

export interface SkillVersionSummary {
  id: string;
  versionNo: number;
  status: string;
  checksum?: string;
  publishedAt?: string;
}

export interface SkillVersionDetail extends SkillVersionSummary {
  skillName?: string;
  description?: string;
  category?: string;
  displayOrder?: number;
  skillKind?: SkillKind | string;
  executionMode?: SkillExecutionMode | string;
  skillMarkdown?: string;
  routeRules: RouteRulesPayload;
  knowledgeConfig: Record<string, unknown>;
  reactConfig: Record<string, unknown>;
  flowDefinition: Record<string, unknown>;
  variablesSchema: Record<string, unknown>;
  flowRuntimeConfig: Record<string, unknown>;
  flowPolicyConfig: Record<string, unknown>;
  resourceRequirement: Record<string, unknown>;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  datasourceConfig: Record<string, unknown>;
  semanticConfig: Record<string, unknown>;
  runtimeConfig: Record<string, unknown>;
}

export interface SkillDetail extends SkillCatalogItem {
  version?: SkillVersionDetail;
  versions: SkillVersionSummary[];
}

export interface SkillPageQuery {
  current?: number;
  size?: number;
  keyword?: string;
  status?: string;
  executionMode?: string;
}

export interface SkillSavePayload {
  skillCode: string;
  skillName: string;
  description?: string;
  category?: string;
  scope: SkillScope | string;
  skillKind: SkillKind | string;
  executionMode: SkillExecutionMode | string;
  displayOrder?: number;
  skillMarkdown?: string;
  routeRules?: RouteRulesPayload;
  knowledgeConfig?: Record<string, unknown>;
  reactConfig?: Record<string, unknown>;
  flowDefinition?: Record<string, unknown>;
  variablesSchema?: Record<string, unknown>;
  flowRuntimeConfig?: Record<string, unknown>;
  flowPolicyConfig?: Record<string, unknown>;
  resourceRequirement?: Record<string, unknown>;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
  runtimeConfig?: Record<string, unknown>;
  toolRefs?: SkillToolRefSave[] | null;
}

export interface SkillValidationResult {
  valid: boolean;
  errors: string[];
  warnings: string[];
  issues?: FlowValidationIssue[];
}

export interface FlowValidationIssue {
  code: string;
  path?: string;
  nodeId?: string;
  severity: string;
  message: string;
}

export interface SkillRouteTestResult {
  matched: boolean;
  skillCode: string;
  reasonCode: string;
  lexicalScore: number;
  exact: boolean;
  excluded: boolean;
  matchedSignals: string[];
}

export interface SkillFlowNodePreview {
  nodeId: string;
  nodeType: string;
  behavior: 'READ_TOOL' | 'BLOCKED_WRITE' | 'WAIT_USER' | 'TERMINAL' | 'LOCAL' | string;
  next?: string;
  branches: string[];
}

export interface SkillFlowTestResult {
  valid: boolean;
  startNode?: string;
  nodes: SkillFlowNodePreview[];
  errors: string[];
}

export interface SkillPackageBundle {
  'manifest.yaml': Record<string, unknown>;
  'SKILL.md': string;
  'flow.yaml'?: Record<string, unknown>;
  'resource-requirement.json': Record<string, unknown>;
  'input-schema.json': Record<string, unknown>;
  'output-schema.json': Record<string, unknown>;
  'tool-refs.json'?: Array<Record<string, unknown>>;
}

export interface SkillPreviewTool {
  resourceVersionId?: string;
  resourceKey: string;
  versionNo?: number;
  accessMode?: string;
  exposureMode?: string;
  permissionCode?: string;
  write?: boolean;
  confirmRequired?: boolean;
  idempotencyRequired?: boolean;
}

export interface SkillPreviewNode {
  nodeId: string;
  nodeType: string;
  resourceVersionId?: string;
  next?: string;
  branches: string[];
  modelParticipates: boolean;
  writes: boolean;
  requiresConfirmation: boolean;
  failureNext?: string;
}

export interface SkillModelParticipation {
  nodeId: string;
  participates: boolean;
  reason: string;
}

export interface SkillResourceSummary {
  datasourceCount: number;
  semanticModelCount: number;
  businessKnowledgeCount: number;
  skillKnowledgeCount: number;
  modelStages: string[];
  snapshotStatus: string;
}

export interface SkillPreview {
  skillCode: string;
  skillName: string;
  executionMode: string;
  versionId?: string;
  versionNo?: number;
  status: string;
  skillMarkdown?: string;
  routeRules: RouteRules;
  manifest: Record<string, unknown>;
  flowDefinition: Record<string, unknown>;
  flowRuntimeConfig: Record<string, unknown>;
  flowPolicyConfig: Record<string, unknown>;
  tools: SkillPreviewTool[];
  nodes: SkillPreviewNode[];
  modelParticipation: SkillModelParticipation[];
  errors: string[];
  warnings: string[];
  resourceSummary: SkillResourceSummary;
  effectiveReactBudget: Record<string, number>;
}

export interface AgentSkillBinding {
  skillId: SkillId;
  pinnedSkillVersionId: string;
  priority: number;
  enabled: boolean;
}

export interface AgentSkillBindingOption {
  skillId: SkillId;
  skillCode: string;
  skillName: string;
  description?: string;
  executionMode: string;
  status: string;
  displayOrder?: number;
  publishedVersionId?: string;
  selectable: boolean;
  unavailableReason?: string;
  publishedVersions: AgentSkillPublishedVersionOption[];
}

export interface AgentSkillBindingEditorContext {
  bindings: AgentSkillBinding[];
  skills: AgentSkillBindingOption[];
  issues: string[];
}

export interface AgentSkillToolRef {
  id?: string;
  skillId?: SkillId;
  skillVersionId?: string;
  resourceKey: string;
  resourceVersionId: string;
  usage?: string;
  status?: string;
  displayOrder?: number;
  extConfig?: Record<string, unknown>;
}

export interface SkillToolRefSave {
  resourceVersionId: string;
  usage?: string;
  displayOrder?: number;
}

export interface ToolVersionOption {
  resourceVersionId: string;
  resourceKey: string;
  resourceName?: string;
  versionNo: number;
  accessMode: string;
  exposureMode: string;
  selectable: boolean;
  unavailableReason?: string;
}

export interface SkillToolEditorContext {
  refs: SkillToolRefSave[];
  options: ToolVersionOption[];
}

export interface AgentSkillPublishedVersionOption {
  id: string;
  versionNo: number;
  skillName?: string;
  description?: string;
  skillKind?: SkillKind | string;
  executionMode?: SkillExecutionMode | string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

class SkillService {
  async page(query: SkillPageQuery): Promise<PageResponse<SkillCatalogItem[]>> {
    const response = await Http.post('/ai/skills/page', query);
    return toPageResponse(response as ServiceResponse<MybatisPage<SkillCatalogItem>>);
  }

  async listPublished(): Promise<SkillCatalogItem[]> {
    const page = await this.page({ current: 1, size: 500, status: 'PUBLISHED' });
    return page.data;
  }

  async detail(skillCode: string): Promise<SkillDetail> {
    const response = await Http.get(`/ai/skills/${encodeURIComponent(skillCode)}/detail`);
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async preview(skillCode: string, version: 'draft' | 'published'): Promise<SkillPreview> {
    const response = await Http.get(`/ai/skills/${encodeURIComponent(skillCode)}/preview`, { version });
    return unwrapData(response as ServiceResponse<SkillPreview>);
  }

  async toolEditorContext(params: {
    skillCode?: string;
    executionMode: SkillExecutionMode | string;
  }): Promise<SkillToolEditorContext> {
    const response = await Http.get('/ai/skills/tool-editor-context', params);
    return unwrapData(response as ServiceResponse<SkillToolEditorContext>);
  }

  async create(payload: SkillSavePayload): Promise<SkillDetail> {
    const response = await Http.post('/ai/skills/create', payload);
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async modify(skillCode: string, payload: SkillSavePayload): Promise<SkillDetail> {
    const response = await Http.put(`/ai/skills/${encodeURIComponent(skillCode)}/modify`, payload);
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async clonePublished(skillCode: string, payload: { skillCode: string; skillName: string }): Promise<SkillDetail> {
    const response = await Http.post(`/ai/skills/${encodeURIComponent(skillCode)}/clone`, payload);
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async publish(skillCode: string): Promise<SkillDetail> {
    const response = await Http.put(`/ai/skills/${encodeURIComponent(skillCode)}/publish`, {});
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async validate(skillCode: string): Promise<SkillValidationResult> {
    const response = await Http.post(`/ai/skills/${encodeURIComponent(skillCode)}/validate`, {});
    return unwrapData(response as ServiceResponse<SkillValidationResult>);
  }

  async testRoute(skillCode: string, query: string): Promise<SkillRouteTestResult> {
    const response = await Http.post(`/ai/skills/${encodeURIComponent(skillCode)}/route-test`, { query });
    return unwrapData(response as ServiceResponse<SkillRouteTestResult>);
  }

  async testFlow(skillCode: string): Promise<SkillFlowTestResult> {
    const response = await Http.post(`/ai/skills/${encodeURIComponent(skillCode)}/flow-test`, {});
    return unwrapData(response as ServiceResponse<SkillFlowTestResult>);
  }

  async importBundle(bundle: SkillPackageBundle): Promise<SkillDetail> {
    const response = await Http.post('/ai/skills/import', { bundle });
    return unwrapData(response as ServiceResponse<SkillDetail>);
  }

  async exportBundle(skillCode: string): Promise<SkillPackageBundle> {
    const response = await Http.get(`/ai/skills/${encodeURIComponent(skillCode)}/export`);
    return unwrapData(response as ServiceResponse<SkillPackageBundle>);
  }

  async delete(skillCode: string): Promise<void> {
    await Http.delete(`/ai/skills/${encodeURIComponent(skillCode)}`);
  }

  async listToolRefs(skillCode: string): Promise<AgentSkillToolRef[]> {
    const response = await Http.get(`/ai/skills/${encodeURIComponent(skillCode)}/tool-refs`);
    return unwrapDataOr(response as ServiceResponse<AgentSkillToolRef[]>, []);
  }

  async replaceToolRefs(skillCode: string, refs: AgentSkillToolRef[]): Promise<AgentSkillToolRef[]> {
    const response = await Http.put(`/ai/skills/${encodeURIComponent(skillCode)}/tool-refs`, refs);
    return unwrapDataOr(response as ServiceResponse<AgentSkillToolRef[]>, []);
  }

  async getAgentBindings(agentId: AgentId): Promise<AgentSkillBinding[]> {
    const response = await Http.get(`/ai/data-agent/${agentId}/skill-bindings`);
    return unwrapDataOr(response as ServiceResponse<AgentSkillBinding[]>, []);
  }

  async getAgentBindingEditorContext(agentId: AgentId): Promise<AgentSkillBindingEditorContext> {
    const response = await Http.get(`/ai/data-agent/${agentId}/skill-bindings/editor-context`);
    return unwrapData(response as ServiceResponse<AgentSkillBindingEditorContext>);
  }

  async replaceAgentBindings(agentId: AgentId, bindings: AgentSkillBinding[]): Promise<AgentSkillBinding[]> {
    const response = await Http.put(`/ai/data-agent/${agentId}/skill-bindings`, { bindings });
    return unwrapDataOr(response as ServiceResponse<AgentSkillBinding[]>, []);
  }
}

export default new SkillService();
