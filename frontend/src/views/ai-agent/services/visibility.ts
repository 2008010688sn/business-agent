import { Http } from '@/service/request';
import {
  normalizeList,
  toPageResponse,
  unwrapData,
  unwrapDataOr,
  type ApiResponse,
  type MybatisPage,
  type PageResponse,
  type XxCloudResult
} from './common';
import { normalizeAgent, type Agent, type AgentId } from './agent';

const API_BASE_URL = '/ai/data-agent';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

export type AgentVisibilityCatalogStatus = 'VISIBLE' | 'APPLYABLE' | 'PENDING' | 'NOT_APPLYABLE';
export type AgentVisibilityApprovalMode = 'LOCAL' | 'WORKFLOW';
export type AgentVisibilityApplyMode = 'DISABLED' | 'AUTO_APPROVE' | 'APPROVAL_REQUIRED';
export type AgentVisibilitySubjectType = 'USER' | 'TEAM' | 'PERMISSION' | 'TENANT';

export interface AgentUserCatalogItem {
  agent: Agent;
  visibilityStatus: AgentVisibilityCatalogStatus | string;
  applyMode?: AgentVisibilityApplyMode | string;
  approvalMode?: AgentVisibilityApprovalMode | string;
  canApply?: boolean;
  disabledReason?: string;
  pendingApplicationId?: string;
}

export interface AgentUserCatalogPageQuery {
  current?: number;
  size?: number;
  keyword?: string;
  status?: string;
}

export interface AgentVisibilityApplicationCreateRequest {
  agentId?: AgentId;
  reason?: string;
  grantDays?: number;
}

export interface AgentVisibilityPolicy {
  id?: string;
  agentId?: string;
  conversationScope?: string;
  catalogScope?: string;
  applyMode?: string;
  approvalMode?: string;
  workflowFlowCode?: string;
  riskLevel?: string;
  defaultGrantDays?: number;
  approverConfigJson?: string;
  status?: string;
}

export interface AgentVisibilityGrant {
  id?: string;
  agentId?: string;
  agentName?: string;
  subjectType?: string;
  subjectId?: string;
  subjectName?: string;
  sourceType?: string;
  applicationId?: string;
  expireTime?: string;
  status?: string;
  createTime?: string;
}

export interface AgentVisibilityApplication {
  id?: string;
  agentId?: string;
  agentName?: string;
  applicantUserId?: string;
  applicantNickName?: string;
  reason?: string;
  grantDays?: number;
  status?: string;
  approvalMode?: string;
  approverUserId?: string;
  approverNickName?: string;
  approvalComment?: string;
  workflowFlowCode?: string;
  workflowInstanceId?: string;
  workflowStatus?: string;
  businessCode?: string;
  submitTime?: string;
  finishTime?: string;
  createTime?: string;
}

export interface AgentVisibilityAgentOption {
  id?: string;
  name?: string;
  status?: string;
}

export interface AgentVisibilitySubjectOption {
  value: string;
  label: string;
  description?: string;
}

interface UserSubjectRecord {
  id?: string;
  username?: string;
  nickName?: string;
  orgName?: string;
}

interface TeamSubjectRecord {
  id?: string;
  name?: string;
  directorName?: string;
}

interface TenantSubjectRecord {
  id?: string;
  name?: string;
  code?: string;
  statusName?: string;
}

export interface AgentVisibilityAgentOptionPageQuery {
  current?: number;
  size?: number;
  agentId?: AgentId;
  keyword?: string;
  status?: string;
}

export interface AgentVisibilityGrantPageQuery {
  agentId?: AgentId;
  current?: number;
  size?: number;
  subjectType?: string;
  subjectId?: string;
  subjectName?: string;
  status?: string;
}

export interface AgentVisibilityGrantCreateRequest {
  agentId?: AgentId;
  subjectType?: string;
  subjectId?: string;
  subjectName?: string;
  expireTime?: string;
}

export interface AgentVisibilityApplicationPageQuery {
  current?: number;
  size?: number;
  agentId?: AgentId;
  agentName?: string;
  applicantUserId?: string;
  applicantNickName?: string;
  status?: string;
  approvalMode?: string;
  businessCode?: string;
  submitTimeStart?: string;
  submitTimeEnd?: string;
}

