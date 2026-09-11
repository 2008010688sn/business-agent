/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr, unwrapList } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

/**
 * Agent 任务定义 / 触发器 / 运行记录服务封装。
 *
 * 对应后端 AgentTaskController（/ai/agent-tasks）。
 * 关键语义：
 * - 任务定义修改传入 paramsSnapshot/promptSnapshot 任意一项即生成新的不可变版本；
 * - 高风险写任务（highRiskWrite=true）后端强制按 ASSISTED（需审批）处理；
 * - 触发器类型创建后不可变更（CHAT/SCHEDULE/EVENT/API/IM）；
 * - API 触发要求 Idempotency-Key 请求头（重放返回已有运行），且强制开放签名校验（失败关闭）：
 *   X-Timestamp（epoch 毫秒，±5 分钟窗口）、X-Nonce（一次性随机串，1~128 字符）、
 *   X-Signature = HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + rawBody) 小写 hex；
 * - 签名密钥经 rotateApiSecret 生成/轮换，明文仅本次返回，读接口一律脱敏为 ******。
 */

/**
 * 任务侧实体 id（Long）经框架全局 ToStringSerializer 序列化，JSON 里是字符串；
 * 类型保留 number/string 兼容存量调用方，拼 URL 与展示一律 String(id)。
 */
export type AgentTaskId = number | string;

/** 任务/触发器启停状态（沿用后端 enabled/disabled 字符串约定）。 */
export type TaskStatus = 'enabled' | 'disabled';

/** 触发类型：CHAT-会话 / SCHEDULE-定时 / EVENT-事件 / API-外部API / IM-IM消息 */
export type TaskTriggerType = 'CHAT' | 'SCHEDULE' | 'EVENT' | 'API' | 'IM';

/** 自治级别：AUTONOMOUS-全自动（仅只读/低风险） / ASSISTED-需审批 */
export type TaskAutonomyLevel = 'AUTONOMOUS' | 'ASSISTED';

/** 运行状态。 */
export type TaskRunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELLED';

/** 错过执行策略：SKIP-错过跳过 / FIRE_ONCE-补偿一次 */
export type TaskMisfirePolicy = 'SKIP' | 'FIRE_ONCE';

/** 并发策略：ALLOW-允许并发 / FORBID-禁止并发（跳过并落 SKIPPED） */
export type TaskConcurrencyPolicy = 'ALLOW' | 'FORBID';

/**
 * 任务定义（对应后端 AgentTaskDefinition 实体）。
 * PR-8: 任务绑定 digitalEmployeeId + employeeReleaseId（二者由数字员工域建表）；
 * activeRunId 为 FORBID 并发槽占用镜像列（agent_task_run.id，可空，仅占用可见性）。
 */
export interface AgentTaskDefinition {
  id?: AgentTaskId;
  tenantId?: string;
  /** 所属数字员工 ID */
  digitalEmployeeId?: AgentTaskId;
  /** 绑定的数字员工发布版本 ID */
  employeeReleaseId?: AgentTaskId;
  taskName?: string;
  taskDescription?: string;
  taskType?: string;
  defaultAutonomyLevel?: TaskAutonomyLevel;
  highRiskWrite?: boolean;
  servicePrincipal?: string;
  /** 当前占用 FORBID 并发槽的任务运行 ID（可空镜像列，终态回写后释放） */
  activeRunId?: AgentTaskId;
  status?: TaskStatus;
  createTime?: string;
  lastModifyTime?: string;
}

export interface AgentTaskVersion {
  id?: AgentTaskId;
  definitionId?: AgentTaskId;
  versionNo?: number;
  paramsSnapshot?: Record<string, unknown>;
  promptSnapshot?: Record<string, unknown>;
  createTime?: string;
}

/**
 * 触发器配置（JSONB），按触发类型解释：
 * SCHEDULE 用 cron；EVENT 用 eventTopic/eventTag；
 * API 用 signatureRequired/nonceRequired；IM 用 provider/connectorCode。
 */
