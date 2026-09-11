/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import type { AgentUiMessage } from '@/views/ai-agent/utils/agentUi';
import { parseEmployeeConversationMessage } from '@/views/ai-agent/utils/employeeConversationStream';
import { toPageResponse, unwrapData, unwrapDataOr, unwrapList } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import { streamWithAuth } from './http';

/**
 * 数字员工（Digital Employee）服务封装。
 * 对应后端 DigitalEmployeeController（/digital-employees）。
 * 权限三档：query、use、manage；与后端 @SaCheckPermission 保持一致。
 */

/** 员工状态：DRAFT/ENABLED/DISABLED/ARCHIVED */
export type EmployeeStatus = 'DRAFT' | 'ENABLED' | 'DISABLED' | 'ARCHIVED';

/** Principal 状态：PENDING/READY/FAILED/DISABLED */
export type PrincipalStatus = 'PENDING' | 'READY' | 'FAILED' | 'DISABLED';

/** 自治级别 */
export type AutonomyLevel = 'ASSISTED' | 'AUTONOMOUS';

/** 数字员工身份（含主文档 4.1/4.2 字段） */
export interface DigitalEmployee {
  id?: string;
  tenantId?: string;
  employeeCode?: string;
  employeeName?: string;
  avatarFileId?: string;
  jobTitle?: string;
  description?: string;
  systemInstruction?: string;
  greeting?: string;
  status?: EmployeeStatus;
  managerUserId?: string;
  approverUserId?: string;
  iamPrincipalId?: string; // sp_前缀
  principalStatus?: PrincipalStatus;
  principalRevision?: number;
  modelConfigId?: string;
  routeProfileId?: string;
  sourceAgentId?: string;
  modelCount?: number;
  capabilityCount?: number;
  autonomyLevel?: AutonomyLevel;
  /** 非授权运营配置；详情接口回传 JSON 字符串，提交时用对象 */
  executionPolicy?: string | Record<string, unknown>;
  /** 创建时选用的岗位模板编码 */
  jobTemplateCode?: string;
  draftRevision?: number;
  stateVersion?: number;
  /** 灰度开关 spring.ai.xx.digital-employee.rollout.enabled；关闭时不可 PRINCIPAL */
  rolloutEnabled?: boolean;
  createBy?: string;
  createName?: string;
  createTime?: string;
  lastModifyTime?: string;
  lastModifyBy?: string;
  lastModifyName?: string;
}

/** 数字员工选择器摘要（GET /options，最多 100 条） */
export interface DigitalEmployeeOption {
  id?: string;
  employeeCode?: string;
  employeeName?: string;
  status?: string;
  iamPrincipalId?: string;
  principalStatus?: string;
}

/** Principal 已绑定角色回显（含后来停用/不可分配项） */
export interface EmployeePrincipalRole {
  id?: string;
  tenantId?: string;
  name?: string;
  code?: string;
  description?: string;
  status?: string;
  serviceAssignable?: boolean;
  superRole?: boolean;
  readonly?: boolean;
}

/** Principal 有效权限预览（套餐过滤后的功能权限码） */
export interface EmployeeAuthPreview {
  principalId?: string;
  tenantId?: string;
  funcPermissions?: string[];
  authRevision?: number;
}

export interface DigitalEmployeePageQuery {
  current?: number;
  size?: number;
  status?: EmployeeStatus | '';
  keyword?: string;
}

export interface DigitalEmployeeCreateRequest {
  employeeName: string;
  employeeCode?: string;
  avatarFileId?: string;
  jobTitle?: string;
  description?: string;
  systemInstruction?: string;
  greeting?: string;
  managerUserId?: string;
  approverUserId?: string;
  modelConfigId?: string;
  routeProfileId?: string;
  sourceAgentId?: string;
  autonomyLevel?: AutonomyLevel;
  executionPolicy?: Record<string, unknown>;
  /** 岗位模板编码，如 OPS_ANALYST */
  templateCode?: string;
}

export interface EmployeeJobTemplateSkillHint {
  skillCode?: string;
  displayName?: string;
  hint?: string;
}

export interface EmployeeJobTemplateTaskHint {
  taskName?: string;
  taskDescription?: string;
  taskType?: string;
  defaultAutonomyLevel?: AutonomyLevel;
  highRiskWrite?: boolean;
  triggerHint?: string;
}

