/*
 * Copyright 2024-2026 the original author or authors.
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

/**
 * 持久运行时（durable runtime）Run 服务。
 *
 * 后端契约：agent-backend RuntimeRunController（/runtime-runs/*）与
 * SessionEventController（POST /chat/runtime-runs/events/stream，持久 seq + afterSeq 断线回放）。
 * TypeScript 类型按后端 runtime/durable/dto 下的 record 定义；后端框架把全部包装类型 Long
 * 序列化为字符串（DefaultWebMvcConfiguration），因此 ID、seq、stateVersion 等 Long 字段
 * 到达前端一律是字符串，参与数值比较前必须先 Number() 转换。
 *
 * SSE attach 采用 src/service/sse 统一客户端的骨架约定（@microsoft/fetch-event-source、
 * V4-Authorization、openWhenHidden）；因该模块默认导出为单连接单例，无法支撑多会话并发订阅，
 * 故在本文件按相同骨架自管理连接，并补齐断线重连（重连携带本地最大 seq 作为 afterSeq）、
 * 按 seq 去重、指数退避（含上限常量）与静默超时主动 reconcile。
 */

import { fetchEventSource } from '@microsoft/fetch-event-source';
import { nanoid } from '@sa/utils';
import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import { STREAM_TRACE_HEADER, StreamHttpError, dataAgentAuthHeaders, resolveDataAgentRequestUrl } from './http';
import type { RuntimeProgressEvent } from './graph';

/** 后端 Long ID 经 ToStringSerializer 序列化为字符串 */
export type RuntimeRunId = string;

/** 运行状态，对应后端 RuntimeRunState */
export type RuntimeRunState =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_APPROVAL'
  | 'WAITING_INPUT'
  | 'CANCELLING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT';

/** 步骤状态，对应后端 RuntimeStepState */
export type RuntimeStepState =
  | 'PENDING'
  | 'READY'
  | 'RUNNING'
  | 'WAITING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'
  | 'SKIPPED';

/** 事件类型，对应后端 RuntimeEventType */
export type RuntimeEventType =
  | 'RUN_CREATED'
  | 'RUN_STARTED'
  | 'RUN_RESUMED'
  | 'RUN_CANCEL_REQUESTED'
  | 'RUN_SUCCEEDED'
  | 'RUN_FAILED'
  | 'RUN_CANCELLED'
  | 'RUN_TIMED_OUT'
  | 'RUN_WAITING'
  | 'STEP_READY'
  | 'STEP_STARTED'
  | 'STEP_WAITING'
  | 'STEP_SUCCEEDED'
  | 'STEP_FAILED'
  | 'STEP_CANCELLED'
  | 'STEP_TIMED_OUT'
  | 'STEP_SKIPPED'
  | 'INVOCATION_STATE_CHANGED'
  | 'APPROVAL_REQUESTED'
  | 'APPROVAL_DECIDED';

/** 运行主体类型，对应后端 RuntimeRunController 契约：DIGITAL_EMPLOYEE / CALLER / PLATFORM */
export type RuntimeRunOwnerType = 'DIGITAL_EMPLOYEE' | 'CALLER' | 'PLATFORM';

/**
 * 创建 Run 请求，对应后端 RuntimeRunCreateReq；
 * PR-1/PR-8: (tenantId, ownerType, ownerId, clientRequestId) 为幂等键，workspaceId 隔离语义已移除。
 */
export interface RuntimeRunCreateReq {
  clientRequestId: string;
  ownerType?: RuntimeRunOwnerType | string;
  /** 运行主体 ID：DIGITAL_EMPLOYEE 时为 digitalEmployeeId，CALLER 时为用户 id */
  ownerId?: RuntimeRunId | null;
  /** 所属数字员工 ID（可空，调用链路直接归属该员工时填充） */
  digitalEmployeeId?: RuntimeRunId | null;
  releaseId?: RuntimeRunId | null;
  agentId?: RuntimeRunId | null;
  threadId?: string;
  query?: string;
  runMode?: string;
  deadlineSeconds?: number;
}