export interface AgentTaskTrigger {
  id?: AgentTaskId;
  tenantId?: string;
  definitionId?: AgentTaskId;
  taskVersionId?: AgentTaskId;
  triggerType?: TaskTriggerType;
  triggerConfig?: Record<string, unknown>;
  timezone?: string;
  misfirePolicy?: TaskMisfirePolicy;
  concurrencyPolicy?: TaskConcurrencyPolicy;
  nextFireTime?: string;
  lastFireTime?: string;
  status?: TaskStatus;
  createTime?: string;
}

export interface AgentTaskRun {
  id?: AgentTaskId;
  tenantId?: string;
  definitionId?: AgentTaskId;
  taskVersionId?: AgentTaskId;
  triggerId?: AgentTaskId;
  triggerType?: TaskTriggerType;
  idempotencyKey?: string;
  externalEventId?: string;
  scheduledTime?: string;
  runStatus?: TaskRunStatus;
  runtimeRunId?: AgentTaskId;
  servicePrincipal?: string;
  /** 触发器并发策略快照（ALLOW/FORBID，落库时取自触发器）；FORBID 行参与 idx_forbid_slot 并发槽竞争 */
  concurrencyPolicy?: TaskConcurrencyPolicy;
  errorMessage?: string;
  startedTime?: string;
  finishedTime?: string;
  createTime?: string;
}

export interface AgentTaskRuntimeArtifact {
  id?: string;
  stepKey?: string;
  schemaVersion?: string;
  data?: string | null;
  sensitivity?: string;
}