export interface EmployeeJobTemplate {
  templateCode?: string;
  displayName?: string;
  jobTitle?: string;
  description?: string;
  systemInstruction?: string;
  greeting?: string;
  autonomyLevel?: AutonomyLevel;
  recommendedSkills?: EmployeeJobTemplateSkillHint[];
  recommendedTask?: EmployeeJobTemplateTaskHint;
}

export interface DigitalEmployeeModifyRequest {
  employeeName?: string;
  avatarFileId?: string;
  jobTitle?: string;
  description?: string;
  systemInstruction?: string;
  greeting?: string;
  managerUserId?: string;
  approverUserId?: string;
  modelConfigId?: string;
  routeProfileId?: string;
  autonomyLevel?: AutonomyLevel;
  executionPolicy?: Record<string, unknown>;
}

export interface EmployeeCapabilityBindRequest {
  /** 已发布 SkillVersion ID（后端 Long，JSON 数字直传） */
  skillVersionId: string;
  enabled?: boolean;
}

export interface EmployeeCapability {
  id?: string;
  employeeId?: string;
  skillVersionId?: string;
  enabled?: boolean;
}

export interface ConversationRequest {
  query: string;
  /** 后端 Long；响应侧 ToStringSerializer 序列化为字符串，回传保持字符串，Jackson 可解析 */
  sessionId?: string;
  /** 对话选用的 CHAT 模型配置 ID；须在员工可用列表内，空则用默认 */
  chatModelConfigId?: string;
}

export interface ConversationResponse {
  /** 后端 Long 经 ToStringSerializer 输出，前端一律按字符串处理 */
  sessionId?: string;
  reply?: string;
  executionMode?: 'MODEL_ONLY' | 'PRINCIPAL';
  /** 本次对话对应的运行 ID，可打开员工运行详情 */
  runtimeRunId?: string;
}

/** 员工 SSE 对话：复用现网 message/complete/error/runtime_progress */
export interface EmployeeConversationStarted {
  sessionId?: string;
  runtimeRunId?: string;
  executionMode?: ConversationResponse['executionMode'];
  /** 出现在 started/runtime_progress metadata 时回传，供 POST /ai/chat/runtime/stop */
  runtimeRequestId?: string;
}

export interface EmployeeConversationStreamHandlers {
  onStarted?: (info: EmployeeConversationStarted) => void;
  onMessage?: (text: string) => void;
  /** metadata.agentUi 经 parseAgentUi 成功时回调；纯文本仍走 onMessage */
  onAgentUi?: (ui: AgentUiMessage) => void;
  onRuntimeRequestId?: (id: string) => void;
  onError?: (error: Error) => void;
  onComplete?: () => void;
}

/** 员工履历行，对应后端 RuntimeRunResp */
export interface EmployeeRuntimeRun {
  id?: string;
  ownerType?: string;
  ownerId?: string;
  digitalEmployeeId?: string;
  releaseId?: string;
  agentId?: string;
  clientRequestId?: string;
  threadId?: string;
  runtimeRequestId?: string;
  triggerSource?: string;
  runMode?: string;
  query?: string;
  state?: string;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  createTime?: string;
  initiatorUserId?: string;
  initiatorUserName?: string;
  executionPrincipalId?: string;
  subjectKind?: string;
}

export interface EmployeeRuntimeArtifact {
  id?: string;
  stepKey?: string;
  schemaVersion?: string;
  data?: string | null;
  sensitivity?: string;
}