/**
 * 分页查询请求，对应后端 RuntimeRunPageQueryReq（继承 PageRequest：current/size）。
 * PR-8: 支持 ownerType/ownerId/digitalEmployeeId 的 owner 维度过滤，原 workspaceId 已移除。
 */
export interface RuntimeRunPageQueryReq {
  current?: number;
  size?: number;
  state?: RuntimeRunState | '';
  runMode?: string;
  /** 运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM */
  ownerType?: RuntimeRunOwnerType | '';
  /** 运行主体 ID（与 ownerType 搭配；空表示不按主体过滤） */
  ownerId?: RuntimeRunId;
  /** 数字员工 ID（空表示不按数字员工过滤） */
  digitalEmployeeId?: RuntimeRunId;
  agentId?: RuntimeRunId;
  threadId?: string;
  keyword?: string;
}

/** Run 响应，对应后端 RuntimeRunResp（PR-8: owner 维度字段） */
export interface RuntimeRunResp {
  id: RuntimeRunId;
  /** 运行主体类型：DIGITAL_EMPLOYEE / CALLER / PLATFORM */
  ownerType?: RuntimeRunOwnerType | string | null;
  /** 运行主体 ID（后端 Long，JSON 里是字符串；DIGITAL_EMPLOYEE 时为数字员工 ID，CALLER 时为用户 id） */
  ownerId?: RuntimeRunId | null;
  /** 数字员工 ID（空表示非数字员工发起；后端 Long，JSON 里是字符串） */
  digitalEmployeeId?: RuntimeRunId | null;
  /** owner 对应的 Release ID（数字员工为 digital_employee_release.id） */
  releaseId?: RuntimeRunId | null;
  agentId?: RuntimeRunId | null;
  clientRequestId?: string | null;
  threadId?: string | null;
  runtimeRequestId?: string | null;
  triggerSource?: string | null;
  runMode?: string | null;
  query?: string | null;
  state: RuntimeRunState;
  /** 后端 Long，JSON 里是字符串；仅用于展示 */
  stateVersion?: string | null;
  /** 后端 Long，JSON 里是字符串；仅用于展示 */
  cancellationEpoch?: string | null;
  deadlineAt?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
  createTime?: string | null;
  /** 发起人用户 ID（审计谁触发；任务调度可空） */
  initiatorUserId?: string | null;
  /** 发起人名称 */
  initiatorUserName?: string | null;
  /** 执行主体 Principal ID（sp_ 前缀；CALLER 运行为空） */
  executionPrincipalId?: string | null;
  /** 决策主体类别：DIGITAL_EMPLOYEE / CALLER / PLATFORM */
  subjectKind?: string | null;
  /** owner 对应 Release 的 spec_hash */
  specHash?: string | null;
}

/** 步骤响应，对应后端 RuntimeStepResp */
export interface RuntimeStepResp {
  id: RuntimeRunId;
  stepKey: string;
  stepName?: string | null;
  capabilityHandle?: string | null;
  /** 依赖的上游步骤键清单（JSON 数组原文） */
  dependsOn?: string | null;
  state: RuntimeStepState;
  attemptCount?: number | null;
  maxAttempts?: number | null;
  outputArtifactId?: RuntimeRunId | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
}

/** Run 详情响应，对应后端 RuntimeRunDetailResp */
export interface RuntimeRunDetailResp {
  run?: RuntimeRunResp | null;
  finalAnswer?: string | null;
  planVersion?: number | null;
  planHash?: string | null;
  steps?: RuntimeStepResp[] | null;
  /** 当前最大事件 seq，作为事件流起始游标；后端 Long，JSON 里是字符串 */
  latestSeq?: string | null;
}

/**
 * 事件响应，对应后端 RuntimeEventResp。payload 后端以 @JsonRawValue 内联输出，
 * 客户端 JSON.parse 后为任意结构对象。
 */
export interface RuntimeEventResp {
  /**
   * run 内单调递增序号，断线重放游标。
   *
   * 后端是 Long，JSON 里实际是字符串；这里保留 number 标注是因为时间线排序、去重等
   * 展示路径依赖隐式转换，收窄标注会牵动整条渲染链。做数值比较务必先 Number() 转换，
   * 不要写 `typeof event.seq === 'number'` 这类恒 false 的判断。
   */
  seq: number;
  eventKey?: string | null;
  eventType: RuntimeEventType | string;
  stepKey?: string | null;
  payload?: Record<string, unknown> | unknown;
  occurredAt?: string | null;
}