export interface AgentTaskRuntimeStep {
  id?: string;
  stepKey?: string;
  stepName?: string;
  state?: string;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface AgentTaskDelivery {
  id?: string;
  channel?: string;
  target?: string;
  deliveryStatus?: string;
  lastDeliveryTime?: string;
  lastError?: string;
}

/** GET /agent-tasks/runs/{taskRunId}/detail */
export interface AgentTaskRunDetail {
  taskRunId?: string;
  definitionId?: string;
  runtimeRunId?: string;
  triggerType?: TaskTriggerType;
  runStatus?: TaskRunStatus;
  errorMessage?: string;
  scheduledTime?: string;
  startedTime?: string;
  finishedTime?: string;
  servicePrincipal?: string;
  finalAnswer?: string;
  steps?: AgentTaskRuntimeStep[];
  artifacts?: AgentTaskRuntimeArtifact[];
  deliveries?: AgentTaskDelivery[];
}

/** 任务定义详情（定义 + 最新版本 + 触发器列表）。 */
export interface AgentTaskDetail {
  definition?: AgentTaskDefinition;
  latestVersion?: AgentTaskVersion;
  triggers?: AgentTaskTrigger[];
}

/**
 * 任务定义分页查询请求（对应后端 AgentTaskPageQueryReq）。
 * PR-8: 过滤维度为 digitalEmployeeId（原 workspaceId/employeeBindingId 已废弃）。
 */
export interface AgentTaskPageQuery {
  current?: number;
  size?: number;
  /** 按所属数字员工过滤（空串表示不过滤） */
  digitalEmployeeId?: AgentTaskId | '';
  taskType?: string;
  status?: TaskStatus | '';
  keyword?: string;
  triggerType?: string;
}

/**
 * 创建任务定义请求（对应后端 AgentTaskDefinitionSaveReq；create 与 modify 不共用 DTO）。
 * PR-8: 钉选数字员工 + 其 PUBLISHED 发布版本；旧 workspaceId/employeeBindingId/releaseId 已废弃。
 */
export interface AgentTaskCreateRequest {
  digitalEmployeeId: AgentTaskId;
  employeeReleaseId: AgentTaskId;
  taskName: string;
  taskDescription?: string;
  taskType?: string;
  defaultAutonomyLevel?: TaskAutonomyLevel;
  highRiskWrite?: boolean;
  servicePrincipal?: string;
  paramsSnapshot?: Record<string, unknown>;
  promptSnapshot?: Record<string, unknown>;
}

/**
 * 修改任务定义请求（对应后端 AgentTaskDefinitionModifyReq）。
 * 后端不支持改绑数字员工（无 digitalEmployeeId 字段）；仅可切换同员工下的发布版本。
 */
export interface AgentTaskModifyRequest {
  taskName?: string;
  taskDescription?: string;
  taskType?: string;
  /** 可切换为同一数字员工下的其他已发布版本（空表示不修改） */
  employeeReleaseId?: AgentTaskId;
  defaultAutonomyLevel?: TaskAutonomyLevel;
  highRiskWrite?: boolean;
  servicePrincipal?: string;
  status?: TaskStatus;
  paramsSnapshot?: Record<string, unknown>;
  promptSnapshot?: Record<string, unknown>;
}

/** 创建触发器请求（触发类型创建后不可变更）。 */
export interface AgentTaskTriggerCreateRequest {
  triggerType: TaskTriggerType;
  taskVersionId?: AgentTaskId;
  triggerConfig?: Record<string, unknown>;
  timezone?: string;
  misfirePolicy?: TaskMisfirePolicy;
  concurrencyPolicy?: TaskConcurrencyPolicy;
  status?: TaskStatus;
}

/** 修改触发器请求，字段为空表示不修改。 */
export interface AgentTaskTriggerModifyRequest {
  taskVersionId?: AgentTaskId;
  triggerConfig?: Record<string, unknown>;
  timezone?: string;
  misfirePolicy?: TaskMisfirePolicy;
  concurrencyPolicy?: TaskConcurrencyPolicy;
  status?: TaskStatus;
}

export interface AgentTaskRunPageQuery {
  current?: number;
  size?: number;
  triggerId?: AgentTaskId | '';
  triggerType?: TaskTriggerType | '';
  runStatus?: TaskRunStatus | '';
}

/** API 触发签名密钥轮换响应；secret 明文仅本次返回。 */
export interface AgentTaskApiSecretResponse {
  secret?: string;
  algorithm?: string;
}

/** 手动（API）触发请求：签名头按后端 ApiTriggerSecurityService 协议组装。 */
export interface AgentTaskApiTriggerCommand {
  idempotencyKey: string;
  timestamp: string;
  nonce: string;
  signature: string;
  /** 参与签名的请求体原文；为空串时表示无业务参数。 */
  rawBody: string;
}

/**
 * 数字员工选项（对应后端 DigitalEmployee 实体的展示字段，仅任务页选择器消费）。
 * 注：数字员工域的完整 service 由专项切片维护，这里仅封装任务钉选所需的最小只读选项。
 */
export interface DigitalEmployeeOption {
  id?: AgentTaskId;
  employeeCode?: string;
  employeeName?: string;
  jobTitle?: string;
  status?: string;
}

/**
 * 数字员工发布版本选项（对应后端 DigitalEmployeeRelease 实体的展示字段）。
 * 任务绑定的 employeeReleaseId 指向本表 PUBLISHED 状态行。
 */
export interface EmployeeReleaseOption {
  id?: AgentTaskId;
  employeeId?: AgentTaskId;
  releaseNo?: number;
  status?: string;
  publishedAt?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/agent-tasks';

/** API 触发的幂等键请求头（与后端 TaskConstants.HEADER_IDEMPOTENCY_KEY 一致）。 */
export const HEADER_IDEMPOTENCY_KEY = 'Idempotency-Key';

/** API 触发签名请求头（与后端 TaskConstants 对齐）。 */
export const HEADER_SIGNATURE = 'X-Signature';
export const HEADER_TIMESTAMP = 'X-Timestamp';
export const HEADER_NONCE = 'X-Nonce';

/** 触发配置中的 API 签名密钥键（读接口脱敏为 ******，密钥只能经轮换接口生成）。 */
export const CONFIG_API_SECRET = 'apiSecret';

/** SCHEDULE 触发默认时区（与后端 TaskConstants.DEFAULT_TIMEZONE 一致）。 */
export const DEFAULT_TIMEZONE = 'Asia/Shanghai';

const taskUrl = (id: AgentTaskId, suffix = ''): string => {
  return `${API_BASE_URL}/${encodeURIComponent(String(id))}${suffix}`;
};

const triggerUrl = (id: AgentTaskId, triggerId: AgentTaskId, suffix = ''): string => {
  return taskUrl(id, `/triggers/${encodeURIComponent(String(triggerId))}${suffix}`);
};

class AgentTaskService {
  /** 分页查询任务定义（POST /page）。 */
  async fetchTaskPage(query: AgentTaskPageQuery): Promise<PageResponse<AgentTaskDefinition[]>> {
    const response = await Http.post(
      `${API_BASE_URL}/page`,
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<AgentTaskDefinition>>);
  }

