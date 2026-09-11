/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

/**
 * Agent 授权中心 PAP（Policy Authorization Point）服务封装。
 * 
 * 对应后端 AgentAuthorizationPapController（/agent-authorizations）。
 * 功能：策略模板、策略 CRUD、版本管理、绑定管理、授权管理、DB 决策模拟。
 * 
 * @author Jack (PR-3b Frontend Implementation)
 */

// ==================== 枚举类型 ====================

/** 策略状态：DRAFT-草稿 / PUBLISHED-已发布 / RETIRED-已退役 */
export type PolicyStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

/** 绑定主体类型：DATA_AGENT-数据智能体 / DIGITAL_EMPLOYEE-数字员工 */
export type BindingOwnerType = 'DATA_AGENT' | 'DIGITAL_EMPLOYEE';

/** 生效环境：SANDBOX-沙箱 / PRODUCTION-生产 */
export type Environment = 'SANDBOX' | 'PRODUCTION';

/** 授权对象类型：DATA_AGENT-数据智能体 / DIGITAL_EMPLOYEE-数字员工 */
export type GrantOwnerType = 'DATA_AGENT' | 'DIGITAL_EMPLOYEE';

/** 授权主体类型：USER-用户 / TEAM-团队 / PERMISSION-权限码 / TENANT-租户 */
export type GrantSubjectType = 'USER' | 'TEAM' | 'PERMISSION' | 'TENANT';

/** 授权权限：DISCOVER-目录可见 / USE-可使用 */
export type GrantPermission = 'DISCOVER' | 'USE';

/** 请求主体类别：CALLER-调用者 / DIGITAL_EMPLOYEE-数字员工 */
export type SubjectKind = 'CALLER' | 'DIGITAL_EMPLOYEE';

/** 能力动作：READ/WRITE/EXECUTE/START_UNATTENDED/DISCOVER/USE/READ_KNOWLEDGE/READ_MEMORY/WRITE_MEMORY */
export type Action =
  | 'READ'
  | 'WRITE'
  | 'EXECUTE'
  | 'START_UNATTENDED'
  | 'DISCOVER'
  | 'USE'
  | 'READ_KNOWLEDGE'
  | 'READ_MEMORY'
  | 'WRITE_MEMORY';

/** 规则效果：ALLOW-允许 / DENY-拒绝 */
export type Effect = 'ALLOW' | 'DENY';

/** 义务类型：APPROVAL-审批 / MASK_FIELDS-字段脱敏 / FILTER_FIELDS-字段过滤 */
export type Obligation = 'APPROVAL' | 'MASK_FIELDS' | 'FILTER_FIELDS';

/** IAM 不可用行为：ALLOW-允许降级 / DENY-拒绝降级 */
export type IamUnavailableBehavior = 'ALLOW' | 'DENY';

/** 主体模式：INDIVIDUAL-个体 / ORGROUP-或组 */
export type SubjectMode = 'INDIVIDUAL' | 'OR_GROUP';

// ==================== 响应/请求 DTO ====================

/** 授权模板响应（code 由后端模板枚举编码填充，恒非空） */
export interface TemplateResp {
  /** 模板编码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER */
  code: string;
  /** 模板默认策略 JSON */
  defaultPolicyJson?: string;
}

/** 策略分页查询请求 */
export interface PolicyPageQueryReq {
  current?: number;
  size?: number;
  /** 策略编码（模糊） */
  code?: string;
  /** 策略名称（模糊） */
  name?: string;
  /** 状态：DRAFT/PUBLISHED/RETIRED */
  status?: PolicyStatus | '';
  /** 来源模板编码 */
  templateCode?: string;
}

/** 策略主档实体 */
export interface PolicyEntity {
  /** 策略 ID */
  id?: string;
  /** 所属租户 ID */
  tenantId?: string;
  /** 策略编码，租户内唯一 */
  code?: string;
  /** 策略名称 */
  name?: string;
  /** 来源模板编码 */
  templateCode?: string;
  /** 状态：DRAFT/PUBLISHED/RETIRED */
  status?: PolicyStatus;
  /** 当前发布版本 ID */
  currentVersionId?: string;
  createTime?: string;
  lastModifyTime?: string;
}