export interface EmployeeRuntimeStep {
  id?: string;
  stepKey?: string;
  stepName?: string;
  capabilityHandle?: string;
  state?: string;
  attemptCount?: number;
  maxAttempts?: number;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface EmployeeRunFeedback {
  rating?: 'UP' | 'DOWN' | string;
  comment?: string;
  createTime?: string;
}

export interface EmployeeRunDetail {
  run?: EmployeeRuntimeRun;
  finalAnswer?: string;
  steps?: EmployeeRuntimeStep[];
  latestSeq?: number | string;
  artifacts?: EmployeeRuntimeArtifact[];
  feedback?: EmployeeRunFeedback | null;
}

export interface EmployeeDigest {
  digestDate?: string;
  managerUserId?: string;
  runTotal?: number;
  runFailed?: number;
  waitingApproval?: number;
  feedbackDown?: number;
  summary?: string;
}

export interface EmployeeBudgetTypeAmount {
  budgetType?: string;
  amount?: number | string;
  runCount?: number | string;
}

export interface EmployeeBudgetReport {
  fromTime?: string;
  toTime?: string;
  totals?: EmployeeBudgetTypeAmount[];
}

export interface EmployeeRunPageQuery {
  current?: number;
  size?: number;
  state?: string;
  runMode?: string;
  keyword?: string;
}

/** 部署环境行，对应后端 DigitalEmployeeDeployment entity */
export interface DigitalEmployeeDeployment {
  id?: string;
  employeeId?: string;
  environment?: 'SANDBOX' | 'PRODUCTION' | string;
  activeReleaseId?: string;
  previousReleaseId?: string;
  deploymentVersion?: number;
  status?: 'INACTIVE' | 'ACTIVE' | 'ROLLING_BACK' | string;
  deployedBy?: string;
  deployedAt?: string;
  lastModifyTime?: string;
}

export interface EmployeeModelConfigItem {
  id?: string;
  employeeId?: string;
  modelConfigId?: string;
  isDefault?: boolean;
  userSelectable?: boolean;
  enabled?: boolean;
  modelConfig?: {
    id?: string;
    modelName?: string;
    provider?: string;
  };
}

export interface EmployeeRuntimeChatModel {
  modelConfigId?: string;
  isDefault?: boolean;
  userSelectable?: boolean;
  enabled?: boolean;
  selectable?: boolean;
  modelConfig?: {
    id?: string;
    modelName?: string;
    provider?: string;
  };
}

export interface UpdateEmployeeModelConfigRequest {
  defaultModelConfigId?: string;
  models?: Array<{
    modelConfigId: string;
    userSelectable?: boolean;
    enabled?: boolean;
  }>;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

/** 网关 ai 域前缀 + 后端 Controller @RequestMapping("/digital-employees") */
const API_BASE_URL = '/ai/digital-employees';

class DigitalEmployeeService {
  /** 分页查询（POST /page） */
  async fetchPage(query: DigitalEmployeePageQuery): Promise<PageResponse<DigitalEmployee[]>> {
    const response = await Http.post(
      `${API_BASE_URL}/page`,
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<DigitalEmployee>>);
  }

  /** 创建（POST /create）；返回档案（雪花 ID 为字符串） */
  async create(payload: DigitalEmployeeCreateRequest): Promise<DigitalEmployee> {
    const response = await Http.post(`${API_BASE_URL}/create`, normalizeObject(payload));
    return unwrapDataOr(response as ServiceResponse<DigitalEmployee>, {});
  }

  /** 岗位模板（GET /templates） */
  async listTemplates(): Promise<EmployeeJobTemplate[]> {
    const response = await Http.get(`${API_BASE_URL}/templates`);
    return unwrapList<EmployeeJobTemplate>(response);
  }

  /** 选择器（GET /options，最多 100 条） */
  async listOptions(keyword?: string, status?: string): Promise<DigitalEmployeeOption[]> {
    const response = await Http.get(
      `${API_BASE_URL}/options`,
      normalizeObject({ keyword, status })
    );
    return unwrapList<DigitalEmployeeOption>(response).map(item => ({
      ...item,
      id: item.id == null ? item.id : String(item.id)
    }));
  }

  /** 按 Principal ID 反查员工（GET /by-principal/{principalId}） */
  async findByPrincipalId(principalId: string): Promise<DigitalEmployee> {
    const response = await Http.get(
      `${API_BASE_URL}/by-principal/${encodeURIComponent(String(principalId))}`
    );
    return unwrapDataOr(response as ServiceResponse<DigitalEmployee>, {});
  }

  /** 查询员工 Principal 角色（GET /{id}/principal/roles）；无 Principal 时为空列表 */
  async listPrincipalRoles(id: string): Promise<EmployeePrincipalRole[]> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/principal/roles`);
    return unwrapDataOr(response as ServiceResponse<EmployeePrincipalRole[]>, []);
  }

  /** 全量替换 Principal 角色（PUT /{id}/principal/roles）；空数组=MODEL_ONLY */
  async replacePrincipalRoles(id: string, roleIds: string[]): Promise<void> {
    const response = await Http.put(`${API_BASE_URL}/${encodeURIComponent(String(id))}/principal/roles`, {
      roleIds
    });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 变更 Principal 状态（PUT /{id}/principal/status） */
  async updatePrincipalStatus(id: string, status: 'ENABLED' | 'DISABLED'): Promise<void> {
    const response = await Http.put(`${API_BASE_URL}/${encodeURIComponent(String(id))}/principal/status`, { status });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 预览有效权限（GET /{id}/principal/auth-preview）；禁止前端调 IAM /internal */
  async previewPrincipalAuth(id: string): Promise<EmployeeAuthPreview> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/principal/auth-preview`);
    return unwrapDataOr(response as ServiceResponse<EmployeeAuthPreview>, {});
  }