export interface AgentVisibilityApplicationAuditRequest {
  status?: string;
  approvalComment?: string;
  grantDays?: number;
}

class AgentVisibilityService {
  async queryUserCatalogPage(query: AgentUserCatalogPageQuery): Promise<PageResponse<AgentUserCatalogItem[]>> {
    const response = await Http.post(`${API_BASE_URL}/user-catalog/page`, query);
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentUserCatalogItem>>);
    return {
      ...page,
      data: page.data.map(normalizeCatalogItem)
    };
  }

  async createApplication(
    agentId: AgentId,
    request: AgentVisibilityApplicationCreateRequest
  ): Promise<AgentVisibilityApplication> {
    const response = await Http.post(`${API_BASE_URL}/visibility-applications/create`, {
      ...request,
      agentId: String(agentId)
    });
    return normalizeApplication(unwrapData(response as ServiceResponse<AgentVisibilityApplication>));
  }

  async queryMyApplicationsPage(
    query: AgentVisibilityApplicationPageQuery
  ): Promise<PageResponse<AgentVisibilityApplication[]>> {
    const response = await Http.post(`${API_BASE_URL}/visibility-applications/my-page`, query);
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentVisibilityApplication>>);
    return {
      ...page,
      data: page.data.map(normalizeApplication)
    };
  }

  async getPolicy(agentId: AgentId): Promise<AgentVisibilityPolicy> {
    const response = await Http.post(`${API_BASE_URL}/visibility-policy/query`, { agentId: String(agentId) });
    return normalizePolicy(unwrapData(response as ServiceResponse<AgentVisibilityPolicy>));
  }

  async modifyPolicy(agentId: AgentId, policy: AgentVisibilityPolicy): Promise<AgentVisibilityPolicy> {
    const response = await Http.put(`${API_BASE_URL}/visibility-policy/modify`, {
      ...policy,
      agentId: String(agentId)
    });
    return normalizePolicy(unwrapData(response as ServiceResponse<AgentVisibilityPolicy>));
  }

  async queryGrantsPage(
    agentId: AgentId,
    query: AgentVisibilityGrantPageQuery
  ): Promise<PageResponse<AgentVisibilityGrant[]>> {
    const response = await Http.post(`${API_BASE_URL}/visibility-grants/page`, {
      ...query,
      agentId: String(agentId)
    });
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentVisibilityGrant>>);
    return {
      ...page,
      data: page.data.map(normalizeGrant)
    };
  }

  async createGrant(agentId: AgentId, request: AgentVisibilityGrantCreateRequest): Promise<AgentVisibilityGrant> {
    const response = await Http.post(`${API_BASE_URL}/visibility-grants/create`, {
      ...request,
      agentId: String(agentId)
    });
    return normalizeGrant(unwrapData(response as ServiceResponse<AgentVisibilityGrant>));
  }

  async deleteGrant(id: string): Promise<void> {
    await Http.delete(`${API_BASE_URL}/visibility-grants/${encodeURIComponent(String(id))}`);
  }

  async queryApplicationsPage(
    query: AgentVisibilityApplicationPageQuery
  ): Promise<PageResponse<AgentVisibilityApplication[]>> {
    const response = await Http.post(`${API_BASE_URL}/visibility-applications/page`, query);
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentVisibilityApplication>>);
    return {
      ...page,
      data: page.data.map(normalizeApplication)
    };
  }

  async queryApplicationAgentOptionsPage(
    query: AgentVisibilityAgentOptionPageQuery
  ): Promise<PageResponse<AgentVisibilityAgentOption[]>> {
    const response = await Http.post(`${API_BASE_URL}/visibility-applications/agent-options/page`, query);
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentVisibilityAgentOption>>);
    return {
      ...page,
      data: page.data.map(normalizeAgentOption)
    };
  }

  async queryGrantSubjectOptions(
    subjectType: AgentVisibilitySubjectType,
    keyword = '',
    current = 1,
    size = 20
  ): Promise<AgentVisibilitySubjectOption[]> {
    if (subjectType === 'USER') {
      return this.queryUserSubjectOptions(keyword, current, size);
    }
    if (subjectType === 'TEAM') {
      return this.queryTeamSubjectOptions(keyword, current, size);
    }
    if (subjectType === 'PERMISSION') {
      return this.queryPermissionSubjectOptions(keyword, size);
    }
    if (subjectType === 'TENANT') {
      return this.queryTenantSubjectOptions(keyword, current, size);
    }
    return [];
  }

  async queryUserSubjectOptions(_keyword = '', _current = 1, _size = 20): Promise<AgentVisibilitySubjectOption[]> {
    return [];
  }

  async queryTeamSubjectOptions(_keyword = '', _current = 1, _size = 20): Promise<AgentVisibilitySubjectOption[]> {
    return [];
  }

  async queryPermissionSubjectOptions(_keyword = '', _size = 20): Promise<AgentVisibilitySubjectOption[]> {
    return [];
  }

  async queryTenantSubjectOptions(_keyword = '', _current = 1, _size = 20): Promise<AgentVisibilitySubjectOption[]> {
    return [];
  }

  async approveApplication(
    id: string,
    request: AgentVisibilityApplicationAuditRequest
  ): Promise<AgentVisibilityApplication> {
    const response = await Http.put(
      `${API_BASE_URL}/visibility-applications/${encodeURIComponent(String(id))}/status`,
      {
        ...request,
        status: 'APPROVED'
      }
    );
    return normalizeApplication(unwrapData(response as ServiceResponse<AgentVisibilityApplication>));
  }

  async rejectApplication(
    id: string,
    request: AgentVisibilityApplicationAuditRequest
  ): Promise<AgentVisibilityApplication> {
    const response = await Http.put(
      `${API_BASE_URL}/visibility-applications/${encodeURIComponent(String(id))}/status`,
      {
        ...request,
        status: 'REJECTED'
      }
    );
    return normalizeApplication(unwrapData(response as ServiceResponse<AgentVisibilityApplication>));
  }
}