/** 策略详情响应（含主档 + 发布版 + 草稿） */
export interface PolicyDetailResp {
  /** 策略 ID */
  id?: string;
  /** 策略编码 */
  code?: string;
  /** 策略名称 */
  name?: string;
  /** 来源模板编码 */
  templateCode?: string;
  /** 状态：DRAFT/PUBLISHED/RETIRED */
  status?: PolicyStatus;
  /** 当前发布版本 ID */
  currentVersionId?: string;
  /** 当前发布版本号 */
  currentVersionNo?: number;
  /** 当前发布版本策略 JSON */
  currentPolicyJson?: string;
  /** 当前发布版本策略 hash */
  currentPolicyHash?: string;
  /** 最新草稿版本 ID */
  draftVersionId?: string;
  /** 最新草稿版本号 */
  draftVersionNo?: number;
  /** 最新草稿策略 JSON */
  draftPolicyJson?: string;
}

/** 策略创建请求 */
export interface PolicyCreateReq {
  /** 策略编码，租户内唯一 */
  code: string;
  /** 策略名称 */
  name: string;
  /** 来源模板编码 */
  templateCode?: string;
  /** 策略 JSON 原文（与 templateCode 至少提供一个；提供时优先使用并经严格校验） */
  policyJson?: string;
}

/** 策略修改请求（仅 DRAFT 可修改） */
export interface PolicyModifyReq {
  /** 策略名称；为空保持原值 */
  name?: string;
  /** 草稿策略 JSON 原文；为空保持原值，提供时经严格校验后覆盖草稿版本 */
  policyJson?: string;
}

/** 策略版本响应 */
export interface PolicyVersionResp {
  /** 版本 ID */
  id?: string;
  /** 策略主档 ID */
  policyId?: string;
  /** 版本号，从 1 递增 */
  versionNo?: number;
  /** 策略 hash（规范化 JSON SHA-256） */
  policyHash?: string;
  /** 是否已发布 */
  published?: boolean;
  /** 创建时间 */
  createTime?: string;
}

/** 策略绑定实体 */
export interface BindingEntity {
  /** 绑定 ID */
  id?: string;
  /** 所属租户 ID */
  tenantId?: string;
  /** 绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE */
  ownerType?: BindingOwnerType;
  /** 绑定主体 ID */
  ownerId?: string;
  /** 生效环境：SANDBOX/PRODUCTION */
  environment?: Environment;
  /** 绑定的策略版本 ID */
  policyVersionId?: string;
  /** 乐观锁版本号 */
  bindRevision?: number;
  createTime?: string;
  lastModifyTime?: string;
}

/** 策略绑定 upsert 请求 */
export interface BindingUpsertReq {
  /** 绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE */
  ownerType: BindingOwnerType;
  /** 绑定主体 ID */
  ownerId: string;
  /** 生效环境：SANDBOX/PRODUCTION */
  environment: Environment;
  /** 绑定的策略版本 ID */
  policyVersionId: string;
  /** 期望的 bind_revision（更新已有绑定时必填，并发冲突返回 400 与当前值） */
  expectedBindRevision?: number;
}

/** 授权记录实体 */
export interface GrantEntity {
  /** 授权 ID */
  id?: string;
  /** 所属租户 ID */
  tenantId?: string;
  /** 授权对象类型：DATA_AGENT/DIGITAL_EMPLOYEE */
  ownerType?: GrantOwnerType;
  /** 授权对象 ID */
  ownerId?: string;
  /** 授权主体类型：USER/TEAM/PERMISSION/TENANT */
  subjectType?: GrantSubjectType;
  /** 授权主体 ID */
  subjectId?: string;
  /** 授权主体名称（冗余展示用） */
  subjectName?: string;
  /** 资源类型 */
  resourceType?: string;
  /** 资源 ID */
  resourceId?: string;
  /** 授权权限：DISCOVER-目录可见；USE-可使用 */
  permission?: GrantPermission;
  /** 来源：MANUAL-手工；LEGACY-存量迁移；SYSTEM-系统初始化 */
  sourceType?: string;
  /** 过期时间 */
  expireTime?: string;
  /** 状态：ACTIVE/REVOKED */
  status?: string;
  createTime?: string;
  lastModifyTime?: string;
}

/** 授权创建请求 */
export interface GrantCreateReq {
  /** 授权对象类型：DATA_AGENT/DIGITAL_EMPLOYEE */
  ownerType: GrantOwnerType;
  /** 授权对象 ID */
  ownerId: string;
  /** 授权主体类型：USER/TEAM/PERMISSION/TENANT */
  subjectType: GrantSubjectType;
  /** 授权主体 ID */
  subjectId: string;
  /** 授权主体名称（冗余展示用，可空） */
  subjectName?: string;
  /** 授权权限：DISCOVER-目录可见；USE-可使用 */
  permission: GrantPermission;
  /** 有效天数；空或非正数表示长期有效 */
  expireDays?: number;
}