  /** 详情（GET /{id}/detail） */
  async fetchDetail(id: string): Promise<DigitalEmployee> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/detail`);
    return unwrapDataOr(response as ServiceResponse<DigitalEmployee>, {});
  }

  /** 修改（PUT /{id}/modify） */
  async modify(id: string, payload: DigitalEmployeeModifyRequest): Promise<void> {
    // 空字符串是后端清空可选文本字段的有效值，不能使用会丢弃 '' 的通用查询清洗。
    const response = await Http.put(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/modify`,
      preserveModifyValues(payload)
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 删除（DELETE /{id}） */
  async remove(id: string): Promise<void> {
    const response = await Http.delete(`${API_BASE_URL}/${encodeURIComponent(String(id))}`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** 封存（POST /{id}/archive，state_version CAS） */
  async archive(id: string, stateVersion: number): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/archive`, { stateVersion });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 启用（POST /{id}/enable，state_version CAS） */
  async enable(id: string, stateVersion: number): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/enable`, { stateVersion });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 停用（POST /{id}/disable，state_version CAS） */
  async disable(id: string, stateVersion: number): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/disable`, { stateVersion });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 手动触发 Principal 开通（POST /{id}/principal/provision） */
  async provisionPrincipal(id: string): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/principal/provision`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** 发布当前配置（POST /{id}/publish-current-config）：Seal + Publish + 激活 PRODUCTION */
  async publishCurrentConfig(id: string): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/publish-current-config`);
    unwrapData(response as ServiceResponse<void>);
  }

  /** 员工可用模型（GET /{id}/model-configs） */
  async listModelConfigs(id: string): Promise<EmployeeModelConfigItem[]> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/model-configs`);
    return unwrapDataOr(response as ServiceResponse<EmployeeModelConfigItem[]>, []);
  }

  /** 全量替换员工可用模型（PUT /{id}/model-configs） */
  async updateModelConfigs(id: string, payload: UpdateEmployeeModelConfigRequest): Promise<EmployeeModelConfigItem[]> {
    const response = await Http.put(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/model-configs`,
      normalizeObject(payload)
    );
    return unwrapDataOr(response as ServiceResponse<EmployeeModelConfigItem[]>, []);
  }

  /**
   * 从能力市场安装当前通过版本。
   * listingId 是市场条目 ID，versionNo 是市场条目版本号；底层 SkillVersion 由后端解析。
   */
  async installMarketSkill(employeeId: string, listingId: string, versionNo: number): Promise<string> {
    if (!employeeId || !listingId || versionNo === null || versionNo === undefined) {
      throw new Error('安装市场技能缺少员工、市场条目或版本号');
    }
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(employeeId))}/install-market-skill/${encodeURIComponent(
        String(listingId)
      )}/${encodeURIComponent(String(versionNo))}`
    );
    const installationId = unwrapDataOr(response as ServiceResponse<string | number>, '');
    return installationId === '' ? '' : String(installationId);
  }

  /** 对话页可选模型（GET /{id}/runtime-chat-models） */
  async listRuntimeChatModels(id: string): Promise<EmployeeRuntimeChatModel[]> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/runtime-chat-models`);
    return unwrapDataOr(response as ServiceResponse<EmployeeRuntimeChatModel[]>, []);
  }

  /** 绑定能力（POST /{id}/capabilities/bind）幂等 */
  async bindCapability(id: string, payload: EmployeeCapabilityBindRequest): Promise<void> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/capabilities/bind`,
      normalizeObject(payload)
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 解绑能力（DELETE /{id}/capabilities/{capabilityId}） */
  async unbindCapability(employeeId: string, capabilityId: string): Promise<void> {
    const response = await Http.delete(
      `${API_BASE_URL}/${encodeURIComponent(String(employeeId))}/capabilities/${encodeURIComponent(String(capabilityId))}`
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 启停能力绑定（PUT /{id}/capabilities/{capabilityId}/enabled） */
  async updateCapabilityEnabled(employeeId: string, capabilityId: string, enabled: boolean): Promise<void> {
    const response = await Http.put(
      `${API_BASE_URL}/${encodeURIComponent(String(employeeId))}/capabilities/${encodeURIComponent(
        String(capabilityId)
      )}/enabled`,
      { enabled }
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 能力清单（GET /{id}/capabilities） */
  async listCapabilities(id: string): Promise<EmployeeCapability[]> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/capabilities`);
    return unwrapDataOr(response as ServiceResponse<EmployeeCapability[]>, []);
  }

  /** 对话（POST /{id}/conversations） */
  async converse(id: string, payload: ConversationRequest): Promise<ConversationResponse> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/conversations`, normalizeObject(payload));
    return unwrapDataOr(response as ServiceResponse<ConversationResponse>, {});
  }

  /**
   * 流式对话（POST /{id}/conversations/stream）。
   * 事件协议与 DataAgent `/stream/search` 相同（message/complete/error/runtime_progress），不新增事件名。
   * 首条 runtime_progress 带 sessionId/runtimeRunId/executionMode，metadata 可能带 runtimeRequestId。
   * 取消无员工专用 stop：已知 runtimeRequestId 时走 GraphService.stopRuntime（POST /ai/chat/runtime/stop），
   * agentId 使用员工 id（运行时合成主体），threadId 为 sessionId（SSE 的 threadId）。
   */
  async converseStream(
    id: string,
    payload: ConversationRequest,
    handlers: EmployeeConversationStreamHandlers
  ): Promise<() => void> {
    return openEmployeeConversationStream(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/conversations/stream`,
      payload,
      handlers
    );
  }

  /** 员工运行履历（POST /{id}/runs/page），路径 id 定归属 */
  async fetchRunPage(id: string, query: EmployeeRunPageQuery): Promise<PageResponse<EmployeeRuntimeRun[]>> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/runs/page`,
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<EmployeeRuntimeRun>>);
  }

  /** 员工运行详情（GET /{id}/runs/{runtimeRunId}/detail） */
  async fetchRunDetail(id: string, runtimeRunId: string): Promise<EmployeeRunDetail> {
    const response = await Http.get(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/runs/${encodeURIComponent(String(runtimeRunId))}/detail`
    );
    return unwrapDataOr(response as ServiceResponse<EmployeeRunDetail>, {});
  }

  /** 提交运行反馈（POST /{id}/runs/{runtimeRunId}/feedback） */
  async saveRunFeedback(
    id: string,
    runtimeRunId: string,
    payload: { rating: 'UP' | 'DOWN'; comment?: string }
  ): Promise<EmployeeRunFeedback> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/runs/${encodeURIComponent(String(runtimeRunId))}/feedback`,
      normalizeObject(payload)
    );
    return unwrapDataOr(response as ServiceResponse<EmployeeRunFeedback>, {});
  }

  /** 本员工运行成本（GET /{id}/budget-report） */
  async fetchBudgetReport(
    id: string,
    query?: { fromTime?: string; toTime?: string }
  ): Promise<EmployeeBudgetReport> {
    const response = await Http.get(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/budget-report`,
      normalizeObject({
        fromTime: query?.fromTime,
        toTime: query?.toTime
      })
    );
    return unwrapDataOr(response as ServiceResponse<EmployeeBudgetReport>, { totals: [] });
  }

  /** 最近一次每日汇总（GET /{id}/digests/latest） */
  async fetchLatestDigest(id: string): Promise<EmployeeDigest | null> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/digests/latest`);
    return unwrapDataOr(response as ServiceResponse<EmployeeDigest | null>, null);
  }

  /** 当前部署（GET /{id}/deployments/current?environment=xxx；未初始化时后端返回 null） */
  async findCurrentDeployment(id: string, environment: string = 'PRODUCTION'): Promise<DigitalEmployeeDeployment | null> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/deployments/current`, { environment });
    return unwrapDataOr(response as ServiceResponse<DigitalEmployeeDeployment | null>, null);
  }

  /** 全部部署（GET /{id}/deployments） */
  async listDeployments(id: string): Promise<DigitalEmployeeDeployment[]> {
    const response = await Http.get(`${API_BASE_URL}/${encodeURIComponent(String(id))}/deployments`);
    return unwrapDataOr(response as ServiceResponse<DigitalEmployeeDeployment[]>, []);
  }

  /** 激活部署（POST /{id}/deployments/activate；deployment_version CAS，首次传 0） */
  async activateDeployment(id: string, releaseId: string, environment: string, expectVersion: number): Promise<void> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/deployments/activate`,
      { releaseId, environment, expectVersion }
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 回滚部署（POST /{id}/deployments/rollback?environment=&expectVersion=；回滚到 previousReleaseId，无历史版本后端拒绝） */
  async rollbackDeployment(id: string, environment: string, expectVersion: number): Promise<void> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(id))}/deployments/rollback`,
      {},
      { params: { environment, expectVersion } }
    );
    unwrapData(response as ServiceResponse<void>);
  }
}

const asOptionalString = (value: unknown): string | undefined => {
  if (typeof value === 'string' && value.trim()) {
    return value.trim();
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return undefined;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value));

const asExecutionMode = (value: unknown): ConversationResponse['executionMode'] | undefined => {
  return value === 'MODEL_ONLY' || value === 'PRINCIPAL' ? value : undefined;
};

const openEmployeeConversationStream = async (
  url: string,
  payload: ConversationRequest,
  handlers: EmployeeConversationStreamHandlers
): Promise<() => void> => {
  let isCompleted = false;
  let isClosed = false;
  let hasTerminalEvent = false;
  let isClientClosed = false;
  let closeStream: (() => void) | null = null;

  const closeConnection = (clientInitiated = true) => {
    if (isClosed) {
      return;
    }
    isClosed = true;
    isClientClosed = clientInitiated;
    closeStream?.();
  };

  const emitRuntimeRequestId = (runtimeRequestId?: string) => {
    if (runtimeRequestId) {
      handlers.onRuntimeRequestId?.(runtimeRequestId);
    }
  };

  const emitStarted = (data: Record<string, unknown>) => {
    const metadata = isRecord(data.metadata) ? data.metadata : {};
    const runtimeRequestId =
      asOptionalString(metadata.runtimeRequestId) || asOptionalString(data.runtimeRequestId);
    handlers.onStarted?.({
      sessionId: asOptionalString(metadata.sessionId) || asOptionalString(data.threadId),
      runtimeRunId: asOptionalString(metadata.runtimeRunId),
      executionMode: asExecutionMode(metadata.executionMode),
      runtimeRequestId
    });
    emitRuntimeRequestId(runtimeRequestId);
  };

  closeStream = await streamWithAuth(
    url,
    async event => {
      if (isCompleted) {
        return;
      }
      if (event.event === 'complete') {
        isCompleted = true;
        hasTerminalEvent = true;
        closeConnection(false);
        handlers.onComplete?.();
        return;
      }
      if (event.event === 'error') {
        isCompleted = true;
        hasTerminalEvent = true;
        closeConnection(false);
        let message = '对话失败';
        try {
          const payloadData = JSON.parse(event.data) as { text?: string };
          if (payloadData?.text) {
            message = payloadData.text;
          }
        } catch {
          if (event.data) {
            message = event.data;
          }
        }
        handlers.onError?.(new Error(message));
        return;
      }
      let node: Record<string, unknown>;
      try {
        node = JSON.parse(event.data) as Record<string, unknown>;
      } catch {
        isCompleted = true;
        hasTerminalEvent = true;
        closeConnection(false);
        handlers.onError?.(new Error('解析对话响应失败'));
        return;
      }
      if (event.event === 'runtime_progress') {
        emitStarted(node);
        return;
      }
      if (node.error) {
        isCompleted = true;
        hasTerminalEvent = true;
        closeConnection(false);
        handlers.onError?.(new Error(typeof node.text === 'string' && node.text ? node.text : '对话失败'));
        return;
      }
      if (node.complete) {
        isCompleted = true;
        hasTerminalEvent = true;
        closeConnection(false);
        handlers.onComplete?.();
        return;
      }
      const parsed = parseEmployeeConversationMessage(node);
      if (parsed.agentUi) {
        handlers.onAgentUi?.(parsed.agentUi);
      }
      if (parsed.text) {
        handlers.onMessage?.(parsed.text);
      }
      emitRuntimeRequestId(parsed.runtimeRequestId);
      if (parsed.sessionId) {
        handlers.onStarted?.({ sessionId: parsed.sessionId });
      }
    },
    async error => {
      if (isClientClosed || isCompleted) {
        return;
      }
      isCompleted = true;
      closeConnection(false);
      handlers.onError?.(error instanceof Error ? error : new Error('对话连接失败'));
    },
    async () => {
      if (isClientClosed || isCompleted || hasTerminalEvent) {
        return;
      }
      isCompleted = true;
      closeConnection(false);
      handlers.onError?.(new Error('对话流意外结束'));
    },
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream'
      },
      body: JSON.stringify(normalizeObject(payload))
    }
  );

  return () => {
    closeConnection(true);
  };
};

const normalizeObject = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== '' && item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

const preserveModifyValues = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

export default new DigitalEmployeeService();