export const RUNTIME_RUN_TERMINAL_STATES: readonly RuntimeRunState[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'TIMED_OUT'
];

export const RUNTIME_RUN_ACTIVE_STATES: readonly RuntimeRunState[] = [
  'PENDING',
  'RUNNING',
  'WAITING_APPROVAL',
  'WAITING_INPUT',
  'CANCELLING'
];

/** 可调用 resume 的状态（后端仅允许等待审批/等待输入恢复为 RUNNING） */
export const RUNTIME_RUN_RESUMABLE_STATES: readonly RuntimeRunState[] = ['WAITING_APPROVAL', 'WAITING_INPUT'];

export const RUNTIME_RUN_STATE_LABELS: Record<RuntimeRunState, string> = {
  PENDING: '待启动',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  WAITING_INPUT: '等待用户输入',
  CANCELLING: '取消中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '超时'
};

export const RUNTIME_STEP_STATE_LABELS: Record<RuntimeStepState, string> = {
  PENDING: '等待依赖',
  READY: '就绪待执行',
  RUNNING: '执行中',
  WAITING: '等待交互',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '超时',
  SKIPPED: '已跳过'
};

export const RUNTIME_EVENT_TYPE_LABELS: Record<RuntimeEventType, string> = {
  RUN_CREATED: '运行已创建',
  RUN_STARTED: '运行已开始',
  RUN_RESUMED: '运行已恢复',
  RUN_CANCEL_REQUESTED: '已请求取消',
  RUN_SUCCEEDED: '运行成功',
  RUN_FAILED: '运行失败',
  RUN_CANCELLED: '运行已取消',
  RUN_TIMED_OUT: '运行超时',
  RUN_WAITING: '运行等待交互',
  STEP_READY: '步骤就绪',
  STEP_STARTED: '步骤开始',
  STEP_WAITING: '步骤等待交互',
  STEP_SUCCEEDED: '步骤成功',
  STEP_FAILED: '步骤失败',
  STEP_CANCELLED: '步骤已取消',
  STEP_TIMED_OUT: '步骤超时',
  STEP_SKIPPED: '步骤已跳过',
  INVOCATION_STATE_CHANGED: '调用状态变化',
  APPROVAL_REQUESTED: '审批已发起',
  APPROVAL_DECIDED: '审批已决定'
};

/** 收到以下事件即认为运行进入终态（与后端 SSE takeUntil 集合一致） */
export const RUN_TERMINAL_EVENT_TYPES: ReadonlySet<string> = new Set([
  'RUN_SUCCEEDED',
  'RUN_FAILED',
  'RUN_CANCELLED',
  'RUN_TIMED_OUT'
]);

export const isTerminalRuntimeRunState = (state?: RuntimeRunState | string | null): boolean =>
  Boolean(state && (RUNTIME_RUN_TERMINAL_STATES as readonly string[]).includes(state));

export const isActiveRuntimeRunState = (state?: RuntimeRunState | string | null): boolean =>
  Boolean(state && (RUNTIME_RUN_ACTIVE_STATES as readonly string[]).includes(state));

export const isResumableRuntimeRunState = (state?: RuntimeRunState | string | null): boolean =>
  Boolean(state && (RUNTIME_RUN_RESUMABLE_STATES as readonly string[]).includes(state));

/** SSE 重连首次退避 */
export const SSE_RETRY_BASE_DELAY_MS = 1_000;
/** SSE 指数退避上限 */
export const SSE_RETRY_MAX_DELAY_MS = 30_000;
/** SSE 连续失败上限，超过后停止重连并回调 onError */
export const SSE_MAX_CONSECUTIVE_FAILURES = 8;
/**
 * 心跳/静默超时约定间隔：后端事件流按 800ms 轮询持久序列、无独立心跳事件，
 * 超过该时长未收到任何事件时，以「静默超时 + 主动 reconcile（REST 详情 + afterSeq 事件回放）」代替心跳判定。
 */