const stringifyId = (value: unknown): string | undefined => {
  if (value === undefined || value === null || value === '') {
    return undefined;
  }
  return String(value);
};

const normalizeCatalogItem = (item: AgentUserCatalogItem): AgentUserCatalogItem => ({
  ...item,
  agent: item.agent ? normalizeAgent(item.agent) : {},
  pendingApplicationId: stringifyId(item.pendingApplicationId)
});

const normalizePolicy = (policy: AgentVisibilityPolicy): AgentVisibilityPolicy => ({
  ...policy,
  id: stringifyId(policy.id),
  agentId: stringifyId(policy.agentId)
});

const normalizeGrant = (grant: AgentVisibilityGrant): AgentVisibilityGrant => ({
  ...grant,
  id: stringifyId(grant.id),
  agentId: stringifyId(grant.agentId),
  applicationId: stringifyId(grant.applicationId)
});

const normalizeApplication = (application: AgentVisibilityApplication): AgentVisibilityApplication => ({
  ...application,
  id: stringifyId(application.id),
  agentId: stringifyId(application.agentId),
  workflowInstanceId: stringifyId(application.workflowInstanceId)
});

const normalizeAgentOption = (option: AgentVisibilityAgentOption): AgentVisibilityAgentOption => ({
  ...option,
  id: stringifyId(option.id)
});

const isSubjectOption = (option: AgentVisibilitySubjectOption | null): option is AgentVisibilitySubjectOption => {
  return option !== null;
};

const normalizeUserSubjectOption = (user: UserSubjectRecord): AgentVisibilitySubjectOption | null => {
  const value = stringifyId(user.id);
  if (!value) {
    return null;
  }
  const label = user.nickName || user.username || value;
  const description = [user.username, user.orgName]
    .filter(item => item && item !== label)
    .join(' / ');
  return {
    value,
    label,
    description: description || undefined
  };
};

const normalizeTeamSubjectOption = (team: TeamSubjectRecord): AgentVisibilitySubjectOption | null => {
  const value = stringifyId(team.id);
  if (!value) {
    return null;
  }
  return {
    value,
    label: team.name || value,
    description: team.directorName ? `负责人：${team.directorName}` : undefined
  };
};

const normalizeTenantSubjectOption = (tenant: TenantSubjectRecord): AgentVisibilitySubjectOption | null => {
  const value = stringifyId(tenant.id);
  if (!value) {
    return null;
  }
  const description = [tenant.code, tenant.statusName].filter(Boolean).join(' / ');
  return {
    value,
    label: tenant.name || value,
    description: description || undefined
  };
};

export default new AgentVisibilityService();