/** 授权决策响应 */
export interface DecisionResp {
  /** 是否允许 */
  allowed?: boolean;
  /** 原因码 */
  reasonCode?: string;
  /** 允许时需执行的义务列表 */
  obligations?: Array<{ type?: string; fields?: string[] }>;
  /** MASK_FIELDS 义务对应的敏感字段名列表 */
  maskFields?: string[];
  /** 参与求值的策略哈希 */
  policyHash?: string;
  /** 决策时间 */
  evaluatedAt?: string;
}

/** 授权决策审计（GET /decisions/{decisionId}，当前租户隔离） */
export interface DecisionAuditResp {
  decisionId?: string;
  runId?: string;
  tenantId?: string;
  policyHash?: string;
  subjectKind?: string;
  reasonCode?: string;
  occurredAt?: string;
  detailedDecisionLog?: string;
}

/** 授权决策模拟请求（inline 策略方式） */
export interface DecisionSimulateReq {
  /** inline 策略 JSON（与 templateCode 二选一） */
  policyJson?: string;
  /** 模板代码：MODEL_ONLY/CALLER_READ_ONLY/CALLER_INTERACTIVE/DIGITAL_WORKER（与 policyJson 二选一） */
  templateCode?: string;
  /** 模板规则覆盖（仅 templateCode 方式生效，空则用模板默认规则） */
  rules?: Array<{
    name?: string;
    effect?: Effect;
    capabilityCodes?: string[];
    actions?: Action[];
    obligations?: Obligation[];
    maskFields?: string[];
  }>;
  /** 请求主体类别：CALLER/DIGITAL_EMPLOYEE */
  subjectKind: SubjectKind;
  /** 能力码 */
  capabilityCode: string;
  /** 请求动作 */
  action: Action;
  /** 请求的能力版本（可空） */
  capabilityVersion?: string;
  /** IAM 是否可用，默认 true */
  iamAvailable?: boolean;
}

/** 授权决策 DB 模拟请求 */
export interface DecisionSimulateDbReq {
  /** 绑定主体类型：DATA_AGENT/DIGITAL_EMPLOYEE */
  ownerType: BindingOwnerType;
  /** 绑定主体 ID */
  ownerId: string;
  /** 生效环境：SANDBOX/PRODUCTION */
  environment: Environment;
  /** 请求主体类别：CALLER/DIGITAL_EMPLOYEE */
  subjectKind: SubjectKind;
  /** 能力码 */
  capabilityCode: string;
  /** 请求动作 */
  action: Action;
  /** 请求的能力版本（可空） */
  capabilityVersion?: string;
  /** IAM 是否可用，默认 true */
  iamAvailable?: boolean;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/agent-authorizations';

class AuthorizationService {
  /** ============================================================
   * 策略模板管理
   * GET /templates
   * ============================================================ */
  
  async listTemplates(): Promise<TemplateResp[]> {
    const response = await Http.get(API_BASE_URL + '/templates');
    return unwrapData(response as ServiceResponse<TemplateResp[]>);
  }

  /** ============================================================
   * 策略 CRUD（Policy Management）
   * POST /policies/create
   * PUT /policies/{id}/modify
   * POST /policies/page
   * GET /policies/{id}/detail
   * DELETE /policies/{id}
   * ============================================================ */

  /** 创建授权策略 */
  async createPolicy(payload: PolicyCreateReq): Promise<PolicyDetailResp> {
    const response = await Http.post(API_BASE_URL + '/policies/create', payload);
    return unwrapData(response as ServiceResponse<PolicyDetailResp>);
  }

  /** 修改授权策略（仅 DRAFT） */
  async modifyPolicy(id: string, payload: PolicyModifyReq): Promise<PolicyDetailResp> {
    const response = await Http.put(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/modify`, payload);
    return unwrapData(response as ServiceResponse<PolicyDetailResp>);
  }

  /** 分页查询授权策略 */
  async pagePolicies(query: PolicyPageQueryReq): Promise<PageResponse<PolicyEntity[]>> {
    const response = await Http.post(
      API_BASE_URL + '/policies/page',
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<PolicyEntity>>);
  }

  /** 查询授权策略详情 */
  async fetchPolicyDetail(id: string): Promise<PolicyDetailResp> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.get(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/detail`);
    return unwrapDataOr(response as ServiceResponse<PolicyDetailResp>, {} as PolicyDetailResp);
  }