export const SSE_SILENCE_TIMEOUT_MS = 45_000;

const BASE_URL = '/ai/runtime-runs';
const EVENT_STREAM_URL = '/ai/chat/runtime-runs/events/stream';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

export interface RuntimeRunAttachOptions {
  runId: RuntimeRunId;
  /** 本地已有最大 seq；只回放 seq 大于该值的事件，空或 0 表示从头回放 */
  afterSeq?: number;
  /** 已按 seq 去重、升序到达的事件 */
  onEvent: (event: RuntimeEventResp) => void;
  /**
   * 运行终态回调。event 正常来自终态事件；当静默超时 reconcile 发现服务端已终态
   * 但事件回放缺失终态事件时，event 为 null 并携带详情中的终态 state。
   */
  onTerminal?: (event: RuntimeEventResp | null, state: RuntimeRunState | null) => void;
  /** 重试耗尽后的最终失败（每次重连本身静默退避，不触发该回调） */
  onError?: (error: Error) => void;
  /** 每次调度重连时回调（用于诊断/提示），attempt 从 1 开始 */
  onReconnect?: (attempt: number, afterSeq: number) => void;
}

export interface RuntimeRunAttachment {
  /** 断开事件流并停止重连 */
  close: () => void;
  /** 当前本地最大 seq（刷新持久化游标用） */
  getLastSeq: () => number;
}

/**
 * 将 durable 事件映射为老进度时间线事件（RuntimeProgressEvent），
 * 供会话运行页复用既有 runtimeProgressEvents 渲染管线；仅做展示映射，不改变契约。
 */
export const toRuntimeProgressEvent = (event: RuntimeEventResp): RuntimeProgressEvent | null => {
  if (!event?.eventType) {
    return null;
  }
  const eventType = event.eventType as RuntimeEventType;
  const statusMap: Partial<Record<RuntimeEventType, RuntimeProgressEvent['status']>> = {
    RUN_SUCCEEDED: 'success',
    RUN_FAILED: 'failed',
    RUN_CANCELLED: 'cancelled',
    RUN_TIMED_OUT: 'timed_out',
    RUN_WAITING: 'waiting',
    STEP_WAITING: 'waiting',
    STEP_SUCCEEDED: 'success',
    STEP_FAILED: 'failed',
    STEP_CANCELLED: 'cancelled',
    STEP_TIMED_OUT: 'timed_out',
    STEP_SKIPPED: 'success',
    APPROVAL_REQUESTED: 'waiting'
  };
  const label = RUNTIME_EVENT_TYPE_LABELS[eventType] || String(event.eventType);
  const stepSuffix = event.stepKey ? `：${event.stepKey}` : '';
  return {
    eventType: 'runtime_progress',
    seq: event.seq,
    stageCode: String(event.eventType),
    status: statusMap[eventType] ?? 'running',
    displayName: `${label}${stepSuffix}`,
    nodeId: event.stepKey ?? undefined,
    clientReceivedAtMs: Date.now()
  };
};

class RuntimeRunService {
  /** 幂等创建运行：(tenantId, ownerType, ownerId, clientRequestId) 幂等，重复请求返回既有运行 */
  async create(payload: RuntimeRunCreateReq): Promise<RuntimeRunResp> {
    const response = await Http.post(`${BASE_URL}/create`, payload);
    return unwrapData(response as ServiceResponse<RuntimeRunResp>);
  }

  /** 分页查询运行（threadId/state 等过滤字段以后端 RuntimeRunPageQueryReq 为准） */
  async page(query: RuntimeRunPageQueryReq): Promise<PageResponse<RuntimeRunResp[]>> {
    const response = await Http.post(`${BASE_URL}/page`, query);
    return toPageResponse(response as ServiceResponse<MybatisPage<RuntimeRunResp>>);
  }

  /** 查询运行详情：运行、生效计划、步骤清单与当前最大事件 seq */
  async detail(id: RuntimeRunId): Promise<RuntimeRunDetailResp> {
    const response = await Http.get(`${BASE_URL}/${id}/detail`);
    return unwrapDataOr(response as ServiceResponse<RuntimeRunDetailResp>, {});
  }

