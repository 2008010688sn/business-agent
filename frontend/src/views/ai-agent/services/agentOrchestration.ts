import { Http } from '@/service/request';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { Agent, AgentId } from './agent';
import type { RouteRulesPayload } from '@/views/ai-agent/utils/routeRules';

export interface AgentCollaborator {
  id?: AgentId;
  agentId?: AgentId;
  collaboratorAgentId?: AgentId;
  roleName?: string;
  capabilityDescription?: string;
  routingRules?: RouteRulesPayload;
  priority?: number;
  enabled?: boolean;
  collaboratorDataAgent?: Agent;
}

export interface OrchestrationClarificationConfig {
  timeAliases: string[];
  explicitMetricAliases: string[];
  ambiguousMetricAliases: string[];
  orderingMetricAliases: string[];
}

export interface AgentOrchestrationPolicy {
  id?: AgentId;
  agentId?: AgentId;
  maxCollaboratorsPerRun?: number;
  failureStrategy?: string;
  exposeTrace?: boolean;
  enabled?: boolean;
  clarificationConfig?: OrchestrationClarificationConfig;
}

export interface OrchestrationRun {
  id?: AgentId;
  agentId?: AgentId;
  threadId?: string;
  runtimeRequestId?: string;
  query?: string;
  status?: string;
  finalAnswer?: string;
  errorMessage?: string;
  routeMs?: number;
  collaboratorMs?: number;
  summaryMs?: number;
  totalMs?: number;
  collaboratorCount?: number;
  startedAt?: string;
  finishedAt?: string;
}

export interface OrchestrationStep {
  id?: AgentId;
  runId?: AgentId;
  stepNo?: number;
  collaboratorAgentId?: AgentId;
  task?: string;
  reason?: string;
  expectedOutput?: string;
  status?: string;
  answer?: string;
  evidence?: string;
  confidence?: string;
  errorCode?: string;
  errorMessage?: string;
  childThreadId?: string;
  childRuntimeRequestId?: string;
  durationMs?: number;
  reactMs?: number;
  toolCount?: number;
  toolFailCount?: number;
  startedAt?: string;
  finishedAt?: string;
}

export interface OrchestrationRunDetail {
  run?: OrchestrationRun;
  steps?: OrchestrationStep[];
}

export interface OrchestrationTrace {
  run?: OrchestrationRun;
  steps?: OrchestrationStep[];
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const BASE_URL = '/ai/data-agent/orchestration';

class AgentOrchestrationService {
  async listCollaborators(agentId: AgentId): Promise<AgentCollaborator[]> {
    const response = await Http.post(`${BASE_URL}/collaborators/query`, { agentId });
    return unwrapDataOr(response as ServiceResponse<AgentCollaborator[]>, []);
  }

  async createCollaborator(agentId: AgentId, payload: AgentCollaborator): Promise<AgentCollaborator> {
    const response = await Http.post(`${BASE_URL}/collaborators/create`, { ...payload, agentId });
    return unwrapDataOr(response as ServiceResponse<AgentCollaborator>, {});
  }

  async updateCollaborator(agentId: AgentId, id: AgentId, payload: AgentCollaborator): Promise<AgentCollaborator> {
    const response = await Http.put(`${BASE_URL}/collaborators/modify`, { ...payload, agentId, id });
    return unwrapDataOr(response as ServiceResponse<AgentCollaborator>, {});
  }

  async deleteCollaborator(agentId: AgentId, id: AgentId): Promise<void> {
    await Http.delete(`${BASE_URL}/collaborators`, { agentId, id });
  }

  async getPolicy(agentId: AgentId): Promise<AgentOrchestrationPolicy> {
    const response = await Http.post(`${BASE_URL}/policy/query`, { agentId });
    return unwrapDataOr(response as ServiceResponse<AgentOrchestrationPolicy>, {});
  }

  async updatePolicy(agentId: AgentId, payload: AgentOrchestrationPolicy): Promise<AgentOrchestrationPolicy> {
    const response = await Http.put(`${BASE_URL}/policy`, { ...payload, agentId });
    return unwrapDataOr(response as ServiceResponse<AgentOrchestrationPolicy>, {});
  }

  async listRuns(agentId: AgentId): Promise<OrchestrationRun[]> {
    const response = await Http.post(`${BASE_URL}/runs/query`, { agentId });
    return unwrapDataOr(response as ServiceResponse<OrchestrationRun[]>, []);
  }

  async getRun(agentId: AgentId, runId: AgentId): Promise<OrchestrationRunDetail> {
    const response = await Http.post(`${BASE_URL}/runs/detail/query`, { agentId, runId });
    return unwrapDataOr(response as ServiceResponse<OrchestrationRunDetail>, {});
  }

  async getRunTrace(agentId: AgentId, runId: AgentId): Promise<OrchestrationTrace> {
    const response = await Http.post(`${BASE_URL}/runs/trace/query`, { agentId, runId });
    return unwrapDataOr(response as ServiceResponse<OrchestrationTrace>, {});
  }

  async getRuntimeTrace(agentId: AgentId, runtimeRequestId: string): Promise<OrchestrationTrace> {
    const response = await Http.post(`${BASE_URL}/runtime/trace/query`, { agentId, runtimeRequestId });
    return unwrapDataOr(response as ServiceResponse<OrchestrationTrace>, {});
  }
}

export default new AgentOrchestrationService();