  /** 删除授权策略 */
  async deletePolicy(id: string): Promise<void> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.delete(`${API_BASE_URL}/policies/${encodeURIComponent(id)}`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** ============================================================
   * 版本管理（Version Control）
   * POST /policies/{id}/versions/publish
   * POST /policies/{id}/versions/draft
   * GET /policies/{id}/versions
   * ============================================================ */

  /** 发布授权策略版本（CAS） */
  async publishPolicy(id: string): Promise<PolicyDetailResp> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.post(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/versions/publish`);
    return unwrapData(response as ServiceResponse<PolicyDetailResp>);
  }

  /** 从当前版本开新草稿 */
  async createDraftFromCurrent(id: string): Promise<PolicyDetailResp> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.post(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/versions/draft`);
    return unwrapData(response as ServiceResponse<PolicyDetailResp>);
  }

  /** 查询策略版本列表 */
  async listPolicyVersions(id: string): Promise<PolicyVersionResp[]> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.get(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/versions`);
    return unwrapData(response as ServiceResponse<PolicyVersionResp[]>);
  }

  /** 停用策略：PUBLISHED → RETIRED */
  async retirePolicy(id: string): Promise<void> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.put(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/retire`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** 启用策略：RETIRED → PUBLISHED */
  async enablePolicy(id: string): Promise<void> {
    if (!id) throw new Error('策略 ID 不能为空');
    const response = await Http.put(`${API_BASE_URL}/policies/${encodeURIComponent(id)}/enable`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** ============================================================
   * 绑定管理（Binding Management）
   * PUT /bindings
   * GET /bindings?ownerType&ownerId&environment
   * ============================================================ */

  /** 策略绑定 upsert（乐观锁 CAS） */
  async upsertBinding(payload: BindingUpsertReq): Promise<BindingEntity> {
    const response = await Http.put(API_BASE_URL + '/bindings', payload);
    return unwrapData(response as ServiceResponse<BindingEntity>);
  }

  /** 查询策略绑定 */
  async fetchBinding(ownerType: BindingOwnerType, ownerId: string, environment: Environment): Promise<BindingEntity> {
    const params = new URLSearchParams();
    params.append('ownerType', ownerType);
    params.append('ownerId', ownerId);
    params.append('environment', environment);
    const response = await Http.get(`${API_BASE_URL}/bindings?${params.toString()}`);
    return unwrapDataOr(response as ServiceResponse<BindingEntity>, {} as BindingEntity);
  }

  /** ============================================================
   * 授权管理（Grant Management）
   * POST /grants/create
   * GET /grants?ownerType&ownerId
   * DELETE /grants/{id}
   * ============================================================ */

  /** 创建授权记录 */
  async createGrant(payload: GrantCreateReq): Promise<GrantEntity> {
    const response = await Http.post(API_BASE_URL + '/grants/create', payload);
    return unwrapData(response as ServiceResponse<GrantEntity>);
  }

  /** 查询授权记录列表 */
  async listGrants(ownerType: GrantOwnerType, ownerId: string): Promise<GrantEntity[]> {
    const params = new URLSearchParams();
    params.append('ownerType', ownerType);
    params.append('ownerId', ownerId);
    const response = await Http.get(`${API_BASE_URL}/grants?${params.toString()}`);
    return unwrapData(response as ServiceResponse<GrantEntity[]>);
  }

  /** 删除授权记录 */
  async deleteGrant(id: string): Promise<void> {
    if (!id) throw new Error('授权 ID 不能为空');
    const response = await Http.delete(`${API_BASE_URL}/grants/${encodeURIComponent(id)}`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** ============================================================
   * 决策模拟（Decision Simulation）
   * POST /decisions/simulate（inline 策略或模板）
   * POST /decisions/simulate-db（连库读取绑定策略）
   * ============================================================ */

  /** 授权决策模拟（inline/模板方式） */
  async simulateDecision(payload: DecisionSimulateReq): Promise<DecisionResp> {
    const response = await Http.post(API_BASE_URL + '/decisions/simulate', normalizeObject(payload));
    return unwrapData(response as ServiceResponse<DecisionResp>);
  }

  /** 授权决策 DB 模拟（按绑定关系读库） */
  async simulateDecisionFromDb(payload: DecisionSimulateDbReq): Promise<DecisionResp> {
    const response = await Http.post(API_BASE_URL + '/decisions/simulate-db', normalizeObject(payload));
    return unwrapData(response as ServiceResponse<DecisionResp>);
  }

  /** 按 decisionId 读取当前租户的授权决策审计（GET /decisions/{decisionId}） */
  async getDecision(decisionId: string): Promise<DecisionAuditResp> {
    if (!decisionId) throw new Error('decisionId 不能为空');
    const response = await Http.get(`${API_BASE_URL}/decisions/${encodeURIComponent(decisionId)}`);
    return unwrapData(response as ServiceResponse<DecisionAuditResp>);
  }
}

const normalizeObject = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== '' && item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

export default new AuthorizationService();