  /** 按 afterSeq 回放运行事件（升序、无遗漏、无重复） */
  async events(id: RuntimeRunId, afterSeq = 0, limit?: number): Promise<RuntimeEventResp[]> {
    const response = await Http.get(`${BASE_URL}/${id}/events`, {
      afterSeq,
      ...(limit ? { limit } : {})
    });
    return unwrapDataOr(response as ServiceResponse<RuntimeEventResp[]>, []);
  }

  /** 取消运行：取消纪元递增 + 中断记录 + 状态机推进（在途态先进入 CANCELLING） */
  async cancel(id: RuntimeRunId, reason?: string): Promise<void> {
    const response = await Http.post(`${BASE_URL}/${id}/cancel`, reason ? { reason } : {});
    unwrapData(response as ServiceResponse<void>);
  }

  /** 恢复运行：仅等待审批/等待输入状态可恢复为 RUNNING */
  async resume(id: RuntimeRunId): Promise<void> {
    const response = await Http.post(`${BASE_URL}/${id}/resume`, {});
    unwrapData(response as ServiceResponse<void>);
  }

  /**
   * 附着持久运行时事件流（SSE）。
   *
   * 可靠性设计：
   * - 断线自动重连：每次重连以本地最大 seq 作为 afterSeq 重新订阅，服务端持久序列保证无遗漏、无乱序；
   * - 按 seq 去重：重放窗口与新事件重叠时丢弃 seq 小于等于本地游标的事件；
   * - 指数退避：SSE_RETRY_BASE_DELAY_MS 起步、SSE_RETRY_MAX_DELAY_MS 封顶，
   *   连续失败超过 SSE_MAX_CONSECUTIVE_FAILURES 次后停止并回调 onError；
   * - 静默超时：超过 SSE_SILENCE_TIMEOUT_MS 无事件时主动 reconcile——先查详情 + REST 回放缺失事件，
   *   运行仍在途则重建连接（对长步骤静默是常态，afterSeq 回放保证重建无副作用）。
   */
  attachRunEvents(options: RuntimeRunAttachOptions): RuntimeRunAttachment {
    const { runId, onEvent, onTerminal, onError, onReconnect } = options;
    const fetchDetail = () => this.detail(runId);
    const replayEvents = (afterSeq: number) => this.events(runId, afterSeq);
    let lastSeq = Math.max(0, options.afterSeq ?? 0);
    let closed = false;
    let terminated = false;
    let consecutiveFailures = 0;
    let controller: AbortController | null = null;
    let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
    let silenceTimer: ReturnType<typeof setTimeout> | null = null;

    function clearReconnectTimer() {
      if (reconnectTimer !== null) {
        clearTimeout(reconnectTimer);
        reconnectTimer = null;
      }
    }

    function clearSilenceTimer() {
      if (silenceTimer !== null) {
        clearTimeout(silenceTimer);
        silenceTimer = null;
      }
    }

    function close() {
      if (closed) {
        return;
      }
      closed = true;
      clearReconnectTimer();
      clearSilenceTimer();
      controller?.abort();
      controller = null;
    }

    function finishWithTerminal(event: RuntimeEventResp | null, state: RuntimeRunState | null) {
      if (terminated) {
        return;
      }
      terminated = true;
      close();
      onTerminal?.(event, state);
    }

    function handleEvent(event: RuntimeEventResp) {
      if (closed || terminated) {
        return;
      }
      const seq = typeof event.seq === 'number' ? event.seq : Number(event.seq);
      // 按 seq 去重：重连回放窗口与已收事件重叠时直接丢弃
      if (Number.isFinite(seq) && seq > 0) {
        if (seq <= lastSeq) {
          return;
        }
        lastSeq = seq;
      }
      onEvent(event);
      if (RUN_TERMINAL_EVENT_TYPES.has(String(event.eventType))) {
        finishWithTerminal(event, (String(event.eventType).replace('RUN_', '') as RuntimeRunState) || null);
      }
    }

    function scheduleReconnect(error: Error | null) {
      if (closed || terminated) {
        return;
      }
      clearSilenceTimer();
      if (error) {
        consecutiveFailures += 1;
        if (consecutiveFailures > SSE_MAX_CONSECUTIVE_FAILURES) {
          const finalError = new Error(
            `运行事件流重连失败（已重试 ${SSE_MAX_CONSECUTIVE_FAILURES} 次）: ${error.message}`
          );
          close();
          onError?.(finalError);
          return;
        }
      }
      const delay = error
        ? Math.min(SSE_RETRY_BASE_DELAY_MS * 2 ** Math.max(0, consecutiveFailures - 1), SSE_RETRY_MAX_DELAY_MS)
        : SSE_RETRY_BASE_DELAY_MS;
      onReconnect?.(consecutiveFailures, lastSeq);
      clearReconnectTimer();
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null;
        connect();
      }, delay);
    }

    function restartConnection() {
      if (closed || terminated) {
        return;
      }
      controller?.abort();
      controller = null;
      connect();
    }

    async function reconcileSilence() {
      if (closed || terminated) {
        return;
      }
      try {
        const detail = await fetchDetail();
        if (closed || terminated) {
          return;
        }
        // REST 回放缺口事件，与 SSE 断点续传一致；终态事件包含在内则直接收尾
        const missedEvents = await replayEvents(lastSeq);
        if (closed || terminated) {
          return;
        }
        missedEvents.forEach(handleEvent);
        if (terminated) {
          return;
        }
        const state = detail.run?.state ?? null;
        if (isTerminalRuntimeRunState(state)) {
          // 服务端已终态但事件序列缺失终态事件的兜底：以详情状态为准收尾
          finishWithTerminal(null, state);
          return;
        }
        // 仍在途：重建连接自愈半开连接（afterSeq 回放保证无重复），不计入失败次数
        restartConnection();
      } catch (error) {
        // reconcile 失败按一次连接失败计入退避，避免静默死循环
        scheduleReconnect(error instanceof Error ? error : new Error(String(error)));
      }
    }

    function armSilenceTimer() {
      clearSilenceTimer();
      silenceTimer = setTimeout(() => {
        // reconcileSilence 内部整体 try/catch，不会产生未处理 rejection
        reconcileSilence();
      }, SSE_SILENCE_TIMEOUT_MS);
    }

    function connect() {
      if (closed || terminated) {
        return;
      }
      controller = new AbortController();
      const currentController = controller;
      fetchEventSource(resolveDataAgentRequestUrl(EVENT_STREAM_URL), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          [STREAM_TRACE_HEADER]: nanoid(),
          ...dataAgentAuthHeaders()
        },
        // 重连时以本地最大 seq 作为 afterSeq 续订
        body: JSON.stringify({ runId, afterSeq: lastSeq }),
        signal: currentController.signal,
        openWhenHidden: true,
        onopen: async response => {
          if (!response.ok) {
            throw new StreamHttpError(response.status);
          }
          consecutiveFailures = 0;
          armSilenceTimer();
        },
        onmessage: message => {
          armSilenceTimer();
          if (!message.data) {
            return;
          }
          let event: RuntimeEventResp;
          try {
            event = JSON.parse(message.data) as RuntimeEventResp;
          } catch (parseError) {
            // 单帧损坏不终止流：seq 去重 + afterSeq 回放保证最终一致
            console.error('解析持久运行时事件失败, runId=%s:', runId, parseError);
            return;
          }
          handleEvent(event);
        },
        onerror: error => {
          // 禁用库内建重试（其重放固定原始 body，afterSeq 会过期），改由本文件调度重连
          throw error;
        }
      })
        .then(() => {
          // 服务端正常结束（终态推送完成或 30 分钟上限轮转）；未终态则立即续订
          if (!closed && !terminated) {
            scheduleReconnect(null);
          }
        })
        .catch((error: unknown) => {
          if (closed || terminated || currentController.signal.aborted) {
            return;
          }
          scheduleReconnect(error instanceof Error ? error : new Error(String(error)));
        });
    }

    connect();

    return {
      close,
      getLastSeq: () => lastSeq
    };
  }
}

export default new RuntimeRunService();