  /** 创建任务定义并生成首个不可变版本（POST /create）。 */
  async createTask(payload: AgentTaskCreateRequest): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/create`, normalizeObject(payload));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 修改任务定义，传入参数/提示词快照时生成新版本（PUT /{id}/modify）。 */
  async modifyTask(id: AgentTaskId, payload: AgentTaskModifyRequest): Promise<void> {
    const response = await Http.put(taskUrl(id, '/modify'), normalizeObject(payload));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 删除任务定义并连带删除其触发器（DELETE /{id}）。 */
  async removeTask(id: AgentTaskId): Promise<void> {
    const response = await Http.delete(taskUrl(id));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 查询任务定义详情，含最新版本与触发器列表（GET /{id}/detail）。 */
  async fetchTaskDetail(id: AgentTaskId): Promise<AgentTaskDetail> {
    const response = await Http.get(taskUrl(id, '/detail'));
    return unwrapDataOr(response as ServiceResponse<AgentTaskDetail>, {});
  }

  /** 查询任务定义下的触发器列表（GET /{id}/triggers）。 */
  async listTriggers(id: AgentTaskId): Promise<AgentTaskTrigger[]> {
    const response = await Http.get(taskUrl(id, '/triggers'));
    return unwrapList<AgentTaskTrigger>(response);
  }

  /** 为任务定义创建触发器（POST /{id}/triggers/create）。 */
  async createTrigger(id: AgentTaskId, payload: AgentTaskTriggerCreateRequest): Promise<void> {
    const response = await Http.post(taskUrl(id, '/triggers/create'), normalizeObject(payload));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 修改任务触发器（PUT /{id}/triggers/{triggerId}/modify）。 */
  async modifyTrigger(id: AgentTaskId, triggerId: AgentTaskId, payload: AgentTaskTriggerModifyRequest): Promise<void> {
    const response = await Http.put(triggerUrl(id, triggerId, '/modify'), normalizeObject(payload));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 删除任务触发器（DELETE /{id}/triggers/{triggerId}）。 */
  async removeTrigger(id: AgentTaskId, triggerId: AgentTaskId): Promise<void> {
    const response = await Http.delete(triggerUrl(id, triggerId));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 任务运行结果详情（GET /runs/{taskRunId}/detail）。 */
  async fetchRunDetail(taskRunId: AgentTaskId): Promise<AgentTaskRunDetail> {
    const response = await Http.get(`${API_BASE_URL}/runs/${encodeURIComponent(String(taskRunId))}/detail`);
    return unwrapDataOr(response as ServiceResponse<AgentTaskRunDetail>, {});
  }

  /** 取消任务运行并释放 FORBID 并发槽（POST /runs/{taskRunId}/cancel）。 */
  async cancelRun(taskRunId: AgentTaskId): Promise<AgentTaskRun> {
    const response = await Http.post(`${API_BASE_URL}/runs/${encodeURIComponent(String(taskRunId))}/cancel`);
    return unwrapDataOr(response as ServiceResponse<AgentTaskRun>, {});
  }

  /** 分页查询任务定义下的运行记录（POST /{id}/runs/page）。 */
  async fetchRunPage(id: AgentTaskId, query: AgentTaskRunPageQuery): Promise<PageResponse<AgentTaskRun[]>> {
    const response = await Http.post(
      taskUrl(id, '/runs/page'),
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<AgentTaskRun>>);
  }

  /**
   * 控制台立即执行一次（POST /{id}/triggers/{triggerId}/runs/manual）。
   * 登录态 + ai-agent:task:trigger；SCHEDULE/EVENT/API 可用，不推进下次 cron。
   */
  async manualTrigger(id: AgentTaskId, triggerId: AgentTaskId): Promise<AgentTaskRun> {
    const response = await Http.post(triggerUrl(id, triggerId, '/runs/manual'));
    return unwrapDataOr(response as ServiceResponse<AgentTaskRun>, {});
  }

  /**
   * 手动（API）触发一次任务运行（POST /{id}/triggers/{triggerId}/runs/create）。
   * Idempotency-Key 必填（重放返回已有运行）；后端强制验签，
   * rawBody 必须与参与签名的原文完全一致，因此以字符串原样发送。
   */
  async triggerRun(
    id: AgentTaskId,
    triggerId: AgentTaskId,
    command: AgentTaskApiTriggerCommand
  ): Promise<AgentTaskRun> {
    const response = await Http.post(triggerUrl(id, triggerId, '/runs/create'), command.rawBody, {
      headers: {
        'Content-Type': 'application/json',
        [HEADER_IDEMPOTENCY_KEY]: command.idempotencyKey,
        [HEADER_TIMESTAMP]: command.timestamp,
        [HEADER_NONCE]: command.nonce,
        [HEADER_SIGNATURE]: command.signature
      }
    });
    return unwrapDataOr(response as ServiceResponse<AgentTaskRun>, {});
  }

  /**
   * 生成/轮换 API 触发签名密钥（POST /{id}/triggers/{triggerId}/api-secret/rotate）。
   * 明文密钥仅本次返回（旧密钥即刻失效），页面须按“仅展示一次”交互处理。
   */
  async rotateApiSecret(id: AgentTaskId, triggerId: AgentTaskId): Promise<AgentTaskApiSecretResponse> {
    const response = await Http.post(triggerUrl(id, triggerId, '/api-secret/rotate'));
    return unwrapDataOr(response as ServiceResponse<AgentTaskApiSecretResponse>, {});
  }

  /**
   * 拉取数字员工选项（GET /ai/digital-employees/options）。
   * 任务表单默认 ENABLED（DRAFT/ARCHIVED 无法拉起运行）；筛选传入 null 不过滤状态。
   */
  async fetchEmployeeOptions(keyword?: string, status: string | null = 'ENABLED'): Promise<DigitalEmployeeOption[]> {
    const response = await Http.get('/ai/digital-employees/options', {
      ...(keyword ? { keyword } : {}),
      ...(status ? { status } : {})
    });
    return unwrapList<DigitalEmployeeOption>(response).map(item => ({
      ...item,
      id: item.id == null ? item.id : String(item.id)
    }));
  }

  /**
   * 拉取指定数字员工的 PUBLISHED 发布版本选项
   * （POST /digital-employee-releases/page，权限 ai-agent:digital-employee:query）。
   * 任务的 employeeReleaseId 只允许指向 PUBLISHED 状态行（RETIRED 后不可再选）。
   */
  async fetchEmployeeReleaseOptions(employeeId: AgentTaskId): Promise<EmployeeReleaseOption[]> {
    const response = await Http.post('/ai/digital-employee-releases/page', {
      current: 1,
      size: 100,
      employeeId,
      status: 'PUBLISHED'
    });
    return toPageResponse(response as ServiceResponse<MybatisPage<EmployeeReleaseOption>>).data;
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

export default new AgentTaskService();
